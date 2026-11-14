#!/usr/bin/env bash
# Free space: show what's big, optionally delete captures, clear app caches.
# Usage: ./clean-storage.sh            report only
#        ./clean-storage.sh --caches   also clear all app caches (safe; apps rebuild them)
#        ./clean-storage.sh --media    also DELETE all screenshots/recordings/photos/videos (asks first)
source "$(dirname "$0")/common.sh"

CACHES=0 MEDIA=0
for a in "$@"; do
  case $a in
    --caches) CACHES=1 ;;
    --media) MEDIA=1 ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) die "unknown option $a" ;;
  esac
done

require_device
MEDIA_DIRS=(/sdcard/Oculus/VideoShots /sdcard/Oculus/Screenshots /sdcard/Movies /sdcard/DCIM /sdcard/Pictures)

echo "Storage: $(free_space)"
echo
echo "Biggest apps (install + data, from Android's periodic size snapshot):"
INSTALLED=$(adb shell pm list packages | sed 's/package://' | tr -d '\r' | tr '\n' ' ')
adb shell dumpsys diskstats | INSTALLED="$INSTALLED" python3 -c '
import re, json, sys, os
ok = set(os.environ["INSTALLED"].split())
t = sys.stdin.read()
arr = lambda k: json.loads(re.search(k + r": (\[.*?\])", t).group(1))
rows = sorted(zip(arr("Package Names"), arr("App Sizes"), arr("App Data Sizes")), key=lambda r: -(r[1] + r[2]))
rows = [r for r in rows if r[0] in ok]
for name, app, data in rows[:15]:
    print(f"  {(app + data) / 1e9:6.2f} GB  {name}")
'
echo
echo "Captures (screenshots, recordings, photos, videos):"
for d in "${MEDIA_DIRS[@]}"; do adb shell du -sh "'$d'" 2>/dev/null | sed 's/^/  /'; done

if ((MEDIA)); then
  echo
  read -rp "Delete ALL files in the folders above? This cannot be undone. [y/N] " ans
  if [[ $ans == [yY]* ]]; then
    for d in "${MEDIA_DIRS[@]}"; do adb shell "find '$d' -type f -delete" >/dev/null 2>&1 || true; done
    echo "Media deleted."
  fi
fi

if ((CACHES)); then
  adb shell pm trim-caches 999G
  echo "App caches cleared."
fi

echo
echo "Storage now: $(free_space)"
echo "To remove a game: adb shell pm uninstall <package>   (or uninstall it from the Library in the headset)"
