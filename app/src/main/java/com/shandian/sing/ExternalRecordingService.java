package com.shandian.sing;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * User-initiated microphone-only foreground recorder.
 * A bound Activity starts capture synchronously while visible, preventing an
 * Android 14+ while-in-use permission race when the user switches apps quickly.
 */
public final class ExternalRecordingService extends Service {
  static final String START = "com.shandian.sing.EXTERNAL_START";
  static final String STOP = "com.shandian.sing.EXTERNAL_STOP";
  private static final int NOTICE = 422;
  private static final String TAG = "SingExternalMic";
  private static volatile boolean active;
  private static volatile boolean silenced;
  private static volatile int peakAmplitude;
  private static volatile String diagnostic = "录音服务尚未启动";
  private final IBinder binder = new LocalBinder();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private MediaRecorder recorder;
  private AudioManager.AudioRecordingCallback recordingCallback;
  private File output;
  private PowerManager.WakeLock wake;
  private boolean attempted, stopping;
  private long startedAt, lastSilenceWarning;
  private int zeroCount;

  public final class LocalBinder extends Binder {
    ExternalRecordingService service() { return ExternalRecordingService.this; }
  }

  static boolean isRecording() { return active; }
  static boolean isSilenced() { return active && silenced; }
  static int amplitude() { return peakAmplitude; }
  static String diagnostic() { return diagnostic; }

  @Override public IBinder onBind(Intent intent) { return binder; }

