package com.shandian.sing;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class WebDavLibraryTest {
  public static void main(String[] args) throws Exception {
    String xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
        + response("/dav/music/", "music")
        + response("/dav/music/%E5%A4%A7%20%E6%A2%A6%E5%BD%92-%E4%BC%B4%E5%A5%8F.mp3", "大 梦归-伴奏.mp3")
        + response("/dav/music/%E5%A4%A7%20%E6%A2%A6%E5%BD%92.lrc", "大 梦归.lrc")
        + response("/dav/music/sub/file.mp3", "file.mp3") + "</d:multistatus>";
    List<String[]> rows = WebDavLibrary.parseListing(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "https://example.com/dav/music/");
    if (rows.size() != 2) throw new AssertionError("expected direct files only: " + rows.size());
    if (!"大 梦归-伴奏.mp3".equals(rows.get(0)[0])) throw new AssertionError(rows.get(0)[0]);
    List<WebDavLibrary.Entry> unused = java.util.Collections.emptyList();
    new WebDavLibrary.Config("https://example.com/dav/music", "user", "pass");
    try { new WebDavLibrary.Config("ftp://example.com/music", "", ""); throw new AssertionError("accepted ftp"); }
    catch (IllegalArgumentException expected) {}
    System.out.println("WebDavLibraryTest OK");
  }

  private static String response(String href, String name) {
    return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop><d:displayname>"
        + name + "</d:displayname></d:prop></d:propstat></d:response>";
  }
}
