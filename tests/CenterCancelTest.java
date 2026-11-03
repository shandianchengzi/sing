package com.shandian.sing;

final class CenterCancelTest {
  private static void equal(int actual, int expected) {
    if (actual != expected) throw new AssertionError(actual + " != " + expected);
  }
  public static void main(String[] args) {
    equal(CenterCancel.sample((short) 30000, (short) 30000), 0);
    equal(CenterCancel.sample((short) 1000, (short) -1000), 1000);
    equal(CenterCancel.sample(Short.MIN_VALUE, Short.MAX_VALUE), -32767);
    byte[] pcm = { 0x10, 0x27, 0x10, 0x27, (byte) 0xe8, 0x03, 0x18, (byte) 0xfc };
    CenterCancel.stereoPcm16(pcm, pcm.length);
    equal((short) ((pcm[0] & 255) | (pcm[1] << 8)), 0);
    equal((short) ((pcm[2] & 255) | (pcm[3] << 8)), 0);
    equal((short) ((pcm[4] & 255) | (pcm[5] << 8)), 1000);
    equal((short) ((pcm[6] & 255) | (pcm[7] << 8)), 1000);
    try {
      CenterCancel.stereoPcm16(new byte[3], 3);
      throw new AssertionError("Accepted a partial frame");
    } catch (IllegalArgumentException expected) {}
    System.out.println("CenterCancelTest OK");
  }
}
