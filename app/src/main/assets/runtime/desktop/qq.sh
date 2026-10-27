#!/bin/bash
set -eu
desktop_root=/manager/extensions/desktop
qq_binary="$desktop_root/qq/opt/QQ/qq"
test -x "$qq_binary"
mkdir -p "$desktop_root/qq-home/.config" "$desktop_root/qq-home/.local/share" "$desktop_root/qq-home/.cache"
export DISPLAY=:20
export XAUTHORITY="$desktop_root/Xauthority"
xdpyinfo -display "$DISPLAY" >/dev/null
cd "$desktop_root/qq/opt/QQ"
exec env HOME="$desktop_root/qq-home" XDG_CONFIG_HOME="$desktop_root/qq-home/.config" XDG_DATA_HOME="$desktop_root/qq-home/.local/share" XDG_CACHE_HOME="$desktop_root/qq-home/.cache" dbus-run-session -- "$qq_binary" --no-sandbox --disable-gpu --disable-software-rasterizer --disable-gpu-compositing
