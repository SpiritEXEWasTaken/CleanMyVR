#!/usr/bin/env bash
# Build the launcher APK with plain SDK tools (no Gradle / Android Studio needed).
# Needs: JDK 17+, Android SDK with build-tools 34+ and platforms;android-34+.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
JAR="$PLATFORM/android.jar"
[[ -f $JAR ]] || { echo "android.jar not found under $SDK/platforms" >&2; exit 1; }
if [[ -z ${JAVA_HOME:-} && -x $HOME/.local/opt/jdk17/bin/javac ]]; then JAVA_HOME=$HOME/.local/opt/jdk17; fi
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

OUT=build
# The launcher shares the app's version (app/cleanmyvr/__init__.py), so every release updates in place.
VERSION=$(sed -n 's/^__version__ = "\(.*\)"/\1/p' ../app/cleanmyvr/__init__.py)
IFS=. read -r V1 V2 V3 <<<"$VERSION"
VERSION_CODE=$(( V1 * 10000 + V2 * 100 + V3 ))
rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex}

"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --version-name "$VERSION" --version-code "$VERSION_CODE" --replace-version \
  --java "$OUT/gen" -A assets "$OUT/res/res.zip"

"$JAVAC" -source 11 -target 11 -Xlint:-options -encoding UTF-8 -classpath "$JAR" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --min-api 29 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
  "$OUT/unsigned.apk" "$OUT/dex/classes.dex"

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-$HOME/.android/cleanmyvr-launcher.keystore}
if [[ ! -f $KS ]]; then
  mkdir -p "$(dirname "$KS")"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android -alias launcher \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=cleanmyvr launcher" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:${KEYSTORE_PASS:-android}" --key-pass "pass:${KEYSTORE_PASS:-android}" \
  --out launcher.apk "$OUT/aligned.apk"
echo "Built $(pwd)/launcher.apk"
