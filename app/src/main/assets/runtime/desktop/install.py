"""Prepare a generic desktop in the existing Ubuntu guest. No bridge is installed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import shlex
import time
import urllib.request
import urllib.error

ROOT = Path('/manager/extensions/desktop')
MARKER = Path('/var/lib/astrbot-manager/desktop.json')
PENDING = MARKER.with_suffix('.pending')
SOURCES = json.loads((ROOT / 'sources.json').read_text())
TOOLS = ['Xvfb', 'xauth', 'fluxbox', 'feh', 'x11vnc', 'websockify', 'tmux', 'xdpyinfo', 'dbus-run-session', 'gcc', 'g++', 'make', 'python3', 'curl']
COMPATIBILITY_FILES = ['guest_compat.c', 'proc_locks.py', 'guest-compat']


class DownloadStopped(Exception):
    pass


def transfer_state(stage, downloaded=None, total=None, speed=0):
    value = dict(stage=stage, speed=speed)
    if downloaded is not None:
        value['downloaded'] = downloaded
    if total is not None:
        value['total'] = total
    print('ASTRBOT_TRANSFER ' + json.dumps(value), flush=True)


def control():
    try:
        value = json.loads((ROOT / 'download-control.json').read_text())
    except FileNotFoundError:
        value = {}
    if value.get('stopped'):
        raise DownloadStopped()
    return value.get('paused', False)


def metadata_update(options, environment):
    transfer_state('PREPARING')
    process = subprocess.Popen(['apt-get', *options, 'update'], env=environment)
    try:
        while process.poll() is None:
            control()
            time.sleep(0.2)
        assert process.returncode == 0, 'Package metadata download failed'
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def download_packages(options, environment):
    output = subprocess.check_output(['apt-get', *options, '--print-uris', 'install', '-y',
        '--no-install-recommends', *SOURCES['packages']], env=environment, text=True)
    packages = []
    cache = Path('/var/cache/apt/archives')
    partial = cache / 'partial'
    partial.mkdir(parents=True, exist_ok=True)
    for line in output.splitlines():
        if not line.startswith(("'http://", "'https://")):
            continue
        fields = shlex.split(line)
        if len(fields) != 4 or not fields[0].startswith(('http://', 'https://')):
            continue
        url, name, size, expected = fields
        assert Path(name).name == name and name.endswith('.deb'), 'Invalid package filename'
        algorithm, digest = expected.split(':', 1)
        algorithm = {'MD5Sum': 'md5'}.get(algorithm, algorithm.lower())
        assert algorithm in ('sha256', 'sha512', 'sha1', 'md5'), 'Unsupported package checksum'
        packages.append((url, name, int(size), algorithm, digest.lower()))

    def verified(path, size, algorithm, expected):
        if not path.is_file() or path.stat().st_size != size:
            return False
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, algorithm).hexdigest() == expected

    total = sum(package[2] for package in packages)
    completed = 0
    started = time.monotonic()
    previous = 0
    received = 0
    current_speed = 0

    def publish_download(downloaded):
        nonlocal started, previous, current_speed
        now = time.monotonic()
        if now - started >= 0.25:
            current_speed = max(0, int((received - previous) / (now - started)))
            started, previous = now, received
        transfer_state('DOWNLOADING', downloaded, total, current_speed)

    for url, name, size, algorithm, expected in packages:
        target = cache / name
        temporary = partial / name
        if verified(target, size, algorithm, expected):
            completed += size
            publish_download(completed)
            continue
        if temporary.exists() and temporary.stat().st_size > size:
            temporary.unlink()
        while not temporary.exists() or temporary.stat().st_size < size:
            while control():
                transfer_state('PAUSED', completed + (temporary.stat().st_size if temporary.exists() else 0), total)
                time.sleep(0.2)
                started = time.monotonic()
                previous = received
                current_speed = 0
            offset = temporary.stat().st_size if temporary.exists() else 0
            publish_download(completed + offset)
            request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-'} if offset else {})
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    if response.status == 206:
                        assert response.headers.get('Content-Range', '').startswith(f'bytes {offset}-'), 'Invalid package range'
                    else:
                        assert response.status == 200
                        if offset:
                            temporary.unlink(missing_ok=True)
                            offset = 0
                    with temporary.open('ab') as stream:
                        while not control():
                            block = response.read(65536)
                            if not block:
                                break
                            assert offset + len(block) <= size, 'Package exceeds expected size'
                            stream.write(block)
                            offset += len(block)
                            received += len(block)
                            now = time.monotonic()
                            if now - started >= 0.25 or offset == size:
                                publish_download(completed + offset)
                if offset < size and not control():
                    raise RuntimeError('Package download was interrupted; retry to resume')
            except (OSError, urllib.error.URLError):
                if not control():
                    raise
        if not verified(temporary, size, algorithm, expected):
            temporary.unlink(missing_ok=True)
            raise RuntimeError('Package checksum mismatch; retry to download again')
        os.replace(temporary, target)
        completed += size
    while control():
        transfer_state('PAUSED', completed, total)
        time.sleep(0.2)
    transfer_state('INSTALLING', completed, total)


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.new')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.chmod(0o600)
    os.replace(temporary, path)


def compatibility_digest():
    digest = hashlib.sha256()
    for name in COMPATIBILITY_FILES:
        digest.update((ROOT / name).read_bytes())
    return digest.hexdigest()


def install_compatibility():
    staged = ROOT / 'libguest-compat.so.new'
    try:
        run('gcc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
            '-Wl,-z,relro,-z,now', '-o', str(staged), str(ROOT / 'guest_compat.c'), '-ldl')
        run('python3', '-c', 'import ctypes,sys; ctypes.CDLL(sys.argv[1])', str(staged))
        staged.chmod(0o700)
        os.replace(staged, ROOT / 'libguest-compat.so')
    finally:
        staged.unlink(missing_ok=True)
    (ROOT / 'guest-compat').chmod(0o700)


def install_qq():
    archive = ROOT / 'download.deb'
    spec = SOURCES['qq']
    assert archive.stat().st_size == spec['bytes']
    with archive.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == spec['sha256']
    assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Architecture'], text=True).strip() == 'arm64'
    assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Version'], text=True).strip() == spec['version']
    target, staged, previous = (ROOT / name for name in ['qq', 'qq.installing', 'qq.previous'])
    marker = ROOT / 'qq.json'
    if previous.exists():
        assert not previous.is_symlink(), 'Previous QQ must be a managed directory'
        if target.exists():
            assert not target.is_symlink(), 'Existing QQ must be a managed directory'
            package = json.loads((target / 'opt/QQ/resources/app/package.json').read_text())
            committed = json.loads(marker.read_text()) if marker.exists() else {}
            if (package.get('version') == spec['version'] == committed.get('version')
                    and committed.get('archiveSHA256') == spec['sha256']):
                # The marker commits the replacement. Cleanup may have been interrupted.
                assert package['main'] == './application.asar/app_launcher/index.js'
                assert not (target / 'opt/QQ/resources/app/loadNapCat.js').exists()
                shutil.rmtree(previous)
            else:
                # Replacement was not committed; retain it as staging and restore the old QQ.
                if staged.exists():
                    assert not staged.is_symlink()
                    shutil.rmtree(staged)
                target.rename(staged)
                previous.rename(target)
        else:
            previous.rename(target)
    if target.exists():
        assert not target.is_symlink(), 'Existing QQ must be a managed directory'
        package = json.loads((target / 'opt/QQ/resources/app/package.json').read_text())
        assert package['main'] == './application.asar/app_launcher/index.js'
        assert not (target / 'opt/QQ/resources/app/loadNapCat.js').exists()
    if staged.exists():
        assert not staged.is_symlink()
        shutil.rmtree(staged)
    staged.mkdir()
    run('dpkg-deb', '-x', str(archive), str(staged))
    package = json.loads((staged / 'opt/QQ/resources/app/package.json').read_text())
    assert package['version'] == spec['version']
    assert package['main'] == './application.asar/app_launcher/index.js'
    assert not (staged / 'opt/QQ/resources/app/loadNapCat.js').exists()
    output = subprocess.check_output(['ldd', str(staged / 'opt/QQ/qq')], text=True)
    assert 'not found' not in output, output
    old_marker = json.loads(marker.read_text()) if marker.exists() else None
    if target.exists():
        target.rename(previous)
    try:
        staged.rename(target)
        atomic_json(marker, {'schemaVersion': 1, 'version': spec['version'], 'archiveSHA256': spec['sha256']})
    except BaseException:
        if target.exists():
            target.rename(staged)
        if previous.exists():
            previous.rename(target)
        if old_marker is not None:
            atomic_json(marker, old_marker)
        else:
            marker.unlink(missing_ok=True)
        raise
    if previous.exists():
        shutil.rmtree(previous)


def main():
    mode = sys.argv[1]
    if mode == 'dependencies':
        assert subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip() == SOURCES['architecture']
        release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
        assert release.get('VERSION_ID', '').strip('"') == SOURCES['ubuntuVersion']
        environment = dict(os.environ, DEBIAN_FRONTEND='noninteractive')
        options = ['-o', 'Acquire::ForceIPv4=true', '-o', 'Acquire::http::Timeout=25', '-o', 'Acquire::Retries=2']
        if PENDING.is_file():
            transfer_state('INSTALLING')
            run('dpkg', '--configure', '-a', env=environment)
        atomic_json(PENDING, {'schemaVersion': 1})
        metadata_update(options, environment)
        download_packages(options, environment)
        run('apt-get', *options, 'install', '-y', '--no-download', '--no-install-recommends', *SOURCES['packages'], env=environment)
        assert all(shutil.which(tool) for tool in TOOLS)
        assert Path('/usr/share/novnc/vnc.html').is_file()
        install_compatibility()
        packages = subprocess.check_output(['dpkg-query', '-W', '-f=${binary:Package}\t${Version}\n', *SOURCES['packages']], text=True)
        atomic_json(MARKER, {'schemaVersion': 1, 'packages': packages.splitlines(), 'compatibilitySHA256': compatibility_digest()})
        PENDING.unlink()
        run('apt-get', 'clean')
    elif mode == 'qq':
        install_qq()
    else:
        raise ValueError('Unknown preparation operation')


if __name__ == '__main__':
    try:
        main()
    except DownloadStopped:
        transfer_state('STOPPED')
        raise SystemExit(130)
