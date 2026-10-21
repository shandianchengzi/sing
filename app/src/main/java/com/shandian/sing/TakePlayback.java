package com.shandian.sing;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import java.io.File;

/** Two platform players share an absolute accompaniment timeline. */
final class TakePlayback implements AutoCloseable {
  interface Listener {
    void changed();

    void failed(Exception error);
  }

  private final MediaPlayer voice = new MediaPlayer();
  private MediaPlayer backing;
  private final int start;
  private final Listener listener;
  private int prepared, waiting, pending = -1;
  private boolean closed, seeking, resume;
  boolean ready, playing;

  TakePlayback(Context context, File file, TakeInfo info, Listener listener) throws Exception {
    this.listener = listener;
    start = (int) info.startMs;
    try {
      voice.setDataSource(file.getPath());
      if (!info.source.isEmpty()) {
        backing = new MediaPlayer();
        backing.setDataSource(context, Uri.parse(info.source));
      }
      configure(voice);
      if (backing != null) configure(backing);
      voice.setOnCompletionListener(
          p -> {
            playing = false;
            if (backing != null) backing.pause();
            listener.changed();
          });
      voice.prepareAsync();
      if (backing != null) backing.prepareAsync();
    } catch (Exception error) {
      close();
      throw error;
    }
  }

  private void configure(MediaPlayer player) {
    player.setOnPreparedListener(
        p -> {
          if (closed) return;
          if (++prepared == (backing == null ? 1 : 2)) {
            ready = true;
            resume = true;
            seek(start);
            listener.changed();
          }
        });
    player.setOnErrorListener(
        (p, what, extra) -> {
          if (!closed) {
            close();
            listener.failed(new java.io.IOException("回听文件或伴奏无法读取，请重新选择原伴奏"));
          }
          return true;
        });
  }

  int position() {
    return ready ? start + voice.getCurrentPosition() : start;
  }

  int duration() {
    return ready
        ? Math.max(start + voice.getDuration(), backing == null ? 0 : backing.getDuration())
        : 0;
  }

  void toggle() {
    if (!ready || closed) return;
    if (seeking) {
      resume = !resume;
      return;
    }
    if (playing) {
      voice.pause();
      if (backing != null) backing.pause();
      playing = false;
    } else if (voice.getCurrentPosition() >= voice.getDuration() - 50) {
      resume = true;
      seek(start);
    } else {
      startPlayers();
    }
    listener.changed();
  }

  void seek(int absoluteMs) {
    if (!ready || closed) return;
    pending = Timeline.voicePosition(absoluteMs, start, voice.getDuration());
    if (seeking) return;
    resume = resume || playing;
    playing = false;
    voice.pause();
    if (backing != null) backing.pause();
    nextSeek();
  }

  private void nextSeek() {
    int target = pending;
    pending = -1;
    seeking = true;
    waiting = backing == null ? 1 : 2;
    MediaPlayer.OnSeekCompleteListener done =
        p -> {
          if (closed || --waiting != 0) return;
          seeking = false;
          if (pending >= 0) {
            nextSeek();
            return;
          }
          if (resume) startPlayers();
          resume = false;
          listener.changed();
        };
    voice.setOnSeekCompleteListener(done);
    voice.seekTo(target, MediaPlayer.SEEK_CLOSEST);
    if (backing != null) {
      backing.setOnSeekCompleteListener(done);
      backing.seekTo(
          Math.min(backing.getDuration(), (long) start + target), MediaPlayer.SEEK_CLOSEST);
    }
  }

  private void startPlayers() {
    if (backing != null && backing.getCurrentPosition() < backing.getDuration()) backing.start();
    voice.start();
    playing = true;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ready = playing = false;
    voice.release();
    if (backing != null) backing.release();
  }
}
