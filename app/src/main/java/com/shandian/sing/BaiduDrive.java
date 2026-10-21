package com.shandian.sing;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Personal-only Baidu OAuth + read-only directory/download client, no SDK. */
final class BaiduDrive {
  static final String OAUTH = "https://openapi.baidu.com/oauth/2.0/";
  static final String API = "https://pan.baidu.com/rest/2.0/xpan/";
  final Context context;
  final SharedPreferences prefs;
  final String appKey, secret;
  volatile HttpURLConnection connection;
  volatile boolean cancelled;

  BaiduDrive(Context context) throws Exception {
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("baidu", Context.MODE_PRIVATE);
    int id = context.getResources().getIdentifier("baidu_config", "raw", context.getPackageName());
    if (id == 0) throw new IOException("此安装包未配置百度网盘，请安装私用版");
    JSONObject config;
    try (InputStream in = context.getResources().openRawResource(id)) {
      config = new JSONObject(new String(read(in, 16384), StandardCharsets.UTF_8));
    }
    appKey = config.getString("appkey");
    secret = config.getString("secretkey");
  }

  static byte[] read(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > limit) throw new IOException("响应文件过大");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  static String form(String... values) throws Exception {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.length; i += 2) {
      if (out.length() > 0) out.append('&');
      out.append(URLEncoder.encode(values[i], "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(values[i + 1], "UTF-8"));
    }
    return out.toString();
  }

  void check() throws IOException {
    if (cancelled || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("已取消");
  }

  synchronized void cancel() {
    cancelled = true;
    HttpURLConnection c = connection;
    if (c != null) c.disconnect();
  }

  JSONObject json(String url, String params, boolean post) throws Exception {
    check();
    HttpURLConnection c =
        (HttpURLConnection) new URL(post ? url : url + "?" + params).openConnection();
    connection = c;
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setInstanceFollowRedirects(false);
    try {
      if (post) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = c.getOutputStream()) {
          out.write(params.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = c.getResponseCode();
      if (status != 200) throw new IOException("百度接口 HTTP " + status);
      try (InputStream in = c.getInputStream()) {
        return new JSONObject(new String(read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
      }
    } finally {
      c.disconnect();
      connection = null;
    }
  }

  SecretKey storageKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("sing-baidu")) {
      KeyGenerator gen =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(
          new KeyGenParameterSpec.Builder(
                  "sing-baidu", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      gen.generateKey();
    }
    return (SecretKey) store.getKey("sing-baidu", null);
  }

  JSONObject tokens() throws Exception {
    String data = prefs.getString("tokens", "");
    if (data.isEmpty()) throw new IOException("请先登录百度网盘");
    byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
    if (bytes.length < 28) throw new IOException("登录信息损坏，请重新登录");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, storageKey(), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
    return new JSONObject(
        new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
  }

  synchronized void saveTokens(JSONObject data) throws Exception {
    check();
    if (data.optString("access_token").isEmpty()) throw new IOException("百度未返回授权令牌");
    data.put(
        "expires_at",
        System.currentTimeMillis() + Math.max(0, data.optLong("expires_in", 0) - 120) * 1000);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, storageKey());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(cipher.getIV());
    bytes.write(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
    if (!prefs
        .edit()
        .putString("tokens", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        .commit()) throw new IOException("无法保存登录信息");
  }

  JSONObject device() throws Exception {
    JSONObject data =
        json(
            OAUTH + "device/code",
            form("response_type", "device_code", "client_id", appKey, "scope", "basic,netdisk"),
            false);
    if (!data.has("device_code")) throw oauthError(data);
    return data;
  }

  static IOException oauthError(JSONObject response) {
    // Codes only: remote descriptions can contain credentials or request URLs.
    String code = response.optString("error", "unknown").replaceAll("[^a-zA-Z0-9_]", "");
    return new IOException("百度授权失败：" + code + "，请确认应用权限或重新登录");
  }

  void authorize(JSONObject device) throws Exception {
    long deadline = System.currentTimeMillis() + device.optLong("expires_in", 300) * 1000;
    int seconds = Math.max(5, device.optInt("interval", 5));
    while (System.currentTimeMillis() < deadline) {
      check();
      JSONObject data =
          json(
              OAUTH + "token",
              form(
                  "grant_type",
                  "device_token",
                  "code",
                  device.getString("device_code"),
                  "client_id",
                  appKey,
                  "client_secret",
                  secret),
              true);
      if (data.has("access_token")) {
        saveTokens(data);
        prefs.edit().putString("account", UUID.randomUUID().toString()).remove("index").apply();
        return;
      }
      String error = data.optString("error");
      if (error.equals("slow_down")) seconds += 5;
      else if (!error.equals("authorization_pending")) throw oauthError(data);
      Thread.sleep(seconds * 1000L);
    }
    throw new IOException("登录授权已超时，请重试");
  }

  String token(boolean force) throws Exception {
    JSONObject current = tokens();
    if (!force && System.currentTimeMillis() < current.optLong("expires_at"))
      return current.getString("access_token");
    JSONObject refreshed =
        json(
            OAUTH + "token",
            form(
                "grant_type",
                "refresh_token",
                "refresh_token",
                current.getString("refresh_token"),
                "client_id",
                appKey,
                "client_secret",
                secret),
            true);
    if (!refreshed.has("access_token")) throw oauthError(refreshed);
    if (refreshed.optString("refresh_token").isEmpty())
      refreshed.put("refresh_token", current.getString("refresh_token"));
    saveTokens(refreshed);
    return refreshed.getString("access_token");
  }

  JSONObject api(String endpoint, String params) throws Exception {
    JSONObject data =
        json(API + endpoint, params + "&" + form("access_token", token(false)), false);
    int error = data.optInt("errno", 0);
    if (error == 110 || error == 111) {
      data = json(API + endpoint, params + "&" + form("access_token", token(true)), false);
      error = data.optInt("errno", 0);
    }
    if (error != 0) throw new IOException("百度网盘错误 " + error + "：请检查登录、目录和应用权限");
    return data;
  }

  JSONArray list(String path) throws Exception {
    if (!path.startsWith("/")) throw new IOException("目录必须以 / 开头");
    JSONArray result = new JSONArray();
    for (int start = 0; start < 100000; start += 1000) {
      JSONArray page =
          api(
                  "file",
                  form(
                      "method",
                      "list",
                      "dir",
                      path,
                      "start",
                      "" + start,
                      "limit",
                      "1000",
                      "order",
                      "name",
                      "web",
                      "0"))
              .getJSONArray("list");
      for (int i = 0; i < page.length(); i++) result.put(page.getJSONObject(i));
      if (page.length() < 1000) return result;
    }
    throw new IOException("目录文件过多，请选择较小的曲库目录");
  }

  static boolean trusted(URL url) {
    String host = url.getHost().toLowerCase(Locale.ROOT);
    return url.getProtocol().equals("https")
        && url.getUserInfo() == null
        && (host.equals("baidu.com")
            || host.endsWith(".baidu.com")
            || host.equals("baidupcs.com")
            || host.endsWith(".baidupcs.com")
            || host.equals("baidubce.com")
            || host.endsWith(".baidubce.com"));
  }

  File download(JSONObject file, boolean lyric, Progress progress) throws Exception {
    check();
    long size = file.getLong("size");
    long max = lyric ? 2 * 1024 * 1024 : 1024L * 1024 * 1024;
    if (size < 0 || size > max) throw new IOException(lyric ? "歌词超过 2 MB" : "伴奏超过 1 GB");
    String identity =
        prefs.getString("account", "")
            + ":"
            + file.getString("fs_id")
            + ":"
            + size
            + ":"
            + file.optString("md5")
            + ":"
            + file.optLong("server_mtime");
    StringBuilder key = new StringBuilder();
    for (byte b :
        MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)))
      key.append(String.format(Locale.ROOT, "%02x", b & 255));
    String name = file.getString("server_filename");
    String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    if (!(lyric ? ext.equals(".lrc") : CloudNames.audio(name))) throw new IOException("不支持的文件格式");
    File dir = new File(context.getFilesDir(), "baidu");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建曲库目录");
    File target = new File(dir, key + ext);
    if (target.isFile() && target.length() == size) return target;
    JSONArray metas =
        api(
                "multimedia",
                form(
                    "method",
                    "filemetas",
                    "fsids",
                    "[" + file.getString("fs_id") + "]",
                    "dlink",
                    "1"))
            .getJSONArray("list");
    if (metas.length() != 1) throw new IOException("文件已失效，请刷新曲库");
    URL url = new URL(metas.getJSONObject(0).getString("dlink"));
    if (!trusted(url)) throw new IOException("百度返回了不支持的下载域名");
    url =
        new URL(
            url.toString()
                + (url.getQuery() == null ? "?" : "&")
                + form("access_token", token(false)));
    File partial = File.createTempFile("download-", ".part", dir);
    try {
      for (int redirects = 0; redirects < 6; redirects++) {
        check();
        if (!trusted(url)) throw new IOException("下载跳转域名不受信任");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        connection = c;
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "pan.baidu.com");
        try {
          int code = c.getResponseCode();
          if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = c.getHeaderField("Location");
            if (location == null) throw new IOException("下载跳转无地址");
            url = new URL(url, location);
            continue;
          }
          if (code != 200) throw new IOException("百度下载 HTTP " + code);
          long total = 0;
          try (InputStream in = c.getInputStream();
              OutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
              check();
              total += n;
              if (total > size || total > max) throw new IOException("下载大小不匹配");
              out.write(buffer, 0, n);
              progress.update(total, size);
            }
          }
          if (total != size) throw new IOException("文件下载不完整，请重试");
          check();
          if (!partial.renameTo(target)) throw new IOException("无法保存伴奏");
          return target;
        } finally {
          c.disconnect();
          connection = null;
        }
      }
      throw new IOException("下载跳转次数过多");
    } finally {
      partial.delete();
    }
  }

  interface Progress {
    void update(long done, long total);
  }
}
