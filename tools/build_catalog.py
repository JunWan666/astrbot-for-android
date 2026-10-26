"""Build a signed HTTPS update catalog from independently signed AstrBot update packages.

Publishing is a separate action. The signing key is explicitly supplied and is never bundled.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def https_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment or len(value) > 4096:
        raise ValueError('Package URLs must use HTTPS without credentials or fragments')
    return value


def sign_catalog(payload: dict, key: ec.EllipticCurvePrivateKey) -> dict:
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != 'secp256r1':
        raise ValueError('Use an ECDSA P-256 private key')
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    if len(raw) > 128 * 1024:
        raise ValueError('Catalog payload exceeds the app limit')
    return {'payload': base64.b64encode(raw).decode(),
            'signature': base64.b64encode(key.sign(raw, ec.ECDSA(hashes.SHA256()))).decode()}


def package_entry(package: Path, url: str, key: ec.EllipticCurvePrivateKey) -> dict:
    https_url(url)
    if not package.is_file() or not 0 < package.stat().st_size <= 256 * 1024 * 1024:
        raise ValueError('Package missing or too large')
    with zipfile.ZipFile(package) as archive:
        if sorted(archive.namelist()) != ['manifest.json', 'manifest.sig', 'program.tar.bin']:
            raise ValueError('Unexpected package structure')
        if archive.getinfo('manifest.json').file_size > 64 * 1024 or archive.getinfo('manifest.sig').file_size > 256:
            raise ValueError('Package manifest too large')
        raw, signature = archive.read('manifest.json'), archive.read('manifest.sig')
        key.public_key().verify(signature, raw, ec.ECDSA(hashes.SHA256()))
        manifest = json.loads(raw)
        if manifest['schema'] != 1 or manifest['component'] != 'astrbot' or not re.fullmatch(r'[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}', manifest['version']):
            raise ValueError('Catalog only supports stable AstrBot packages')
        if manifest['abi'] not in ['arm64-v8a', 'x86_64'] or not re.fullmatch('[a-f0-9]{64}', manifest['environmentKey']):
            raise ValueError('Package compatibility metadata invalid')
        digest = hashlib.sha256()
        payload_size = 0
        with archive.open('program.tar.bin') as stream:
            for block in iter(lambda: stream.read(128 * 1024), b''):
                payload_size += len(block)
                if payload_size > 256 * 1024 * 1024:
                    raise ValueError('Package payload too large')
                digest.update(block)
        if payload_size != manifest['payloadBytes'] or digest.hexdigest() != manifest['payloadSha256']:
            raise ValueError('Package payload does not match its signature')
    package_hash = hashlib.file_digest(package.open('rb'), 'sha256').hexdigest()
    return {'id': f'astrbot-{manifest["version"]}-{manifest["abi"]}-{package_hash[:16]}',
            'component': 'astrbot', 'version': manifest['version'], 'abi': manifest['abi'],
            'environmentKey': manifest['environmentKey'], 'minAppCode': manifest['minAppCode'],
            'notes': manifest['notes'], 'bytes': package.stat().st_size, 'sha256': package_hash, 'url': url}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-key', type=Path, required=True)
    parser.add_argument('--entry', nargs=2, metavar=('HTTPS_URL', 'PACKAGE_ZIP'), action='append', default=[])
    parser.add_argument('--sequence', type=int, required=True, help='Monotonically increasing publication number')
    parser.add_argument('--valid-days', type=int, default=7, choices=range(1, 32), metavar='1..31')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.sequence <= 9_007_199_254_740_991 or len(args.entry) > 100:
        raise ValueError('Invalid sequence or number of entries')
    key = serialization.load_pem_private_key(args.private_key.read_bytes(), password=None)
    entries = [package_entry(Path(package), url, key) for url, package in args.entry]
    combinations = [(entry['version'], entry['environmentKey'], entry['abi']) for entry in entries]
    if len(combinations) != len(set(combinations)):
        raise ValueError('Duplicate version/environment/ABI combination')
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    payload = {'schema': 1, 'channel': 'stable', 'sequence': args.sequence, 'issuedAtMs': now,
               'expiresAtMs': now + args.valid_days * 24 * 60 * 60 * 1000, 'entries': entries}
    envelope = sign_catalog(payload, key)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(envelope, separators=(',', ':')), encoding='utf-8')
    print(f'Created signed catalog with {len(entries)} packages, sequence {args.sequence}; not published.')


if __name__ == '__main__':
    main()
