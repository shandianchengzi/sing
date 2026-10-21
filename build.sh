#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME to your Android SDK}"
BT="$SDK/build-tools/35.0.0"
API="$SDK/platforms/android-35/android.jar"
mkdir -p build/classes build/dex
rm -rf build/classes/* build/dex/*
"$BT/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$BT/aapt2" link -o build/base.apk -I "$API" --manifest app/src/main/AndroidManifest.xml build/resources.zip
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$API:$BT/core-lambda-stubs.jar" -d build/classes app/src/main/java/com/shandian/sing/*.java
find build/classes -name '*.class' > build/classes.list
"$BT/d8" --release --min-api 26 --lib "$API" --output build/dex @build/classes.list
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -9 ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Generate a local test key; never commit signing keys.
if [[ -z "${SIGNING_KEYSTORE:-}" && ! -f build/development.jks ]]; then
  keytool -genkeypair -keystore build/development.jks -storepass android -keypass android -alias androiddebugkey -dname "CN=Sing Development" -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks "${SIGNING_KEYSTORE:-build/development.jks}" --ks-pass "pass:${SIGNING_PASSWORD:-android}" --ks-key-alias "${SIGNING_ALIAS:-androiddebugkey}" --out build/sing.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/sing.apk
wc -c build/sing.apk
