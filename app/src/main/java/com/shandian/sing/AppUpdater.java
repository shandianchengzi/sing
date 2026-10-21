package com.shandian.sing;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Foreground update check/download; installation always requires system confirmation. */
final class AppUpdater {
  static final int INSTALL_PERMISSION = 90;
  final Activity activity;
  final BooleanSupplier idle;
  final Handler ui = new Handler(Looper.getMainLooper());
  ProgressDialog progress;
  volatile HttpURLConnection connection;
  volatile boolean cancelled;
  boolean busy, closed;
  UpdateInfo pending;

  AppUpdater(Activity activity, BooleanSupplier idle) {
    this.activity = activity;
    this.idle = idle;
  }

  void notice(String text) {
    if (!closed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
  }

  void dialog(String text) {
    progress = new ProgressDialog(activity);
    progress.setMessage(text);
    progress.setCancelable(false);
    progress.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (d, w) -> cancel());
    progress.show();
  }

  void cancel() {
    cancelled = true;
    HttpURLConnection c = connection;
    if (c != null) c.disconnect();
  }

  void finish() {
    busy = false;
    if (progress != null) {
      progress.dismiss();
      progress = null;
    }
    connection = null;
  }

  void checkCancelled() throws IOException {
    if (cancelled) throw new InterruptedIOException("已取消更新");
  }

  void post(Runnable action) {
    ui.post(
        () -> {
          if (!closed && !activity.isDestroyed()) action.run();
        });
  }

