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
    curl -fsSL -o "$APK" "$RELEASE_URL" || die "не удалось скачать $RELEASE_URL"
  fi
fi
[ -f "$APK" ] || die "нет файла $APK"

# Push + pm install instead of `adb install`: on Google TV that can wait forever for an on-screen confirmation.
A push "$APK" /data/local/tmp/showylauncher.apk >/dev/null
# The launcher as its own installer of record: on Android 12+ it then updates itself from the gear menu without a
# system prompt. A package that is not installed yet cannot be named as installer, so a first install goes in twice.
installed "$PKG" || sh_out pm install -r -g /data/local/tmp/showylauncher.apk >/dev/null 2>&1 || true
out=$(sh_out pm install -r -g -i "$PKG" /data/local/tmp/showylauncher.apk 2>&1 || true)
A shell rm -f /data/local/tmp/showylauncher.apk
case "$out" in
  *Success*) echo "Установлено: $(sh_out dumpsys package $PKG | grep -m1 versionName | tr -d ' ')" ;;
  *UPDATE_INCOMPATIBLE*) die "на ТВ стоит лаунчер с другой подписью. Удалите его (adb -s $TV uninstall $PKG) и запустите снова." ;;
  *) die "установка не удалась: $out" ;;
esac
# The TV provider (Live TV channels); install-time on some firmware, then this grant fails harmlessly.
A shell pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA >/dev/null 2>&1 || true
# «Install unknown apps» for the launcher: its updates from GitHub (gear menu → «Обновить»).
A shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
# Lets the launcher put its remote-buttons service back when force-stopping it has cleared the list (KeysService).
A shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true

# The only home screen. The first-run wizard goes first, while Google TV's home still owns Home: once that is off,
# the wizard's recovery screen would claim Home itself. A saved Wi-Fi network owned by the wizard would be deleted
# with it, so then it is left on.
# Google TV's own home then stays on too: with the wizard on and the home off, the wizard's recovery screen takes Home
# (a blank screen, Wi-Fi dropping).
keep_stock=""
if installed com.google.android.tungsten.setupwraith; then
  if sh_out dumpsys wifi | grep -q 'cname=com.google.android.tungsten.setupwraith'; then
    keep_stock=1
    echo "Стандартный экран Google TV оставлен включённым: мастеру первого запуска принадлежит сохранённая сеть Wi-Fi."
    echo "Забудьте эту сеть и подключитесь к ней заново в Настройках, потом запустите скрипт ещё раз."
  else
    sh_out pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null || true
  fi
fi
disabled_homes=""
if [ -z "$keep_stock" ]; then
  for home in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher; do
    if installed "$home"; then sh_out pm disable-user --user 0 "$home" >/dev/null || true; disabled_homes="$disabled_homes $home"; fi
  done
fi
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
home_now=$(sh_out cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)
echo "Главный экран: $home_now"
# set-home-activity can answer Success and change nothing: then Google TV's home comes back rather than no home at all.
if [ "$home_now" != "$PKG/.MainActivity" ] && [ -n "$disabled_homes" ]; then
  for home in $disabled_homes; do A shell pm enable --user 0 "$home" >/dev/null; done
  die "лаунчер не стал главным экраном ($home_now); стандартный экран Google TV включён обратно. Перезагрузите ТВ и запустите скрипт снова."
fi
[ "$(sh_out getprop ro.adb.secure)" = 0 ] && echo "Внимание: этот ТВ пускает отладку без подтверждения — после установки обязательно выключите её (шаг 3)."
installed net.gtvbox.vimuhd || echo "Плеера ViMu нет: фильмы играют во встроенном плеере, а редкие файлы (образы Blu-ray) не откроются. По желанию — «ViMu Media Player for TV» из Play Маркета."
