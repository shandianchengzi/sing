package com.shandian.sing;

import java.io.*;
import java.nio.*;

public class AudioTest {
  static void check(boolean b) {
    if (!b) throw new AssertionError();
  }

  public static void main(String[] args) throws Exception {
    check(Timeline.voicePosition(1000, 12000, 5000) == 0);
    check(Timeline.voicePosition(13750, 12000, 5000) == 1750);
    check(Timeline.voicePosition(90000, 12000, 5000) == 5000);
    check(Timeline.mix(32767, 32767) == 32767);
    check(Timeline.mix(-32768, -32768) == -32768);
    check(Timeline.mix(20000, -20000) == 0);
    check(Timeline.mix(1000, 3000) == 2000);
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    WaveFile.header(header, 48000, 48000);
    byte[] h = header.toByteArray();
    check(h.length == 44);
    ByteBuffer fields = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
    check(fields.getInt(4) == 192036);
    check(fields.getShort(22) == 2);
    check(fields.getInt(24) == 48000);
    check(fields.getInt(40) == 192000);
    File temp = File.createTempFile("resample", ".pcm");
    try {
      try (OutputStream out = new FileOutputStream(temp)) {
        for (int sample : new int[] {0, 1000, 2000, 3000}) WaveFile.number(out, sample, 2);
      }
      PcmAudio pcm = new PcmAudio(temp);
      pcm.rate = 44100;
      pcm.channels = 1;
      pcm.frames = 4;
      double[] sample = new double[2];
      try (PcmAudio.Reader reader = new PcmAudio.Reader(pcm)) {
        reader.sample(0, 48000, sample);
        check(sample[0] == 0 && sample[1] == 0);
        reader.sample(1, 48000, sample);
        check(Math.abs(sample[0] - 918.75) < .0001);
        check(sample[0] == sample[1]);
        reader.sample(5, 48000, sample);
        check(sample[0] == 0 && sample[1] == 0);
      }
      try (OutputStream out = new FileOutputStream(temp)) {
        for (int value : new int[] {-30000, 30000, 1000, -1000}) WaveFile.number(out, value, 2);
      }
      pcm.rate = 48000;
      pcm.channels = 2;
      pcm.frames = 2;
      try (PcmAudio.Reader reader = new PcmAudio.Reader(pcm)) {
        reader.sample(0, 48000, sample);
        check(sample[0] == -30000 && sample[1] == 30000);
        reader.sample(1, 48000, sample);
        check(sample[0] == 1000 && sample[1] == -1000);
      }
    } finally {
      temp.delete();
    }
    System.out.println("Audio timeline, clipping, resampling, stereo and WAV tests passed");
  }
}
