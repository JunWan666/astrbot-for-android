#!/bin/sh
set -eu
# Build artifact only: never include home directories, verification profiles, user data or caches.
test -f /opt/qq/.astrbot-components.json
test -f /opt/astrbot/.astrbot-source.json
test -f /var/lib/astrbot-manager/astrbot-python.lock.txt
cd /
tar --sort=name --mtime='UTC 2026-10-25' --owner=0 --group=0 --numeric-owner \
    --exclude='*/.l2s.*' \
    --exclude='opt/astrbot/data' \
    -czf /tmp/astrbot-runtime.tar.gz \
    bin lib sbin usr etc opt \
    var/lib/dpkg var/lib/apt/extended_states \
    var/lib/astrbot-manager/system-packages.lock \
    var/lib/astrbot-manager/astrbot-python.lock.txt
sha256sum /tmp/astrbot-runtime.tar.gz
du -h /tmp/astrbot-runtime.tar.gz
