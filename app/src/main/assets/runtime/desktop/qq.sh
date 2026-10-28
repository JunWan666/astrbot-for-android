#!/bin/bash
set -eu
desktop_root=/manager/extensions/desktop
qq_binary="$desktop_root/qq/opt/QQ/qq"
test -x "$qq_binary"
mkdir -p "$desktop_root/qq-home/.config" "$desktop_root/qq-home/.local/share" "$desktop_root/qq-home/.cache" "$desktop_root/qq-runtime"
# PRoot's guest UID can differ from the UID exposed by procfs. Give desktop
# clients an explicit standard directory for their per-user IPC sockets.
chmod 700 "$desktop_root/qq-runtime"
export DISPLAY=:20
export XAUTHORITY="$desktop_root/Xauthority"
xdpyinfo -display "$DISPLAY" >/dev/null
cd "$desktop_root/qq/opt/QQ"
# Keep Electron's GPU work in the main process. A separate GPU process can fail
# under Android/PRoot even when hardware acceleration is disabled.
exec env HOME="$desktop_root/qq-home" XDG_CONFIG_HOME="$desktop_root/qq-home/.config" XDG_DATA_HOME="$desktop_root/qq-home/.local/share" XDG_CACHE_HOME="$desktop_root/qq-home/.cache" XDG_RUNTIME_DIR="$desktop_root/qq-runtime" dbus-run-session -- "$desktop_root/guest-compat" --profile "$qq_binary" --no-sandbox --disable-gpu --disable-dev-shm-usage --in-process-gpu --disable-gpu-compositing --disable-features=Vulkan
