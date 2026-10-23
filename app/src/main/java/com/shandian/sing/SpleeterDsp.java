package com.shandian.sing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Spleeter's original 44.1 kHz stereo STFT, 512-frame partitions and ratio masks.
 * Bounded to one partition, with continuous overlap-add across partitions.
 */
final class SpleeterDsp {
  static final int RATE = 44100, FFT = 4096, HOP = 1024, TIME = 512, BINS = 1024;
  static final int ELEMENTS = TIME * BINS * 2;
  interface Source { void read(long frame, float[] stereo) throws Exception; }
  interface Sink { void write(float left, float right) throws Exception; }
  interface Model { void run(ByteBuffer magnitude, ByteBuffer mask) throws Exception; }
  interface Progress { void update(long done, long total) throws Exception; }

  static void separate(long samples, Source source, Sink sink, Model model, Progress progress)
      throws Exception {
    if (samples <= 0) throw new IOException("歌曲没有可处理的声音");
    long frames = (samples + FFT + HOP - 1) / HOP;
    ByteBuffer input = ByteBuffer.allocateDirect(ELEMENTS * 4).order(ByteOrder.nativeOrder());
    ByteBuffer output = ByteBuffer.allocateDirect(ELEMENTS * 4).order(ByteOrder.nativeOrder());
    FloatBuffer magnitude = input.asFloatBuffer(), mask = output.asFloatBuffer();
    // Only the first 1024 complex bins survive Spleeter's zeros mask extension.
    float[] spectrum = new float[TIME * BINS * 4];
    float[] waveform = new float[((TIME - 1) * HOP + FFT) * 2];
    double[] real = new double[FFT], imag = new double[FFT], window = new double[FFT];
    float[] sample = new float[2];
    double[][] overlap = new double[2][FFT];
    for (int i = 0; i < FFT; i++) window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / FFT);
    long written = 0;
    for (long block = 0; block < frames; block += TIME) {
      progress.update(written, samples);
      int count = (int) Math.min(TIME, frames - block);
      Arrays.fill(waveform, 0);
      int needed = (count - 1) * HOP + FFT;
      long start = block * HOP - FFT;
      for (int i = 0; i < needed; i++) {
        long at = start + i;
        if (at >= 0 && at < samples) {
          source.read(at, sample);
          waveform[i * 2] = sample[0]; waveform[i * 2 + 1] = sample[1];
        }
      }
      magnitude.clear();
      for (int t = 0; t < TIME; t++) {
        for (int c = 0; c < 2; c++) {
          if (t < count) {
            for (int i = 0; i < FFT; i++) real[i] = waveform[(t * HOP + i) * 2 + c] * window[i];
            Arrays.fill(imag, 0);
            fft(real, imag, false);
          }
          for (int f = 0; f < BINS; f++) {
            int k = (t * BINS + f) * 2 + c;
            float r = t < count ? (float) real[f] : 0;
            float im = t < count ? (float) imag[f] : 0;
            magnitude.put(k, (float) Math.hypot(r, im));
            spectrum[k * 2] = r; spectrum[k * 2 + 1] = im;
          }
        }
      }
      input.rewind(); output.rewind();
      model.run(input, output);
      for (int t = 0; t < count; t++) {
        for (int c = 0; c < 2; c++) {
          Arrays.fill(real, 0); Arrays.fill(imag, 0);
          for (int f = 0; f < BINS; f++) {
            int k = (t * BINS + f) * 2 + c;
            float ratio = mask.get(k);
            if (!Float.isFinite(ratio) || ratio < 0 || ratio > 1.001f)
              throw new IOException("模型输出异常，已停止保存");
            real[f] = spectrum[k * 2] * ratio;
            imag[f] = spectrum[k * 2 + 1] * ratio;
            if (f > 0) { real[FFT - f] = real[f]; imag[FFT - f] = -imag[f]; }
          }
          fft(real, imag, true);
          for (int i = 0; i < FFT; i++) overlap[c][i] += real[i] * window[i] * (2.0 / 3.0);
        }
        long first = (block + t) * HOP - FFT;
        for (int i = 0; i < HOP; i++) {
          long at = first + i;
          if (at >= 0 && at < samples) {
            sink.write((float) overlap[0][i], (float) overlap[1][i]); written++;
          }
        }
        for (int c = 0; c < 2; c++) {
          System.arraycopy(overlap[c], HOP, overlap[c], 0, FFT - HOP);
          Arrays.fill(overlap[c], FFT - HOP, FFT, 0);
        }
        if (t % 32 == 0) progress.update(written, samples);
      }
    }
    if (written != samples) throw new IOException("伴奏长度校验失败");
    progress.update(samples, samples);
  }

  /** In-place radix-2 FFT; inverse includes 1/N, like TensorFlow irfft. */
  static void fft(double[] real, double[] imag, boolean inverse) {
    int n = real.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        double r = real[i]; real[i] = real[j]; real[j] = r;
        double im = imag[i]; imag[i] = imag[j]; imag[j] = im;
      }
    }
    for (int size = 2; size <= n; size <<= 1) {
      double angle = (inverse ? 2 : -2) * Math.PI / size;
      double wr = Math.cos(angle), wi = Math.sin(angle);
      for (int start = 0; start < n; start += size) {
        double r = 1, im = 0;
        for (int j = 0; j < size / 2; j++) {
          int a = start + j, b = a + size / 2;
          double tr = r * real[b] - im * imag[b], ti = r * imag[b] + im * real[b];
          real[b] = real[a] - tr; imag[b] = imag[a] - ti;
          real[a] += tr; imag[a] += ti;
          double next = r * wr - im * wi; im = r * wi + im * wr; r = next;
        }
      }
    }
    if (inverse) for (int i = 0; i < n; i++) { real[i] /= n; imag[i] /= n; }
  }
}
