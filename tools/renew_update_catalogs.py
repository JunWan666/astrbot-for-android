"""Renew signed catalog lifetimes without adding or changing approved release entries."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from build_catalog import sign_catalog


def renew(directory: Path, key: ec.EllipticCurvePrivateKey, now: int) -> None:
    changes = []
    for name in ['astrbot.json', 'napcat.json', 'manager.json']:
        path = directory / name
        envelope = json.loads(path.read_text('utf8'))
        raw = base64.b64decode(envelope['payload'], validate=True)
        key.public_key().verify(base64.b64decode(envelope['signature'], validate=True), raw, ec.ECDSA(hashes.SHA256()))
        payload = json.loads(raw)
        if payload['schema'] != 1 or payload['channel'] != 'stable' or not isinstance(payload['entries'], list):
            raise ValueError('Unexpected catalog; renewal must preserve existing approved entries')
        payload.update(sequence=max(payload['sequence'] + 1, now), issuedAtMs=now, expiresAtMs=now + 30 * 24 * 60 * 60 * 1000)
        if payload['sequence'] > 9_007_199_254_740_991:
            raise ValueError('Catalog sequence exhausted')
        changes.append((path, json.dumps(sign_catalog(payload, key), separators=(',', ':'))))
    # Validate all catalogs before touching any file.
    for path, content in changes:
        path.write_text(content, 'utf8')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory', type=Path, default=Path('stable'))
    a = p.parse_args()
    key = serialization.load_pem_private_key(os.environ['UPDATE_SIGNING_KEY'].encode(), password=None)
    renew(a.directory, key, int(datetime.now(timezone.utc).timestamp() * 1000))
    print('Renewed three catalogs; approved package entries are unchanged.')


if __name__ == '__main__':
    main()
