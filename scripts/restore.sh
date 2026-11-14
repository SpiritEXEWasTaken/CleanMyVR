#!/usr/bin/env bash
# Bring back packages removed by debloat.sh.
# Usage: ./restore.sh [--user N]              restore everything in logs/removed-<serial>-u<N>.txt
#        ./restore.sh [--user N] pkg [pkg..]  restore specific packages
source "$(dirname "$0")/common.sh"

U=
if [[ ${1:-} == --user ]]; then U=${2:?--user needs a number}; shift 2; fi
require_device
U=${U:-$(current_user)}
LOG="$LOG_DIR/removed-$(serial)-u$U.txt"

if (($#)); then pkgs=("$@")
else
  [[ -f $LOG ]] || die "no removal log at $LOG. Pass package names instead."
  mapfile -t pkgs < "$LOG"
fi

for pkg in "${pkgs[@]}"; do
  out=$(adb shell cmd package install-existing --user "$U" "$pkg" </dev/null 2>&1 | tr -d '\r' || true)
  if [[ $out == *"installed for user"* ]]; then
    printf '  %-40s restored\n' "$pkg"
    [[ -f $LOG ]] && sed -i "/^${pkg//./\\.}\$/d" "$LOG"
  else
    printf '  %-40s could not restore (%s). Reinstall it from the Meta Store.\n' "$pkg" "$out"
  fi
done
echo "Done. Reboot recommended: adb reboot"
