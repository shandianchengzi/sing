package com.shandian.sing;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Adapter for server/app.py API v1, using only Android platform APIs. */
final class SeparationClient {
 interface Progress {void update(String text);}
 static final class Task {
  volatile boolean cancelled;
  volatile HttpURLConnection connection;
  void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
  void check()throws IOException{if(cancelled)throw new IOException("已取消分离");}
 }
 static String address(String input)throws Exception{
  String value=input.trim().replaceAll("/+$", "");
  URL url=new URL(value);
  if(!("http".equals(url.getProtocol())||"https".equals(url.getProtocol()))||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null)throw new IOException("请输入 HTTP(S) 服务地址，例如 http://192.168.1.10:8765");
  return value;
 }
 static HttpURLConnection connect(String url,String token,String method,Task task)throws Exception{
  task.check();HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
  c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);task.connection=c;return c;
 }
 static JSONObject json(HttpURLConnection c)throws Exception{
  int code=c.getResponseCode();StringBuilder body=new StringBuilder();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
  if(stream!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){body.append(line);if(body.length()>65536)throw new IOException("服务器响应异常");}}
  if(code<200||code>=300){String detail="";try{detail=new JSONObject(body.toString()).optString("detail");}catch(Exception ignored){}throw new IOException(code==401?"服务令牌不正确":("服务 HTTP "+code+(detail.isEmpty()?"":"："+detail)));}
  return new JSONObject(body.toString());
 }
 static void health(String base,String token)throws Exception{
  Task task=new Task();HttpURLConnection c=connect(base+"/health",token,"GET",task);
  try{JSONObject response=json(c);if(!"sing-separation".equals(response.optString("service"))||response.optInt("api_version")!=1)throw new IOException("该地址不是兼容的轻唱去人声服务");}finally{c.disconnect();}
 }
 static File separate(Context context,Uri source,String base,String token,Task task,Progress progress)throws Exception{
  File directory=new File(context.getFilesDir(),"accompaniments");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建伴奏目录");
  progress.update("正在检查本地伴奏缓存…");MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update((base+"/demucs-htdemucs-v1").getBytes(StandardCharsets.UTF_8));
  try(InputStream in=context.getContentResolver().openInputStream(source)){if(in==null)throw new IOException("无法读取歌曲");byte[] buffer=new byte[65536];int count;long size=0;while((count=in.read(buffer))!=-1){task.check();size+=count;if(size>100L*1024*1024)throw new IOException("歌曲不能超过 100 MB");hash.update(buffer,0,count);}}
  StringBuilder key=new StringBuilder();for(byte b:hash.digest())key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  File cached=new File(directory,key+".m4a");if(cached.length()>0)return cached;
  String id=null;File temp=null;
  try{
   progress.update("正在上传原曲到你的服务器…");HttpURLConnection upload=connect(base+"/jobs",token,"POST",task);upload.setDoOutput(true);upload.setChunkedStreamingMode(65536);upload.setRequestProperty("Content-Type","application/octet-stream");
   try{try(InputStream in=context.getContentResolver().openInputStream(source);OutputStream out=upload.getOutputStream()){if(in==null)throw new IOException("无法读取歌曲");byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1){task.check();out.write(buffer,0,count);}}id=json(upload).getString("id");if(!id.matches("[a-f0-9]{32}"))throw new IOException("服务返回无效的任务 ID");}finally{upload.disconnect();}
   long deadline=System.nanoTime()+60L*60*1000000000L;
   while(true){task.check();HttpURLConnection poll=connect(base+"/jobs/"+id,token,"GET",task);JSONObject state;try{state=json(poll);}finally{poll.disconnect();}String status=state.optString("status");if("succeeded".equals(status))break;if("failed".equals(status)||"cancelled".equals(status))throw new IOException(state.optString("error","分离任务失败或已取消"));if(!"queued".equals(status)&&!"running".equals(status))throw new IOException("服务返回未知任务状态");progress.update("queued".equals(status)?"服务器正在排队…":"服务器正在去人声；首次使用需要下载模型，请稍候…");if(System.nanoTime()>deadline)throw new IOException("分离等待超时");for(int i=0;i<20;i++){task.check();Thread.sleep(150);}}
   progress.update("正在下载伴奏…");temp=File.createTempFile("download-",".part",directory);HttpURLConnection result=connect(base+"/jobs/"+id+"/result",token,"GET",task);
   try{if(result.getResponseCode()!=200)throw new IOException("伴奏下载失败，HTTP "+result.getResponseCode());try(InputStream in=result.getInputStream();OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];int count;long size=0;while((count=in.read(buffer))!=-1){task.check();size+=count;if(size>100L*1024*1024)throw new IOException("伴奏文件过大");out.write(buffer,0,count);}if(size==0)throw new IOException("伴奏文件为空");}}finally{result.disconnect();}
   task.check();if(!temp.renameTo(cached))throw new IOException("无法保存伴奏");return cached;
  }finally{
   if(temp!=null)temp.delete();
   // Best effort server cleanup, using a separate task so cancellation still deletes jobs.
   if(id!=null&&id.matches("[a-f0-9]{32}"))try{HttpURLConnection c=connect(base+"/jobs/"+id,token,"DELETE",new Task());c.setConnectTimeout(3000);c.setReadTimeout(3000);try{c.getResponseCode();}finally{c.disconnect();}}catch(Exception ignored){}
   task.connection=null;
  }
 }
}
