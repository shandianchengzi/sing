package com.shandian.sing;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.io.File;

/** User-started foreground processing survives leaving the recording screen. */
public final class LocalSeparationService extends Service {
  static final String CANCEL = "com.shandian.sing.CANCEL_LOCAL_SEPARATION";
  static final int NOTIFICATION = 421;
  static final class State {
    final long id;
    final String title;
    volatile boolean running = true, cancelled;
    volatile String text = "准备本地分离…", error;
    volatile File result;
    State(String title) { this(title, System.currentTimeMillis()); }
    State(String title, long id) { this.title=title; this.id=id; }
  }
  static volatile State state;
  LocalSeparation.Task task;
  PowerManager.WakeLock wake;
  long lastNotification;

  static boolean running() { State s=state; return s!=null && s.running; }
  static State restored(Context context) {
    if (state!=null) return state;
    android.content.SharedPreferences saved=context.getSharedPreferences("session",MODE_PRIVATE);
    long id=saved.getLong("local_result_id",0);
    if(id==0) return null;
    State result=new State(saved.getString("local_result_title","歌曲"),id);
    result.running=false;
    result.cancelled=saved.getBoolean("local_result_cancelled",false);
    String path=saved.getString("local_result_file","");
    if(!path.isEmpty()) result.result=new File(path);
    String error=saved.getString("local_result_error","");
    if(!error.isEmpty()) result.error=error;
    return result;
  }
  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("separation", "本地伴奏提取", NotificationManager.IMPORTANCE_LOW));
  }
  Notification notification(String text, boolean ongoing) {
    PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder builder = new Notification.Builder(this,"separation")
      .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("轻唱 · 本地伴奏提取")
      .setContentText(text.replace('\n',' ')).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true);
    if (ongoing) {
      PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,LocalSeparationService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
      builder.addAction(new Notification.Action.Builder(null,"取消",cancel).build());
    }
    return builder.build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId) {
    if (intent==null) { stopSelf(); return START_NOT_STICKY; }
    if (CANCEL.equals(intent.getAction())) { if(task!=null) task.cancel(); else stopSelf(); return START_NOT_STICKY; }
    if (task!=null) return START_NOT_STICKY;
    String source=intent.getStringExtra("source"), title=intent.getStringExtra("title");
    if (source==null) { stopSelf(); return START_NOT_STICKY; }
    State job=new State(title==null?"歌曲":title); state=job;
    Notification notice=notification(job.text,true);
    if(Build.VERSION.SDK_INT>=35) startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    else if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(NOTIFICATION,notice);
    task=new LocalSeparation.Task();
    wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"sing:separation");
    wake.acquire(6*60*60*1000L);
    task.thread=new Thread(()->{
      try {
        job.result=LocalSeparation.separate(getApplicationContext(),Uri.parse(source),job.title,task,text->{
          job.text=text;
          long now=SystemClock.elapsedRealtime();
          if(now-lastNotification>1000) {
            lastNotification=now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text,true));
          }
        });
        job.text="伴奏提取完成，点击开始录歌";
      } catch (Exception|LinkageError e) {
        job.cancelled=task.cancelled;
        job.error=task.cancelled?"已取消本地分离":(e.getMessage()==null?e.toString():e.getMessage());
        job.text=job.error;
      } catch (OutOfMemoryError e) {
        job.error="本地分离内存不足，请关闭其他大型应用后重试"; job.text=job.error;
      } finally {
        getSharedPreferences("session",MODE_PRIVATE).edit()
          .putLong("local_result_id",job.id).putString("local_result_title",job.title)
          .putString("local_result_file",job.result==null?"":job.result.getAbsolutePath())
          .putString("local_result_error",job.error==null?"":job.error)
          .putBoolean("local_result_cancelled",job.cancelled).commit();
        job.running=false;
        if(wake!=null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if(!job.cancelled) getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(job.text,false));
        stopSelf();
      }
    },"spleeter-local");
    task.thread.start(); return START_NOT_STICKY;
  }
  @Override public void onTimeout(int startId,int fgsType) { if(task!=null) task.cancel(); stopSelf(); }
  @Override public void onDestroy() {
    if(task!=null && running()) task.cancel();
    if(wake!=null && wake.isHeld()) wake.release();
    super.onDestroy();
  }
}
