#!/usr/bin/env bash
set -euo pipefail
adb install -r build/sing-offline.apk
adb install -r build/android-tests/tests.apk
adb shell pm grant com.shandian.sing android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -r com.shandian.sing.smoke/.LocalSeparationInstrumentation | tee build/android-tests/result.txt
grep -q 'INSTRUMENTATION_RESULT: passed=6' build/android-tests/result.txt
grep -q 'INSTRUMENTATION_CODE: -1' build/android-tests/result.txt
# Program-only APK must reuse the already installed model without network/assets.
adb install -r build/sing.apk
adb shell am instrument -w -r com.shandian.sing.smoke/.LocalSeparationInstrumentation | tee build/android-tests/update-result.txt
grep -q 'INSTRUMENTATION_RESULT: passed=6' build/android-tests/update-result.txt
grep -q 'INSTRUMENTATION_CODE: -1' build/android-tests/update-result.txt
adb shell am start -n com.shandian.sing/.MainActivity
adb shell input keyevent 4
adb logcat -d -b crash > build/android-tests/crash.txt
if grep -q 'Process: com.shandian.sing,' build/android-tests/crash.txt; then
  cat build/android-tests/crash.txt
  exit 1
fi
