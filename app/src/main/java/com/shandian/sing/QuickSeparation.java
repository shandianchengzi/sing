package com.shandian.sing;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fast O(n) center reduction using Android's audio decoder; no model or network. */
final class QuickSeparation {
  interface Progress { void update(String status); }
  private QuickSeparation() {}

  static File separate(Context context, Uri source, Progress progress) throws Exception {
    String fingerprint = key(context, source);
    File folder = new File(context.getFilesDir(), "quick-accompaniments");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建快速伴奏缓存");
    File result = new File(folder, fingerprint + ".wav");
    if (result.isFile() && result.length() > 44) {
      progress.update("已找到快速伴奏缓存");
      return result;
    }
    File decoded = File.createTempFile("quick-stereo-", ".pcm", context.getCacheDir());
    File partial = File.createTempFile("quick-center-", ".part", folder);
    boolean success = false;
    try {
      progress.update("正在解码原曲音轨…");
      PcmAudio audio = PcmAudio.decode(context, source, 0, -1, decoded);
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消快速消音");
      if (audio.frames <= 0) throw new IOException("原曲没有可用音频");
      if (audio.channels != 2)
        throw new IOException("轻量消音要求立体声；单声道请使用高质量分离模式");
      if (audio.frames > (0xffffffffL - 36) / 4)
        throw new IOException("原曲过长，超过 WAV 输出限制");
      if (folder.getUsableSpace() < audio.frames * 4 + 16L * 1024 * 1024)
        throw new IOException("存储空间不足，无法保存快速伴奏");
      try (DataInputStream input = new DataInputStream(
               new BufferedInputStream(new FileInputStream(decoded), 65536));
           OutputStream output = new BufferedOutputStream(new FileOutputStream(partial), 65536)) {
        WaveFile.header(output, audio.frames, audio.rate);
        byte[] block = new byte[65536];
        long done = 0;
        while (done < audio.frames) {
          if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("已取消快速消音");
          int frames = (int) Math.min(block.length / 4, audio.frames - done);
          input.readFully(block, 0, frames * 4);
          CenterCancel.stereoPcm16(block, frames * 4);
          output.write(block, 0, frames * 4);
          done += frames;
          progress.update("正在快速消音 " + (done * 100 / audio.frames) + "% · 无需 AI 模型");
        }
      }
      if (!partial.renameTo(result)) throw new IOException("快速伴奏缓存保存失败");
      success = true;
      return result;
    } finally {
      decoded.delete();
      if (!success) partial.delete();
    }
  }

  private static String key(Context context, Uri source) throws Exception {
    String signature = "center-difference-v1|" + source;
    if ("file".equals(source.getScheme())) {
      File file = new File(source.getPath());
      signature += "|" + file.length() + "|" + file.lastModified();
    } else {
      try (Cursor cursor = context.getContentResolver().query(
          source, new String[] {OpenableColumns.SIZE}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
          signature += "|" + cursor.getLong(0);
      } catch (IllegalArgumentException ignored) {
        // Some providers expose no size; isolate their cache by URI.
      }
    }
    byte[] hash = MessageDigest.getInstance("SHA-256")
        .digest(signature.getBytes(StandardCharsets.UTF_8));
    StringBuilder value = new StringBuilder();
    for (byte b : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }
}
