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
TOOLS = ['Xvfb', 'xauth', 'fluxbox', 'feh', 'x11vnc', 'websockify', 'tmux', 'xdpyinfo', 'dbus-run-session']


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.new')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.chmod(0o600)
    os.replace(temporary, path)


def main():
    mode = sys.argv[1]
    if mode == 'dependencies':
        assert subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip() == SOURCES['architecture']
        release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
        assert release.get('VERSION_ID', '').strip('"') == SOURCES['ubuntuVersion']
        environment = dict(os.environ, DEBIAN_FRONTEND='noninteractive')
        options = ['-o', 'Acquire::ForceIPv4=true', '-o', 'Acquire::http::Timeout=25', '-o', 'Acquire::Retries=2']
        if PENDING.is_file():
            run('dpkg', '--configure', '-a', env=environment)
        atomic_json(PENDING, {'schemaVersion': 1})
        run('apt-get', *options, 'update', env=environment)
        run('apt-get', *options, 'install', '-y', '--no-install-recommends', *SOURCES['packages'], env=environment)
        assert all(shutil.which(tool) for tool in TOOLS)
        assert Path('/usr/share/novnc/vnc.html').is_file()
        packages = subprocess.check_output(['dpkg-query', '-W', '-f=${binary:Package}\t${Version}\n', *SOURCES['packages']], text=True)
        atomic_json(MARKER, {'schemaVersion': 1, 'packages': packages.splitlines()})
        PENDING.unlink()
        run('apt-get', 'clean')
    elif mode == 'qq':
        archive = ROOT / 'download.deb'
        spec = SOURCES['qq']
        assert archive.stat().st_size == spec['bytes']
        with archive.open('rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == spec['sha256']
        assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Architecture'], text=True).strip() == 'arm64'
        assert subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Version'], text=True).strip() == spec['version']
        target = ROOT / 'qq'
        staged = ROOT / 'qq.installing'
        if target.exists():
            package = json.loads((target / 'opt/QQ/resources/app/package.json').read_text())
            assert package['version'] == spec['version'], 'Existing QQ directory is preserved'
            assert package['main'] == './application.asar/app_launcher/index.js'
            assert not (target / 'opt/QQ/resources/app/loadNapCat.js').exists()
        else:
            if staged.exists():
                assert not staged.is_symlink()
                shutil.rmtree(staged)
            staged.mkdir()
            run('dpkg-deb', '-x', str(archive), str(staged))
            package = json.loads((staged / 'opt/QQ/resources/app/package.json').read_text())
            assert package['version'] == spec['version']
            assert package['main'] == './application.asar/app_launcher/index.js'
            assert not (staged / 'opt/QQ/resources/app/loadNapCat.js').exists()
            os.replace(staged, target)
        output = subprocess.check_output(['ldd', str(target / 'opt/QQ/qq')], text=True)
        assert 'not found' not in output, output
        atomic_json(ROOT / 'qq.json', {'schemaVersion': 1, 'version': spec['version'], 'archiveSHA256': spec['sha256']})
    else:
        raise ValueError('Unknown preparation operation')


if __name__ == '__main__':
    main()
