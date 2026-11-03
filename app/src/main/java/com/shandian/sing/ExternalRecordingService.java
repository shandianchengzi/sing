package com.shandian.sing;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.*;
import java.io.File;
import java.util.Date;

/** Captures microphone only, without requesting audio focus or controlling another music app. */
public final class ExternalRecordingService extends Service {
  static final String START = "com.shandian.sing.EXTERNAL_START";
  static final String STOP = "com.shandian.sing.EXTERNAL_STOP";
  private static final int NOTICE = 422;
  private static volatile boolean active;
  private MediaRecorder recorder;
  private File output;
  private PowerManager.WakeLock wake;

  static boolean isRecording() { return active; }

  @Override public IBinder onBind(Intent intent) { return null; }

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
    return new Notification.Builder(this, "external-recording")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("轻唱 · 正在录制外部伴奏人声")
        .setContentText("其他 App 的音乐继续播放；点击查看录音")
        .setContentIntent(open)
        .setWhen(System.currentTimeMillis()).setUsesChronometer(true)
        .setOngoing(true).setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "停止并保存", stop).build())
        .build();
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      finishRecording();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!START.equals(intent.getAction()) || recorder != null) return START_NOT_STICKY;
    try {
      // The user starts this from a visible Activity: required by Android 14+ for microphone FGS.
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(NOTICE, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      else startForeground(NOTICE, notice());
      output = new File(getFilesDir(), RecordingNames.fileName("外部录音", new Date()));
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(44100);
      recorder.setAudioEncodingBitRate(192000);
      recorder.setAudioChannels(1);
      recorder.setOutputFile(output.getAbsolutePath());
      recorder.setOnErrorListener((r, what, extra) ->
          new Handler(Looper.getMainLooper()).post(() -> {
            finishRecording();
            stopSelf();
          }));
      recorder.prepare();
      new TakeInfo(0, "", false).save(output);
      recorder.start();
      wake = ((PowerManager) getSystemService(POWER_SERVICE))
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sing:external-recording");
      wake.acquire(6 * 60 * 60 * 1000L);
      active = true;
      getSharedPreferences("session", MODE_PRIVATE).edit()
          .putString("external_recording_error", "").apply();
    } catch (Exception | LinkageError e) {
      failStart(e.getMessage() == null ? e.toString() : e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void releaseWake() {
    if (wake != null) {
      if (wake.isHeld()) wake.release();
      wake = null;
    }
  }

  private void failStart(String error) {
    active = false;
    if (recorder != null) {
      try { recorder.reset(); } catch (RuntimeException ignored) {}
      recorder.release();
      recorder = null;
    }
    if (output != null) {
      output.delete();
      new File(output.getPath() + ".json").delete();
      output = null;
    }
    releaseWake();
    getSharedPreferences("session", MODE_PRIVATE).edit()
        .putString("external_result_file", "")
        .putString("external_recording_error", "外部录音启动失败：" + error)
        .putLong("external_result_seq", System.currentTimeMillis()).commit();
    stopForeground(STOP_FOREGROUND_REMOVE);
  }

  private void finishRecording() {
    if (recorder == null) return;
    MediaRecorder current = recorder;
    recorder = null;
    boolean saved = false;
    try {
      current.stop();
      saved = output != null && output.isFile() && output.length() > 0;
    } catch (RuntimeException ignored) {
      // Invalid or too-short recordings must not be shown as saved.
    } finally {
      current.release();
      active = false;
      releaseWake();
      String path = saved ? output.getAbsolutePath() : "";
      if (!saved && output != null) {
        output.delete();
        new File(output.getPath() + ".json").delete();
      }
      getSharedPreferences("session", MODE_PRIVATE).edit()
          .putString("external_result_file", path)
          .putString("external_recording_error",
              saved ? "" : "录音过短或中断，本次录音未保存")
          .putLong("external_result_seq", System.currentTimeMillis()).commit();
      output = null;
      stopForeground(STOP_FOREGROUND_REMOVE);
    }
  }

  @Override public void onDestroy() {
    finishRecording();
    super.onDestroy();
  }
}
