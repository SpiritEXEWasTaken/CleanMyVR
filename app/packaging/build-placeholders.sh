#!/usr/bin/env bash
# Build the "block permanently" placeholders (see app/cleanmyvr/blocker.py): one tiny APK per package in the
# headset profiles' "blockable" lists, with no code, a huge version number and the placeholder key.
# Maintainers only; the APKs are committed in app/cleanmyvr/placeholders/. Re-run when a list changes.
# Needs the Android SDK (aapt2, zipalign, apksigner) and JDK 17.
#   KEYSTORE (default ~/.android/cleanmyvr-placeholder.keystore), KEYSTORE_PASS (default: contents of
#   ~/.android/cleanmyvr-placeholder.pass). CleanMyVR recognises a placeholder by its version number; the key
#   only matters when a new placeholder replaces an installed one, so keep using the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
if [[ -z ${JAVA_HOME:-} && -x $HOME/.local/opt/jdk17/bin/java ]]; then export JAVA_HOME=$HOME/.local/opt/jdk17; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
KS=${KEYSTORE:-$HOME/.android/cleanmyvr-placeholder.keystore}
PASS=${KEYSTORE_PASS:-$(cat "$HOME/.android/cleanmyvr-placeholder.pass")}
OUT=cleanmyvr/placeholders
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
python3 - <<'PY' > "$TMP/list.tsv"
import json, glob
seen = {}
for f in sorted(glob.glob("cleanmyvr/devices/*.json")):
    for it in json.load(open(f)).get("blockable", []):
        seen[it["pkg"]] = it.get("name", it["pkg"])
for pkg, name in seen.items():
    print(f"{pkg}\t{name}")
PY
while IFS=$'\t' read -r pkg name; do
  label=$(printf '%s (blocked by CleanMyVR)' "$name" | sed 's/&/\&amp;/g')
  cat > "$TMP/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg"
    android:versionCode="2100000000" android:versionName="blocked-by-cleanmyvr">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34" />
    <application android:label="$label" android:hasCode="false" android:allowBackup="false" />
</manifest>
XML
  "$BT/aapt2" link -o "$TMP/u.apk" -I "$JAR" --manifest "$TMP/AndroidManifest.xml"
  "$BT/zipalign" -f 4 "$TMP/u.apk" "$TMP/a.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out "$OUT/$pkg.apk" "$TMP/a.apk"
  rm -f "$OUT/$pkg.apk.idsig"
  echo "built $OUT/$pkg.apk"
done < "$TMP/list.tsv"
