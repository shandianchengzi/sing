package com.shandian.sing;
import java.util.Locale;
final class LibraryNames {
  static String stem(String s) { int dot=s.lastIndexOf('.'); return dot<0?s:s.substring(0,dot); }
  static boolean isAudio(String name) {
    String s=name.toLowerCase(Locale.ROOT);
    for(String ext:new String[]{".mp3",".m4a",".wav",".ogg",".opus",".aac",".flac",".mp4",".3gp"})
      if(s.endsWith(ext)) return true;
    return false;
  }
  static boolean validTitle(String s) {
    if(s==null||s.isEmpty()||!s.equals(s.trim())||s.equals(".")||s.equals("..")) return false;
    for(int i=0;i<s.length();i++) if(Character.isISOControl(s.charAt(i)) || "\\/:*?\"<>|".indexOf(s.charAt(i))>=0) return false;
    return true;
  }
  static String audioTitle(String file) {
    String s=stem(file);
    if(!isAudio(file)||!s.endsWith("-伴奏")) return "";
    s=s.substring(0,s.length()-3);
    return validTitle(s)?s:"";
  }
  static String lyricsTitle(String file) {
    if(!file.endsWith(".lrc")) return "";
    String s=stem(file); return validTitle(s)?s:"";
  }
  static String audioName(String title,String original) {
    if(!validTitle(title)||!isAudio(original)) throw new IllegalArgumentException("请输入有效歌名并选择支持的音频");
    return title+"-伴奏"+original.substring(original.lastIndexOf('.')).toLowerCase(Locale.ROOT);
  }
  static String key(String file) {
    String title=isAudio(file)?audioTitle(file):lyricsTitle(file);
    return title.toLowerCase(Locale.ROOT);
  }
  static String titleKey(String file) { return key(file); }
}
