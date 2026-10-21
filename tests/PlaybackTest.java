package com.shandian.sing;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PlaybackTest {
  static void check(boolean value) {
    if (!value) throw new AssertionError();
  }

  static void waitFor(java.util.function.BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!test.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("Timed out");
      Thread.sleep(5);
    }
  }

  static PcmAudio pcm(int value) throws Exception {
    File f = File.createTempFile("playback-test", ".pcm");
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
      for (int i = 0; i < 48000; i++) WaveFile.number(out, value, 2);
    }
    PcmAudio p = new PcmAudio(f);
    p.rate = 48000;
    p.channels = 1;
    p.frames = 48000;
    return p;
  }

  static class Output implements TakePlayback.Sink {
    volatile long frames;
    volatile boolean paused = true, closed;
    volatile int sample;

    public void play() {
      paused = false;
    }

    public void pause() {
      paused = true;
    }

    public void flush() {}

    public int write(byte[] b, int at, int size) throws Exception {
      Thread.sleep(4);
      if (closed) return -1;
      if (paused) return 0;
      sample = (short) ((b[at] & 255) | (b[at + 1] << 8));
      frames += size / 4;
      return size;
    }

    public long frames() {
      return frames;
    }

    public void close() {
      closed = true;
    }
  }

  public static void main(String[] args) throws Exception {
    AtomicReference<Output> output = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    TakePlayback.Listener listener =
        new TakePlayback.Listener() {
          public void changed() {}

          public void failed(Exception e) {
            failure.set(e);
          }
        };
    PcmAudio v = pcm(10000), b = pcm(20000);
    ByteArrayOutputStream mixed = new ByteArrayOutputStream();
    TakeExport.render(v, b, 48000, 1, 0, mixed);
    byte[] wav = mixed.toByteArray();
    check(wav.length == 44 + 48000 * 4);
    for (int i = 44; i < wav.length; i += 2)
      check((short) ((wav[i] & 255) | (wav[i + 1] << 8)) == 5000);
    ByteArrayOutputStream muted = new ByteArrayOutputStream();
    TakeExport.render(v, null, 48000, 0, 0, muted);
    byte[] silent = muted.toByteArray();
    for (int i = 44; i < silent.length; i++) check(silent[i] == 0);
    TakePlayback playback =
        new TakePlayback(
            () -> new TakeAudio(v, b, 12000),
            12000,
            () -> {
              Output o = new Output();
              output.set(o);
              return o;
            },
            Runnable::run,
            1,
            0,
            listener);
    waitFor(() -> output.get() != null && output.get().frames > 2048);
    check(output.get().sample == 5000);
    check(playback.position() > 12000);
    playback.toggle();
    Thread.sleep(20);
    long paused = output.get().frames;
    Thread.sleep(25);
    check(output.get().frames == paused);
    playback.seek(12500);
    waitFor(() -> output.get().frames == 0);
    check(playback.position() == 12500);
    check(!playback.playing);
    playback.levels(0, 1);
    playback.toggle();
    waitFor(() -> output.get().frames > 2048);
    check(output.get().sample == 10000);
    waitFor(() -> !playback.playing);
    check(playback.position() == 13000);
    playback.toggle();
    waitFor(() -> playback.playing && playback.position() < 12500);
    playback.close();
    waitFor(() -> !v.file.exists() && !b.file.exists());
    check(failure.get() == null);
    CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
    TakePlayback preparing =
        new TakePlayback(
            () -> {
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } finally {
                cancelled.countDown();
              }
              return null;
            },
            0,
            Output::new,
            Runnable::run,
            1,
            1,
            listener);
    check(entered.await(2, TimeUnit.SECONDS));
    preparing.close();
    check(cancelled.await(2, TimeUnit.SECONDS));
    check(failure.get() == null);
    TakePlayback broken =
        new TakePlayback(
            () -> {
              throw new IOException("bad input");
            },
            0,
            Output::new,
            Runnable::run,
            1,
            1,
            listener);
    waitFor(() -> failure.get() != null);
    check(!broken.ready && !broken.playing);
    broken.close();
    System.out.println(
        "Single-stream playback: automatic start, pause, seek, live gains, completion, restart,"
            + " cancellation and errors passed");
  }
}
