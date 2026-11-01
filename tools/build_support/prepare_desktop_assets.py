"""Fetch the pinned Ubuntu desktop packages for offline preparation on ARM64."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def prepare(output: Path):
    lock = json.loads((ROOT / 'runtime/desktop-packages.lock.json').read_text('utf-8'))
    sources = json.loads((ROOT / 'app/src/main/assets/runtime/desktop/sources.json').read_text('utf-8'))
    assert lock['schemaVersion'] == 1 and lock['architecture'] == 'arm64'
    assert lock['requestedPackages'] == sources['packages']
    directory = output / 'desktop-packages/arm64-v8a'
    directory.mkdir(parents=True, exist_ok=True)

    def fetch(item):
        name = item['file']
        assert Path(name).name == name and name.endswith('.deb')
        assert item['url'].startswith('https://ports.ubuntu.com/ubuntu-ports/pool/')
        target = directory / name
        def valid(path):
            if not path.is_file() or path.stat().st_size != item['bytes']:
                return False
            with path.open('rb') as stream:
                return hashlib.file_digest(stream, 'sha256').hexdigest() == item['sha256']
        if valid(target):
            return
        staged = target.with_suffix('.part')
        try:
            with urllib.request.urlopen(item['url'], timeout=90) as source, staged.open('wb') as destination:
                remaining = item['bytes'] + 1
                while remaining:
                    block = source.read(min(131072, remaining))
                    if not block:
                        break
                    destination.write(block)
                    remaining -= len(block)
            if not valid(staged):
                raise ValueError(f'Desktop package integrity mismatch: {name}')
            staged.replace(target)
        finally:
            staged.unlink(missing_ok=True)

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(fetch, lock['packages']))
    (directory / 'manifest.json').write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8')
    return {'packages': len(lock['packages']), 'bytes': sum(item['bytes'] for item in lock['packages'])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    print(json.dumps(prepare(parser.parse_args().output_dir), indent=2))
