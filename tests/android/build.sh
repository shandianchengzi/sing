#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/35.0.0"
API="$SDK/platforms/android-35/android.jar"
mkdir -p build/android-tests/classes build/android-tests/dex
"$BT/aapt2" link -o build/android-tests/base.apk -I "$API" --manifest tests/android/AndroidManifest.xml -A build/android-test-assets
javac -source 8 -target 8 -bootclasspath "$API:$BT/core-lambda-stubs.jar" -d build/android-tests/classes tests/android/LocalSeparationInstrumentation.java
find build/android-tests/classes -name '*.class' > build/android-tests/classes.list
"$BT/d8" --release --min-api 26 --lib "$API" --output build/android-tests/dex @build/android-tests/classes.list
python scripts/pack_apk.py build/android-tests/base.apk build/android-tests/dex/classes.dex build/android-tests/unsigned.apk
"$BT/zipalign" -f 4 build/android-tests/unsigned.apk build/android-tests/aligned.apk
"$BT/apksigner" sign --v1-signing-enabled false --ks "$SIGNING_KEYSTORE" --ks-pass env:SIGNING_PASSWORD --ks-key-alias "${SIGNING_ALIAS:-androiddebugkey}" --out build/android-tests/tests.apk build/android-tests/aligned.apk
