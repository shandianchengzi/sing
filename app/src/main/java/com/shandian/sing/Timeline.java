package com.shandian.sing;

final class Timeline {
  static int voicePosition(long absoluteMs, long startMs, int durationMs) {
    return (int) Math.max(0, Math.min(durationMs, absoluteMs - startMs));
  }

  static short mix(double voice, double backing) {
    return (short) Math.max(-32768, Math.min(32767, Math.round((voice + backing) * .5)));
  }
}
