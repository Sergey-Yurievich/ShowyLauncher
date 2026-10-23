#!/usr/bin/env bash
# Saves what the TV is like now into backups/<date>-<model>-<IP>/: packages, settings, home screen, assistant, Wi-Fi
# owners. debloat.sh runs it by itself before the first change; restore.sh does not need it, but it is the record of
# how the TV was.
# Usage: scripts/backup.sh <IP>
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/common.sh
connect_tv "${1:-}"

model=$(sh_out getprop ro.product.name)
dir="backups/$(date +%Y-%m-%d)-${model:-tv}-$HOST"
mkdir -p "$dir"
sh_out getprop > "$dir/getprop.txt"
sh_out pm list packages > "$dir/packages-all.txt"
sh_out pm list packages -d > "$dir/packages-disabled.txt"
sh_out pm list packages -3 > "$dir/packages-thirdparty.txt"
for ns in global secure system; do sh_out settings list "$ns" > "$dir/settings-$ns.txt"; done
sh_out cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$dir/home.txt"
# Which app owns each saved Wi-Fi network: disabling that app would delete the network (install.sh checks it).
sh_out dumpsys wifi | grep -o 'cname=[^ ]*' | sort | uniq -c > "$dir/wifi-owners.txt" || true
A exec-out screencap -p > "$dir/screen.png" 2>/dev/null || true
echo "Сохранено: $dir"