  HttpURLConnection open(String address) throws Exception {
    for (int redirect = 0; redirect < 6; redirect++) {
      checkCancelled();
      URL url = new URL(address);
      if (!"https".equals(url.getProtocol())) throw new IOException("更新仅允许 HTTPS");
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      connection = c;
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
      c.setInstanceFollowRedirects(false);
      c.setUseCaches(false);
      c.setRequestProperty("Cache-Control", "no-cache");
      int code = c.getResponseCode();
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null) throw new IOException("更新下载地址无效");
        address = new URL(url, location).toString();
        continue;
      }
      if (code != 200) {
        c.disconnect();
        throw new IOException("更新服务 HTTP " + code + "，请稍后重试");
      }
      return c;
    }
    throw new IOException("更新地址重定向过多");
  }

  void check() {
    if (activity.getResources().getIdentifier("baidu_config", "raw", activity.getPackageName())
        != 0) {
      if (!idle.getAsBoolean()) {
        notice("请先完成当前操作");
        return;
      }
      new AlertDialog.Builder(activity)
          .setTitle("私有版更新")
          .setMessage("此仓库已私有，请在浏览器登录你的 GitHub 账号，下载 Release 中的 sing.apk 覆盖安装。录音和设置会保留。")
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "打开私有下载页",
              (d, w) -> {
                try {
                  activity.startActivity(
                      new Intent(
                          Intent.ACTION_VIEW,
                          Uri.parse(
                              "https://github.com/shandianchengzi/sing/releases/tag/preview")));
                } catch (ActivityNotFoundException e) {
                  notice("请安装浏览器后访问 GitHub 仓库");
                }
              })
          .show();
      return;
    }

    if (busy) {
      notice("正在检查或下载更新");
      return;
    }
    if (!idle.getAsBoolean()) {
      notice("请先停止录音或等待当前操作完成");
      return;
    }
    busy = true;
    cancelled = false;
    dialog("正在检查新版本…");
    new Thread(
            () -> {
              try {
                HttpURLConnection c =
                    open(UpdateInfo.RELEASE + "update.json?check=" + System.currentTimeMillis());
                byte[] bytes;
                try (InputStream input = c.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                  byte[] buffer = new byte[4096];
                  int size;
                  while ((size = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (output.size() + size > 16384) throw new IOException("更新信息异常");
                    output.write(buffer, 0, size);
                  }
                  bytes = output.toByteArray();
                } finally {
                  c.disconnect();
                }
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                UpdateInfo info =
                    new UpdateInfo(
                        json.getInt("versionCode"),
                        json.getString("versionName"),
                        json.getString("sha256"),
                        json.getLong("size"),
                        json.getString("url"));
                PackageInfo installed =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                checkCancelled();
                post(
                    () -> {
                      finish();
                      if (!info.newerThan(installed.versionCode)) {
                        notice("当前已是最新版本：" + installed.versionName);
                        return;
                      }
                      new AlertDialog.Builder(activity)
                          .setTitle("发现新版本 " + info.name)
                          .setMessage(
                              "当前："
                                  + installed.versionName
                                  + "\n下载大小："
                                  + ((info.size + 1023) / 1024)
                                  + " KB\n固定签名覆盖安装，保留录音和设置。")
                          .setPositiveButton("下载更新", (d, w) -> download(info))
                          .setNegativeButton("稍后", null)
                          .show();
                    });
              } catch (Exception error) {
                post(
                    () -> {
                      finish();
                      notice(cancelled ? "已取消更新" : error.getMessage());
                    });
              }
            },
            "update-check")
        .start();
  }

  static String digest(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int size;
      while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest())
      hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }

  void validate(File file, UpdateInfo info) throws Exception {
    if (file.length() != info.size || !digest(file).equals(info.sha256))
      throw new IOException("更新文件校验失败，请重新下载");
    PackageManager manager = activity.getPackageManager();
    PackageInfo apk = manager.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNATURES);
    PackageInfo installed =
        manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
    if (apk == null
        || !activity.getPackageName().equals(apk.packageName)
        || apk.versionCode != info.code
        || !info.newerThan(installed.versionCode)
        || apk.signatures == null
        || installed.signatures == null
        || apk.signatures.length != 1
        || installed.signatures.length != 1
        || !Arrays.equals(apk.signatures[0].toByteArray(), installed.signatures[0].toByteArray())) {
      throw new IOException("更新包的包名、版本或固定签名不匹配，已停止安装");
    }
  }

  void download(UpdateInfo info) {
    if (busy) return;
    if (!idle.getAsBoolean()) {
      notice("请先停止录音或等待当前操作完成");
      return;
    }
    busy = true;
    cancelled = false;
    pending = null;
    dialog("正在下载更新…");
    File part = new File(activity.getCacheDir(), "update-" + System.nanoTime() + ".part");
    new Thread(
            () -> {
              try {
                File cached = UpdateApkProvider.apk(activity);
                if (cached.exists()) {
                  try {
                    validate(cached, info);
                    checkCancelled();
                    post(
                        () -> {
                          finish();
                          offerInstall(info);
                        });
                    return;
                  } catch (Exception ignored) {
                    checkCancelled();
                  }
                }
                HttpURLConnection c = open(info.url);
                long total = 0;
                try (InputStream input = c.getInputStream();
                    OutputStream output = new FileOutputStream(part)) {
                  byte[] buffer = new byte[65536];
                  int size, last = -1;
                  while ((size = input.read(buffer)) != -1) {
                    checkCancelled();
                    total += size;
                    if (total > info.size) throw new IOException("更新文件大小异常");
                    output.write(buffer, 0, size);
                    int percent = (int) (total * 100 / info.size);
                    if (percent != last) {
                      last = percent;
                      post(
                          () -> {
                            if (progress != null) progress.setMessage("正在下载更新… " + percent + "%");
                          });
                    }
                  }
                } finally {
                  c.disconnect();
                }
                checkCancelled();
                validate(part, info);
                checkCancelled();
                File file = UpdateApkProvider.apk(activity);
                if (file.exists() && !file.delete()) throw new IOException("无法替换旧安装包");
                if (!part.renameTo(file)) throw new IOException("无法保存更新包");
                checkCancelled();
                post(
                    () -> {
                      finish();
                      offerInstall(info);
                    });
              } catch (Exception error) {
                post(
                    () -> {
                      finish();
                      notice(cancelled ? "已取消下载" : error.getMessage());
                    });
              } finally {
                part.delete();
              }
            },
            "update-download")
        .start();
  }

  void offerInstall(UpdateInfo info) {
    pending = info;
    new AlertDialog.Builder(activity)
        .setTitle("更新下载完成")
        .setMessage("版本 " + info.name + " 已校验通过。安装后保留已有数据，无需卸载。")
        .setPositiveButton("安装", (d, w) -> install())
        .setNegativeButton("稍后", null)
        .show();
  }

  void install() {
    if (pending == null) return;
    if (!idle.getAsBoolean()) {
      notice("安装包已下载，请停止录音后再检查更新并安装");
      return;
    }
    try {
      validate(UpdateApkProvider.apk(activity), pending);
      if (!activity.getPackageManager().canRequestPackageInstalls()) {
        new AlertDialog.Builder(activity)
            .setTitle("允许安装应用更新")
            .setMessage("请在系统设置中允许轻唱安装此来源的应用，返回后继续安装。")
            .setPositiveButton(
                "打开设置",
                (d, w) ->
                    activity.startActivityForResult(
                        new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())),
                        INSTALL_PERMISSION))
            .setNegativeButton("取消", null)
            .show();
        return;
      }
      Uri uri =
          new Uri.Builder()
              .scheme("content")
              .authority(activity.getPackageName() + ".updates")
              .appendPath("update.apk")
              .build();
      Intent intent =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri("update", uri));
      activity.startActivity(intent);
    } catch (Exception error) {
      notice("无法开始安装：" + error.getMessage());
    }
  }

  void permissionReturned() {
    if (activity.getPackageManager().canRequestPackageInstalls()) install();
    else notice("尚未允许此来源安装，可稍后重试");
  }

  void close() {
    closed = true;
    cancel();
    if (progress != null) progress.dismiss();
    ui.removeCallbacksAndMessages(null);
  }
}
