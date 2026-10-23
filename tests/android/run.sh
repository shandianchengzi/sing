#!/usr/bin/env bash
set -euo pipefail
adb install -r build/sing-offline.apk
adb install -r build/android-tests/tests.apk
adb shell pm grant com.shandian.sing android.permission.POST_NOTIFICATIONS
adb shell am instrument -w com.shandian.sing.smoke/.LocalSeparationInstrumentation | tee build/android-tests/result.txt
rg -q 'INSTRUMENTATION_RESULT: passed=6' build/android-tests/result.txt
rg -q 'INSTRUMENTATION_CODE: -1' build/android-tests/result.txt
adb shell am start -n com.shandian.sing/.MainActivity
adb shell input keyevent 4
if adb logcat -d -b crash | rg -q 'Process: com.shandian.sing,'; then
  adb logcat -d -b crash > build/android-tests/crash.txt
  exit 1
fi
