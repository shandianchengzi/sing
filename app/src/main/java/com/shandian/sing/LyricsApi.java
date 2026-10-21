package com.shandian.sing;
import java.net.*;import java.io.*;import java.math.BigInteger;import java.nio.charset.StandardCharsets;import java.security.SecureRandom;import javax.crypto.*;import javax.crypto.spec.*;import android.util.Base64;import org.json.*;
// Adapted from jitwxs/163MusicLyrics NetEaseMusicNativeApi (Apache-2.0). See NOTICE.
final class LyricsApi {
 static String aes(String s,String key)throws Exception{Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"AES"),new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));return Base64.encodeToString(c.doFinal(s.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
 static String fetch(String id)throws Exception{
  String chars="0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";SecureRandom random=new SecureRandom();StringBuilder key=new StringBuilder();for(int i=0;i<16;i++)key.append(chars.charAt(random.nextInt(chars.length())));
  String modulus="00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
  String rsa=new BigInteger(1,new StringBuilder(key).reverse().toString().getBytes(StandardCharsets.UTF_8)).modPow(new BigInteger("010001",16),new BigInteger(modulus,16)).toString(16);while(rsa.length()<256)rsa="0"+rsa;
  JSONObject data=new JSONObject();data.put("id",id);data.put("os","pc");data.put("lv","-1");data.put("tv","-1");data.put("csrf_token","");
  String body="params="+URLEncoder.encode(aes(aes(data.toString(),"0CoJUm6Qyw8W8jud"),key.toString()),"UTF-8")+"&encSecKey="+rsa;
  HttpURLConnection c=(HttpURLConnection)new URL("https://music.163.com/weapi/song/lyric?csrf_token=").openConnection();c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Referer","https://music.163.com/");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
  try{try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}if(c.getResponseCode()!=200)throw new IOException("接口 HTTP "+c.getResponseCode());StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)s.append(l);}JSONObject obj=new JSONObject(s.toString());JSONObject lrc=obj.optJSONObject("lrc");String text=lrc==null?"":lrc.optString("lyric");if(text.isEmpty())throw new IOException("该曲无可用歌词，或接口需要登录；请导入本地 LRC");return text;}finally{c.disconnect();}
 }
}
