package com.shandian.sing;
final class LibraryNamesTest {
  static void same(String a,String b) { if(!a.equals(b)) throw new AssertionError(a+" != "+b); }
  public static void main(String[] args) {
    same(LibraryNames.key("大梦归-伴奏.ogg"),LibraryNames.key("大梦归.lrc"));
    same(LibraryNames.audioName("月亮桥","source.WAV"),"月亮桥-伴奏.wav");
    same(LibraryNames.key("Song-伴奏.mp3"),LibraryNames.key("song.lrc"));
    for(String name:new String[]{"大梦归_伴奏.ogg","Song (Instrumental).m4a","歌名.mp3","-伴奏.wav"," 歌名-伴奏.ogg"})
      same(LibraryNames.key(name),"");
    if(LibraryNames.key("大梦归 - 周深.lrc").equals(LibraryNames.key("大梦归-伴奏.ogg"))) throw new AssertionError("no fuzzy artist matching");
    if(LibraryNames.key("A B-伴奏.ogg").equals(LibraryNames.key("AB.lrc"))) throw new AssertionError("preserve spaces");
    for(String title:new String[]{"","../x","a/b","a\\b"," a","a\n","a:b"})
      if(LibraryNames.validTitle(title)) throw new AssertionError("unsafe title: "+title);
    if(!LibraryNames.validTitle("大梦归")||!LibraryNames.isAudio("test.aac")) throw new AssertionError();
    java.util.Set<String> paired=new java.util.HashSet<>();
    paired.add(LibraryNames.key("大梦归-伴奏.ogg"));
    if(LibraryNames.needsLyricsOrganization("大梦归.lrc",paired)) throw new AssertionError("paired lyrics excluded");
    for(String file:new String[]{"大梦归 - 周深.lrc","大梦归_伴奏.lrc","大梦归.LRC","月亮桥.lrc"})
      if(!LibraryNames.needsLyricsOrganization(file,paired)) throw new AssertionError("needs organization: "+file);
    if(LibraryNames.needsLyricsOrganization("song.txt",paired)) throw new AssertionError("only LRC files");
    System.out.println("LibraryNamesTest passed");
  }
}
