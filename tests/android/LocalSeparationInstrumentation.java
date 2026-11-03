package com.shandian.sing.smoke;

import android.app.*;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;

/** Separate signed test APK: production APK has no hidden smoke-test entry point. */
public final class LocalSeparationInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report=new Bundle();
    try {
      Context target=getTargetContext(); ClassLoader loader=target.getClassLoader();
      Class<?> separator=Class.forName("com.shandian.sing.LocalSeparation",true,loader);
      Class<?> taskClass=Class.forName("com.shandian.sing.LocalSeparation$Task",true,loader);
      Class<?> progressClass=Class.forName("com.shandian.sing.SeparationClient$Progress",true,loader);
      Constructor<?> constructor=taskClass.getDeclaredConstructor(); constructor.setAccessible(true);
      Method separate=separator.getDeclaredMethod("separate",Context.class,Uri.class,String.class,taskClass,progressClass);
      separate.setAccessible(true);
      Object progress=Proxy.newProxyInstance(loader,new Class<?>[]{progressClass},(p,m,a)->null);
      int passed=0;
      for (String format:new String[]{"wav","mp3","aac","m4a"}) {
        File input=new File(target.getCacheDir(),"手机原曲 测试."+format);
        try(InputStream in=getContext().getAssets().open("input."+format);OutputStream out=new FileOutputStream(input)) {
          byte[] bytes=new byte[65536]; int n; while((n=in.read(bytes))!=-1) out.write(bytes,0,n);
        }
        File result=(File)separate.invoke(null,target,Uri.fromFile(input),"手机测试",constructor.newInstance(),progress);
        try(RandomAccessFile wave=new RandomAccessFile(result,"r")) {
          if(wave.readInt()!=0x52494646) throw new AssertionError("Missing RIFF: "+format);
          wave.seek(8); if(wave.readInt()!=0x57415645) throw new AssertionError("Missing WAVE");
          long samples=(wave.length()-44)/4;
          if(Math.abs(samples-88200)>5000) throw new AssertionError("Wrong duration: "+format+" "+samples);
          if(format.equals("wav")) {
            byte[] expected;
            try(InputStream in=getContext().getAssets().open("expected.pcm");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
              byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);expected=out.toByteArray();
            }
            if(wave.length()!=expected.length+44) throw new AssertionError("Changed sample count");
            wave.seek(44); int maximum=0; double squared=0;
            for(int i=0;i<expected.length;i+=2) {
              int actual=(short)(wave.readUnsignedByte()|wave.readUnsignedByte()<<8);
              int reference=(short)((expected[i]&255)|(expected[i+1]&255)<<8);
              int difference=Math.abs(actual-reference); maximum=Math.max(maximum,difference); squared+=(double)difference*difference;
            }
            double rmse=Math.sqrt(squared/(expected.length/2));
            // Allows Android decoder and ARM/x86 FP32 convolution accumulation differences.
            if(maximum>16 || rmse>2) throw new AssertionError("Original-model mismatch max="+maximum+" rmse="+rmse);
            report.putString("waveform","max PCM difference="+maximum+", RMSE="+rmse);
          }
        } finally { result.delete(); result.getParentFile().delete(); input.delete(); }
        passed++;
      }
      Object cancelled=constructor.newInstance();
      Method cancel=taskClass.getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(cancelled);
      try { separate.invoke(null,target,Uri.parse("file:///does-not-exist"),"取消测试",cancelled,progress); throw new AssertionError("Cancellation ignored"); }
      catch(InvocationTargetException expected) { if(!(expected.getCause() instanceof InterruptedIOException)) throw expected; }
      Activity screen=startActivitySync(new android.content.Intent().setClassName(target,"com.shandian.sing.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      File foregroundInput=new File(target.getCacheDir(),"后台测试.wav");
      try(InputStream in=getContext().getAssets().open("input.wav");OutputStream out=new FileOutputStream(foregroundInput)) {
        byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
      }
      Class<?> service=Class.forName("com.shandian.sing.LocalSeparationService",true,loader);
      Field state=service.getDeclaredField("state");state.setAccessible(true);
      target.startForegroundService(new android.content.Intent().setClassName(target,service.getName())
        .putExtra("source",Uri.fromFile(foregroundInput).toString()).putExtra("title","后台测试"));
      long deadline=System.currentTimeMillis()+10000;
      while(state.get(null)==null && System.currentTimeMillis()<deadline) Thread.sleep(20);
      Object job=state.get(null);
      if(job==null)throw new AssertionError("Foreground service did not start");
      // Leaving the activity must not destroy the processing service.
      runOnMainSync(()->screen.moveTaskToBack(true));
      target.startService(new android.content.Intent().setClassName(target,service.getName()).setAction("com.shandian.sing.CANCEL_LOCAL_SEPARATION"));
      Field running=job.getClass().getDeclaredField("running");running.setAccessible(true);
      Field wasCancelled=job.getClass().getDeclaredField("cancelled");wasCancelled.setAccessible(true);
      deadline=System.currentTimeMillis()+30000;
      while(running.getBoolean(job) && System.currentTimeMillis()<deadline) Thread.sleep(50);
      if(running.getBoolean(job)||!wasCancelled.getBoolean(job))throw new AssertionError("Background foreground-service cancellation failed");
      foregroundInput.delete();

      // Reopen the UI (Spleeter smoke moved it to the background) and verify
      // that external capture genuinely survives losing the visible Activity.
      Activity again=startActivitySync(new android.content.Intent()
          .setClassName(target,"com.shandian.sing.MainActivity")
          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
              | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
      waitForIdleSync();
      if (target.checkSelfPermission("android.permission.RECORD_AUDIO")
          != android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new AssertionError("RECORD_AUDIO must be granted to run the background smoke test");
      Class<?> external=Class.forName("com.shandian.sing.ExternalRecordingService",true,loader);
      Method active=external.getDeclaredMethod("isRecording"); active.setAccessible(true);
      Method muted=external.getDeclaredMethod("isSilenced"); muted.setAccessible(true);
      Field buttonField=again.getClass().getDeclaredField("externalRecord");
      buttonField.setAccessible(true);
      android.widget.Button startButton=(android.widget.Button)buttonField.get(again);
      // Wait for its pre-bound local service to become ready.
      long readyUntil=System.currentTimeMillis()+8000;
      while (!startButton.isEnabled() && System.currentTimeMillis()<readyUntil)
        Thread.sleep(50);
      if (!startButton.isEnabled()) throw new AssertionError("Microphone service binding never became ready");
      runOnMainSync(()->startButton.performClick());
      long recordingUntil=System.currentTimeMillis()+10000;
      while (!((Boolean)active.invoke(null)).booleanValue()
          && System.currentTimeMillis()<recordingUntil) Thread.sleep(50);
      if (!((Boolean)active.invoke(null)).booleanValue())
        throw new AssertionError("External recorder failed to start: "
            + target.getSharedPreferences("session",Context.MODE_PRIVATE)
                .getString("external_capture_diagnostic","unknown"));
      Thread.sleep(1300);
      runOnMainSync(()->again.moveTaskToBack(true));
      Thread.sleep(2600);
      if (!((Boolean)active.invoke(null)).booleanValue())
        throw new AssertionError("External recorder stopped when Activity entered background");
      if (((Boolean)muted.invoke(null)).booleanValue())
        throw new AssertionError("Android marked background microphone capture as silenced");

      target.startService(new android.content.Intent()
          .setClassName(target,external.getName())
          .setAction("com.shandian.sing.EXTERNAL_STOP"));
      long savedUntil=System.currentTimeMillis()+10000;
      while (((Boolean)active.invoke(null)).booleanValue()
          && System.currentTimeMillis()<savedUntil) Thread.sleep(50);
      if (((Boolean)active.invoke(null)).booleanValue())
        throw new AssertionError("External recorder stop action did not finish");
      String savedPath=target.getSharedPreferences("session",Context.MODE_PRIVATE)
          .getString("external_result_file","");
      if (savedPath.isEmpty()) throw new AssertionError("External recording not saved: "
          + target.getSharedPreferences("session",Context.MODE_PRIVATE)
              .getString("external_capture_diagnostic","unknown"));
      File recordingFile=new File(savedPath);
      if (!recordingFile.isFile() || recordingFile.length()<500)
        throw new AssertionError("External recording contains no valid media");
      android.media.MediaMetadataRetriever info=new android.media.MediaMetadataRetriever();
      try {
        info.setDataSource(savedPath);
        String duration=info.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration==null || Long.parseLong(duration)<2000)
          throw new AssertionError("External take duration too short: "+duration);
      } finally {
        info.release();
        recordingFile.delete();
        new File(savedPath+".json").delete();
      }
      report.putInt("passed",passed+5); report.putString("stream",
          "Spleeter + background external microphone smoke passed (WAV/MP3/AAC/M4A; capture persisted after Activity backgrounding)\n");
      finish(Activity.RESULT_OK,report);
    } catch(Throwable e) { report.putString("stream",android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED,report); }
  }
}
