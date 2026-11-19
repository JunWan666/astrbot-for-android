#!/bin/bash
set -eu
desktop_root=/manager/extensions/desktop
qq_binary="$desktop_root/qq/opt/QQ/qq"
compat_args=(--profile)
case "$#:${1:-}" in
    0:) ;;
    1:--profile) ;;
    1:--no-profile) compat_args=() ;;
    *) printf '%s\n' 'Usage: qq-desktop [--profile | --no-profile]' >&2; exit 2 ;;
esac
if ! test -x "$qq_binary"; then
    printf '%s\n' 'Install Linux QQ from Settings > Extensions first.' >&2
    exit 1
fi
if test -n "${ASTRBOT_DESKTOP_SESSION:-}"; then
    python3 "$desktop_root/processes.py" register qq --session "$ASTRBOT_DESKTOP_SESSION" --leader "$$"
fi
mkdir -p "$desktop_root/qq-home/.config" "$desktop_root/qq-home/.local/share" "$desktop_root/qq-home/.cache" "$desktop_root/qq-runtime"
# PRoot's guest UID can differ from the UID exposed by procfs. Give desktop
# clients an explicit standard directory for their per-user IPC sockets.
chmod 700 "$desktop_root/qq-runtime"
export DISPLAY=:20
export XAUTHORITY="$desktop_root/Xauthority"
if ! xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
    printf '%s\n' 'Open the desktop from Linux QQ settings before running qq-desktop.' >&2
    exit 1
fi
cd "$desktop_root/qq/opt/QQ"
# Keep Electron's GPU work in the main process. A separate GPU process can fail
# under Android/PRoot even when hardware acceleration is disabled.
# App and terminal launches both apply a profile previously saved by the user.
# With no profile, guest-compat only supplies its normal glibc compatibility.
exec env HOME="$desktop_root/qq-home" XDG_CONFIG_HOME="$desktop_root/qq-home/.config" XDG_DATA_HOME="$desktop_root/qq-home/.local/share" XDG_CACHE_HOME="$desktop_root/qq-home/.cache" XDG_RUNTIME_DIR="$desktop_root/qq-runtime" dbus-run-session -- "$desktop_root/guest-compat" "${compat_args[@]}" "$qq_binary" --no-sandbox --disable-gpu --disable-dev-shm-usage --in-process-gpu --disable-gpu-compositing --disable-features=Vulkan
