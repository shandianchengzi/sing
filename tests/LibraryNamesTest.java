package com.shandian.sing;
final class LibraryNamesTest {
  static void same(String a,String b) { if (!a.equals(b)) throw new AssertionError(a+" != "+b); }
  public static void main(String[] args) {
    same(LibraryNames.titleKey("大梦归 - 周深.lrc"),LibraryNames.key("大梦归_伴奏.ogg"));
    same(LibraryNames.titleKey("月亮桥 - 周深.lrc"),LibraryNames.key("月亮桥_伴奏.wav"));
    same(LibraryNames.key("Song (Instrumental).m4a"),"song");
    if (!LibraryNames.isAudio("test.aac") || LibraryNames.isAudio("test.lrc"))
      throw new AssertionError("extensions");
    System.out.println("LibraryNamesTest passed");
  }
}
