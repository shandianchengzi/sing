package com.shandian.sing;

import android.content.Context;
import android.net.Uri;
import java.io.*;

/** Shared decoded sources for synchronized playback and exports. */
final class TakeAudio implements AutoCloseable {
  private static TakeAudio cached;
  private static String cacheKey;
  private int users = 1;
  private boolean retained;
  final PcmAudio voice, backing;
  final long startMs, frames;

  TakeAudio(PcmAudio voice, PcmAudio backing, long startMs) {
    this.voice = voice;
    this.backing = backing;
    this.startMs = startMs;
    frames = voice.frames * 48000 / voice.rate;
  }

  // Retain one decoded take, bounded to 128 MB. Active readers survive cache replacement.
  static synchronized TakeAudio preparePlayback(Context context, File file, TakeInfo info)
      throws Exception {
    String sourceStamp = info.source;
    if (info.source.startsWith("file:")) {
      File source = new File(Uri.parse(info.source).getPath());
      sourceStamp += ":" + source.length() + ":" + source.lastModified();
    }
    String key = file.getCanonicalPath() + ":" + file.length() + ":" + file.lastModified()
        + ":" + info.startMs + ":" + sourceStamp;
    if (cached != null && key.equals(cacheKey)
        && cached.voice.file.isFile() && cached.backing.file.isFile()) {
      cached.users++;
      return cached;
    }
    TakeAudio next = prepare(context, file, info, true);
    if (cached != null) {
      cached.retained = false;
      if (cached.users == 0) cached.delete();
      cached = null;
      cacheKey = null;
    }
    if (next.voice.file.length() + next.backing.file.length() <= 128L * 1024 * 1024) {
      next.retained = true;
      cached = next;
      cacheKey = key;
    }
    return next;
  }

  static TakeAudio prepare(Context context, File file, TakeInfo info, boolean needBacking)
      throws Exception {
    File v = File.createTempFile("voice-", ".pcm", context.getCacheDir());
    File b = null;
    try {
      PcmAudio voice = PcmAudio.decode(context, Uri.fromFile(file), 0, -1, v);
      if (voice.frames == 0) throw new IOException("录音没有可播放的声音");
      PcmAudio backing = null;
      if (needBacking) {
        if (info.source.isEmpty()) throw new IOException("请先选择这次录音对应的伴奏");
        b = File.createTempFile("backing-", ".pcm", context.getCacheDir());
        backing =
            PcmAudio.decode(
                context,
                Uri.parse(info.source),
                info.startMs * 1000,
                voice.frames * 1000000L / voice.rate,
                b);
        if (backing.frames == 0) throw new IOException("伴奏在本次录音起点没有可播放的音频，请确认伴奏正确");
      }
      return new TakeAudio(voice, backing, info.startMs);
    } catch (Exception e) {
      v.delete();
      if (b != null) b.delete();
      throw e;
    }
  }

  static int block(
      PcmAudio.Reader voice,
      PcmAudio.Reader backing,
      long at,
      int frames,
      float voiceGain,
      float backingGain,
      byte[] bytes)
      throws IOException {
    double[] v = new double[2], b = new double[2];
    int offset = 0;
    for (int i = 0; i < frames; i++) {
      voice.sample(at + i, 48000, v);
      if (backing != null) backing.sample(at + i, 48000, b);
      for (int channel = 0; channel < 2; channel++) {
        short sample =
            backing == null
                ? Timeline.scale(v[channel], voiceGain)
                : Timeline.mix(v[channel], b[channel], voiceGain, backingGain);
        bytes[offset++] = (byte) sample;
        bytes[offset++] = (byte) (sample >> 8);
      }
    }
    return offset;
  }

  @Override
  public void close() {
    synchronized (TakeAudio.class) {
      if (--users == 0 && !retained) delete();
    }
  }

  private void delete() {
    voice.file.delete();
    if (backing != null) backing.file.delete();
  }
}