  @Override public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class).createNotificationChannel(
        new NotificationChannel("external-recording", "外部伴奏录音",
            NotificationManager.IMPORTANCE_LOW));
  }

  private Notification notice() {
    PendingIntent stop = PendingIntent.getService(this, 2,
        new Intent(this, ExternalRecordingService.class).setAction(STOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent open = PendingIntent.getActivity(this, 2,
        new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text = silenced
        ? "系统已将麦克风静音；检查其他 App 的录音占用和后台权限"
        : "其他 App 的音乐继续播放；通知栏可停止并保存";
    return new Notification.Builder(this, "external-recording")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(silenced ? "轻唱 · 麦克风被系统静音" : "轻唱 · 外部伴奏录音中")
        .setContentText(text).setContentIntent(open)
        .setWhen(startedAt > 0 ? startedAt : System.currentTimeMillis())
        .setUsesChronometer(true).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "停止并保存", stop).build())
        .build();
  }

  private void showStatus(String value) {
    diagnostic = value;
    Log.i(TAG, value);
    getSharedPreferences("session", MODE_PRIVATE).edit()
        .putString("external_capture_diagnostic", value).apply();
  }

  /**
   * Must be invoked from the bound, visible Activity before switching apps.
   * No audio focus is acquired and no playback is started.
   */
  boolean startNow() {
    if (active) return true;
    attempted = true;
    stopping = false;
    try {
      startedAt = System.currentTimeMillis();
      silenced = false;
      peakAmplitude = 0;
      zeroCount = 0;
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(NOTICE, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      else startForeground(NOTICE, notice());

      output = new File(getFilesDir(), RecordingNames.fileName("外部录音", new Date()));
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      // Privacy-sensitive capture receives priority over non-sensitive recorders.
      // This does not request audio output focus or interrupt another app's music.
      if (Build.VERSION.SDK_INT >= 30) recorder.setPrivacySensitive(true);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(44100);
      recorder.setAudioEncodingBitRate(192000);
      recorder.setAudioChannels(1);
      recorder.setOutputFile(output.getAbsolutePath());
      recorder.setOnErrorListener((r, what, extra) ->
          handler.post(() -> {
            if (r != recorder) return;
            showStatus("录音器报错：" + what + "/" + extra);
            finishRecording(false);
            stopSelf();
          }));
      if (Build.VERSION.SDK_INT >= 29) {
        recordingCallback = new AudioManager.AudioRecordingCallback() {
          @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            inspectInput();
          }
        };
        recorder.registerAudioRecordingCallback(getMainExecutor(), recordingCallback);
      }
      recorder.prepare();
      new TakeInfo(0, "", false).save(output);
      recorder.start();
      wake = ((PowerManager) getSystemService(POWER_SERVICE))
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sing:external-recording");
      wake.acquire(6 * 60 * 60 * 1000L);
      active = true;
      showStatus("麦克风已启动，录音服务正在前台运行");
      getSharedPreferences("session", MODE_PRIVATE).edit()
          .putString("external_recording_error", "")
          .putBoolean("external_recording_running", true).commit();
      handler.postDelayed(monitor, 1000);
      return true;
    } catch (Exception | LinkageError e) {
      failStart(e.getClass().getSimpleName() + ": " + e.getMessage());
      return false;
    }
  }

  private final Runnable monitor = new Runnable() {
    @Override public void run() {
      if (!active || recorder == null) return;
      inspectInput();
      try {
        peakAmplitude = recorder.getMaxAmplitude();
        zeroCount = peakAmplitude == 0 ? zeroCount + 1 : 0;
        if (!silenced && zeroCount >= 8
            && SystemClock.elapsedRealtime() - lastSilenceWarning > 10000) {
          showStatus("麦克风输入连续为 0：可能环境安静、麦克风被系统限制或设备路由异常");
          lastSilenceWarning = SystemClock.elapsedRealtime();
        }
      } catch (RuntimeException e) {
        showStatus("无法读取麦克风电平：" + e.getMessage());
      }
      handler.postDelayed(this, 1000);
    }
  };

  private void inspectInput() {
    if (Build.VERSION.SDK_INT < 29 || recorder == null || !active) return;
    try {
      AudioRecordingConfiguration config = recorder.getActiveRecordingConfiguration();
      if (config == null) return;
      boolean muted = config.isClientSilenced();
      if (silenced != muted) {
        silenced = muted;
        showStatus(muted ? "系统已静音当前录音流：检查后台活动和其他 App 的麦克风使用"
            : "系统已恢复麦克风采集");
        getSystemService(NotificationManager.class).notify(NOTICE, notice());
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Failed to inspect recording state", e);
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      stopping = true;
      finishRecording(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    // Normal UI flow starts synchronously through the binder. The fallback
    // preserves compatibility when the service was started without binding.
    if (START.equals(intent.getAction()) && !attempted) {
      if (!startNow()) stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void releaseWake() {
    if (wake != null) {
      if (wake.isHeld()) wake.release();
      wake = null;
    }
  }

  private void releaseRecorder() {
    handler.removeCallbacks(monitor);
    if (recorder != null) {
      if (Build.VERSION.SDK_INT >= 29 && recordingCallback != null) {
        try { recorder.unregisterAudioRecordingCallback(recordingCallback); }
        catch (RuntimeException ignored) {}
      }
      recorder.release();
      recorder = null;
    }
    recordingCallback = null;
    releaseWake();
  }

  private void failStart(String error) {
    active = false;
    showStatus("外部录音启动失败：" + error);
    if (recorder != null) {
      try { recorder.reset(); } catch (RuntimeException ignored) {}
    }
    releaseRecorder();
    if (output != null) {
      output.delete();
      new File(output.getPath() + ".json").delete();
      output = null;
    }
    getSharedPreferences("session", MODE_PRIVATE).edit()
        .putBoolean("external_recording_running", false)
        .putString("external_result_file", "")
        .putString("external_recording_error", diagnostic)
        .putLong("external_result_seq", System.currentTimeMillis()).commit();
    stopForeground(STOP_FOREGROUND_REMOVE);
  }

  private void finishRecording(boolean requested) {
    if (recorder == null) return;
    MediaRecorder current = recorder;
    boolean saved = false;
    try {
      current.stop();
      saved = output != null && output.isFile() && output.length() > 0;
    } catch (RuntimeException e) {
      showStatus("录音停止异常：" + e.getMessage());
    } finally {
      releaseRecorder();
      active = false;
      String path = saved ? output.getAbsolutePath() : "";
      if (!saved && output != null) {
        output.delete();
        new File(output.getPath() + ".json").delete();
      }
      String note = !saved ? "录音过短或中断，未保存"
          : !requested ? "录音服务被意外停止；已保存可恢复的内容"
          : silenced ? "录音已保存，但系统曾静音麦克风，音频可能存在静音段"
          : "录音已保存";
      showStatus(note);
      getSharedPreferences("session", MODE_PRIVATE).edit()
          .putBoolean("external_recording_running", false)
          .putString("external_result_file", path)
          .putString("external_recording_error", saved && requested && !silenced ? "" : note)
          .putLong("external_result_seq", System.currentTimeMillis()).commit();
      output = null;
      stopForeground(STOP_FOREGROUND_REMOVE);
    }
  }

  @Override public void onDestroy() {
    finishRecording(stopping);
    super.onDestroy();
  }
}
