package com.shandian.sing;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/** A single clock and output stream for both tracks; no MediaPlayer seek barriers. */
final class TakePlayback implements AutoCloseable {
  interface Listener {
    void changed();

    void failed(Exception error);
  }

  interface Sink extends AutoCloseable {
    void play();

    void pause();

    void flush();

    int write(byte[] bytes, int offset, int size) throws Exception;

    long frames();

    void close();
  }

  interface Factory {
    Sink open() throws Exception;
  }

  private final Object lock = new Object();
  private final Listener listener;
  private final Executor callbacks;
  private final Factory factory;
  private final Thread worker;
  private final long startMs;
  private volatile Sink sink;
  private volatile boolean closed;
  private volatile long totalFrames, baseFrame, pendingFrame = 0;
  private volatile float voiceGain, backingGain;
  volatile boolean ready, playing = true;

  TakePlayback(Context context, File file, TakeInfo info, float v, float b, Listener listener) {
    this(
        () -> TakeAudio.prepare(context.getApplicationContext(), file, info, true),
        info.startMs,
        AndroidSink::new,
        new Handler(Looper.getMainLooper())::post,
        v,
        b,
        listener);
  }

  // Injectable sources/output let JVM tests exercise pause, seek, completion and cancellation.
  TakePlayback(
      Callable<TakeAudio> prepare,
      long startMs,
      Factory factory,
      Executor callbacks,
      float v,
      float b,
      Listener listener) {
    this.startMs = startMs;
    this.factory = factory;
    this.callbacks = callbacks;
    this.listener = listener;
    levels(v, b);
    worker = new Thread(() -> run(prepare), "take-playback");
    worker.start();
  }

  void levels(float v, float b) {
    voiceGain = Timeline.gain(v);
    backingGain = Timeline.gain(b);
  }

  private void changed() {
    callbacks.execute(
        () -> {
          if (!closed) listener.changed();
        });
  }

  int position() {
    long requested = pendingFrame;
    if (requested >= 0) return (int) (startMs + requested * 1000 / 48000);
    Sink output = sink;
    long elapsed = 0;
    if (output != null)
      try {
        elapsed = output.frames();
      } catch (RuntimeException ignored) {
      }
    return (int) (startMs + Math.min(totalFrames, baseFrame + elapsed) * 1000 / 48000);
  }

  int duration() {
    return (int) (startMs + totalFrames * 1000 / 48000);
  }

  void toggle() {
    synchronized (lock) {
      if (closed) return;
      if (playing) {
        playing = false;
        Sink output = sink;
        if (output != null) output.pause();
      } else {
        if (position() >= duration() - 20) pendingFrame = 0;
        playing = true;
        lock.notifyAll();
      }
    }
    changed();
  }

  void seek(int absoluteMs) {
    synchronized (lock) {
      if (!ready || closed) return;
      pendingFrame = Math.min(totalFrames, Math.max(0, (absoluteMs - startMs) * 48));
      Sink output = sink;
      if (output != null) {
        output.pause();
        output.flush();
      }
      lock.notifyAll();
    }
    changed();
  }

  private boolean awaitPlayback() throws InterruptedException {
    synchronized (lock) {
      while (!closed && !playing && pendingFrame < 0) lock.wait();
      return !closed;
    }
  }

  private void run(Callable<TakeAudio> prepare) {
    TakeAudio audio = null;
    PcmAudio.Reader v = null, b = null;
    long frame = 0;
    try {
      audio = prepare.call();
      if (closed) return;
      totalFrames = audio.frames;
      ready = true;
      changed();
      byte[] bytes = new byte[4096];
      while (awaitPlayback()) {
        long target;
        synchronized (lock) {
          target = pendingFrame;
          pendingFrame = -1;
        }
        if (target >= 0) {
          synchronized (lock) {
            Sink old = sink;
            sink = null;
            if (old != null) old.close();
          }
          if (v != null) v.close();
          if (b != null) b.close();
          baseFrame = frame = target;
          v = new PcmAudio.Reader(audio.voice, target, 48000);
          b = new PcmAudio.Reader(audio.backing, target, 48000);
          Sink next = factory.open();
          synchronized (lock) {
            if (closed) {
              next.close();
              return;
            }
            sink = next;
          }
        }
        if (pendingFrame >= 0 || !playing) continue;
        if (frame >= totalFrames) {
          if (sink.frames() >= totalFrames - baseFrame) {
            synchronized (lock) {
              if (pendingFrame < 0) {
                playing = false;
                sink.pause();
              }
            }
            changed();
          } else Thread.sleep(5);
          continue;
        }
        int count = (int) Math.min(1024, totalFrames - frame);
        int size = TakeAudio.block(v, b, frame, count, voiceGain, backingGain, bytes), offset = 0;
        while (offset < size && awaitPlayback() && pendingFrame < 0) {
          if (!playing) continue;
          int written;
          synchronized (lock) {
            if (closed || !playing || pendingFrame >= 0) continue;
            Sink output = sink;
            output.play();
            written = output.write(bytes, offset, size - offset);
          }
          if (written < 0) throw new IOException("音频输出失败：" + written);
          if (written == 0) Thread.sleep(5);
          else offset += written;
        }
        if (closed) return;
        if (pendingFrame < 0) frame += count;
      }
    } catch (Exception error) {
      ready = playing = false;
      callbacks.execute(
          () -> {
            if (!closed) listener.failed(error);
          });
    } finally {
      synchronized (lock) {
        Sink output = sink;
        sink = null;
        if (output != null) output.close();
      }
      try {
        if (v != null) v.close();
        if (b != null) b.close();
      } catch (IOException ignored) {
      }
      if (audio != null) audio.close();
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      ready = playing = false;
      lock.notifyAll();
    }
    worker.interrupt();
    Sink output = sink;
    if (output != null) {
      try {
        output.pause();
        output.flush();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private static final class AndroidSink implements Sink {
    private final AudioTrack track;

    AndroidSink() throws IOException {
      int minimum =
          AudioTrack.getMinBufferSize(
              48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("设备不支持 48kHz 立体声音频输出");
      track =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(48000)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setBufferSizeInBytes(Math.max(minimum, 8192))
              .build();
      if (track.getState() != AudioTrack.STATE_INITIALIZED) {
        track.release();
        throw new IOException("无法初始化音频输出");
      }
    }

    public void play() {
      if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
    }

    public void pause() {
      track.pause();
    }

    public void flush() {
      track.flush();
    }

    public int write(byte[] bytes, int offset, int size) {
      return track.write(bytes, offset, size, AudioTrack.WRITE_NON_BLOCKING);
    }

    public long frames() {
      return track.getPlaybackHeadPosition() & 0xffffffffL;
    }

    public void close() {
      track.release();
    }
  }
}
