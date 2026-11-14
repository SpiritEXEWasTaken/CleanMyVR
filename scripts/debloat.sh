#!/usr/bin/env bash
# Remove Meta bloat from a Quest profile (reversible, no root).
# Usage: ./debloat.sh [light|balanced|no-updates|path/to/list.txt] [--dry-run] [--user N]
#   --user N   target another headset profile (see: adb shell pm list users). Default: active one.
source "$(dirname "$0")/common.sh"

LIST=balanced DRY=0 U=
while (($#)); do
  case $1 in
    --dry-run|-n) DRY=1 ;;
    --user) U=${2:?--user needs a number}; shift ;;
    -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
    *) LIST=$1 ;;
  esac
  shift
done
[[ -f $LIST ]] || LIST="$ROOT/lists/$LIST.txt"

require_device
U=${U:-$(current_user)}
installed=$(adb shell pm list packages --user "$U" | sed 's/package://' | tr -d '\r')
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/removed-$(serial)-u$U.txt"

echo "List: $(basename "$LIST")  |  user $U  |  $( ((DRY)) && echo DRY RUN || echo LIVE)"
removed=0 skipped=0 failed=0
while read -r pkg; do
  if ! grep -qx "$pkg" <<<"$installed"; then
    printf '  %-40s not installed, skipping\n' "$pkg"; skipped=$((skipped+1)); continue
  fi
  if ((DRY)); then printf '  %-40s would remove\n' "$pkg"; continue; fi
  out=$(adb shell pm uninstall -k --user "$U" "$pkg" </dev/null 2>&1 | tr -d '\r' || true)
  if [[ $out == Success* ]]; then
    printf '  %-40s removed\n' "$pkg"; echo "$pkg" >> "$LOG"; removed=$((removed+1))
  else
    printf '  %-40s FAILED: %s\n' "$pkg" "$out"; failed=$((failed+1))
  fi
done < <(read_list "$LIST")

((DRY)) && exit 0
sort -u -o "$LOG" "$LOG"
echo
echo "Removed $removed, skipped $skipped, failed $failed. Log: $LOG"
echo "Reboot the headset (adb reboot) and check games still launch. Undo anytime: ./restore.sh"
