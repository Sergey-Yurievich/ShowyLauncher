#!/usr/bin/env bash
# Switches off the TV's tracking, ads and junk from scripts/tcl-google-tv.txt, batch by batch. Nothing is
# uninstalled: packages are disabled (pm disable-user), and scripts/restore.sh turns them back on.
#
# Usage: scripts/debloat.sh <IP> 1           one batch (see the list file); then check the TV before the next one
#        scripts/debloat.sh <IP> 1 2 3       several
#        scripts/debloat.sh <IP> safe        batches 1-3: tracking, ads, demo modes
#        LIST=my-list.txt scripts/debloat.sh <IP> 1    another list in the same format
# Batch 4 is apps by choice: read it and delete the lines you need first. Batch 5 only after the launcher is in.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/common.sh
LIST="${LIST:-scripts/tcl-google-tv.txt}"
[ $# -ge 2 ] || die "укажите IP и номер партии, например: $0 192.168.1.50 1   (партии — в $LIST)"
connect_tv "$1"; shift
batches=("$@")
[ "${batches[0]}" = safe ] && batches=(1 2 3)

if [ -z "${FORCE:-}" ]; then
  is_tcl || die "это не TCL ($(sh_out getprop ro.product.manufacturer)). Список $LIST проверен только на TCL G08.
Если уверены: FORCE=1 $0 $HOST ${batches[*]}"
  # On other TCL boards some of these packages do more: com.tcl.ttvs carries firmware updates on G07 and G10.
  [ "$(sh_out getprop ro.product.device)" = G08 ] || die "это TCL $(sh_out getprop ro.product.device), а список проверен только
на G08: на других моделях часть пакетов (например com.tcl.ttvs) отвечает за обновления прошивки.
Сверьте список со своей моделью; если уверены: FORCE=1 $0 $HOST ${batches[*]}"
fi
for b in "${batches[@]}"; do
  if [ "$b" = 5 ]; then
    installed ru.homescreen.tv || die "партия 5 — только после установки лаунчера (scripts/install.sh $HOST)"
    # Without the launcher's remote-buttons service the Input button would do nothing at all.
    sh_out dumpsys accessibility | grep -q 'Bound services:{.*Кнопки пульта' ||
      die "служба кнопок пульта лаунчера не работает: запустите scripts/install.sh $HOST ещё раз, потом партию 5"
  fi
done

# The first change on this TV: keep a record of how it was.
ls -d backups/*-"$(serial)" >/dev/null 2>&1 || scripts/backup.sh "$HOST"
# Disabling the app a saved Wi-Fi network belongs to deletes that network: such packages are left on.
owners=$(sh_out dumpsys wifi | grep -o 'cname=[^ ]*' | sed 's/^cname=//' | sort -u || true)

pkgs=$(batch_packages "$LIST" "${batches[@]}")
[ -n "$pkgs" ] || die "в $LIST нет партий ${batches[*]}"
for p in $pkgs; do
  printf '%-45s ' "$p"
  if ! installed "$p"; then echo "нет на этом ТВ"; continue; fi
  if echo "$owners" | grep -qx "$p"; then echo "оставлен: ему принадлежит сохранённая сеть Wi-Fi"; continue; fi
  # One pm per adb call: chained pm commands can stop after the first.
  sh_out pm disable-user --user 0 "$p" 2>&1 | tail -n 1 || true
done
echo
echo "Готово. Проверьте ТВ (главный экран, пульт, HDMI, звук), потом следующая партия."
echo "Что-то сломалось: scripts/restore.sh $HOST <пакет> — или scripts/restore.sh $HOST, чтобы вернуть всё."
