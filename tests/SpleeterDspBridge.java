package com.shandian.sing;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Host-side bridge: exercise production Java DSP with real TF/TFLite masks. */
public final class SpleeterDspBridge {
  public static void main(String[] args) throws Exception {
    float[] samples;
    ByteBuffer audio = ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
    samples = new float[audio.remaining() / 4]; audio.asFloatBuffer().get(samples);
    try (OutputStream magnitudes = new BufferedOutputStream(new FileOutputStream(args[1]));
         OutputStream wave = new BufferedOutputStream(new FileOutputStream(args[3]))) {
      final int[] block = {0};
      SpleeterDsp.separate(samples.length / 2,
        (at, stereo) -> { stereo[0] = samples[(int)at*2]; stereo[1] = samples[(int)at*2+1]; },
        (l,r) -> { write(wave,l); write(wave,r); },
        (in,out) -> {
          byte[] bytes = new byte[in.remaining()]; in.get(bytes); magnitudes.write(bytes);
          if (args[2].equals("unity")) for (int i=0;i<SpleeterDsp.ELEMENTS;i++) out.putFloat(1);
          else {
            byte[] mask = Files.readAllBytes(Paths.get(args[2]+"-"+(block[0]++)+".bin"));
            if(mask.length!=out.capacity()) throw new IOException("Invalid mask size");
            out.put(mask);
          }
        }, (done,total)->{});
    }
  }
  static void write(OutputStream out,float f) throws IOException {
    int n=Float.floatToIntBits(f); for(int i=0;i<4;i++) out.write(n>>>(i*8));
  }
}
