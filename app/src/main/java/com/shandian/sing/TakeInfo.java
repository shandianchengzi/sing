package com.shandian.sing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** The voice file stays at its existing path; its sidecar stores timeline alignment. */
final class TakeInfo {
  final long startMs;
  final String source;
  final boolean legacy;

  TakeInfo(long startMs, String source, boolean legacy) {
    this.startMs = Math.max(0, startMs);
    this.source = source;
    this.legacy = legacy;
  }

  static TakeInfo read(File voice, String fallback) throws Exception {
    File metadata = new File(voice.getPath() + ".json");
    if (!metadata.exists()) return new TakeInfo(0, fallback, true);
    byte[] bytes;
    try (FileInputStream input = new FileInputStream(metadata);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int size;
      while ((size = input.read(buffer)) != -1) {
        if (output.size() + size > 65536) throw new IOException("录音信息异常");
        output.write(buffer, 0, size);
      }
      bytes = output.toByteArray();
    }
    JSONObject object = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    return new TakeInfo(object.getLong("startMs"), object.getString("source"), false);
  }

  void save(File voice) throws Exception {
    JSONObject object = new JSONObject();
    object.put("startMs", startMs);
    object.put("source", source);
    File target = new File(voice.getPath() + ".json");
    File temporary = new File(target.getPath() + ".tmp");
    try (FileOutputStream stream = new FileOutputStream(temporary)) {
      stream.write(object.toString().getBytes(StandardCharsets.UTF_8));
      stream.getFD().sync();
    }
    if (!temporary.renameTo(target)) throw new IOException("无法保存录音起点");
  }
}
