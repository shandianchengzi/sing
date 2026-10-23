#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME to your Android SDK}"
BT="$SDK/build-tools/35.0.0"
API="$SDK/platforms/android-35/android.jar"
# Refuse ephemeral keys: every published APK must retain the established identity.
KEYSTORE="${SIGNING_KEYSTORE:-build/development.jks}"
if [[ ! -f "$KEYSTORE" ]]; then
  echo "Missing fixed signing key. Restore your private backup or configure SING_KEYSTORE_BASE64 in Actions Secrets." >&2
  exit 1
fi
export SIGNING_PASSWORD="${SIGNING_PASSWORD:-android}"
python scripts/mobile-model/prepare_runtime.py
TF_JARS="build/tflite/tensorflow-lite.jar:build/tflite/tensorflow-lite-api.jar"
mkdir -p build/classes build/dex
rm -rf build/classes/* build/dex/*
"$BT/aapt2" compile --dir app/src/main/res -o build/resources.zip
version_args=(--replace-version)
if [[ -n "${VERSION_CODE:-}" ]]; then version_args+=(--version-code "$VERSION_CODE"); fi
if [[ -n "${VERSION_NAME:-}" ]]; then version_args+=(--version-name "$VERSION_NAME"); fi
asset_args=()
if [[ -n "${OFFLINE_ASSETS:-}" ]]; then asset_args+=(-A "$OFFLINE_ASSETS"); fi
"$BT/aapt2" link -o build/base.apk -I "$API" --manifest app/src/main/AndroidManifest.xml "${version_args[@]}" "${asset_args[@]}" build/resources.zip
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$API:$BT/core-lambda-stubs.jar" -classpath "$TF_JARS" -d build/classes app/src/main/java/com/shandian/sing/*.java
find build/classes -name '*.class' > build/classes.list
if [[ "${NO_SHRINK:-0}" == 1 ]]; then
  "$BT/d8" --release --min-api 26 --lib "$API" --output build/dex @build/classes.list build/tflite/tensorflow-lite.jar build/tflite/tensorflow-lite-api.jar
else
  jar cf build/classes.jar -C build/classes .
  java -Xmx1g -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --lib "$API" --pg-conf shrink-rules.pro --output build/dex build/classes.jar build/tflite/tensorflow-lite.jar build/tflite/tensorflow-lite-api.jar
fi
python scripts/pack_apk.py build/base.apk build/dex/classes.dex build/unsigned.apk build/tflite/lib
"$BT/zipalign" -P 16 -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --v1-signing-enabled false --ks "$KEYSTORE" --ks-pass env:SIGNING_PASSWORD --ks-key-alias "${SIGNING_ALIAS:-androiddebugkey}" --out build/sing.apk build/aligned.apk
expected="$(tr -d '[:space:]' < signing/certificate.sha256)"
actual="$("$BT/apksigner" verify --print-certs build/sing.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ "$actual" != "$expected" ]]; then
  rm -f build/sing.apk
  echo "Signing certificate mismatch: refusing to publish an incompatible update." >&2
  exit 1
fi
"$BT/apksigner" verify --verbose build/sing.apk
wc -c build/sing.apk
