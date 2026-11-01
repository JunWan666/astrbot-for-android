"""Prepare a generic desktop in the existing Ubuntu guest. No bridge is installed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path('/manager/extensions/desktop')
MARKER = Path('/var/lib/astrbot-manager/desktop.json')
PENDING = MARKER.with_suffix('.pending')
SOURCES = json.loads((ROOT / 'sources.json').read_text())
TOOLS = ['Xvfb', 'xauth', 'fluxbox', 'feh', 'x11vnc', 'websockify', 'tmux', 'xdpyinfo', 'dbus-run-session', 'gcc', 'g++', 'make', 'python3', 'curl']
COMPATIBILITY_FILES = ['guest_compat.c', 'proc_locks.py', 'guest-compat']


def transfer_state(stage, downloaded=None, total=None, speed=0):
    value = dict(stage=stage, speed=speed)
    if downloaded is not None:
        value['downloaded'] = downloaded
    if total is not None:
        value['total'] = total
    print('ASTRBOT_TRANSFER ' + json.dumps(value), flush=True)


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
        if PENDING.is_file():
            transfer_state('INSTALLING')
            run('dpkg', '--configure', '-a', env=environment)
        atomic_json(PENDING, {'schemaVersion': 1})
        # No network during initialization or repair. Every package is supplied by
        # the APK and independently verified before dpkg changes the environment.
        bundle_directory = ROOT / 'bundled-packages'
        bundle = json.loads((bundle_directory / 'manifest.json').read_text())
        assert bundle['schemaVersion'] == 1 and bundle['architecture'] == SOURCES['architecture']
        assert bundle['requestedPackages'] == SOURCES['packages']
        archives = []
        for item in bundle['packages']:
            assert Path(item['file']).name == item['file'] and item['file'].endswith('.deb')
            archive = bundle_directory / item['file']
            assert archive.stat().st_size == item['bytes']
            with archive.open('rb') as stream:
                assert hashlib.file_digest(stream, 'sha256').hexdigest() == item['sha256']
            assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Package'], text=True).strip() == item['name']
            assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Version'], text=True).strip() == item['version']
            current = subprocess.run(['dpkg-query', '-W', '-f=${db:Status-Status}\t${Version}', item['name']], text=True, capture_output=True)
            fields = current.stdout.strip().split('\t')
            if current.returncode == 0 and len(fields) == 2 and fields[0] == 'installed' and subprocess.run(
                    ['dpkg', '--compare-versions', fields[1], 'ge', item['version']]).returncode == 0:
                continue  # Preserve newer user-installed packages.
            archives.append(str(archive))
        transfer_state('INSTALLING')
        if archives:
            run('dpkg', '--force-confold', '-i', *archives, env=environment)
        run('dpkg', '--configure', '-a', env=environment)
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
    main()
