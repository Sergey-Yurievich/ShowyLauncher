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

if ! is_tcl && [ -z "${FORCE:-}" ]; then
  die "это не TCL ($(sh_out getprop ro.product.manufacturer)). Список $LIST проверен только на TCL Google TV.
Если уверены: FORCE=1 $0 $HOST ${batches[*]}"
fi
for b in "${batches[@]}"; do
  if [ "$b" = 5 ] && ! installed ru.homescreen.tv; then
    die "партия 5 — только после установки лаунчера (scripts/install.sh $HOST)"
  fi
done

# The first change on this TV: keep a record of how it was.
ls -d backups/*-"$HOST" >/dev/null 2>&1 || scripts/backup.sh "$HOST"

pkgs=$(batch_packages "$LIST" "${batches[@]}")
[ -n "$pkgs" ] || die "в $LIST нет партий ${batches[*]}"
for p in $pkgs; do
  printf '%-45s ' "$p"
  if ! installed "$p"; then echo "нет на этом ТВ"; continue; fi
  # One pm per adb call: chained pm commands can stop after the first.
  sh_out pm disable-user --user 0 "$p" 2>&1 | tail -n 1 || true
done
echo
echo "Готово. Проверьте ТВ (главный экран, пульт, HDMI, звук), потом следующая партия."
echo "Что-то сломалось: scripts/restore.sh $HOST <пакет> — или scripts/restore.sh $HOST, чтобы вернуть всё."
