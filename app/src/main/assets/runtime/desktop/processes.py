#!/usr/bin/python3
"""Recover only this manager's marked desktop/QQ processes after a launcher dies."""
import argparse
import json
import os
from pathlib import Path
import re
import select
import signal
import tempfile
import time

ROOT = Path('/manager/extensions/desktop')
PROC = Path('/proc')
OWNER = 'ASTRBOT_DESKTOP_OWNER'
SESSION = 'ASTRBOT_DESKTOP_SESSION'
ROLE = 'ASTRBOT_DESKTOP_ROLE'


def identity(pid):
    directory = PROC / str(pid)
    status = (directory / 'status').read_text()
    uid = int(next(line.split()[1] for line in status.splitlines() if line.startswith('Uid:')))
    fields = (directory / 'stat').read_text().rsplit(')', 1)[1].split()
    return dict(pid=pid, uid=uid, parent=int(fields[1]), start=fields[19], state=fields[0])


def environment(pid):
    return dict(item.split(b'=', 1) for item in (PROC / str(pid) / 'environ').read_bytes().split(b'\0') if b'=' in item)


def same_process(expected):
    try:
        current = identity(expected['pid'])
        return all(current[key] == expected[key] for key in ('pid', 'uid', 'start')) and current['state'] != 'Z'
    except (OSError, ValueError, StopIteration):
        return False


def boot_id():
    return (PROC / 'sys/kernel/random/boot_id').read_text().strip()


def valid_session(value):
    if re.fullmatch(r'[0-9a-f]{32}', value) is None:
        raise ValueError('Invalid desktop session identity')
    return value


