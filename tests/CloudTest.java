package com.shandian.sing;

import java.io.*;
import java.net.URL;
import java.util.*;

public final class CloudTest {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) throws Exception {
    List<String> names = Arrays.asList("大梦归 - 周深.lrc", "月亮桥 - 周深.lrc", "大梦归(Live) - 周深.lrc");
    check(CloudNames.matches("大梦归_伴奏.ogg", names).equals(Arrays.asList(0)));
    check(CloudNames.matches("月亮桥_伴奏.wav", names).equals(Arrays.asList(1)));
    check(CloudNames.matches("大梦归(Live)_伴奏.MP3", names).equals(Arrays.asList(2)));
    check(CloudNames.matches("大梦归(合唱版)_伴奏.wav", names).isEmpty());
    check(CloudNames.matches("大梦归_伴奏.ogg", Arrays.asList("大梦归 - 周深.lrc", "大梦归 - 另一位.lrc")).size() == 2);
    check(CloudNames.matches("大梦归_伴奏.ogg", Arrays.asList("大梦归 - 周深.lrc", "大梦归.lrc")).equals(Arrays.asList(1)));
    check(CloudNames.matches("A - B_伴奏.ogg", Arrays.asList("A - B - Singer.lrc")).equals(Arrays.asList(0)));
    check(CloudNames.audio("音乐.AAC") && !CloudNames.audio("恶意.txt"));
    check(BaiduDrive.form("dir", "/大梦归 a&b", "fsids", "[123]").equals("dir=%2F%E5%A4%A7%E6%A2%A6%E5%BD%92+a%26b&fsids=%5B123%5D"));
    check(BaiduDrive.trusted(new URL("https://d.pcs.baidu.com/file")));
    check(BaiduDrive.trusted(new URL("https://example.baidupcs.com/file")));
    check(!BaiduDrive.trusted(new URL("http://d.pcs.baidu.com/file")));
    check(!BaiduDrive.trusted(new URL("https://baidu.com.example.org/file")));
    check(!BaiduDrive.trusted(new URL("https://user@pan.baidu.com/file")));
    check(Arrays.equals(BaiduDrive.read(new ByteArrayInputStream(new byte[]{1,2}), 2), new byte[]{1,2}));
    try { BaiduDrive.read(new ByteArrayInputStream(new byte[]{1,2,3}),2); throw new AssertionError(); }
    catch (IOException expected) {}
    System.out.println("Cloud pairing, ambiguity, version preservation, URL encoding, trusted downloads and size limits passed");
  }
}
