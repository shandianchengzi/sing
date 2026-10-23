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
      report.putInt("passed",passed+1); report.putString("stream","Local Spleeter smoke passed: WAV/MP3/AAC/M4A, original model numerical comparison, cancellation\n");
      finish(Activity.RESULT_OK,report);
    } catch(Throwable e) { report.putString("stream",android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED,report); }
  }
}
