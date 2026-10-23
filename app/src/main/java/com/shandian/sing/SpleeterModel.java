package com.shandian.sing;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

/** Versioned original-weight model; installed once, independent of APK updates. */
final class SpleeterModel {
  static final String ID = "spleeter-2stems-fp32-v1";
  static final long BYTES = 78607648;
  static String expected(Context c) throws Exception {
    try (InputStream in=c.getAssets().open("spleeter-2stems-fp32.json");
         ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
      byte[] buffer=new byte[4096];int n;
      while((n=in.read(buffer))!=-1) {
        if(bytes.size()+n>16384)throw new IOException("模型信息异常");
        bytes.write(buffer,0,n);
      }
      org.json.JSONObject metadata=new org.json.JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
      String sha=metadata.getString("sha256");
      if(!ID.equals(metadata.getString("id"))||metadata.getLong("bytes")!=BYTES||!sha.matches("[0-9a-f]{64}"))
        throw new IOException("模型信息不兼容");
      return sha;
    }
  }
  static File file(Context c) { return new File(c.getFilesDir(), ID + ".tflite"); }
  static boolean installed(Context c) { return file(c).length() == BYTES; }
  static void ensure(Context c, LocalSeparation.Task task, SeparationClient.Progress progress) throws Exception {
    File target = file(c);
    String expected=expected(c);
    android.content.SharedPreferences saved=c.getSharedPreferences("models",Context.MODE_PRIVATE);
    if (installed(c)) {
      progress.update("正在校验原两轨模型…");
      String trusted=saved.getString(ID+".sha256",expected);
      if (trusted.equals(digest(target, task))) {
        saved.edit().putString(ID+".sha256",trusted).commit(); return;
      }
      throw new IOException("本地模型校验失败，请在本地分离设置中重新安装模型");
    }
    File temp = new File(c.getFilesDir(), ID + ".part");
    boolean success = false;
    try {
      // Full offline APKs include this asset. Program-only updates retain filesDir.
      InputStream stream;
      HttpURLConnection connection = null;
      try { stream = c.getAssets().open("spleeter-2stems-fp32.tflite"); }
      catch (FileNotFoundException missing) {
        progress.update("正在下载原两轨模型（约 75 MB，仅首次需要）…");
        String url="https://github.com/shandianchengzi/sing/releases/download/preview/spleeter-2stems-fp32-"+expected+".tflite";
        connection = open(url, task); stream = connection.getInputStream();
      }
      try (InputStream input = stream;
           OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
        byte[] block = new byte[65536]; int size; long copied = 0; long shown = -1;
        while ((size = input.read(block)) != -1) {
          task.check(); copied += size;
          if (copied > BYTES) throw new IOException("模型文件大小不正确");
          output.write(block, 0, size);
          long percent = copied * 100 / BYTES;
          if (percent != shown) { shown = percent; progress.update("正在安装原两轨模型 " + percent + "%（约 75 MB）"); }
        }
        if (copied != BYTES) throw new IOException("模型下载不完整，请重试");
      } finally { if (connection != null) connection.disconnect(); task.connection = null; }
      task.check();
      if (!expected.equals(digest(temp, task))) throw new IOException("模型 SHA-256 校验失败，请重试");
      if (!temp.renameTo(target)) throw new IOException("无法保存模型");
      saved.edit().putString(ID+".sha256",expected).commit();
      success = true;
    } finally { if (!success) temp.delete(); }
  }
  static HttpURLConnection open(String address, LocalSeparation.Task task) throws Exception {
    for (int i = 0; i < 6; i++) {
      task.check(); java.net.URL url = new java.net.URL(address);
      if (!"https".equals(url.getProtocol())) throw new IOException("模型下载仅允许 HTTPS");
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      task.connection = c; c.setConnectTimeout(15000); c.setReadTimeout(30000);
      c.setInstanceFollowRedirects(false);
      int status = c.getResponseCode();
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String next = c.getHeaderField("Location"); c.disconnect();
        if (next == null) throw new IOException("模型下载地址无效");
        address = new java.net.URL(url, next).toString(); continue;
      }
      if (status != 200) { c.disconnect(); throw new IOException("模型下载 HTTP " + status + "，可改用完整离线安装包"); }
      return c;
    }
    throw new IOException("模型下载跳转过多");
  }
  static String digest(File f, LocalSeparation.Task task) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new BufferedInputStream(new FileInputStream(f))) {
      byte[] bytes = new byte[65536]; int n;
      while ((n = input.read(bytes)) != -1) { task.check(); hash.update(bytes, 0, n); }
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }
  static MappedByteBuffer map(Context c) throws Exception {
    try (RandomAccessFile f = new RandomAccessFile(file(c), "r")) {
      return f.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, f.length());
    }
  }
}
