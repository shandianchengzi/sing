package com.shandian.sing;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.*;
import java.util.regex.*;

public class MainActivity extends Activity {
  AppUpdater updater;
  Uri pendingPairAudio;
  java.util.ArrayList<Uri> libraryFolders = new java.util.ArrayList<>();
  String pendingPairName;
  boolean importingPair, scanningFolders;
  int folderScanId, folderOpenId;
  LinearLayout libraryList;
  EditText librarySearch;
  java.util.ArrayList<FolderLibrary.Item> folderItems = new java.util.ArrayList<>();
  java.util.Map<String, java.util.ArrayList<String>> groupedSources = new java.util.HashMap<>();
  LinearLayout root;
  FrameLayout pages;
  View homePage, secondaryPage;
  TextView status, song, previous, current, next, clock;
  Button record, preview, listen, export;
  SeekBar seek;
  EditText shift;
  MediaPlayer player;
  TakePlayback replay;
  TakeInfo replayInfo;
  float voiceGain = 1, backingGain = 1, pendingVoiceGain = 1, pendingBackingGain = 1;
  int seekVersion;
  MediaRecorder recorder;
  Uri audio;
  File take, pending;
  TakeInfo pendingInfo;
  int pendingMode;
  boolean exporting, starting, previewSeeking;
  long recordingStart;
  String loadedSource = "";
  Runnable afterAudioReady;
  Thread exportThread;
  ProgressDialog exportProgress;
  Lrc lrc = new Lrc("");
  int requestVersion, importVersion;
  File extracted;
  String originalAudio = "";
  android.content.SharedPreferences preferences;
  boolean recording, ready, dragging, separating;
  SeparationClient.Task separationTask;
  final Handler handler = new Handler();
  final Runnable tick =
      new Runnable() {
        public void run() {
          boolean hearing = replay != null && replay.ready;
          if (hearing || (player != null && ready)) {
            int pos = hearing ? replay.position() : player.getCurrentPosition();
            int duration = hearing ? replay.duration() : player.getDuration();
            if (!dragging) {
              seek.setMax(duration);
              seek.setProgress(pos);
            }
            clock.setText(time(pos) + " / " + time(duration));
            long adjustment = 0;
            try {
              adjustment = Long.parseLong(shift.getText().toString());
            } catch (Exception ignored) {
            }
            int i = lrc.at(pos - adjustment);
            previous.setText(i > 0 ? lrc.lines.get(i - 1).text : "");
            current.setText(i >= 0 ? lrc.lines.get(i).text : "准备好，开始唱");
            next.setText(i + 1 < lrc.lines.size() ? lrc.lines.get(i + 1).text : "");
          }
          handler.postDelayed(this, 80);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    preferences = getSharedPreferences("session", MODE_PRIVATE);
    loadLibraryFolders();
    updater =
        new AppUpdater(
            this, () -> !recording && !starting && !previewSeeking && !separating && !exporting && !importingPair);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    root = new LinearLayout(this);
    root.setOrientation(1);
    root.setPadding(24, 16, 24, 16);
    root.setBackgroundColor(0xfff5f6f2);
    ScrollView page = new ScrollView(this);
    page.setFillViewport(true);
    page.addView(root);
    pages = new FrameLayout(this);
    homePage = page;
    pages.addView(homePage);
    setContentView(pages);
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          v.setPadding(
              24, 16 + in.getSystemWindowInsetTop(), 24, 16 + in.getSystemWindowInsetBottom());
          return in;
        });
    status = text("戴耳机录制；从伴奏当前位置开始，可同步回听和导出混音。", 13);
    song = text("尚未选择伴奏", 15);
    button("选择伴奏（含 OGG / MP4）", () -> pick(1, "*/*"));
    LinearLayout row = new LinearLayout(this);
    root.addView(row);
    Button local = new Button(this);
    local.setText("导入 LRC");
    row.addView(local, new LinearLayout.LayoutParams(0, -2, 1));
    local.setOnClickListener(v -> pick(2, "*/*"));
    Button online = new Button(this);
    online.setText("联网获取歌词");
    row.addView(online, new LinearLayout.LayoutParams(0, -2, 1));
    online.setOnClickListener(v -> lyricsDialog());
    button("本地曲库", () -> showLibrary());
    TextView lrcHelp = text("LRC 可用电脑软件 163MusicLyrics 获取，导出后传到手机导入。点此查看软件。", 13);
    lrcHelp.setTextColor(0xff176448);
    lrcHelp.setOnClickListener(
        v ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://github.com/jitwxs/163MusicLyrics"))));
    previous = text("", 18);
    current = text("准备好，开始唱", 26);
    current.setTextColor(0xff176448);
    next = text("", 18);
    for (TextView t : new TextView[] {previous, current, next}) {
      t.setGravity(Gravity.CENTER);
      t.setMaxLines(3);
    }
    root.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
    clock = text("00:00 / 00:00", 14);
    seek = new SeekBar(this);
    root.addView(seek);
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            dragging = true;
          }

          public void onStopTrackingTouch(SeekBar s) {
            dragging = false;
            if (recording || starting) return;
            if (replay != null && replay.ready) {
              replay.seek(s.getProgress());
            } else if (ready) movePreview(s.getProgress(), null);
          }

