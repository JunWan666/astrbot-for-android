#!/bin/sh
set -eu
base=/manager/extensions/harness
export HOME="$base/home"
export XDG_CONFIG_HOME="$HOME/.config"
export XDG_DATA_HOME="$HOME/.local/share"
export XDG_CACHE_HOME="$HOME/.cache"
export PATH="$base/program/node/bin:/manager/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
host=${1:-127.0.0.1}
# The upstream server accepts one concrete interface; wildcard bindings are intentionally rejected.
case "$host" in ''|*[!0-9.]*|0.0.0.0) echo 'Invalid listen address' >&2; exit 2;; esac
mkdir -p "$XDG_CONFIG_HOME" "$base/workspace"
# The upstream Linux picker needs a Documents directory even without a desktop session.
# Keep its default workspace inside this extension, and preserve a user's chosen directory.
if ! test -f "$XDG_CONFIG_HOME/user-dirs.dirs" || ! grep -q '^XDG_DOCUMENTS_DIR=' "$XDG_CONFIG_HOME/user-dirs.dirs"; then
    printf 'XDG_DOCUMENTS_DIR="%s"\n' "$base/workspace" >> "$XDG_CONFIG_HOME/user-dirs.dirs"
fi
cd "$base/workspace"
exec "$base/program/node/bin/node" "$base/program/node_modules/@deepseek-ai/dsh/lib/bin.js" web --no-open --host "$host" --port 3080
