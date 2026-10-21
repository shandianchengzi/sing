package com.shandian.sing;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Small native UI for the private cloud library. Every remote operation is read-only. */
final class BaiduLibrary {
  final MainActivity a;
  BaiduDrive drive;
  Thread worker;
  int generation;
  ProgressDialog progress;
  AlertDialog login;
  LinearLayout page, rows;
  TextView state;
  EditText directory, search;
  JSONArray index = new JSONArray();

  BaiduLibrary(MainActivity activity) {
    a = activity;
  }

  void cancel() {
    generation++;
    if (drive != null) drive.cancel();
    if (worker != null) worker.interrupt();
    worker = null;
    if (progress != null) {
      progress.dismiss();
      progress = null;
    }
    if (login != null) {
      login.dismiss();
      login = null;
    }
  }

  void show() {
    if (!a.canNavigate()) return;
    page = a.subpage("百度网盘曲库");
    try {
      drive = new BaiduDrive(a);
    } catch (Exception e) {
      TextView error = new TextView(a);
      error.setText(e.getMessage());
      page.addView(error);
      return;
    }
    state = new TextView(a);
    state.setText(drive.prefs.contains("tokens") ? "已保存登录授权；可刷新目录" : "请登录百度网盘授权");
    page.addView(state);
    a.pageButton(page, "登录 / 更换百度账号", () -> login());
    a.pageButton(
        page,
        "退出百度账号",
        () -> {
          cancel();
          drive.prefs.edit().remove("tokens").remove("index").remove("account").apply();
          index = new JSONArray();
          render();
          state.setText("已退出；已下载到本地曲库的歌曲保留");
        });
    directory = new EditText(a);
    directory.setSingleLine(true);
    directory.setHint("网盘目录，例如 /my-lrc-bgm-repo");
    directory.setText(drive.prefs.getString("directory", "/"));
    page.addView(directory);
    a.pageButton(page, "保存目录并刷新", () -> refresh());
    TextView note = new TextView(a);
    note.setText("只读取此目录，不上传或删除网盘文件。点击伴奏下载并配套 LRC；多份歌词可选择。已下载的歌曲也在本地曲库。进入文件夹按钮可浏览子目录。");
    page.addView(note);
    search = new EditText(a);
    search.setSingleLine(true);
    search.setHint("搜索云端曲库");
    page.addView(search);
    rows = new LinearLayout(a);
    rows.setOrientation(1);
    page.addView(rows);
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            render();
          }

