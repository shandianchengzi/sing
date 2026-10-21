package com.shandian.sing;

import java.text.Normalizer;
import java.util.*;

/** Conservative filename matching: retain version/arrangement suffixes. */
final class CloudNames {
  static String stem(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  static String key(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  static String title(String name) {
    return stem(name).replaceFirst("(?i)(?:[_\\s-]+伴奏)$", "").trim();
  }

  static boolean audio(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|mp4)$");
  }

  static List<Integer> matches(String audio, List<String> lyrics) {
    List<Integer> exact = new ArrayList<>(), songs = new ArrayList<>();
    String wanted = key(title(audio));
    for (int i = 0; i < lyrics.size(); i++) {
      String full = stem(lyrics.get(i));
      if (key(full).equals(wanted)) exact.add(i);
      int artist = full.lastIndexOf(" - ");
      if (artist > 0 && key(full.substring(0, artist)).equals(wanted)) songs.add(i);
    }
    return exact.isEmpty() ? songs : exact;
  }
}
