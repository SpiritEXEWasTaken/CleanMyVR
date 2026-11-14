#!/usr/bin/env bash
# Repackage an installed Quest environment's scene under another environment's package name.
#
# Why: if your headset is stuck on an environment Meta removed (e.g. the old "Cyber" home,
# com.oculus.environment.prod.cyberhome), Home shows a white void. Installing a package with
# that exact name, carrying a scene from an environment you DO have, gives the shell something
# to load. Fully reversible: adb uninstall <target package>.
#
# Usage: ./build-env.sh <source-env.apk | scene.zip> [target.package] [--install]
#   scene.zip        a scene archive, e.g. from voxelworld.py (classic glTF .ovrscene)
#   source-env.apk   a pulled environment APK, e.g. from:
#                    adb pull $(adb shell pm path com.meta.environment.prod.theaterhome | cut -d: -f2)
#   target.package   default: com.oculus.environment.prod.cyberhome
set -euo pipefail
cd "$(dirname "$0")"

SRC=${1:?usage: ./build-env.sh <source-env.apk> [target.package] [--install]}
TARGET=com.oculus.environment.prod.cyberhome INSTALL=0 LEGACY=0
for a in "${@:2}"; do
  case $a in
    --install) INSTALL=1 ;;
    --legacy) LEGACY=1 ;;   # omit HSR metadata (classic glTF .ovrscene environments)
    *) TARGET=$a ;;
  esac
done

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
if [[ -z ${JAVA_HOME:-} && -x $HOME/.local/opt/jdk17/bin/java ]]; then export JAVA_HOME=$HOME/.local/opt/jdk17; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

TYPE=
if [[ $SRC != *.zip ]]; then
  TYPE=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$SRC" \
    | grep -A1 'hsr_package_type' | grep -oE 'Raw: "[a-z]+"' | cut -d'"' -f2 || true)
fi
TYPE=${TYPE:-combined}

OUT=build/$TARGET
rm -rf "$OUT" && mkdir -p "$OUT/assets"
if [[ $SRC == *.zip ]]; then cp "$SRC" "$OUT/assets/scene.zip"; else
python3 -I -c '
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.startswith("assets/"):
        z.extract(n, sys.argv[2])
' "$SRC" "$OUT"
fi

cat > "$OUT/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$TARGET" android:versionCode="1" android:versionName="1.0-cleanmyvr">
    <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="34" />
    <application android:hasCode="false" android:allowBackup="false" android:label="Environment ($TARGET)">
$( ((LEGACY)) || printf '        <meta-data android:name="com.meta.shell.env.hsr_package_type" android:value="%s" />\n        <meta-data android:name="com.meta.shell.env.compatible_packages" android:value="None" />' "$TYPE")
    </application>
</manifest>
EOF

# scene.zip must stay uncompressed (the shell maps it straight out of the APK)
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest "$OUT/AndroidManifest.xml" \
  -A "$OUT/assets" -0 zip -0 bin -0 txt
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-$HOME/.android/cleanmyvr-launcher.keystore}
if [[ ! -f $KS ]]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias launcher \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=cleanmyvr" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$TARGET.apk" "$OUT/aligned.apk"
echo "Built $(pwd)/$TARGET.apk  (scene from $(basename "$SRC"), type=$TYPE)"

if ((INSTALL)); then
  # Install for every profile, attributed to the Meta Store installer like real environments
  adb install -r ${ENV_USER:+--user $ENV_USER} -i com.oculus.ocms "$TARGET.apk"
  echo "Installed. Restart Home with: adb shell am force-stop com.oculus.vrshell"
  echo "Undo with: adb uninstall $TARGET"
fi
