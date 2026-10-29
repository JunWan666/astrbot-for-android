#!/bin/bash
set -eu
desktop_root=/manager/extensions/desktop
export DISPLAY=:20
export XAUTHORITY="$desktop_root/Xauthority"
test -s "$desktop_root/vnc.passwd"
test -s "$XAUTHORITY"
if test -n "${ASTRBOT_DESKTOP_SESSION:-}"; then
    python3 "$desktop_root/processes.py" register desktop --session "$ASTRBOT_DESKTOP_SESSION" --leader "$$"
fi
mkdir -p "$desktop_root/logs" "$desktop_root/desktop-home/.fluxbox"
if ! test -f "$desktop_root/desktop-home/.fluxbox/init"; then
    printf '%s\n' 'session.screen0.rootCommand: /bin/true' 'session.screen0.workspaces: 1' > "$desktop_root/desktop-home/.fluxbox/init"
fi
# Ubuntu's default theme tries to launch an optional wallpaper setter. A neutral
# overlay keeps a minimal desktop from showing a missing-wallpaper-tool dialog.
printf '%s\n' 'background: none' > "$desktop_root/desktop-home/.fluxbox/overlay"
sed -i '/^session.styleOverlay:/d' "$desktop_root/desktop-home/.fluxbox/init"
printf '\nsession.styleOverlay: %s\n' "$desktop_root/desktop-home/.fluxbox/overlay" >> "$desktop_root/desktop-home/.fluxbox/init"
desktop_children=()
desktop_cleanup() {
    trap - EXIT INT TERM HUP
    for desktop_pid in "${desktop_children[@]}"; do kill -TERM "$desktop_pid" 2>/dev/null || true; done
    for desktop_pid in "${desktop_children[@]}"; do wait "$desktop_pid" 2>/dev/null || true; done
}
if xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then echo 'desktop: display :20 is already in use' >&2; exit 1; fi
trap desktop_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
Xvfb "$DISPLAY" -screen 0 1280x800x24 -nolisten tcp -extension MIT-SHM -auth "$XAUTHORITY" > "$desktop_root/logs/xvfb.log" 2>&1 &
desktop_children+=("$!")
for desktop_attempt in {1..75}; do
    if xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then break; fi
    kill -0 "${desktop_children[0]}"
    sleep 0.2
done
xdpyinfo -display "$DISPLAY" >/dev/null
env HOME="$desktop_root/desktop-home" fluxbox -rc "$desktop_root/desktop-home/.fluxbox/init" > "$desktop_root/logs/fluxbox.log" 2>&1 &
desktop_children+=("$!")
x11vnc -display "$DISPLAY" -listen 127.0.0.1 -localhost -noipv6 -noshm -rfbport 5920 -rfbauth "$desktop_root/vnc.passwd" -forever -shared > "$desktop_root/logs/vnc.log" 2>&1 &
desktop_children+=("$!")
websockify --web=/usr/share/novnc 127.0.0.1:6081 127.0.0.1:5920 > "$desktop_root/logs/novnc.log" 2>&1 &
desktop_children+=("$!")
echo 'desktop: local desktop is starting at http://127.0.0.1:6081/vnc.html'
wait -n "${desktop_children[@]}" || true
echo 'desktop: a component exited; inspect extension logs' >&2
exit 1
