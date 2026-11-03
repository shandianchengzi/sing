package com.shandian.sing;

/** Model-free center-channel reduction for stereo PCM16. */
final class CenterCancel {
  private CenterCancel() {}

  static short sample(short left, short right) {
    return (short) (((int) left - (int) right) / 2);
  }

  /** Duplicate the difference channel so the output also works on mono speakers. */
  static void stereoPcm16(byte[] pcm, int bytes) {
    if (bytes < 0 || bytes > pcm.length || (bytes & 3) != 0)
      throw new IllegalArgumentException("Invalid stereo PCM16 buffer");
    for (int i = 0; i < bytes; i += 4) {
      short left = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
      short right = (short) ((pcm[i + 2] & 255) | (pcm[i + 3] << 8));
      short difference = sample(left, right);
      pcm[i] = pcm[i + 2] = (byte) difference;
      pcm[i + 1] = pcm[i + 3] = (byte) (difference >> 8);
    }
  }
}
