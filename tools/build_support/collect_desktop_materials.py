"""Collect notices from pinned desktop .debs without installing or executing them.

Run with dpkg-deb available (Linux, or inside WSL). Source archives are prepared
separately from authenticated Ubuntu indices; this tool verifies their binding.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

from collect_distribution import (ROOT, MAX_TEXT, MAX_TOTAL_TEXT, clean_path, digest,
    json_bytes, link_target, resolve_notice, retain_source_archive, scan_runtime, verify, write_zip)


def source_record(root: Path, directory: Path, lock: dict) -> dict:
    record = json.loads((directory / 'ubuntu-desktop-source-collection.json').read_text('utf-8'))
    if (record['file'] != 'ubuntu-desktop-source-packages.zip' or
            record['runtimeSha256'] != lock['baseRuntimeSha256'] or
            record['desktopLockSha256'] != digest(root / 'runtime/desktop-packages.lock.json') or
            not record['allRequiredVersionsCollected'] or not record['allArchiveEntriesVerified']):
        raise ValueError('Desktop source collection does not match the pinned packages')
    path = directory / record['file']
    verify(path, record['sha256'])
    if path.stat().st_size != record['bytes']:
        raise ValueError('Desktop source archive size differs')
    with zipfile.ZipFile(path) as archive:
        data = archive.read('plan.json')
        plan = json.loads(data)
        expected = {(p['sourcePackage'], p['sourceVersion']) for p in lock['packages']}
        if (hashlib.sha256(data).hexdigest() != record['planSha256'] or plan['missing'] or
                plan['runtimeSha256'] != lock['baseRuntimeSha256'] or
                {(p['name'], p['version']) for p in plan['packages']} != expected or
                record['sourcePackagesVerified'] != len(expected) or
                record['sourceFilesVerified'] != len({f['path'] for p in plan['packages'] for f in p['files']})):
            raise ValueError('Desktop source plan does not cover the exact source versions')
        verify_zip(archive, record['entries'])
    return record


def verify_zip(archive: zipfile.ZipFile, entries: int) -> None:
    names = archive.namelist()
    manifest = json.loads(archive.read('COLLECTION-MANIFEST.json'))['files']
    if len(names) != len(set(names)) or len(names) != entries or len(manifest) + 1 != entries or set(names) != {p['path'] for p in manifest} | {'COLLECTION-MANIFEST.json'}:
        raise ValueError('Desktop material archive entries differ')
    for item in manifest:
        name = clean_path(item['path'])
        if archive.getinfo(name).file_size != item['bytes']:
            raise ValueError('Desktop material entry size differs')
        with archive.open(name) as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != item['sha256']:
                raise ValueError('Desktop material entry checksum differs')


def load_materials(root: Path, directory: Path) -> tuple[dict, dict]:
    lock = json.loads((root / 'runtime/desktop-packages.lock.json').read_text('utf-8'))
    record = json.loads((directory / 'desktop-materials.json').read_text('utf-8'))
    if record['desktopLockSha256'] != digest(root / 'runtime/desktop-packages.lock.json'):
        raise ValueError('Desktop materials belong to another dependency lock')
    if record['sourceCollection'] != source_record(root, directory, lock):
        raise ValueError('Desktop source record differs from materials')
    notice = record['notices']
    if notice['file'] != 'desktop-notices.zip':
        raise ValueError('Unexpected desktop notices filename')
    path = directory / notice['file']
    verify(path, notice['sha256'])
    if path.stat().st_size != notice['bytes']:
        raise ValueError('Desktop notice archive size differs')
    with zipfile.ZipFile(path) as archive:
        verify_zip(archive, notice['entries'])
        expected = {p['file']: p for p in lock['packages']}
        if len(record['packages']) != len(expected):
            raise ValueError('Desktop package notice count differs')
        seen = set()
        for package in record['packages']:
            original = expected.get(package['file'])
            if not original or package['file'] in seen or any(package[k] != original[k] for k in original):
                raise ValueError('Desktop package notice identity differs')
            seen.add(package['file'])
            path = clean_path(package['copyright'])
            if not path.startswith('usr/share/doc/') or not path.endswith('/copyright') or path not in archive.namelist():
                raise ValueError('Desktop package copyright text missing')
    return lock, record


def collect(packages: Path, source_directory: Path, destination: Path, runtime_directory: Path, root: Path = ROOT) -> dict:
    lock = json.loads((root / 'runtime/desktop-packages.lock.json').read_text('utf-8'))
    source = source_record(root, source_directory, lock)
    dpkg = shutil.which('dpkg-deb')
    if dpkg is None:
        raise ValueError('Run this collector in Linux or WSL with dpkg-deb installed')
    # A package may link its copyright to a dependency already in the base image.
    image = runtime_directory / 'ubuntu.tar.bin'
    verify(image, lock['baseRuntimeSha256'])
    base_files, links, _ = scan_runtime(image)
    files = {name: data for name, data in base_files.items()
             if name.startswith(('usr/share/doc/', 'usr/share/common-licenses/'))}
    desktop_paths = set()
    total = 0
    for item in lock['packages']:
        if Path(item['file']).name != item['file']:
            raise ValueError('Invalid desktop package filename')
        package = packages / item['file']
        verify(package, item['sha256'])
        if package.stat().st_size != item['bytes']:
            raise ValueError('Desktop package size differs')
        process = subprocess.Popen([dpkg, '--fsys-tarfile', str(package)], stdout=subprocess.PIPE)
        try:
            with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
                for member in archive:
                    name = member.name.removeprefix('./').rstrip('/')
                    if not name.startswith(('usr/share/doc/', 'usr/share/common-licenses/')):
                        continue
                    name = clean_path(name)
                    public_link = name.startswith('usr/share/common-licenses/') or name.count('/') == 3 or name.endswith('/copyright')
                    if (member.issym() or member.islnk()) and public_link:
                        links[name] = link_target(name, member.linkname, member.islnk())
                    elif member.isfile() and (name.endswith('/copyright') or name.startswith('usr/share/common-licenses/')):
                        if member.size > MAX_TEXT or total + member.size > MAX_TOTAL_TEXT:
                            raise ValueError('Desktop notices exceed collection limits')
                        data = archive.extractfile(member).read()
                        if name in desktop_paths and files[name] != data:
                            raise ValueError('Conflicting desktop copyright files')
                        desktop_paths.add(name)
                        links.pop(name, None)
                        files[name] = data
                        total += len(data)
                    elif member.isdir():
                        links.pop(name, None)
            if process.wait(timeout=30):
                raise ValueError('Unable to read desktop package metadata')
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    records = []
    for item in lock['packages']:
        target = resolve_notice('usr/share/doc/' + item['name'] + '/copyright', files, links)
        if target is None:
            raise ValueError('Missing copyright text for ' + item['name'])
        records.append({**item, 'copyright': target})
    destination.mkdir(parents=True, exist_ok=True)
    needed = {p['copyright'] for p in records} | {name for name in files if name.startswith('usr/share/common-licenses/')}
    notices = write_zip(destination / 'desktop-notices.zip', {name: files[name] for name in needed})
    retain_source_archive(source_directory / source['file'], destination / source['file'], source)
    (destination / 'ubuntu-desktop-source-collection.json').write_bytes(json_bytes(source))
    result = {'schemaVersion': 1, 'desktopLockSha256': digest(root / 'runtime/desktop-packages.lock.json'),
              'notices': notices, 'sourceCollection': source, 'packages': records}
    (destination / 'desktop-materials.json').write_bytes(json_bytes(result))
    load_materials(root, destination)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--runtime-dir', type=Path, required=True)
    args = parser.parse_args()
    result = collect(args.package_dir, args.source_dir, args.output_dir, args.runtime_dir)
    print(json.dumps({'packages': len(result['packages']), 'notices': result['notices'],
                      'sources': result['sourceCollection']}, indent=2))
