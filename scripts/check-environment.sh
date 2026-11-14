#!/usr/bin/env bash
# Diagnose the "white sky / empty void" home environment bug.
# Restarts the home shell (harmless, like reopening Home), reads its log, and reports
# whether your selected environment is actually installed.
source "$(dirname "$0")/common.sh"

require_device
echo "Restarting the home shell and watching it load (about 20s)..."
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
adb logcat -T 1 > "$TMP" &
LC=$!
sleep 2
adb shell am force-stop com.oculus.vrshell
sleep 18
kill "$LC" 2>/dev/null || true

missing=$(grep -oE 'Could not get package info for \(([^)]*environment[^)]*|[^)]*\.env\.[^)]*)\)' "$TMP" \
  | sed -E 's/.*\((.*)\)/\1/' | sort -u || true)

echo
echo "Installed environments:"
adb shell pm list packages | sed 's/package://' | tr -d '\r' \
  | grep -E '\.environment\.|\.env\.' | sed 's/^/  /'

echo
if [[ -n $missing ]]; then
  echo "PROBLEM: your home is set to an environment that is NOT installed:"
  echo "$missing" | sed 's/^/  /'
  echo
  echo "Meta removed or never downloaded it, so Home renders an empty white/grey void."
  echo "(Meta's UI calls the old default environment 'Loft'; it may say 'Loft downloading...' forever.)"
  echo "Fix (no factory reset needed): install a stand-in under that name. See README, or run:"
  echo "  envstub/build-env.sh <a pulled environment .apk> $(echo "$missing" | head -1) --install"
elif grep -q 'Invalid environment root' "$TMP"; then
  echo "The selected environment failed to load, but its package is installed."
  echo "Try switching to a different environment and back, then reboot (adb reboot)."
else
  echo "No environment problems detected."
fi