def register(role, session, leader):
    valid_session(session)
    uid = identity(os.getpid())['uid']  # PRoot's getuid() reports the guest's root UID.
    process = identity(leader)
    while process['parent'] > 1:
        process = identity(process['parent'])
        command = (PROC / str(process['pid']) / 'cmdline').read_bytes().split(b'\0')[0]
        if Path(os.fsdecode(command)).name == 'libprocess-runner.so':
            break
    else:
        raise RuntimeError('Desktop launcher identity is unavailable')
    if process['uid'] != uid or not same_process(process):
        raise RuntimeError('Desktop launcher identity changed')
    record = dict(schema=1, role=role, session=session, uid=uid, boot=boot_id(), runner=process)
    directory = ROOT / 'processes'
    directory.mkdir(mode=0o700, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.owner-', dir=directory)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(record, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, directory / (session + '.json'))
    finally:
        Path(temporary).unlink(missing_ok=True)


def marked(pid, role, session):
    values = environment(pid)
    return (values.get(OWNER.encode()) == os.fsencode(ROOT)
            and values.get(ROLE.encode()) == role.encode()
            and values.get(SESSION.encode()) == session.encode())


def legacy_desktop(pid, role):
    """Migrate an orphan from older builds only when its exact log file also matches."""
    if role != 'desktop' or identity(pid)['parent'] != 1:
        return False
    argv = [os.fsdecode(item) for item in (PROC / str(pid) / 'cmdline').read_bytes().split(b'\0') if item]
    if not argv:
        return False
    executable = Path(argv[0]).name
    expected = None
    if executable in ('python3', 'websockify') and argv[-3:] == ['--web=/usr/share/novnc', '127.0.0.1:6081', '127.0.0.1:5920']:
        if executable == 'websockify' or len(argv) == 5 and argv[1] == '/usr/bin/websockify':
            expected = 'novnc.log'
    elif executable == 'Xvfb' and ':20' in argv and str(ROOT / 'Xauthority') in argv:
        expected = 'xvfb.log'
    elif executable == 'fluxbox' and argv[1:] == ['-rc', str(ROOT / 'desktop-home/.fluxbox/init')]:
        expected = 'fluxbox.log'
    elif executable == 'x11vnc' and ':20' in argv and str(ROOT / 'vnc.passwd') in argv and '5920' in argv:
        expected = 'vnc.log'
    if expected is None:
        return False
    actual_log = (PROC / str(pid) / 'fd/1').stat()
    own_log = (ROOT / 'logs' / expected).stat()
    return (actual_log.st_dev, actual_log.st_ino) == (own_log.st_dev, own_log.st_ino)


def targets(uid, role, session=None, legacy=False):
    """Keep pidfds open throughout validation and signalling; PID reuse cannot redirect a signal."""
    result = []
    try:
        for directory in PROC.iterdir():
            if not directory.name.isdecimal():
                continue
            pid = int(directory.name)
            descriptor = None
            try:
                before = identity(pid)
                if pid == os.getpid() or before['uid'] != uid or before['state'] == 'Z':
                    continue
                matched = legacy_desktop(pid, role) if legacy else marked(pid, role, session)
                if not matched:
                    continue
                # Unsupported kernels fail closed rather than signal a possibly recycled PID.
                descriptor = os.pidfd_open(pid)
                if not same_process(before):
                    continue
                matched = legacy_desktop(pid, role) if legacy else marked(pid, role, session)
                if matched:
                    result.append(descriptor)
                    descriptor = None
            except (FileNotFoundError, ProcessLookupError, PermissionError, ValueError, StopIteration):
                continue
            finally:
                if descriptor is not None:
                    os.close(descriptor)
        return result
    except BaseException:
        for descriptor in result:
            os.close(descriptor)
        raise


def terminate(descriptors):
    try:
        for descriptor in descriptors:
            try:
                signal.pidfd_send_signal(descriptor, signal.SIGTERM)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 1.0
        pending = set(descriptors)
        while pending and time.monotonic() < deadline:
            ready, _, _ = select.select(list(pending), [], [], min(0.1, max(0, deadline - time.monotonic())))
            pending.difference_update(ready)
        for descriptor in pending:
            try:
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
            except ProcessLookupError:
                pass
    finally:
        for descriptor in descriptors:
            os.close(descriptor)


def cleanup(role, session=None):
    uid = identity(os.getpid())['uid']
    current_boot = boot_id()
    count = 0
    directory = ROOT / 'processes'
    for path in sorted(directory.glob('*.json')):
        if path.is_symlink() or path.stat().st_size > 4096:
            raise RuntimeError('Invalid desktop process record')
        record = json.loads(path.read_text())
        token = valid_session(record['session'])
        if path.name != token + '.json' or record.get('schema') != 1 or record['uid'] != uid:
            raise RuntimeError('Invalid desktop process owner')
        if record['role'] != role or session is not None and token != session:
            continue
        if record['boot'] != current_boot:
            path.unlink()
            continue
        # Never take over another live launcher, even when the requested session matches.
        if same_process(record['runner']):
            if session is not None:
                raise RuntimeError('Desktop launcher is still running')
            continue
        for _ in range(3):
            owned = targets(uid, role, token)
            if not owned:
                break
            count += len(owned)
            terminate(owned)
        else:
            remaining = targets(uid, role, token)
            if remaining:
                for descriptor in remaining:
                    os.close(descriptor)
                raise RuntimeError('Desktop descendants did not stop')
        path.unlink()
    if session is None:
        old = targets(uid, role, legacy=True)
        count += len(old)
        terminate(old)
    print('desktop process recovery: stopped ' + str(count) + ' owned processes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('register', 'cleanup'))
    parser.add_argument('role', choices=('desktop', 'qq'))
    parser.add_argument('--session', type=valid_session)
    parser.add_argument('--leader', type=int)
    args = parser.parse_args()
    if args.operation == 'register':
        if args.session is None or args.leader is None:
            parser.error('Register requires a session and leader')
        register(args.role, args.session, args.leader)
    else:
        cleanup(args.role, args.session)


if __name__ == '__main__':
    main()