          public void onProgressChanged(SeekBar s, int p, boolean user) {}
        });
    LinearLayout offset = new LinearLayout(this);
    root.addView(offset);
    TextView label = new TextView(this);
    label.setText("歌词延后(ms)：");
    offset.addView(label);
    shift = new EditText(this);
    shift.setSingleLine(true);
    shift.setInputType(4098);
    shift.setText("0");
    offset.addView(shift, new LinearLayout.LayoutParams(0, -2, 1));
    preview =
        button(
            "试听伴奏 / 暂停",
            () -> {
              if ((!ready && replay == null)
                  || recording
                  || starting
                  || previewSeeking
                  || exporting
                  || separating) return;
              if (replay != null) {
                replayPosition(
                    at ->
                        movePreview(
                            at,
                            () -> {
                              requestFocus();
                              player.start();
                            }));
                return;
              }
              if (player.isPlaying()) {
                player.pause();
              } else {
                requestFocus();
                player.start();
              }
            });
    record =
        button(
            "从当前位置开始录音",
            () -> {
              if (recording) finishTake();
              else begin();
            });
    voiceGain = preferences.getFloat("voice_gain", 1);
    backingGain = preferences.getFloat("backing_gain", 1);
    volume("人声", true);
    volume("伴奏", false);
    listen = button("回听最近录音", () -> playTake());
    export = button("导出最近录音", () -> exportOptions());
    button("已保存的录音", () -> chooseTake());
    button("检查更新（应用内下载）", () -> updater.check());
    button("更多", () -> showMore());
    take = latest();
    controls();
    restoreSession();
    handler.post(tick);
  }

  boolean canNavigate() {
    if (recording || starting || previewSeeking || separating || exporting || importingPair) {
      message("请先完成当前录音或处理");
      return false;
    }
    return true;
  }

  LinearLayout subpage(String title) {
    returnHome();
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(1);
    content.setPadding(24, root.getPaddingTop(), 24, root.getPaddingBottom() + 16);
    content.setOnApplyWindowInsetsListener(
        (v, in) -> {
          v.setPadding(
              24, 16 + in.getSystemWindowInsetTop(), 24, 24 + in.getSystemWindowInsetBottom());
          return in;
        });
    content.setBackgroundColor(0xfff5f6f2);
    scroll.addView(content);
    pageButton(content, "返回", () -> returnHome());
    TextView heading = new TextView(this);
    heading.setText(title);
    heading.setTextSize(26);
    content.addView(heading);
    secondaryPage = scroll;
    homePage.setVisibility(View.GONE);
    pages.addView(scroll);
    return content;
  }

  void pageButton(LinearLayout parent, String title, Runnable action) {
    Button button = new Button(this);
    button.setText(title);
    parent.addView(button);
    button.setOnClickListener(v -> action.run());
  }

  void returnHome() {
    if (secondaryPage != null) {
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(secondaryPage.getWindowToken(), 0);
      pages.removeView(secondaryPage);
      secondaryPage = null;
    }
    homePage.setVisibility(View.VISIBLE);
  }

  @Override
  public void onBackPressed() {
    if (secondaryPage != null) returnHome();
    else super.onBackPressed();
  }

  void showMore() {
    if (!canNavigate()) return;
    LinearLayout content = subpage("更多");
    pageButton(
        content,
        "上传歌曲去人声",
        () -> {
          if (preferences.getString("server", "").isEmpty()) separationSettings();
          else pick(4, "*/*");
        });
    pageButton(content, "去人声服务设置", () -> separationSettings());
    pageButton(content, "关于", () -> showAbout());
  }

  void showAbout() {
    LinearLayout content = subpage("关于");
    TextView info = new TextView(this);
    info.setText(
        "Copyright © 2026 shandianchengzi\n\n"
            + "个人非商业使用免费；商业使用须付费取得书面授权。"
            + "商业授权请通过项目仓库联系作者。\n\n"
            + "歌词接口参考 jitwxs/163MusicLyrics，相关第三方部分保留 Apache-2.0 许可。"
            + "既有旧版本授权不追溯撤销。");
    info.setTextIsSelectable(true);
    content.addView(info);
    pageButton(
        content,
        "项目仓库 · shandianchengzi/sing",
        () -> openLink("https://github.com/shandianchengzi/sing"));
    pageButton(content, "商业授权联系", () -> openLink("https://github.com/shandianchengzi/sing/issues"));
    pageButton(
        content,
        "歌词工具 · jitwxs/163MusicLyrics",
        () -> openLink("https://github.com/jitwxs/163MusicLyrics"));
    pageButton(
        content,
        "许可证与第三方声明",
        () -> {
          LinearLayout page = subpage("许可证与第三方声明");
          TextView terms = new TextView(this);
          terms.setTextIsSelectable(true);
          try (InputStream in =
                  getResources()
                      .openRawResource(
                          getResources().getIdentifier("licenses", "raw", getPackageName()));
              ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            terms.setText(new String(out.toByteArray(), StandardCharsets.UTF_8));
          } catch (Exception e) {
            terms.setText("请查看项目仓库 LICENSE 和 NOTICE。");
          }
          page.addView(terms);
        });
  }

  void openLink(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, url, Toast.LENGTH_LONG).show();
    }
  }

  org.json.JSONArray library() {
    try {
      return new org.json.JSONArray(preferences.getString("library", "[]"));
    } catch (org.json.JSONException e) {
      return new org.json.JSONArray();
    }
  }

  void rememberAudio(String source, String title) {
    if (source.isEmpty()) return;
    org.json.JSONArray list = library(), updated = new org.json.JSONArray();
    try {
      org.json.JSONObject selected = libraryItem(source);
      if (selected == null)
        selected =
            new org.json.JSONObject().put("uri", source).put("title", title).put("filename", title);
      if (!selected.has("filename")) selected.put("filename", title);
      updated.put(selected);
      for (int i = 0; i < list.length(); i++) {
        org.json.JSONObject item = list.optJSONObject(i);
        if (item != null && !source.equals(item.optString("uri"))) updated.put(item);
      }
      preferences.edit().putString("library", updated.toString()).apply();
    } catch (org.json.JSONException ignored) {
    }
  }

  org.json.JSONObject libraryItem(String source) {
    org.json.JSONArray items = library();
    for (int i = 0; i < items.length(); i++) {
      org.json.JSONObject item = items.optJSONObject(i);
      if (item != null && source.equals(item.optString("uri"))) return item;
    }
    return null;
  }

  void libraryField(String source, String field, String value) {
    org.json.JSONArray items = library();
    for (int i = 0; i < items.length(); i++) {
      org.json.JSONObject item = items.optJSONObject(i);
      if (item != null && source.equals(item.optString("uri"))) {
        try {
          item.put(field, value);
        } catch (org.json.JSONException ignored) {
        }
        preferences.edit().putString("library", items.toString()).apply();
        return;
      }
    }
  }

  void renameEntry(String source, String title, Runnable refresh) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(title);
    input.setSelectAllOnFocus(true);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("改名（仅修改显示名称）")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      String changed = input.getText().toString().trim();
                      if (changed.isEmpty()) {
                        input.setError("名称不能为空");
                        return;
                      }
                      org.json.JSONObject previous = libraryItem(source);
                      if (previous != null && !previous.has("filename"))
                        libraryField(source, "filename", title);
                      libraryField(source, "title", changed);
                      if (source.equals(loadedSource)) song.setText(changed);
                      dialog.dismiss();
                      refresh.run();
                    }));
    dialog.show();
  }

  void loadLibraryFolders() {
    libraryFolders.clear();
    try {
      org.json.JSONArray saved = new org.json.JSONArray(preferences.getString("library_folders", "[]"));
      for (int i = 0; i < saved.length(); i++) {
        String path = saved.optString(i);
        if (!path.isEmpty()) {
          Uri uri = Uri.parse(path);
          if (!libraryFolders.contains(uri)) libraryFolders.add(uri);
        }
      }
    } catch (org.json.JSONException ignored) {}
    // Preserve the single folder granted in earlier releases.
    if (libraryFolders.isEmpty()) {
      String old = preferences.getString("library_folder", "");
      if (!old.isEmpty()) libraryFolders.add(Uri.parse(old));
    }
    saveLibraryFolders();
  }

  void saveLibraryFolders() {
    org.json.JSONArray data = new org.json.JSONArray();
    for (Uri folder : libraryFolders) data.put(folder.toString());
    android.content.SharedPreferences.Editor editor = preferences.edit()
        .putString("library_folders", data.toString());
    if (libraryFolders.isEmpty()) editor.remove("library_folder");
    else editor.putString("library_folder", libraryFolders.get(0).toString());
    editor.apply();
  }

  String folderLabel(Uri folder) {
    try {
      String id = android.provider.DocumentsContract.getTreeDocumentId(folder);
      int slash = id.lastIndexOf('/');
      return slash >= 0 ? id.substring(slash + 1) : id;
    } catch (Exception e) { return folder.toString(); }
  }

  void chooseFolder() {
    if (!canNavigate()) return;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(intent, 6);
  }

  void showLibrary() {
    if (!canNavigate()) return;
    LinearLayout content = subpage("本地曲库");
    TextView help = new TextView(this);
    help.setText("可管理多个曲库文件夹。第一个是默认导入目标；自动读取每个文件夹的伴奏和配套 LRC。");
    content.addView(help);
    pageButton(content, "添加曲库文件夹", () -> chooseFolder());
    if (libraryFolders.isEmpty()) {
      TextView missing = new TextView(this);
      missing.setText("暂无曲库文件夹，请点击上方按钮添加。");
      content.addView(missing);
    }
    for (int i = 0; i < libraryFolders.size(); i++) {
      final int index = i;
      Uri folder = libraryFolders.get(i);
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      content.addView(row);
      TextView label = new TextView(this);
      label.setText((i == 0 ? "默认：" : (i + 1) + "：") + folderLabel(folder));
      row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      if (i > 0) {
        Button first = new Button(this);
        first.setText("设为默认");
        row.addView(first);
        first.setOnClickListener(v -> {
          Uri preferred = libraryFolders.remove(index);
          libraryFolders.add(0, preferred);
          saveLibraryFolders();
          showLibrary();
        });
      }
      Button remove = new Button(this);
      remove.setText("移除");
      row.addView(remove);
      remove.setOnClickListener(v ->
          new AlertDialog.Builder(this)
              .setTitle("从列表移除曲库？")
              .setMessage("只移除这个文件夹的曲库授权记录，不删除文件。")
              .setNegativeButton("取消", null)
              .setPositiveButton("移除", (d,w) -> {
                libraryFolders.remove(index);
                saveLibraryFolders();
                showLibrary();
              }).show());
    }
    CheckBox checkbox = new CheckBox(this);
    checkbox.setText("复制而不是移动（保留原文件）");
    checkbox.setChecked(preferences.getBoolean("copy_instead_of_move", false));
    checkbox.setOnCheckedChangeListener((button, checked) ->
        preferences.edit().putBoolean("copy_instead_of_move", checked).apply());
    content.addView(checkbox);
    pageButton(content, "添加伴奏＋LRC", () -> {
      if (libraryFolders.isEmpty()) message("请先添加至少一个曲库文件夹");
      else pick(7, "*/*");
    });
    pageButton(content, "仅添加伴奏（不复制文件）", () -> pick(1, "*/*"));
    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setHint("搜索所有曲库");
    content.addView(search);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(1);
    content.addView(list);
    libraryList = list;
    librarySearch = search;
    search.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) {
        renderLibrary(list, s.toString());
      }
      public void afterTextChanged(android.text.Editable text) {}
    });
    renderLibrary(list, "");
    scanLibraryFolders(list, search);
    renderLibrary(list, "");
  }

  void scanLibraryFolders(LinearLayout list, EditText search) {
    final int version = ++folderScanId;
    folderItems.clear();
    scanningFolders = true;
    final java.util.ArrayList<Uri> folders = new java.util.ArrayList<>(libraryFolders);
    if (folders.isEmpty()) { scanningFolders = false; return; }
    message("正在扫描 " + folders.size() + " 个曲库文件夹…");
    new Thread(() -> {
      java.util.ArrayList<FolderLibrary.Item> files = new java.util.ArrayList<>();
      java.util.ArrayList<String> failed = new java.util.ArrayList<>();
      for (Uri folder : folders) {
        try { files.addAll(FolderLibrary.scan(getApplicationContext(), folder)); }
        catch (Exception e) { failed.add(folderLabel(folder)); }
      }
      handler.post(() -> {
        if (version != folderScanId || isDestroyed()
            || secondaryPage == null || list != libraryList) return;
        folderItems = files;
        scanningFolders = false;
        renderLibrary(list, search.getText().toString());
        if (failed.isEmpty()) message("已扫描 " + folders.size() + " 个曲库");
        else message("以下曲库无法访问，请重新授权：" + android.text.TextUtils.join("、", failed));
      });
    }, "multi-library-scan").start();
  }

  FolderLibrary.Item folderItem(String source) {
    for (FolderLibrary.Item item : folderItems)
      if (source.equals(item.audio.toString())) return item;
    return null;
  }

  /** One logical song per normalized backing filename, with all physical copies retained. */
  org.json.JSONArray allLibrary() {
    org.json.JSONArray stored = library();
    java.util.LinkedHashMap<String, org.json.JSONObject> groups =
        new java.util.LinkedHashMap<>();
    groupedSources.clear();
    // Scanned folders are already in user-defined priority order; first is default.
    for (FolderLibrary.Item file : folderItems) {
      String source = file.audio.toString();
      org.json.JSONObject record = libraryItem(source);
      if (record == null) {
        try {
          record = new org.json.JSONObject().put("uri", source)
              .put("title", file.name).put("filename", file.name);
        } catch (org.json.JSONException ignored) { continue; }
      }
      addGrouped(groups, record);
    }
    // Standalone imports or old saved songs stay visible without a selected folder.
    for (int i = 0; i < stored.length(); i++) {
      org.json.JSONObject row = stored.optJSONObject(i);
      if (row != null) addGrouped(groups, row);
    }
    org.json.JSONArray result = new org.json.JSONArray();
    for (org.json.JSONObject row : groups.values()) result.put(row);
    return result;
  }

  String songKey(org.json.JSONObject row) {
    String filename = row.optString("filename", row.optString("title", ""));
    if (!LibraryNames.isAudio(filename)) filename = row.optString("title", filename);
    return LibraryNames.key(filename);
  }

  void addGrouped(java.util.Map<String, org.json.JSONObject> groups,
      org.json.JSONObject row) {
    String source = row.optString("uri", "");
    if (source.isEmpty()) return;
    String key = songKey(row);
    if (key.isEmpty()) key = source;
    java.util.ArrayList<String> sources = groupedSources.get(key);
    if (sources == null) {
      sources = new java.util.ArrayList<>();
      groupedSources.put(key, sources);
    }
    if (!sources.contains(source)) sources.add(source);
    org.json.JSONObject representative = groups.get(key);
    if (representative == null) groups.put(key, row);
    else if (representative.optString("lrc").isEmpty() && !row.optString("lrc").isEmpty()) {
      // Merge saved lyrics into the logical display, without changing the source preference.
      try {
        org.json.JSONObject enriched = new org.json.JSONObject(representative.toString());
        enriched.put("lrc", row.optString("lrc"));
        groups.put(key, enriched);
      } catch (org.json.JSONException ignored) {}
    }
  }

  void chooseSongSource(String source, java.util.function.Consumer<String> onSelected) {
    org.json.JSONObject row = libraryItem(source);
    String key = row == null ? "" : songKey(row);
    if (key.isEmpty()) {
      FolderLibrary.Item file = folderItem(source);
      if (file != null) key = LibraryNames.key(file.name);
    }
    java.util.ArrayList<String> copies = groupedSources.get(key);
    if (copies == null || copies.size() < 2) { onSelected.accept(source); return; }
    String[] options = new String[copies.size()];
    for (int i = 0; i < copies.size(); i++) {
      FolderLibrary.Item file = folderItem(copies.get(i));
      options[i] = (file == null ? "外部文件" : folderLabel(file.folder))
          + " · " + (file == null ? name(Uri.parse(copies.get(i))) : file.name);
    }
    new AlertDialog.Builder(this).setTitle("选择文件来源")
        .setItems(options, (dialog, index) -> onSelected.accept(copies.get(index)))
        .setNegativeButton("取消", null).show();
  }

  void selectLibraryAudio(String source, String title, FolderLibrary.Item file) {
    final int version = ++folderOpenId;
    if (file == null || file.lyrics == null) {
      originalAudio = source;
      preferences.edit().putString("audio", source).apply();
      returnHome();
      importAudio(Uri.parse(source), title);
      return;
    }
    message("正在读取匹配的 LRC…");
    new Thread(() -> {
      try {
        String text = FolderLibrary.readLrc(getApplicationContext(), file.lyrics);
        if (new Lrc(text).lines.isEmpty()) throw new IOException("LRC 缺少时间戳");
        handler.post(() -> {
          if (isDestroyed() || version != folderOpenId) return;
          rememberAudio(source, title);
          libraryField(source, "lrc", text);
          originalAudio = source;
          preferences.edit().putString("audio", source).apply();
          returnHome();
          importAudio(Uri.parse(source), title);
        });
      } catch (Exception e) {
        handler.post(() -> { if (!isDestroyed() && version == folderOpenId) error(e); });
      }
    }, "folder-lyrics").start();
  }

  void chooseTargetFolder(String title, java.util.function.Consumer<Uri> action) {
    if (libraryFolders.isEmpty()) {
      message("先添加一个曲库文件夹");
      return;
    }
    String[] names = new String[libraryFolders.size()];
    for (int i=0; i<names.length; i++)
      names[i] = (i == 0 ? "默认 · " : "") + folderLabel(libraryFolders.get(i));
    final int[] selected = {0};
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(names, 0, (dialog, index) -> selected[0] = index)
        .setNegativeButton("取消", null)
        .setPositiveButton("确定", (dialog,which) ->
            action.accept(libraryFolders.get(selected[0])))
        .show();
  }

  String filenameFor(String source, String fallback) {
    org.json.JSONObject record = libraryItem(source);
    FolderLibrary.Item item = folderItem(source);
    return item != null ? item.name
        : record != null ? record.optString("filename", fallback) : fallback;
  }

  /** Move or copy selected backing and its paired lyrics. No source is deleted before verification. */
  void relocateLibraryItem(String source, String title, String filename,
      FolderLibrary.Item scanned, Uri targetFolder) {
    if (!canNavigate()) return;
    if (scanningFolders) { message("曲库正在扫描，请稍后再整理"); return; }
    if (scanned != null && targetFolder.equals(scanned.folder)) {
      message("这个文件已经位于目标文件夹");
      return;
    }
    final boolean copyOnly = preferences.getBoolean("copy_instead_of_move", false);
    final Uri sourceAudio = Uri.parse(source);
    final Uri oldLyrics = scanned == null ? null : scanned.lyrics;
    final org.json.JSONObject saved = libraryItem(source);
    final String storedLrc = saved == null ? "" : saved.optString("lrc");
    final String actualName = LibraryNames.isAudio(filename) ? filename : name(sourceAudio);
    if (!LibraryNames.isAudio(actualName)) { message("无法确认伴奏文件名，操作已取消"); return; }
    final boolean wasLoaded = source.equals(loadedSource);
    if (wasLoaded && !copyOnly) {
      stopVoice();
      releasePlayer();
    }
    importingPair = true;
    controls();
    message(copyOnly ? "正在复制并校验歌曲…" : "正在移动并校验歌曲…");
    new Thread(() -> {
      Uri targetUri = null;
      String lyricsText = storedLrc;
      String note = "";
      Exception failure = null;
      try {
        if (oldLyrics != null) {
          lyricsText = FolderLibrary.readLrc(getApplicationContext(), oldLyrics);
          if (new Lrc(lyricsText).lines.isEmpty()) throw new IOException("原始 LRC 无有效时间戳");
        }
        String lyricsFileName = oldLyrics != null ? name(oldLyrics)
            : lyricsText.isEmpty() ? null : LibraryNames.stem(actualName) + ".lrc";
        FolderLibrary.Imported copied = FolderLibrary.copyPair(
            getApplicationContext(), targetFolder, sourceAudio, actualName,
            oldLyrics, lyricsFileName, oldLyrics == null ? lyricsText : null);
        targetUri = copied.audio;
        note = copied.warning;
        String destinationSource = copied.audio.toString();
        if (!copyOnly) {
          persistMovedReferences(source, destinationSource, title, actualName, lyricsText);
          String deletion = FolderLibrary.deleteSources(
              getApplicationContext(), sourceAudio, oldLyrics, copied.audio, copied.lyrics);
          if (!deletion.isEmpty()) note = deletion;
        }
      } catch (Exception e) { failure = e; }
      final Uri destination = targetUri;
      final String finalLyrics = lyricsText, finalNote = note;
      final Exception error = failure;
      handler.post(() -> {
        if (isDestroyed()) return;
        importingPair = false;
        if (error != null) {
          if (wasLoaded && !copyOnly) {
            originalAudio = source;
            importAudio(sourceAudio, title);
          }
          message("整理失败，原文件保留：" + error.getMessage());
          controls();
          return;
        }
        if (copyOnly) {
          rememberAudio(destination.toString(), title);
          if (!finalLyrics.isEmpty()) libraryField(destination.toString(), "lrc", finalLyrics);
        } else if (wasLoaded) {
          originalAudio = destination.toString();
          importAudio(destination, title);
        }
        showLibrary();
        message(finalNote.isEmpty()
            ? (copyOnly ? "已复制到 " : "已移动到 ") + folderLabel(targetFolder)
            : finalNote);
        controls();
      });
    }, "library-transfer").start();
  }

  /** Only changes exact matching URIs; preserves titles, cached lyrics and take timing. */
  void persistMovedReferences(String oldSource, String newSource, String title,
      String filename, String lyrics) throws Exception {
    File[] takes = getFilesDir().listFiles((dir, name) -> name.endsWith(".m4a"));
    if (takes != null) {
      for (File voice : takes) {
        File sidecar = new File(voice.getPath() + ".json");
        if (!sidecar.isFile()) continue;
        TakeInfo info = TakeInfo.read(voice, "");
        if (oldSource.equals(info.source))
          new TakeInfo(info.startMs, newSource, false).save(voice);
      }
    }
    org.json.JSONArray existing = library(), changed = new org.json.JSONArray();
    org.json.JSONObject replacement = null;
    for (int i = 0; i < existing.length(); i++) {
      org.json.JSONObject row = existing.optJSONObject(i);
      if (row != null && oldSource.equals(row.optString("uri"))) {
        replacement = new org.json.JSONObject(row.toString());
        break;
      }
    }
    if (replacement == null)
      replacement = new org.json.JSONObject().put("title", title).put("filename", filename);
    replacement.put("uri", newSource);
    replacement.put("filename", filename);
    if (!lyrics.isEmpty()) replacement.put("lrc", lyrics);
    changed.put(replacement);
    for (int i = 0; i < existing.length(); i++) {
      org.json.JSONObject row = existing.optJSONObject(i);
      if (row != null && !oldSource.equals(row.optString("uri"))
          && !newSource.equals(row.optString("uri"))) changed.put(row);
    }
    android.content.SharedPreferences.Editor update =
        preferences.edit().putString("library", changed.toString());
    if (oldSource.equals(preferences.getString("audio", "")))
      update.putString("audio", newSource);
    if (!update.commit()) throw new IOException("曲库引用保存失败，原文件保留");
  }

  void renderLibrary(LinearLayout parent, String query) {
    parent.removeAllViews();
    org.json.JSONArray entries = allLibrary();
    String filter = query.toLowerCase(java.util.Locale.ROOT);
    int shown = 0;
    for (int i = 0; i < entries.length(); i++) {
      org.json.JSONObject item = entries.optJSONObject(i);
      if (item == null) continue;
      String source = item.optString("uri"), title = item.optString("title", "伴奏");
      if (source.isEmpty() || !title.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
      shown++;
      FolderLibrary.Item file = folderItem(source);
      java.util.ArrayList<String> sources = groupedSources.get(songKey(item));
      int copies = sources == null ? 1 : sources.size();
      LinearLayout entryRow = new LinearLayout(this);
      entryRow.setOrientation(LinearLayout.HORIZONTAL);
      parent.addView(entryRow);
      Button entry = new Button(this);
      entry.setText(title + (copies > 1 ? "（" + copies + " 处）" : ""));
      entryRow.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
      Button lyrics = new Button(this);
      lyrics.setText(item.optString("lrc").isEmpty() && (file == null || file.lyrics == null) ? "LRC" : "LRC ✓");
      entryRow.addView(lyrics);
      lyrics.setOnClickListener(
          v -> {
            if (libraryItem(source) == null) rememberAudio(source, title);
             preferences.edit().putString("pending_lrc_source", source).apply();
            pick(5, "*/*");
          });
      Button relocate = new Button(this);
      relocate.setText("整理到");
      entryRow.addView(relocate);
      relocate.setEnabled(!scanningFolders);
      relocate.setOnClickListener(v ->
          chooseSongSource(source, chosenSource ->
              chooseTargetFolder("选择目标曲库（按复选框复制或移动）", target ->
                  relocateLibraryItem(chosenSource, title,
                      filenameFor(chosenSource, item.optString("filename", title)),
                      folderItem(chosenSource), target))));
      Button rename = new Button(this);
      rename.setText("改名");
      entryRow.addView(rename);
      rename.setOnClickListener(
          v -> {
            if (libraryItem(source) == null) rememberAudio(source, title);
            renameEntry(source, title, () -> renderLibrary(parent, query));
          });
      entry.setOnClickListener(v -> selectLibraryAudio(source, title, file));
      entry.setOnLongClickListener(
          v -> {
            if (file != null || copies > 1) { message("使用「整理到」复制或移动文件；不同位置的副本会合并显示"); return true; }
            new AlertDialog.Builder(this)
                .setTitle("从曲库移除？")
                .setMessage(title + "\n只移除记录，不删除原文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                    "移除",
                    (d, which) -> {
                      org.json.JSONArray current = library(), kept = new org.json.JSONArray();
                      for (int j = 0; j < current.length(); j++) {
                        org.json.JSONObject row = current.optJSONObject(j);
                        if (row != null && !source.equals(row.optString("uri"))) kept.put(row);
                      }
                      preferences.edit().putString("library", kept.toString()).apply();
                      if (source.equals(preferences.getString("audio", "")))
                        preferences.edit().remove("audio").apply();
                      renderLibrary(parent, query);
                    })
                .show();
            return true;
          });
    }
    if (shown == 0) {
      TextView empty = new TextView(this);
      empty.setText(query.isEmpty() ? "暂无伴奏，点击添加伴奏即可建立曲库。" : "没有匹配的伴奏");
      parent.addView(empty);
    }
  }

  void volume(String name, boolean vocal) {
    TextView label =
        text(name + "音量：" + Math.round((vocal ? voiceGain : backingGain) * 100) + "%", 14);
    SeekBar bar = new SeekBar(this);
    bar.setMax(100);
    bar.setProgress(Math.round((vocal ? voiceGain : backingGain) * 100));
    root.addView(bar);
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int value, boolean user) {
            label.setText(name + "音量：" + value + "%");
            if (vocal) voiceGain = value / 100f;
            else backingGain = value / 100f;
            preferences
                .edit()
                .putFloat("voice_gain", voiceGain)
                .putFloat("backing_gain", backingGain)
                .apply();
            if (replay != null) replay.levels(voiceGain, backingGain);
            if (ready) player.setVolume(backingGain, backingGain);
          }
        });
  }

  void replayPosition(java.util.function.IntConsumer action) {
    int at =
        replay != null && replay.ready
            ? replay.position()
            : ready ? player.getCurrentPosition() : 0;
    String source = replayInfo == null ? loadedSource : replayInfo.source;
    stopVoice();
    if (!source.isEmpty() && (!ready || !source.equals(loadedSource))) {
      originalAudio = source;
      Uri uri = Uri.parse(source);
      importAudio(uri, name(uri));
      afterAudioReady = () -> action.accept(at);
    } else action.accept(at);
  }

  TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(0xff22382f);
    root.addView(t);
    return t;
  }

  Button button(String s, Runnable action) {
    Button b = new Button(this);
    b.setText(s);
    root.addView(b);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  String time(int ms) {
    return String.format(java.util.Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60);
  }

  void message(String s) {
    status.setText(s);
  }

  void error(Exception e) {
    message("失败：" + e.getMessage());
  }

  void controls() {
    boolean busy = recording || starting || previewSeeking || separating || exporting || importingPair;
    record.setText(recording ? "停止并保存录音" : "从当前位置开始录音");
    record.setEnabled(
        (ready || !loadedSource.isEmpty()) && replay == null && !starting
            && !previewSeeking && !separating && !exporting && !importingPair);
    preview.setEnabled((ready || replay != null) && !busy);
    seek.setEnabled((ready || (replay != null && replay.ready)) && !busy);
    listen.setEnabled(!busy && take != null);
    listen.setText(
        replay != null && !replay.ready
            ? "取消准备回听"
            : replay != null && replay.playing ? "暂停回听" : "回听最近录音 / 继续");
    export.setEnabled(!busy && take != null);
  }

  void pick(int code, String type) {
    if (starting || previewSeeking || exporting || importingPair) {
      message("请等待当前操作完成");
      return;
    }
    if (separating) {
      message("正在分离歌曲，请等待或取消");
      return;
    }
    if (recording) {
      message("请先停止录音");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
    if (code == 1 || code == 4 || code == 7)
      intent.putExtra(
          Intent.EXTRA_MIME_TYPES,
          new String[] {
            "audio/*", "application/ogg", "application/x-ogg", "video/mp4", "application/mp4"
          });
    startActivityForResult(intent, code);
  }

  String name(Uri uri) {
    if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
    try (Cursor c =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getString(0);
    }
    return "伴奏";
  }

  @Override
  public void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code == AppUpdater.INSTALL_PERMISSION) {
      updater.permissionReturned();
      return;
    }
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (code == 5) preferences.edit().remove("pending_lrc_source").apply();
      if (code == 7 || code == 8) { pendingPairAudio = null; pendingPairName = null; }
      return;
    }
    Uri uri = data.getData();
    try {
      if (code == 6) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, flags);
        if (!libraryFolders.contains(uri)) {
          libraryFolders.add(uri);
          saveLibraryFolders();
        }
        showLibrary();
      } else if (code == 7) {
        saveGrant(uri, data.getFlags());
        pendingPairAudio = uri;
        pendingPairName = name(uri);
        pick(8, "*/*");
      } else if (code == 8) {
        saveGrant(uri, data.getFlags());
        chooseTargetFolder("选择伴奏＋LRC 的目标曲库", destination ->
            addPair(uri, name(uri), destination));
      } else if (code == 1) {
        try {
          saveGrant(uri, data.getFlags());
          originalAudio = uri.toString();
        } catch (SecurityException e) {
          originalAudio = "";
        }
        preferences.edit().putString("audio", originalAudio).apply();
        importAudio(uri, name(uri));
      } else if (code == 2 || code == 5) {
        requestVersion++;
        byte[] bytes;
        try (InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] b = new byte[4096];
          int n;
          while ((n = in.read(b)) != -1) {
            if (out.size() + n > 2 * 1024 * 1024) throw new IOException("歌词文件大于 2MB");
            out.write(b, 0, n);
          }
          bytes = out.toByteArray();
        }
        String s;
        if (bytes.length >= 2
            && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
                || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255))
          s = new String(bytes, "UTF-16");
        else {
          try {
            s =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
          } catch (CharacterCodingException e) {
            s = new String(bytes, "GB18030");
          }
        }
        if (code == 5) {
          String target = preferences.getString("pending_lrc_source", "");
          preferences.edit().remove("pending_lrc_source").apply();
          if (new Lrc(s).lines.isEmpty()) throw new IOException("请选择带时间戳的标准 LRC");
          if (libraryItem(target) == null) throw new IOException("该曲库条目已不存在");
          libraryField(target, "lrc", s);
          if (target.equals(loadedSource)) setLrc(s);
          if (secondaryPage != null) showLibrary();
          Toast.makeText(this, "已配套 LRC", Toast.LENGTH_SHORT).show();
        } else setLrc(s);
      } else if (code == 4) {
        returnHome();
        startSeparation(uri, name(uri));
      } else if (code == 3 && pending != null) {
        exportTo(uri);
      }
    } catch (Exception e) {
      error(e);
    }
  }

  void saveGrant(Uri uri, int flags) {
    int bits = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if (bits == 0) return;
    try { getContentResolver().takePersistableUriPermission(uri, bits); }
    catch (SecurityException ignored) {}
  }

  void addPair(Uri lrcUri, String lrcName, Uri destination) {
    if (pendingPairAudio == null || importingPair) return;
    final Uri input = pendingPairAudio, folder = destination;
    final String filename = pendingPairName;
    final boolean copy = preferences.getBoolean("copy_instead_of_move", false);
    pendingPairAudio = null; pendingPairName = null;
    if (folder == null) { message("请先选择曲库文件夹"); return; }
    importingPair = true;
    message(copy ? "正在复制伴奏和 LRC…" : "正在移动伴奏和 LRC…");
    new Thread(() -> {
      Uri resultAudio = input;
      String lyrics = null, warning = "";
      Exception failure = null;
      try {
        lyrics = FolderLibrary.readLrc(getApplicationContext(), lrcUri);
        if (new Lrc(lyrics).lines.isEmpty()) throw new IOException("LRC 没有有效时间戳");
        FolderLibrary.Imported result = FolderLibrary.copyPair(
            getApplicationContext(), folder, input, filename, lrcUri, lrcName, null);
        resultAudio = result.audio;
        warning = result.warning;
        if (!copy) {
          // Update any pre-existing recording references before deleting imported sources.
          persistMovedReferences(input.toString(), resultAudio.toString(),
              filename, filename, lyrics);
          String deletion = FolderLibrary.deleteSources(
              getApplicationContext(), input, lrcUri, result.audio, result.lyrics);
          if (!deletion.isEmpty()) warning = deletion;
        }
      } catch (Exception e) { failure = e; }
      final Uri loaded = resultAudio;
      final String text = lyrics, note = warning;
      final Exception error = failure;
      handler.post(() -> {
        if (isDestroyed()) return;
        importingPair = false;
        if (error != null) { message("添加失败，原文件保留：" + error.getMessage()); return; }
        String source = loaded.toString();
        rememberAudio(source, filename);
        libraryField(source, "lrc", text);
        originalAudio = source;
        preferences.edit().putString("audio", source).apply();
        importAudio(loaded, filename);
        message(note.isEmpty() ? (copy ? "已复制伴奏及 LRC" : "已移动伴奏及 LRC") : note);
        if (secondaryPage != null) showLibrary();
      });
    }, "pair-import").start();
  }

  void separationSettings() {
    if (recording || starting || separating || exporting) return;
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(1);
    form.setPadding(24, 8, 24, 8);
    EditText base = new EditText(this);
    base.setSingleLine(true);
    base.setHint("http://电脑局域网IP:8765");
    base.setText(preferences.getString("server", ""));
    form.addView(base);
    EditText token = new EditText(this);
    token.setSingleLine(true);
    token.setInputType(129);
    token.setHint("服务端 SING_TOKEN");
    token.setText(preferences.getString("server_token", ""));
    form.addView(token);
    TextView note = new TextView(this);
    note.setText("使用仓库 server 目录的自部署服务。手机与电脑需能互相访问；外网请使用 HTTPS。令牌仅保存在本机。");
    form.addView(note);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("自部署去人声服务")
            .setView(form)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试连接", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    try {
                      String address = SeparationClient.address(base.getText().toString());
                      String secret = token.getText().toString().trim();
                      if (!secret.matches("[!-~]{12,}"))
                        throw new IOException("令牌至少 12 个字符，请使用无空格的英文字母、数字或符号");
                      preferences
                          .edit()
                          .putString("server", address)
                          .putString("server_token", secret)
                          .apply();
                      dialog.dismiss();
                      message("服务设置已保存，可选择歌曲上传分离");
                    } catch (Exception e) {
                      note.setText(e.getMessage());
                    }
                  });
          dialog
              .getButton(-3)
              .setOnClickListener(
                  v -> {
                    try {
                      String address = SeparationClient.address(base.getText().toString());
                      String secret = token.getText().toString().trim();
                      note.setText("正在连接…");
                      new Thread(
                              () -> {
                                try {
                                  SeparationClient.health(address, secret);
                                  handler.post(
                                      () -> {
                                        if (!isDestroyed()) note.setText("连接成功，服务接口兼容");
                                      });
                                } catch (Exception e) {
                                  handler.post(
                                      () -> {
                                        if (!isDestroyed()) note.setText("连接失败：" + e.getMessage());
                                      });
                                }
                              },
                              "service-test")
                          .start();
                    } catch (Exception e) {
                      note.setText(e.getMessage());
                    }
                  });
        });
    dialog.show();
  }

  void startSeparation(Uri uri, String title) {
    if (recording || starting || separating || exporting) return;
    String base = preferences.getString("server", "");
    String token = preferences.getString("server_token", "");
    if (base.isEmpty()) {
      separationSettings();
      return;
    }
    separating = true;
    stopVoice();
    if (ready) player.pause();
    controls();
    final SeparationClient.Task task = new SeparationClient.Task();
    separationTask = task;
    ProgressDialog progress = new ProgressDialog(this);
    progress.setTitle("自部署去人声");
    progress.setMessage("准备上传…");
    progress.setCancelable(false);
    progress.setButton(-2, "取消", (d, w) -> task.cancel());
    progress.show();
    new Thread(
            () -> {
              try {
                File result =
                    SeparationClient.separate(
                        getApplicationContext(),
                        uri,
                        base,
                        token,
                        task,
                        text ->
                            handler.post(
                                () -> {
                                  if (!isDestroyed() && !task.cancelled) progress.setMessage(text);
                                }));
                handler.post(
                    () -> {
                      if (isDestroyed()) return;
                      separating = false;
                      progress.dismiss();
                      controls();
                      if (task.cancelled) return;
                      originalAudio = Uri.fromFile(result).toString();
                      preferences.edit().putString("audio", originalAudio).apply();
                      importAudio(Uri.fromFile(result), title + " · 去人声伴奏");
                    });
              } catch (Exception e) {
                handler.post(
                    () -> {
                      if (isDestroyed()) return;
                      separating = false;
                      progress.dismiss();
                      controls();
                      error(e);
                    });
              }
            },
            "vocal-separation")
        .start();
  }

  void importAudio(Uri uri, String title) {
    loadedSource = uri.toString();
    requestVersion++;
    org.json.JSONObject savedItem = libraryItem(loadedSource);
    String paired = savedItem == null ? "" : savedItem.optString("lrc");
    lrc = new Lrc(paired);
    preferences.edit().putString("lrc", paired).apply();
    stopVoice();
    releasePlayer();
    final int version = ++importVersion;
    controls();
    String mime = getContentResolver().getType(uri);
    boolean mp4 =
        "video/mp4".equals(mime)
            || "application/mp4".equals(mime)
            || (savedItem == null ? title : savedItem.optString("filename", title))
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".mp4");
    message(mp4 ? "正在提取 MP4 音轨…" : "正在打开伴奏…");
    if (mp4) {
      new Thread(
              () -> {
                try {
                  File f = AudioImport.extract(getApplicationContext(), uri);
                  handler.post(
                      () -> {
                        if (version != importVersion || isDestroyed()) {
                          f.delete();
                          return;
                        }
                        extracted = f;
                        openAudio(Uri.fromFile(f), title + " · 仅音频");
                      });
                } catch (Exception e) {
                  handler.post(
                      () -> {
                        if (version == importVersion && !isDestroyed()) error(e);
                      });
                }
              },
              "mp4-audio")
          .start();
    } else openAudio(uri, title);
  }

  void restoreSession() {
    String saved = preferences.getString("lrc", "");
    if (!saved.isEmpty()) {
      lrc = new Lrc(saved);
    }
    shift.setText(preferences.getString("shift", "0"));
    originalAudio = preferences.getString("audio", "");
    if (!preferences.getBoolean("paired_lrc", false)) {
      if (!saved.isEmpty()) libraryField(originalAudio, "lrc", saved);
      preferences.edit().putBoolean("paired_lrc", true).apply();
    }
    if (!originalAudio.isEmpty())
      try {
        Uri uri = Uri.parse(originalAudio);
        importAudio(uri, name(uri));
      } catch (Exception e) {
        message("原伴奏已移动或权限失效，请重新选择；录音仍然保留。");
      }
  }

  void openAudio(Uri uri, String title) {
    final String source = loadedSource;
    try {
      audio = uri;
      player = new MediaPlayer();
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build());
      player.setDataSource(this, uri);
      player.setOnPreparedListener(
          p -> {
            ready = true;
            rememberAudio(source, title.replace(" · 仅音频", ""));
            p.setVolume(backingGain, backingGain);
            seek.setMax(p.getDuration());
            org.json.JSONObject item = libraryItem(source);
            song.setText(item == null ? title : item.optString("title", title));
            if (secondaryPage != null) showLibrary();
            message("伴奏已就绪");
            controls();
            Runnable action = afterAudioReady;
            afterAudioReady = null;
            if (action != null) action.run();
          });
      player.setOnCompletionListener(
          p -> {
            if (recording) finishTake();
          });
      player.setOnErrorListener(
          (p, w, e) -> {
            if (recording) finishTake();
            releasePlayer();
            starting = false;
            message("音轨无法播放，请换用系统支持的 MP3/M4A/WAV/OGG（Vorbis、Opus）或 AAC 音轨 MP4");
            controls();
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      releasePlayer();
      starting = false;
      error(e);
      controls();
    }
  }

  void setLrc(String s) {
    Lrc parsed = new Lrc(s);
    if (parsed.lines.isEmpty()) {
      message("未发现带时间戳的歌词，请选择标准 LRC");
      return;
    }
    lrc = parsed;
    preferences.edit().putString("lrc", s).apply();
    if (!loadedSource.isEmpty()) libraryField(loadedSource, "lrc", s);
    message("已载入 " + lrc.lines.size() + " 行歌词；正数偏移让歌词延后。");
  }

  void lyricsDialog() {
    if (recording || starting || exporting) return;
    EditText input = new EditText(this);
    input.setHint("网易云歌曲 ID 或含 id= 的歌曲链接");
    new AlertDialog.Builder(this)
        .setTitle("获取网易云 LRC")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "获取",
            (d, w) -> {
              String s = input.getText().toString().trim();
              Matcher m = Pattern.compile("(?:[?&]id=|/song/)(\\d+)").matcher(s);
              String id = m.find() ? m.group(1) : s;
              if (!id.matches("\\d{1,19}")) {
                message("请输入歌曲数字 ID 或完整歌曲链接");
                return;
              }
              final int version = ++requestVersion;
              message("正在获取歌词…");
              new Thread(
                      () -> {
                        try {
                          String lyrics = LyricsApi.fetch(id);
                          handler.post(
                              () -> {
                                if (version == requestVersion && !isDestroyed()) setLrc(lyrics);
                              });
                        } catch (Exception e) {
                          handler.post(
                              () -> {
                                if (version == requestVersion && !isDestroyed()) error(e);
                              });
                        }
                      },
                      "lyrics")
                  .start();
            })
        .show();
  }

  void begin() {
    if (replay != null || starting || previewSeeking || separating || exporting || importingPair) return;
    if (!ready) {
      if (loadedSource.isEmpty()) { message("请先导入伴奏"); return; }
      starting = true;
      message("正在加载伴奏，完成后将自动从 00:00 开始录音");
      afterAudioReady = () -> {
        starting = false;
        begin();
      };
      controls();
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 9);
      return;
    }
    replayPosition(
        position -> {
          if (position >= player.getDuration() - 100) {
            message("已到伴奏结尾，请拖动到想录制的位置");
            return;
          }
          // MediaPlayer.pause() from Prepared (never started) is invalid on some devices.
          // Also seeking to the current 00:00 position need not emit onSeekComplete.
          boolean wasPlaying = player.isPlaying();
          if (wasPlaying) player.pause();
          starting = true;
          controls();
          int currentPosition = player.getCurrentPosition();
          if (Math.abs(currentPosition - position) <= 50) {
            startRecorder();
          } else {
            movePreview(position, () -> {
              if (starting) startRecorder();
            });
          }
        });
  }

  void movePreview(int position, Runnable after) {
    final int version = ++seekVersion;
    previewSeeking = true;
    controls();
    player.setOnSeekCompleteListener(
        p -> {
          if (version != seekVersion) return;
          ++seekVersion;
          p.setOnSeekCompleteListener(null);
          previewSeeking = false;
          if (after != null) after.run();
          controls();
        });
    player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
    handler.postDelayed(
        () -> {
          if (version != seekVersion || !previewSeeking) return;
          ++seekVersion;
          previewSeeking = false;
          starting = false;
          previewSeeking = false;
          ++seekVersion;
          if (player != null) player.setOnSeekCompleteListener(null);
          message("伴奏定位超时，请重试或重新导入伴奏");
          controls();
        },
        3000);
  }

  void startRecorder() {
    if (isFinishing() || isDestroyed()) {
      starting = false;
      return;
    }
    String displayedName = song == null ? "伴奏" : song.getText().toString();
    File f = new File(getFilesDir(), RecordingNames.fileName(displayedName, new java.util.Date()));
    try {
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(44100);
      recorder.setAudioEncodingBitRate(192000);
      recorder.setAudioChannels(1);
      recorder.setOutputFile(f.getAbsolutePath());
      recorder.setOnErrorListener(
          (r, w, e) -> {
            if (recording) finishTake();
          });
      recorder.prepare();
      requestFocus();
      recordingStart = player.getCurrentPosition();
      new TakeInfo(
              recordingStart, originalAudio.isEmpty() ? audio.toString() : originalAudio, false)
          .save(f);
      recorder.start();
      recording = true;
      starting = false;
      take = f;
      player.start();
      message("从 " + time((int) recordingStart) + " 开始录音；停止后自动保存");
      controls();
    } catch (Exception e) {
      if (recorder != null) {
        recorder.release();
        recorder = null;
      }
      recording = starting = false;
      f.delete();
      new File(f.getPath() + ".json").delete();
      take = latest();
      error(e);
      controls();
    }
  }

  void finishTake() {
    if (!recording) return;
    recording = false;
    if (player != null && ready) player.pause();
    try {
      recorder.stop();
      message("录音已保存，可同步回听或选择导出方式");
    } catch (Exception e) {
      if (take != null) {
        take.delete();
        new File(take.getPath() + ".json").delete();
      }
      take = latest();
      message("录音过短或中断，未保存本次录音");
    } finally {
      recorder.release();
      recorder = null;
      controls();
    }
  }

  File latest() {
    File[] files = getFilesDir().listFiles((d, n) -> n.endsWith(".m4a"));
    File last = null;
    if (files != null)
      for (File f : files) if (last == null || f.lastModified() > last.lastModified()) last = f;
    return last;
  }

  void chooseTake() {
    if (recording || starting || separating || exporting || importingPair) return;
    File[] files = getFilesDir().listFiles((d, n) -> n.endsWith(".m4a"));
    if (files == null || files.length == 0) { message("暂无录音"); return; }
    java.util.Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
    String[] names = new String[files.length];
    for (int i=0; i<files.length; i++) names[i] = files[i].getName();
    new AlertDialog.Builder(this)
        .setTitle("已保存的录音")
        .setItems(names, (dialog, index) -> {
          File selected = files[index];
          new AlertDialog.Builder(this)
              .setTitle(selected.getName())
              .setItems(new String[]{"选中以回听／导出", "删除这条录音"}, (d, action) -> {
                if (action == 0) {
                  stopVoice();
                  take = selected;
                  message("已选择：" + take.getName());
                  controls();
                } else {
                  new AlertDialog.Builder(this)
                      .setTitle("确认永久删除录音？")
                      .setMessage(selected.getName() + "\n同时删除这条录音的时间轴信息，不会删除伴奏或 LRC。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton("删除", (confirm, which) -> deleteTake(selected))
                      .show();
                }
              })
              .setNegativeButton("取消", null)
              .show();
        })
        .setNegativeButton("关闭", null)
        .show();
  }

  void deleteTake(File selected) {
    if (recording || starting || separating || exporting || importingPair) return;
    boolean current = selected.equals(take);
    if (current) stopVoice();
    if (!selected.delete()) {
      message("录音删除失败，请检查文件是否仍被使用");
      return;
    }
    File metadata = new File(selected.getPath() + ".json");
    boolean metaRemoved = !metadata.exists() || metadata.delete();
    new File(selected.getPath() + ".json.tmp").delete();
    if (current) take = latest();
    controls();
    message(metaRemoved ? "录音已删除" : "录音已删除，但时间轴文件无法删除");
  }

  void playTake() {
    if (take == null || recording || starting || separating || exporting) return;
    if (replay != null) {
      if (!replay.ready) {
        stopVoice();
        message("已取消准备回听");
      } else {
        requestFocus();
        replay.toggle();
      }
      controls();
      return;
    }
    try {
      ++seekVersion;
      previewSeeking = false;
      if (player != null) {
        player.setOnSeekCompleteListener(null);
        if (ready) player.pause();
      }
      requestFocus();
      replayInfo = TakeInfo.read(take, originalAudio);
      replay =
          new TakePlayback(
              this,
              take,
              replayInfo,
              voiceGain,
              backingGain,
              new TakePlayback.Listener() {
                public void changed() {
                  controls();
                  if (replay != null && replay.ready)
                    message(
                        replayInfo.legacy
                            ? "旧录音按 00:00 对齐当前伴奏，请确认伴奏正确"
                            : "同步回听：拖动进度条定位，音量设置同时用于导出");
                }

                public void failed(Exception e) {
                  stopVoice();
                  error(e);
                  controls();
                }
              });
      message("正在准备人声和伴奏…再次点击可取消");
      controls();
    } catch (Exception e) {
      error(e);
      controls();
    }
  }

  void stopVoice() {
    replayInfo = null;
    if (replay != null) {
      replay.close();
      replay = null;
    }
  }

  void releasePlayer() {
    ready = false;
    previewSeeking = false;
    afterAudioReady = null;
    if (player != null) {
      player.release();
      player = null;
    }
    if (extracted != null) {
      extracted.delete();
      extracted = null;
    }
  }

  void exportOptions() {
    if (take == null || recording || starting || separating || exporting) return;
    new AlertDialog.Builder(this)
        .setTitle("导出录音")
        .setItems(
            new String[] {
              "仅导出人声（调节音量后为 WAV，否则 M4A）", "人声＋伴奏混音（WAV，本次录制时段）", "分别导出人声和伴奏（ZIP，本次时段，应用音量）"
            },
            (d, mode) -> {
              try {
                TakeInfo info =
                    mode == 0 ? new TakeInfo(0, "", false) : TakeInfo.read(take, originalAudio);
                if (mode != 0 && info.source.isEmpty()) throw new IOException("请先选择这次录音对应的原伴奏");
                Runnable choose =
                    () -> {
                      pending = take;
                      pendingInfo = info;
                      pendingMode = mode;
                      pendingVoiceGain = voiceGain;
                      pendingBackingGain = backingGain;
                      boolean original = mode == 0 && pendingVoiceGain == 1;
                      String suffix =
                          mode == 0
                              ? (original ? "_人声.m4a" : "_人声.wav")
                              : mode == 1 ? "_混音.wav" : "_双轨.zip";
                      String mime =
                          mode == 0
                              ? (original ? "audio/mp4" : "audio/wav")
                              : mode == 1 ? "audio/wav" : "application/zip";
                      startActivityForResult(
                          new Intent(Intent.ACTION_CREATE_DOCUMENT)
                              .setType(mime)
                              .addCategory(Intent.CATEGORY_OPENABLE)
                              .putExtra(Intent.EXTRA_TITLE, take.getName().replace(".m4a", suffix)),
                          3);
                    };
                if (info.legacy && mode != 0)
                  new AlertDialog.Builder(this)
                      .setTitle("确认旧录音伴奏")
                      .setMessage("旧录音没有保存起点和伴奏信息，将使用当前伴奏并按 00:00 对齐。请确认当前伴奏正确。")
                      .setPositiveButton("确认导出", (a, w) -> choose.run())
                      .setNegativeButton("取消", null)
                      .show();
                else choose.run();
              } catch (Exception e) {
                error(e);
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void exportTo(Uri destination) {
    final File file = pending;
    final TakeInfo info = pendingInfo;
    final int mode = pendingMode;
    final float vocals = pendingVoiceGain, music = pendingBackingGain;
    pending = null;
    stopVoice();
    if (ready) player.pause();
    exporting = true;
    controls();
    exportProgress = new ProgressDialog(this);
    exportProgress.setMessage(mode == 1 ? "正在本机混音，可能需要一些时间…" : "正在导出…");
    exportProgress.setCancelable(false);
    exportProgress.setButton(
        -2,
        "取消",
        (d, w) -> {
          if (exportThread != null) exportThread.interrupt();
        });
    exportProgress.show();
    exportThread =
        new Thread(
            () -> {
              Exception failure = null;
              try {
                TakeExport.write(
                    getApplicationContext(), file, info, mode, destination, vocals, music);
              } catch (Exception e) {
                failure = e;
              }
              final Exception result = failure;
              handler.post(
                  () -> {
                    if (isDestroyed()) return;
                    exporting = false;
                    exportProgress.dismiss();
                    exportProgress = null;
                    exportThread = null;
                    controls();
                    if (result == null) message("导出完成");
                    else message("导出未完成：" + result.getMessage() + "；可删除目标处的不完整文件后重试");
                  });
            },
            "take-export");
    exportThread.start();
  }

  final AudioManager.OnAudioFocusChangeListener focus =
      change -> {
        if (change < 0) {
          starting = false;
          previewSeeking = false;
          ++seekVersion;
          if (player != null) player.setOnSeekCompleteListener(null);
          if (recording) finishTake();
          if (ready) player.pause();
          stopVoice();
          controls();
        }
      };

  void requestFocus() {
    ((AudioManager) getSystemService(AUDIO_SERVICE))
        .requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 9) {
      if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) begin();
      else message("需要麦克风权限才能录音，可在系统设置授权。");
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    preferences.edit().putString("shift", shift.getText().toString()).apply();
  }

  @Override
  protected void onStop() {
    super.onStop();
    afterAudioReady = null;
    previewSeeking = false;
    starting = false;
    if (player != null) player.setOnSeekCompleteListener(null);
    if (recording) finishTake();
    if (ready) player.pause();
    stopVoice();
    controls();
  }

  @Override
  protected void onDestroy() {
    if (updater != null) updater.close();
    folderScanId++;
    folderOpenId++;
    if (exportThread != null) exportThread.interrupt();
    if (exportProgress != null) exportProgress.dismiss();
    if (separationTask != null) separationTask.cancel();
    handler.removeCallbacksAndMessages(null);
    requestVersion++;
    importVersion++;
    releasePlayer();
    stopVoice();
    ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocus(focus);
    super.onDestroy();
  }
}
