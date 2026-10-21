package com.shandian.sing;

final class Timeline {
  static int voicePosition(long absoluteMs, long startMs, int durationMs) {
    return (int) Math.max(0, Math.min(durationMs, absoluteMs - startMs));
  }

  static short mix(double voice, double backing) {
    return mix(voice, backing, 1, 1);
  }

  static float gain(float value) {
    return Math.max(0, Math.min(1, value));
  }

  static short scale(double value, float gain) {
    return (short) Math.max(-32768, Math.min(32767, Math.round(value * gain(gain))));
  }

  static short mix(double voice, double backing, float v, float b) {
    return scale((voice * gain(v) + backing * gain(b)) * .5, 1);
  }
}
