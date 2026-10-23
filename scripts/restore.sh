#!/usr/bin/env bash
# Puts the TV back: turns on again what debloat.sh and install.sh switched off, gives Home, the remote's buttons and the
# assistant back to Google TV.
#
# Usage: scripts/restore.sh <IP>                  everything back; ShowyLauncher stays installed, just not the home screen
#        scripts/restore.sh <IP> <package>...     turn on just these packages
#        UNINSTALL=1 scripts/restore.sh <IP>      everything back and ShowyLauncher removed
# Before a factory reset run the full restore: the first-run wizard must be on.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/common.sh
PKG=ru.homescreen.tv
LIST="${LIST:-scripts/tcl-google-tv.txt}"
connect_tv "${1:-}"
shift || true

if [ $# -gt 0 ]; then
  for p in "$@"; do echo "$p: $(sh_out pm enable --user 0 "$p" | tail -n 1)"; done
  exit 0
fi

# Only what these scripts switch off: anything disabled by hand before stays as it was.
ours="$(batch_packages "$LIST" 1 2 3 4 5 6 7 8 9) com.google.android.tungsten.setupwraith
com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher"
disabled=$(sh_out pm list packages -d | sed 's/^package://')
for p in $ours; do
  if echo "$disabled" | grep -qx "$p"; then
    echo "включаю $p"
    A shell pm enable --user 0 "$p" >/dev/null
  fi
done

# Home to Google TV's own, the remote's buttons and the assistant as they were.
for home in com.google.android.apps.tv.launcherx/.home.HomeActivity com.google.android.tvlauncher/.MainActivity; do
  installed "${home%%/*}" && { A shell cmd package set-home-activity --user 0 "$home" >/dev/null; break; }
done
cur=$(sh_out settings get secure enabled_accessibility_services)
new=$(echo "$cur" | tr ':' '\n' | grep -v "^$PKG/" | paste -sd ':' -)
A shell settings put secure enabled_accessibility_services "${new:-\"\"}"
installed com.google.android.katniss && A shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss >/dev/null 2>&1

if [ -n "${UNINSTALL:-}" ]; then
  A shell pm uninstall "$PKG"
fi

A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Главный экран: $(sh_out cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
echo "Если старый главный экран не появился после кнопки «Домой», перезагрузите ТВ."
