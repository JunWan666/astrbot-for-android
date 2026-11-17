"""Derive an AstrBot-only image from a verified, previously provisioned runtime.

This is an offline build operation. It never touches a phone or user data.
Optional desktop QQ is downloaded separately by the user from Tencent.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[2]
RETIRED_ROOTS = ('opt/qq',)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def transform(source, target, replacements=None):
    """Stream tar records without extracting or following any archive links."""
    replacements = replacements or {}
    partial = target.with_suffix(target.suffix + '.part')
    count = size = removed = 0
    with tarfile.open(source, 'r|gz') as original, partial.open('wb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=6) as gzip_output:
            with tarfile.open(fileobj=gzip_output, mode='w|', format=tarfile.PAX_FORMAT) as output:
                for entry in original:
                    name = entry.name.removeprefix('./').rstrip('/')
                    if any(name == p or name.startswith(p + '/') for p in RETIRED_ROOTS):
                        removed += 1
                        continue
                    if entry.islnk() and any(entry.linkname.removeprefix('./').startswith(p + '/') for p in RETIRED_ROOTS):
                        raise ValueError('A retained hard link targets a removed component')
                    record = copy.copy(entry)
                    data = original.extractfile(entry) if entry.isfile() else None
                    if name in replacements:
                        assert entry.isfile()
                        content = replacements[name]
                        record.size = len(content)
                        data = io.BytesIO(content)
                    output.addfile(record, data)
                    count += 1
                    if entry.isfile() or entry.islnk():
                        size += record.size
    partial.replace(target)
    return dict(sha256=digest(target), unpackedBytes=size, entryCount=count, removedEntries=removed)


def prepare(source, destination, components):
    manifest = json.loads((source / 'manifest.json').read_text('utf-8'))
    if manifest.get('kind') != 'complete' or manifest.get('abi') != 'arm64-v8a':
        raise ValueError('A complete ARM64 build image is required')
    image = source / 'ubuntu.tar.bin'
    if digest(image) != manifest['sha256']:
        raise ValueError('Input runtime digest mismatch')
    if set(components) - {'schemaVersion', 'status', 'astrbot'} or 'astrbot' not in components:
        raise ValueError('The core image must declare only AstrBot')
    destination.mkdir(parents=True, exist_ok=True)
    result = transform(image, destination / 'ubuntu.tar.bin')
    manifest.update({k: result[k] for k in ('sha256', 'unpackedBytes', 'entryCount')})
    manifest['id'] = 'ubuntu24.04.3-astrbot' + components['astrbot']['version'] + '-core-v1'
    manifest['components'] = components
    manifest['recipe'] = dict(tool='tools/build_support/prepare_core_runtime.py',
        inputSha256=digest(image), sourceSha256=digest(Path(__file__)), excludedProgramRoots=list(RETIRED_ROOTS))
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', 'utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.runtime_dir, args.output_dir,
        json.loads((ROOT / 'runtime/projects.lock.json').read_text('utf-8')))), flush=True)
