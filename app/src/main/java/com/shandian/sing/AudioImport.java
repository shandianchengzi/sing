package com.shandian.sing;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Remux only the first audio track: no video decoding, no re-encoding. */
final class AudioImport {
  static File extract(Context context, Uri uri) throws IOException {
    MediaExtractor source = new MediaExtractor();
    MediaMuxer muxer = null;
    File result = null;
    boolean success = false;
    try {
      source.setDataSource(context, uri, null);
      int track = -1;
      for (int i = 0; i < source.getTrackCount(); i++) {
        String mime = source.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          track = i;
          break;
        }
      }
      if (track < 0) throw new IOException("这个 MP4 没有音轨，请选择含声音的视频");
      MediaFormat format = source.getTrackFormat(track);
      source.selectTrack(track);
      result = File.createTempFile("accompaniment-", ".m4a", context.getCacheDir());
      muxer = new MediaMuxer(result.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int out = muxer.addTrack(format);
      muxer.start();
      int capacity = 2 * 1024 * 1024;
      if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
        capacity = Math.max(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
      ByteBuffer buffer = ByteBuffer.allocate(capacity);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long first = -1;
      while (true) {
        buffer.clear();
        int size = source.readSampleData(buffer, 0);
        if (size < 0) break;
        if (size > capacity) throw new IOException("音频帧过大，无法导入");
        long time = source.getSampleTime();
        if (first < 0) first = time;
        // Extractor and codec flag values differ: copy only the sync flag.
        info.set(
            0,
            size,
            Math.max(0, time - first),
            (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                : 0);
        muxer.writeSampleData(out, buffer, info);
        source.advance();
      }
      if (first < 0) throw new IOException("这个 MP4 的音轨为空");
      muxer.stop();
      success = true;
      return result;
    } catch (RuntimeException e) {
      throw new IOException("无法读取该 MP4 音轨，请换用 AAC 音轨的 MP4 或普通音频文件", e);
    } finally {
      source.release();
      if (muxer != null)
        try {
          muxer.release();
        } catch (RuntimeException ignored) {
        }
      if (!success && result != null) result.delete();
    }
  }
}