          public void afterTextChanged(android.text.Editable text) {}
        });
    try {
      index = new JSONArray(drive.prefs.getString("index", "[]"));
    } catch (JSONException ignored) {
      index = new JSONArray();
    }
    render();
  }

  interface Job {
    void run(BaiduDrive client, int version) throws Exception;
  }

  void run(String text, Job job) {
    cancel();
    final int version = generation;
    final BaiduDrive client;
    try {
      client = new BaiduDrive(a);
      drive = client;
    } catch (Exception e) {
      state.setText(e.getMessage());
      return;
    }
    progress = new ProgressDialog(a);
    progress.setMessage(text);
    progress.setCancelable(false);
    progress.setButton(
        -2,
        "取消",
        (d, which) -> {
          cancel();
          state.setText("已取消");
        });
    progress.show();
    worker =
        new Thread(
            () -> {
              try {
                job.run(client, version);
              } catch (Exception e) {
                post(
                    version,
                    () -> {
                      finish();
                      state.setText("未完成：" + e.getMessage());
                    });
              }
            },
            "baidu-library");
    worker.start();
  }

  void post(int version, Runnable action) {
    a.handler.post(
        () -> {
          if (generation == version && !a.isDestroyed()) action.run();
        });
  }

  void finish() {
    if (progress != null) {
      progress.dismiss();
      progress = null;
    }
    if (login != null) {
      login.dismiss();
      login = null;
    }
    worker = null;
  }

  void login() {
    run(
        "正在请求百度授权…",
        (client, version) -> {
          JSONObject device = client.device();
          String url = device.getString("verification_url");
          java.net.URL parsed = new java.net.URL(url);
          if (!BaiduDrive.trusted(parsed)
              || !(parsed.getHost().equals("baidu.com") || parsed.getHost().endsWith(".baidu.com")))
            throw new IOException("不支持的百度授权地址");
          String code = device.getString("user_code");
          post(
              version,
              () -> {
                if (progress != null) {
                  progress.dismiss();
                  progress = null;
                }
                login =
                    new AlertDialog.Builder(a)
                        .setTitle("百度网盘登录授权")
                        .setMessage("授权码：" + code + "\n打开百度授权页，输入此码完成授权；返回后自动连接。")
                        .setPositiveButton("打开百度授权页", null)
                        .setNegativeButton(
                            "取消",
                            (d, w) -> {
                              cancel();
                              state.setText("已取消登录");
                            })
                        .setCancelable(false)
                        .create();
                login.setOnShowListener(
                    d -> login.getButton(-1).setOnClickListener(v -> a.openLink(url)));
                login.show();
              });
          client.authorize(device);
          post(
              version,
              () -> {
                finish();
                index = new JSONArray();
                render();
                state.setText("授权成功，请指定目录并刷新；可用目录以应用获准权限为准");
              });
        });
  }

  void refresh() {
    final String path = directory.getText().toString().trim();
    if (!path.startsWith("/")) {
      directory.setError("请输入以 / 开头的网盘路径");
      return;
    }
    run(
        "正在读取网盘目录…",
        (client, version) -> {
          JSONArray result = client.list(path);
          client.check();
          client
              .prefs
              .edit()
              .putString("directory", path)
              .putString("index", result.toString())
              .apply();
          post(
              version,
              () -> {
                finish();
                index = result;
                render();
                state.setText("目录已刷新：" + result.length() + " 个文件或文件夹");
              });
        });
  }

  void render() {
    if (rows == null) return;
    rows.removeAllViews();
    ArrayList<JSONObject> lyrics = new ArrayList<>();
    ArrayList<String> names = new ArrayList<>();
    for (int i = 0; i < index.length(); i++) {
      JSONObject file = index.optJSONObject(i);
      if (file != null
          && file.optInt("isdir") == 0
          && file.optString("server_filename").toLowerCase(Locale.ROOT).endsWith(".lrc")) {
        lyrics.add(file);
        names.add(file.optString("server_filename"));
      }
    }
    String query = CloudNames.key(search.getText().toString());
    int visible = 0;
    for (int i = 0; i < index.length(); i++) {
      JSONObject file = index.optJSONObject(i);
      if (file == null) continue;
      String name = file.optString("server_filename");
      if (!CloudNames.key(name).contains(query)) continue;
      if (file.optInt("isdir") == 1) {
        a.pageButton(
            rows,
            "📁 " + name,
            () -> {
              directory.setText(file.optString("path"));
              refresh();
            });
        visible++;
        continue;
      }
      if (!CloudNames.audio(name)) continue;
      List<Integer> matches = CloudNames.matches(name, names);
      String paired =
          matches.size() == 1 ? " · LRC ✓" : matches.size() > 1 ? " · 选择 LRC" : " · 无配套 LRC";
      a.pageButton(
          rows,
          name + paired,
          () -> {
            if (matches.size() < 2) {
              load(file, matches.isEmpty() ? null : lyrics.get(matches.get(0)));
            } else {
              String[] options = new String[matches.size()];
              for (int j = 0; j < options.length; j++) options[j] = names.get(matches.get(j));
              new AlertDialog.Builder(a)
                  .setTitle("选择配套歌词")
                  .setItems(options, (d, which) -> load(file, lyrics.get(matches.get(which))))
                  .setNegativeButton("取消", null)
                  .show();
            }
          });
      visible++;
    }
    if (visible == 0) {
      TextView empty = new TextView(a);
      empty.setText("暂无匹配伴奏；请登录、检查目录并刷新。");
      rows.addView(empty);
    }
  }

  void load(JSONObject file, JSONObject lyric) {
    run(
        "正在下载伴奏…",
        (client, version) -> {
          long[] last = {0};
          File audio =
              client.download(
                  file,
                  false,
                  (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 300) {
                      last[0] = now;
                      post(
                          version,
                          () -> {
                            if (progress != null)
                              progress.setMessage(
                                  "下载伴奏：" + (total == 0 ? 0 : done * 100 / total) + "%");
                          });
                    }
                  });
          String text = "";
          if (lyric != null) {
            post(
                version,
                () -> {
                  if (progress != null) progress.setMessage("正在下载配套歌词…");
                });
            File lrc = client.download(lyric, true, (done, total) -> {});
            try (InputStream in = new FileInputStream(lrc)) {
              text = decodeLyrics(BaiduDrive.read(in, 2 * 1024 * 1024));
            }
            if (new Lrc(text).lines.isEmpty()) throw new IOException("配套歌词不是标准 LRC，请检查网盘文件");
          }
          final String paired = text;
          client.check();
          post(
              version,
              () -> {
                finish();
                String source = Uri.fromFile(audio).toString();
                a.rememberAudio(source, file.optString("server_filename"));
                a.libraryField(source, "lrc", paired);
                a.originalAudio = source;
                a.preferences.edit().putString("audio", source).apply();
                a.returnHome();
                a.importAudio(Uri.fromFile(audio), file.optString("server_filename"));
              });
        });
  }

  static String decodeLyrics(byte[] bytes) throws Exception {
    if (bytes.length >= 2
        && ((bytes[0] == -1 && bytes[1] == -2) || (bytes[0] == -2 && bytes[1] == -1)))
      return new String(bytes, "UTF-16");
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      return new String(bytes, "GB18030");
    }
  }
}
