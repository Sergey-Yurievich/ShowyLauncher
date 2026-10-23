# Shared by the scripts in scripts/: finds adb, connects to the TV whose IP is given, and defines A (adb on that TV).
# Sourced, not run. Works in Terminal on macOS and Linux, and in Git Bash on Windows.

die() { echo "Ошибка: $*" >&2; exit 1; }

find_adb() {
  [ -n "${ADB:-}" ] && return
  local c
  for c in adb "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb" \
           "${LOCALAPPDATA:-/nonexistent}/Android/Sdk/platform-tools/adb.exe" "$HOME/platform-tools/adb" \
           "$HOME/platform-tools/adb.exe" "./platform-tools/adb" "./platform-tools/adb.exe"; do
    if command -v "$c" >/dev/null 2>&1; then ADB="$c"; return; fi
  done
  die "не найден adb. Скачайте Android SDK Platform-Tools (https://developer.android.com/tools/releases/platform-tools),
распакуйте в домашнюю папку (получится ~/platform-tools) или укажите путь: ADB=/путь/к/adb $0 ..."
}

# connect_tv <IP>: sets HOST and TV, waits up to a minute for the "Allow USB debugging?" prompt on the TV.
connect_tv() {
  HOST="${1:-}"
  [ -n "$HOST" ] || die "укажите IP телевизора, например: $0 192.168.1.50"
  TV="$HOST:5555"
  find_adb
  "$ADB" connect "$TV" >/dev/null 2>&1 || true
  local i state asked=""
  for i in $(seq 1 30); do
    state=$("$ADB" -s "$TV" get-state 2>&1 | tr -d '\r' || true)
    case "$state" in
      device) return ;;
      *unauthorized*)
        [ -n "$asked" ] || echo "На экране ТВ: «Разрешить отладку?» — отметьте «Всегда разрешать с этого компьютера» и нажмите «Разрешить»."
        asked=1 ;;
      *) "$ADB" connect "$TV" >/dev/null 2>&1 || true ;;
    esac
    sleep 2
  done
  die "нет связи с $TV ($state). Проверьте: ТВ включён, IP верный, в «Для разработчиков» включена «Отладка по USB»
(и «Отладка по сети», если такой пункт есть), компьютер и ТВ в одной сети."
}

A() { "$ADB" -s "$TV" "$@"; }

# Output of an adb shell command without the trailing carriage returns.
sh_out() { A shell "$@" | tr -d '\r'; }

installed() { [ -n "$(sh_out pm path "$1" 2>/dev/null)" ]; }

is_tcl() { sh_out getprop ro.product.manufacturer | grep -qi tcl; }

# Packages of one batch of the list file, or of several: batch_packages <file> <n>...
batch_packages() {
  local file="$1"; shift
  local n
  for n in "$@"; do
    awk -v n="$n" '/^# Batch /{on=($3==n":")} on && /^[a-zA-Z]/{print $1}' "$file"
  done
}
