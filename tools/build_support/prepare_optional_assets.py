"""Prepare the unmodified, pinned Shizuku installer for explicit user installation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def prepare(output: Path):
    lock = json.loads((ROOT / 'runtime/shizuku-manager.lock.json').read_text('utf-8'))
    if hashlib.sha256((ROOT / lock['licenseFile']).read_bytes()).hexdigest() != lock['licenseSha256']:
        raise ValueError('Shizuku license differs from pinned upstream text')
    directory = output / 'optional'
    directory.mkdir(parents=True, exist_ok=True)
    apk = directory / 'shizuku.apk'
    def valid(path):
        return path.is_file() and path.stat().st_size == lock['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == lock['sha256']
    if not valid(apk):
        partial = apk.with_suffix('.part')
        try:
            request = urllib.request.Request(lock['url'], headers={'User-Agent': 'AstrBot-Android-Build'})
            with urllib.request.urlopen(request, timeout=60) as source, partial.open('wb') as target:
                remaining = lock['bytes'] + 1
                while remaining:
                    block = source.read(min(65536, remaining))
                    if not block:
                        break
                    target.write(block)
                    remaining -= len(block)
            if not valid(partial):
                raise ValueError('Shizuku installer differs from pinned upstream APK')
            partial.replace(apk)
        finally:
            partial.unlink(missing_ok=True)
    shutil.copyfile(ROOT / 'runtime/shizuku-manager.lock.json', directory / 'shizuku.json')
    shutil.copyfile(ROOT / lock['licenseFile'], directory / 'shizuku-LICENSE.txt')
    return lock


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    print(json.dumps(prepare(parser.parse_args().output_dir), indent=2))
