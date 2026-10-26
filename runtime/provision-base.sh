#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
export TZ=Asia/Shanghai
export TMPDIR=/tmp

# This script runs at build/provisioning time, not on every application start.
apt-get -o Acquire::ForceIPv4=true -o Acquire::http::Timeout=20 -o Acquire::Retries=2 update
apt-get -o Acquire::ForceIPv4=true -o Acquire::http::Timeout=20 -o Acquire::Retries=2 install -y --no-install-recommends \
    python3 python3-venv ca-certificates git curl xz-utils unzip \
    libgomp1 ffmpeg \
    xvfb xauth dbus libglib2.0-0t64 libnss3 libnspr4 libatk1.0-0t64 \
    libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxkbcommon0 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libgtk-3-0t64
mkdir -p /opt/astrbot /opt/astrbot-venv /var/lib/astrbot-manager
python3 --version
dpkg-query -W > /var/lib/astrbot-manager/system-packages.lock
