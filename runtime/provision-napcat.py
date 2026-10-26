"""Build-time guest provisioning of the pinned Linux QQ / NapCat combination.

Inputs are copied to /tmp by the development host. No upstream installer is executed.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import zipfile

lock = json.loads(Path('/tmp/projects.lock.json').read_text())
arch = subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip()
abi = {'arm64': 'arm64-v8a', 'amd64': 'x86_64'}[arch]
qq_archive = Path(f'/tmp/linuxqq_{lock["qq"]["version"]}_{arch}.deb')
napcat_archive = Path(f'/tmp/napcat-v{lock["napcat"]["version"]}.zip')
for archive, expected in [(qq_archive, lock['qq'][abi]['sha256']),
                          (napcat_archive, lock['napcat']['source']['sha256'])]:
    with archive.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != expected:
        raise ValueError(f'Checksum mismatch: {archive.name}')

target = Path('/opt/qq')
marker = target / '.astrbot-components.json'
versions = {'qq': lock['qq']['version'], 'napcat': lock['napcat']['version']}
if marker.exists():
    if json.loads(marker.read_text()) != versions:
        raise RuntimeError('Existing component version differs; use an update transaction')
    print('Pinned QQ and NapCat already provisioned', flush=True)
else:
    if target.exists():
        raise RuntimeError('Unrecognized QQ directory; preserving it')
    staging = Path('/opt/qq.installing')
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    subprocess.run(['dpkg-deb', '-x', str(qq_archive), str(staging)], check=True)
    qq = staging / 'opt/QQ'
    app = qq / 'resources/app'
    package_file = app / 'package.json'
    package = json.loads(package_file.read_text())
    if package['version'] != lock['qq']['version']:
        raise ValueError('Unexpected Linux QQ version')
    napcat = app / 'app_launcher/napcat'
    napcat.mkdir(parents=True)
    with zipfile.ZipFile(napcat_archive) as archive:
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename:
                raise ValueError(f'Unsafe archive entry: {entry.filename}')
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unexpected symbolic link in NapCat archive')
        archive.extractall(napcat)
    shutil.copyfile(package_file, app / 'package.original.json')
    (app / 'loadNapCat.js').write_text(
        "const path = require('node:path');\n"
        "const { pathToFileURL } = require('node:url');\n"
        "import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);\n")
    package['main'] = './loadNapCat.js'
    package_file.write_text(json.dumps(package, indent=2))
    (qq / '.astrbot-components.json').write_text(json.dumps(versions))
    qq.rename(target)
    shutil.rmtree(staging)
    print('Pinned Linux QQ and NapCat installed', flush=True)

missing = subprocess.run(['ldd', str(target / 'qq')], capture_output=True, text=True)
print(missing.stdout, flush=True)
if 'not found' in missing.stdout:
    raise RuntimeError('Linux QQ shared libraries missing')
