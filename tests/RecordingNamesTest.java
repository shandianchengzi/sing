package com.shandian.sing;

import java.util.Date;
import java.util.TimeZone;

final class RecordingNamesTest {
  public static void main(String[] args) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    String normal = RecordingNames.fileName("大梦归_伴奏.ogg", new Date(0));
    if (!"大梦归_伴奏_19700101_000000_000.m4a".equals(normal))
      throw new AssertionError(normal);
    String renamed = RecordingNames.fileName("新曲库名", new Date(0));
    if (!renamed.startsWith("新曲库名_") || renamed.contains("轻唱"))
      throw new AssertionError(renamed);
    if (RecordingNames.fileName("A/B:C?.wav", new Date(0)).contains("/"))
      throw new AssertionError("Invalid filename characters");
    System.out.println("RecordingNamesTest passed");
  }
}
