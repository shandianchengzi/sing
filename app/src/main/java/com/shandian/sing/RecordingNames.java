package com.shandian.sing;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Stable, filesystem-safe names that follow the selected song's display title. */
final class RecordingNames {
  static String fileName(String displayName, Date time) {
    String name = displayName == null ? "" : displayName.trim();
    if (LibraryNames.isAudio(name)) name = LibraryNames.stem(name);
    name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    name = name.replaceAll("[.\\s]+$", "").trim();
    if (name.length() > 80) name = name.substring(0, 80).trim();
    if (name.isEmpty()) name = "伴奏";
    return name + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
        .format(time) + ".m4a";
  }
}
