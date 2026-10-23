#!/usr/bin/env bash
# Installs ShowyLauncher on the TV and makes it the only home screen: Google TV's own home and first-run wizard are
# switched off, the remote's Settings / Input buttons and its microphone button go to the launcher.
# Run it again to update: the history, bookmarks and Showy sign-in stay.
#
# Usage: scripts/install.sh <IP>              the latest release from GitHub (or a fresh build, see below)
#        scripts/install.sh <IP> file.apk     this APK
# A build from the sources is used when local.properties has a TMDB key (tmdb.apiKey=...): developers only.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/common.sh
PKG=ru.homescreen.tv
RELEASE_URL="https://github.com/Sergey-Yurievich/ShowyLauncher/releases/latest/download/ShowyLauncher.apk"
connect_tv "${1:-}"
APK="${2:-}"

sdk=$(sh_out getprop ro.build.version.sdk)
[ "${sdk:-0}" -ge 28 ] || die "нужен Android TV 9 или новее (у этого ТВ SDK $sdk)"

if [ -z "$APK" ]; then
  if grep -qE '^tmdb\.apiKey=.+' local.properties 2>/dev/null; then
    echo "Собираю из исходников…"
    if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
      export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    fi
    ./gradlew -q assembleRelease
    APK=app/build/outputs/apk/release/app-release.apk
  else
    echo "Скачиваю последнюю версию с GitHub…"
    mkdir -p build
    APK=build/ShowyLauncher.apk
    curl -fL --progress-bar -o "$APK" "$RELEASE_URL" || die "не удалось скачать $RELEASE_URL"
  fi
fi
[ -f "$APK" ] || die "нет файла $APK"

# Push + pm install instead of `adb install`: on Google TV that can wait forever for an on-screen confirmation.
A push "$APK" /data/local/tmp/showylauncher.apk >/dev/null
out=$(sh_out pm install -r -g /data/local/tmp/showylauncher.apk 2>&1 || true)
A shell rm -f /data/local/tmp/showylauncher.apk
case "$out" in
  *Success*) echo "Установлено: $(sh_out dumpsys package $PKG | grep -m1 versionName | tr -d ' ')" ;;
  *UPDATE_INCOMPATIBLE*) die "на ТВ стоит лаунчер с другой подписью. Удалите его (adb -s $TV uninstall $PKG) и запустите снова." ;;
  *) die "установка не удалась: $out" ;;
esac
# The TV provider (Live TV channels); install-time on some firmware, then this grant fails harmlessly.
A shell pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA >/dev/null 2>&1 || true

# The only home screen. The first-run wizard goes first, while Google TV's home still owns Home: once that is off,
# the wizard's recovery screen would claim Home itself. A saved Wi-Fi network owned by the wizard would be deleted
# with it, so then it is left on.
if installed com.google.android.tungsten.setupwraith; then
  if sh_out dumpsys wifi | grep -q 'cname=com.google.android.tungsten.setupwraith'; then
    echo "Мастер первого запуска оставлен включённым: ему принадлежит сохранённая сеть Wi-Fi."
    echo "Забудьте эту сеть и подключитесь к ней заново в Настройках, потом запустите скрипт ещё раз."
  else
    sh_out pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null || true
  fi
fi
for home in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher; do
  if installed "$home"; then sh_out pm disable-user --user 0 "$home" >/dev/null || true; fi
done
A shell cmd package set-home-activity --user 0 "$PKG/.MainActivity" >/dev/null

# The remote's Settings and Input buttons (KeysService), added to whatever accessibility services are on. Put again on
# every install so the updated app is bound again.
svc="$PKG/.KeysService"
cur=$(sh_out settings get secure enabled_accessibility_services)
case "$cur" in
  *"$svc"*) new="$cur" ;;
  ""|null) new="$svc" ;;
  *) new="$cur:$svc" ;;
esac
A shell settings put secure enabled_accessibility_services "$new"
A shell settings put secure accessibility_enabled 1
# The remote's microphone button goes to the assistant (it is not a key): the launcher takes the role, so the button
# opens its voice search. Google Assistant back: scripts/restore.sh.
A shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG" >/dev/null 2>&1 || true

A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Главный экран: $(sh_out cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
installed net.gtvbox.vimuhd || echo "Внимание: нет плеера ViMu. Установите «ViMu Media Player for TV» из Play Маркета — фильмы играют в нём."
