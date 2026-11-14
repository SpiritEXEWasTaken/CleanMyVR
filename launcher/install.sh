#!/usr/bin/env bash
# Install the launcher on the headset and make it replace Meta's Library.
# Usage: ./install.sh [--user N]      (--user to install for another headset profile)
#        ./install.sh --uninstall [--user N]
set -euo pipefail
cd "$(dirname "$0")"
PKG=com.cleanmyvr.launcher
SVC="$PKG/$PKG.LibraryInterceptor"

U= UNINSTALL=0
while (($#)); do
  case $1 in
    --user) U=$2; shift ;;
    --uninstall) UNINSTALL=1 ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
U=${U:-$(adb shell am get-current-user | tr -d '\r')}

# Edit the accessibility list for user $U without clobbering other services.
set_service() { # on|off
  local cur new
  cur=$(adb shell settings --user "$U" get secure enabled_accessibility_services | tr -d '\r')
  [[ $cur == null ]] && cur=
  new=$(tr ':' '\n' <<<"$cur" | grep -v "^$PKG/" | grep -v '^$' || true)
  [[ $1 == on ]] && new=$(printf '%s\n%s' "$new" "$SVC" | grep -v '^$')
  new=$(paste -sd: <<<"$new")
  if [[ -z $new ]]; then
    adb shell settings --user "$U" delete secure enabled_accessibility_services >/dev/null
    adb shell settings --user "$U" put secure accessibility_enabled 0
  else
    adb shell settings --user "$U" put secure enabled_accessibility_services "$new"
    adb shell settings --user "$U" put secure accessibility_enabled 1
  fi
}

if ((UNINSTALL)); then
  set_service off
  adb shell pm uninstall --user "$U" "$PKG"
  echo "Launcher removed for user $U. Meta's Library is back."
  exit 0
fi

[[ -f launcher.apk ]] || ./build.sh
adb install -r --user "$U" launcher.apk
adb shell appops set --user "$U" "$PKG" GET_USAGE_STATS allow   # "Continue playing" row
adb shell appops set --user "$U" "$PKG" REQUEST_INSTALL_PACKAGES allow   # Store can install apps
set_service off; sleep 1   # an update unbinds the service; toggling re-binds it
set_service on
echo "Installed for user $U. Library takeover is ON."
echo "Open 'Launcher' from the Library once, or just press the Library button."
