#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/tests
javac -encoding UTF-8 -d build/tests app/src/main/java/com/shandian/sing/Lrc.java tests/LrcTest.java
java -cp build/tests com.shandian.sing.LrcTest
javac -encoding UTF-8 -d build/tests app/src/main/java/com/shandian/sing/LibraryNames.java tests/LibraryNamesTest.java
java -cp build/tests com.shandian.sing.LibraryNamesTest
javac -encoding UTF-8 -d build/tests app/src/main/java/com/shandian/sing/LibraryNames.java app/src/main/java/com/shandian/sing/RecordingNames.java tests/RecordingNamesTest.java
java -cp build/tests com.shandian.sing.RecordingNamesTest

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME for audio tests}"
API="$SDK/platforms/android-35/android.jar"
javac -encoding UTF-8 -cp "$API" -d build/tests app/src/main/java/com/shandian/sing/Timeline.java app/src/main/java/com/shandian/sing/WaveFile.java app/src/main/java/com/shandian/sing/PcmAudio.java tests/AudioTest.java
java -cp "build/tests:$API" com.shandian.sing.AudioTest
javac -encoding UTF-8 -d build/tests app/src/main/java/com/shandian/sing/UpdateInfo.java tests/UpdateTest.java
java -cp build/tests com.shandian.sing.UpdateTest

javac -encoding UTF-8 -cp "build/tests:$API" -d build/tests app/src/main/java/com/shandian/sing/TakeInfo.java app/src/main/java/com/shandian/sing/TakeAudio.java app/src/main/java/com/shandian/sing/TakePlayback.java app/src/main/java/com/shandian/sing/TakeExport.java tests/PlaybackTest.java
java -cp "build/tests:$API" com.shandian.sing.PlaybackTest
