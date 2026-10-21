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
  LinearLayout root;
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
    updater =
        new AppUpdater(
            this, () -> !recording && !starting && !previewSeeking && !separating && !exporting);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    root = new LinearLayout(this);
    root.setOrientation(1);
    root.setPadding(24, 16, 24, 16);
    root.setBackgroundColor(0xfff5f6f2);
    ScrollView page = new ScrollView(this);
    page.setFillViewport(true);
    page.addView(root);
    setContentView(page);
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          v.setPadding(
              24, 16 + in.getSystemWindowInsetTop(), 24, 16 + in.getSystemWindowInsetBottom());
          return in;
        });
    text("轻唱", 30);
    text("导入伴奏 · 看词录音", 14);
    song = text("尚未选择伴奏", 15);
    button("选择伴奏（含 OGG / MP4）", () -> pick(1, "*/*"));
    button(
        "上传歌曲去人声",
        () -> {
          if (preferences.getString("server", "").isEmpty()) {
            separationSettings();
            return;
          }
          pick(4, "*/*");
        });
    button("去人声服务设置", () -> separationSettings());
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
              if (!ready || recording || starting || previewSeeking || exporting || separating)
                return;
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
    status = text("戴耳机录制；从伴奏当前位置开始，可同步回听和导出混音。", 13);
    take = latest();
    controls();
    restoreSession();
    handler.post(tick);
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
    int at = replay != null && replay.ready ? replay.position() : player.getCurrentPosition();
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
    boolean busy = recording || starting || previewSeeking || separating || exporting;
    record.setText(recording ? "停止并保存录音" : "从当前位置开始录音");
    record.setEnabled(ready && !starting && !previewSeeking && !separating && !exporting);
    preview.setEnabled(ready && !busy);
    seek.setEnabled((ready || (replay != null && replay.ready)) && !busy);
    listen.setEnabled(!busy && take != null);
    listen.setText(
        replay != null && !replay.ready
            ? "取消准备回听"
            : replay != null && replay.playing ? "暂停回听" : "回听最近录音 / 继续");
    export.setEnabled(!busy && take != null);
  }

  void pick(int code, String type) {
    if (starting || previewSeeking || exporting) {
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
    if (code == 1 || code == 4)
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
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    try {
      if (code == 1) {
        try {
          getContentResolver()
              .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
          originalAudio = uri.toString();
        } catch (SecurityException e) {
          originalAudio = "";
        }
        preferences.edit().putString("audio", originalAudio).apply();
        importAudio(uri, name(uri));
      } else if (code == 2) {
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
        setLrc(s);
      } else if (code == 4) {
        startSeparation(uri, name(uri));
      } else if (code == 3 && pending != null) {
        exportTo(uri);
      }
    } catch (Exception e) {
      error(e);
    }
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
    stopVoice();
    releasePlayer();
    final int version = ++importVersion;
    controls();
    String mime = getContentResolver().getType(uri);
    boolean mp4 =
        "video/mp4".equals(mime)
            || "application/mp4".equals(mime)
            || title.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
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
    if (!originalAudio.isEmpty())
      try {
        Uri uri = Uri.parse(originalAudio);
        importAudio(uri, name(uri));
      } catch (Exception e) {
        message("原伴奏已移动或权限失效，请重新选择；录音仍然保留。");
      }
  }

  void openAudio(Uri uri, String title) {
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
            p.setVolume(backingGain, backingGain);
            seek.setMax(p.getDuration());
            song.setText(title);
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
            message("音轨无法播放，请换用系统支持的 MP3/M4A/WAV/OGG（Vorbis、Opus）或 AAC 音轨 MP4");
            controls();
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      releasePlayer();
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
    if (!ready || starting || previewSeeking || separating || exporting) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 9);
      return;
    }
    replayPosition(
        position -> {
          player.pause();
          if (position >= player.getDuration() - 100) {
            message("已到伴奏结尾，请拖动到想录制的位置");
            return;
          }
          starting = true;
          controls();
          movePreview(
              position,
              () -> {
                if (starting) startRecorder();
              });
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
    File f =
        new File(
            getFilesDir(),
            "轻唱_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.ROOT)
                    .format(new java.util.Date())
                + ".m4a");
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
    if (recording || starting || separating || exporting) return;
    File[] files = getFilesDir().listFiles((d, n) -> n.endsWith(".m4a"));
    if (files == null || files.length == 0) {
      message("暂无录音");
      return;
    }
    java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    String[] names = new String[files.length];
    for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
    new AlertDialog.Builder(this)
        .setTitle("选择录音后可回听或导出")
        .setItems(
            names,
            (d, i) -> {
              stopVoice();
              take = files[i];
              message("已选择：" + take.getName());
              controls();
            })
        .setNegativeButton("关闭", null)
        .show();
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
