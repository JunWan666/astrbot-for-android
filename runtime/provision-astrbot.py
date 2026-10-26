"""Build-time guest installation; versioned inputs must be present in /tmp."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import zipfile

lock = json.loads(Path('/tmp/projects.lock.json').read_text())['astrbot']
version = lock['version']
source_archive = Path(f'/tmp/astrbot-v{version}.tar.gz')
dashboard_archive = Path(f'/tmp/astrbot-v{version}-dashboard.zip')
for archive, expected in [(source_archive, lock['source']['sha256']),
                          (dashboard_archive, lock['dashboard']['sha256'])]:
    with archive.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != expected:
            raise ValueError(f'Checksum mismatch: {archive.name}')

target = Path('/opt/astrbot')
marker = target / '.astrbot-source.json'
if marker.exists():
    if json.loads(marker.read_text()) != lock:
        raise RuntimeError('Source version differs; use an update transaction')
else:
    if target.exists():
        # provision-base.sh creates an empty directory; preserve anything unrecognized.
        target.rmdir()
    staging = Path('/opt/astrbot.installing')
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    with tarfile.open(source_archive) as archive:
        archive.extractall(staging, filter='data')
    project = staging / f'AstrBot-{version}'
    with zipfile.ZipFile(dashboard_archive) as archive:
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename:
                raise ValueError(f'Unsafe dashboard entry: {entry.filename}')
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unexpected dashboard symlink')
        archive.extractall(project / 'astrbot/dashboard')
    (project / '.astrbot-source.json').write_text(json.dumps(lock))
    project.rename(target)
    shutil.rmtree(staging)

venv = Path('/opt/astrbot-venv')
if not (venv / 'pyvenv.cfg').exists():
    subprocess.run(['python3', '-m', 'venv', str(venv)], check=True)
pip = [str(venv / 'bin/python'), '-m', 'pip']
constraints = Path('/tmp/astrbot-python.lock.txt')
if not constraints.is_file():
    raise RuntimeError('Pinned Python constraints are required')
subprocess.run([*pip, 'install', '--disable-pip-version-check', '--prefer-binary',
                '--constraint', str(constraints), '--requirement', str(target / 'requirements.txt')], check=True)
subprocess.run([*pip, 'check'], check=True)
package_lock = subprocess.check_output([*pip, 'freeze', '--all'], text=True)
Path('/var/lib/astrbot-manager/astrbot-python.lock.txt').write_text(package_lock)
subprocess.run([str(venv / 'bin/python'), 'main.py', '--help'], cwd=target, check=True)
print('Pinned AstrBot source, dashboard and dependencies ready', flush=True)
