package com.shandian.sing;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Minimal, dependency-free WebDAV client for read-only remote music libraries. */
final class WebDavLibrary {
  static final class Config {
    final String url, username, password;
    Config(String url, String username, String password) {
      this.url = normalize(url); this.username = username == null ? "" : username;
      this.password = password == null ? "" : password;
    }
    String label() {
      try { URL u = new URL(url); return u.getHost() + URLDecoder.decode(u.getPath(), "UTF-8"); }
      catch (Exception e) { return url; }
    }
  }

  static final class Entry {
    final Config config;
    final String url, name, lyricsUrl;
    Entry(Config config, String url, String name, String lyricsUrl) {
      this.config = config; this.url = url; this.name = name; this.lyricsUrl = lyricsUrl;
    }
  }

  private static String normalize(String value) {
    value = value == null ? "" : value.trim();
    if (!(value.startsWith("https://") || value.startsWith("http://")))
      throw new IllegalArgumentException("WebDAV 地址必须以 http:// 或 https:// 开头");
    return value.endsWith("/") ? value : value + "/";
  }

  static List<Entry> scan(Config config) throws Exception {
    HttpURLConnection connection = open(config, config.url, "PROPFIND");
    connection.setRequestProperty("Depth", "1");
    connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
    connection.setDoOutput(true);
    byte[] request = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/>"
        + "</d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);
    try (OutputStream out = connection.getOutputStream()) { out.write(request); }
    int status = connection.getResponseCode();
    if (status != 207 && status != 200) throw httpError(connection, "无法读取 WebDAV 曲库");
    List<String[]> files = parseListing(connection.getInputStream(), config.url);
    Map<String, String> lyrics = new HashMap<>();
    for (String[] file : files)
      if (file[0].toLowerCase(Locale.ROOT).endsWith(".lrc"))
        lyrics.put(file[0].substring(0, file[0].length() - 4).toLowerCase(Locale.ROOT), file[1]);
    List<Entry> result = new ArrayList<>();
    for (String[] file : files) {
      String title = LibraryNames.audioTitle(file[0]);
      if (!title.isEmpty()) result.add(new Entry(config, file[1], file[0], lyrics.get(title.toLowerCase(Locale.ROOT))));
    }
    return result;
  }

  static List<String[]> parseListing(InputStream input, String baseUrl) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document document = factory.newDocumentBuilder().parse(input);
    NodeList responses = document.getElementsByTagNameNS("DAV:", "response");
    List<String[]> result = new ArrayList<>();
    URI base = new URI(baseUrl);
    String basePath = base.getPath().endsWith("/") ? base.getPath() : base.getPath() + "/";
    for (int i = 0; i < responses.getLength(); i++) {
      Element response = (Element) responses.item(i);
      String href = text(response, "href");
      if (href.isEmpty()) continue;
      URI resolved = base.resolve(href);
      if (!sameOrigin(base, resolved)) continue;
      String path = resolved.getPath();
      if (path.endsWith("/") || path.equals(basePath)) continue;
      String relative = path.startsWith(basePath) ? path.substring(basePath.length()) : "";
      if (relative.isEmpty() || relative.contains("/")) continue;
      String name = text(response, "displayname");
      if (name.isEmpty()) name = URLDecoder.decode(relative, "UTF-8");
      result.add(new String[] {name, resolved.toASCIIString()});
    }
    result.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
    return result;
  }

  private static boolean sameOrigin(URI first, URI second) {
    int firstPort = first.getPort() >= 0 ? first.getPort() : "https".equalsIgnoreCase(first.getScheme()) ? 443 : 80;
    int secondPort = second.getPort() >= 0 ? second.getPort() : "https".equalsIgnoreCase(second.getScheme()) ? 443 : 80;
    return first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost()) && firstPort == secondPort;
  }

  private static String text(Element parent, String localName) {
    NodeList nodes = parent.getElementsByTagNameNS("DAV:", localName);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
  }

  static File download(Config config, String url, File directory, String name) throws Exception {
    if (url == null || url.isEmpty()) return null;
    directory.mkdirs();
    String suffix = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    File target = new File(directory, sha256(url) + suffix);
    if (target.isFile() && target.length() > 0) return target;
    File temporary = new File(target.getPath() + ".part");
    HttpURLConnection connection = open(config, url, "GET");
    int status = connection.getResponseCode();
    if (status < 200 || status >= 300) throw httpError(connection, "WebDAV 下载失败");
    try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temporary)) {
      byte[] buffer = new byte[64 * 1024]; int read;
      while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
    } catch (Exception e) { temporary.delete(); throw e; }
    if (temporary.length() == 0) { temporary.delete(); throw new IOException("WebDAV 返回了空文件"); }
    if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("无法保存 WebDAV 缓存"); }
    return target;
  }

  private static HttpURLConnection open(Config config, String url, String method) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod(method); connection.setConnectTimeout(12000); connection.setReadTimeout(30000);
    connection.setInstanceFollowRedirects(true); connection.setRequestProperty("User-Agent", "Sing/1.8");
    if (!config.username.isEmpty()) {
      String value = config.username + ":" + config.password;
      connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
    return connection;
  }

  private static IOException httpError(HttpURLConnection connection, String prefix) throws IOException {
    int code = connection.getResponseCode();
    if (code == 401 || code == 403) return new IOException(prefix + "：用户名、密码或权限错误（HTTP " + code + "）");
    return new IOException(prefix + "（HTTP " + code + "）");
  }

  private static String sha256(String value) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }
}
