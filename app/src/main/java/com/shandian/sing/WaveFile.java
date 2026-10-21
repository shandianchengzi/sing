package com.shandian.sing;

import java.io.*;

final class WaveFile {
  static void header(OutputStream out, long frames, int rate) throws IOException {
    long bytes = frames * 4;
    if (bytes > 0xffffffffL - 36) throw new IOException("混音超过 WAV 大小限制");
    out.write(new byte[] {'R', 'I', 'F', 'F'});
    number(out, bytes + 36, 4);
    out.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    number(out, 16, 4);
    number(out, 1, 2);
    number(out, 2, 2);
    number(out, rate, 4);
    number(out, rate * 4, 4);
    number(out, 4, 2);
    number(out, 16, 2);
    out.write(new byte[] {'d', 'a', 't', 'a'});
    number(out, bytes, 4);
  }

  static void number(OutputStream out, long number, int bytes) throws IOException {
    for (int i = 0; i < bytes; i++) out.write((int) (number >> (i * 8)) & 255);
  }
}
