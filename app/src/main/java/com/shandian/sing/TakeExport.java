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

  static void write(Context context, File voice, TakeInfo take, int mode, Uri destination)
      throws Exception {
    try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
      if (output == null) throw new IOException("无法写入导出文件");
      if (mode == 0) {
        try (InputStream input = new FileInputStream(voice)) {
          copy(input, output);
        }
      } else if (mode == 2) {
        archive(context, voice, take, output);
      } else {
        mix(context, voice, take, output);
      }
    }
  }

  private static void archive(Context context, File voice, TakeInfo take, OutputStream output)
      throws Exception {
    Uri source = Uri.parse(take.source);
    String mime = context.getContentResolver().getType(source);
    String name = source.getLastPathSegment();
    boolean mp4 =
        "video/mp4".equals(mime)
            || "application/mp4".equals(mime)
            || (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
    File extracted = null;
    try {
      if (mp4) {
        extracted = AudioImport.extract(context, source);
        source = Uri.fromFile(extracted);
      }
      String extension =
          mp4 ? "m4a" : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
      if (extension == null && name != null && name.lastIndexOf('.') >= 0)
        extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
      if (extension == null || !extension.matches("[a-z0-9]{1,5}")) extension = "bin";
      try (ZipOutputStream zip = new ZipOutputStream(output)) {
        zip.setLevel(0);
        zip.putNextEntry(new ZipEntry("vocals.m4a"));
        try (InputStream input = new FileInputStream(voice)) {
          copy(input, zip);
        }
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("backing." + extension));
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
          if (input == null) throw new IOException("无法读取原伴奏");
          copy(input, zip);
        }
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("alignment.txt"));
        zip.write(
            ("Voice starts at accompaniment "
                    + take.startMs
                    + " ms.\nBacking contains the full audio track.\n")
                .getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } finally {
      if (extracted != null) extracted.delete();
    }
  }

  private static void mix(Context context, File voice, TakeInfo take, OutputStream output)
      throws Exception {
    File vocalPcm = File.createTempFile("voice-", ".pcm", context.getCacheDir());
    File backingPcm = File.createTempFile("backing-", ".pcm", context.getCacheDir());
    try {
      PcmAudio vocal = PcmAudio.decode(context, Uri.fromFile(voice), 0, -1, vocalPcm);
      if (vocal.frames == 0) throw new IOException("录音没有可解码的声音");
      long lengthUs = vocal.frames * 1000000L / vocal.rate;
      PcmAudio backing =
          PcmAudio.decode(
              context, Uri.parse(take.source), take.startMs * 1000, lengthUs, backingPcm);
      long frames = vocal.frames * 48000 / vocal.rate;
      BufferedOutputStream stream = new BufferedOutputStream(output, 65536);
      WaveFile.header(stream, frames, 48000);
      try (PcmAudio.Reader v = new PcmAudio.Reader(vocal);
          PcmAudio.Reader b = new PcmAudio.Reader(backing)) {
        double[] vocals = new double[2], music = new double[2];
        for (long frame = 0; frame < frames; frame++) {
          if ((frame & 4095) == 0 && Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("已取消导出");
          v.sample(frame, 48000, vocals);
          b.sample(frame, 48000, music);
          for (int c = 0; c < 2; c++) WaveFile.number(stream, Timeline.mix(vocals[c], music[c]), 2);
        }
      }
      stream.flush();
    } finally {
      vocalPcm.delete();
      backingPcm.delete();
    }
  }
}
