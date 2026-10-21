package com.shandian.sing;
import java.util.Locale;
final class LibraryNames {
  static String stem(String s) {
    int dot = s.lastIndexOf('.');
    return dot < 0 ? s : s.substring(0, dot);
  }
  static boolean isAudio(String name) {
    String s = name.toLowerCase(Locale.ROOT);
    for (String ext : new String[]{".mp3",".m4a",".wav",".ogg",".opus",".aac",".flac",".mp4",".3gp"})
      if (s.endsWith(ext)) return true;
    return false;
  }
  static String key(String file) {
    String s = stem(file).trim();
    s = s.replaceAll("(?i)[\\s_-]*(?:（伴奏）|\\((?:instrumental|inst|backing|伴奏)\\))$", "");
    s = s.replaceAll("(?i)[\\s_-]*(?:伴奏(?:版)?|instrumental|backing(?: track)?|karaoke|off vocal|inst)$", "");
    return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_·.]+", "");
  }
  static String titleKey(String file) {
    String[] chunks = stem(file).split("\\s+[-–—]\\s+", 2);
    return key(chunks[0]);
  }
}
