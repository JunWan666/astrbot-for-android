#!/bin/bash
set -eu
desktop_root=/manager/extensions/desktop
port="${1:?LAN port required}"
[[ "$port" =~ ^[0-9]{4,5}$ ]] && (( port >= 1024 && port <= 65535 && port != 5920 && port != 6081 ))
if test -n "${ASTRBOT_DESKTOP_SESSION:-}"; then
    python3 "$desktop_root/processes.py" register lan --session "$ASTRBOT_DESKTOP_SESSION" --leader "$$"
fi
# Separate web gateway: changing LAN access never restarts X11, VNC or QQ.
# The same VNC password is required by both the local and remote browser.
exec websockify --web=/usr/share/novnc "0.0.0.0:$port" 127.0.0.1:5920
