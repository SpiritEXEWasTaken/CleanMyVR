# shellcheck shell=bash
# Shared helpers for cleanmyvr scripts.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/logs"

die() { echo "error: $*" >&2; exit 1; }

require_device() {
  command -v adb >/dev/null || die "adb not found. Install Android platform-tools."
  local n
  n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
  [[ $n -ge 1 ]] || die "no authorized device. Plug in the headset, enable Developer Mode, accept the USB debugging prompt."
  [[ $n -eq 1 ]] || die "more than one device connected; set ANDROID_SERIAL."
  local model
  model=$(adb shell getprop ro.product.model | tr -d '\r')
  [[ $model == Quest* ]] || echo "warning: device model is '$model', not a Quest." >&2
}

current_user() { adb shell am get-current-user | tr -d '\r'; }

serial() { adb get-serialno | tr -d '\r'; }

# Expand a list file: strip comments, follow "@include other.txt".
read_list() {
  local file=$1 line
  [[ -f $file ]] || die "list not found: $file"
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%%#*}
    line=$(echo "$line" | xargs)
    [[ -z $line ]] && continue
    if [[ $line == @include* ]]; then
      read_list "$(dirname "$file")/${line#@include }"
    else
      echo "$line"
    fi
  done < "$file"
}

free_space() { adb shell df -h /sdcard | tail -1 | awk '{print $4 " free of " $2}'; }
