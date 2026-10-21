package com.shandian.sing;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

final class TakeExport {
  static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[65536];
    int size;
    while ((size = input.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消导出");
      output.write(buffer, 0, size);
    }
  }

  static void write(
      Context context,
      File voice,
      TakeInfo info,
      int mode,
      Uri destination,
      float vocals,
      float music)
      throws Exception {
    try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
      if (output == null) throw new IOException("无法写入导出文件");
      if (mode == 0 && vocals == 1) {
        try (InputStream input = new FileInputStream(voice)) {
          copy(input, output);
        }
        return;
      }
      try (TakeAudio audio = TakeAudio.prepare(context, voice, info, mode != 0)) {
        if (mode == 2) {
          try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            zip.putNextEntry(new ZipEntry("vocals.wav"));
            render(audio.voice, null, audio.frames, vocals, 0, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("backing.wav"));
            render(audio.backing, null, audio.frames, music, 0, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("alignment.txt"));
            zip.write(
                ("Both WAV files start together at 0 ms and cover the recorded section.\n"
                     + "Original backing offset: "
                        + info.startMs
                        + " ms.\nApplied vocal gain: "
                        + vocals
                        + "; backing gain: "
                        + music
                        + ".\nMixed playback = (vocals + backing) / 2 for clipping headroom.\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
          }
        } else render(audio.voice, audio.backing, audio.frames, vocals, music, output);
      }
    }
  }

  static void render(
      PcmAudio voice, PcmAudio backing, long frames, float vocals, float music, OutputStream output)
      throws Exception {
    BufferedOutputStream stream = new BufferedOutputStream(output, 65536);
    WaveFile.header(stream, frames, 48000);
    try (PcmAudio.Reader v = new PcmAudio.Reader(voice);
        PcmAudio.Reader b = backing == null ? null : new PcmAudio.Reader(backing)) {
      byte[] bytes = new byte[4096];
      for (long at = 0; at < frames; at += 1024) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消导出");
        int size =
            TakeAudio.block(v, b, at, (int) Math.min(1024, frames - at), vocals, music, bytes);
        stream.write(bytes, 0, size);
      }
    }
    stream.flush();
  }
}
