package com.shandian.sing;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** SAF user-selected folder; no broad external storage permissions. */
final class FolderLibrary {
  static class Item {
    final Uri audio, lyrics;
    final String name;
    Item(Uri audio, String name, Uri lyrics) {
      this.audio = audio; this.name = name; this.lyrics = lyrics;
    }
  }
  static class Imported {
    final Uri audio;
    final String warning;
    Imported(Uri audio, String warning) { this.audio = audio; this.warning = warning; }
  }
  private static class Entry {
    final Uri uri;
    final String name;
    Entry(Uri uri, String name) { this.uri = uri; this.name = name; }
  }
  static ArrayList<Item> scan(Context ctx, Uri tree) throws IOException {
    ArrayList<Entry> audio = new ArrayList<>(), lyrics = new ArrayList<>();
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree));
    try (Cursor c = ctx.getContentResolver().query(children, new String[]{
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
      if (c == null) throw new IOException("无法访问曲库文件夹");
      while (c.moveToNext()) {
        String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
        if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
        Entry e = new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name);
        if (name.toLowerCase(Locale.ROOT).endsWith(".lrc")) lyrics.add(e);
        else if (LibraryNames.isAudio(name)) audio.add(e);
      }
    } catch (Exception e) { throw new IOException("无法扫描已授权的曲库文件夹", e); }
    Map<String, List<Uri>> exact = new HashMap<>(), byTitle = new HashMap<>();
    for (Entry e : lyrics) {
      exact.computeIfAbsent(LibraryNames.key(e.name), k -> new ArrayList<>()).add(e.uri);
      byTitle.computeIfAbsent(LibraryNames.titleKey(e.name), k -> new ArrayList<>()).add(e.uri);
    }
    ArrayList<Item> files = new ArrayList<>();
    for (Entry e : audio) {
      List<Uri> found = exact.get(LibraryNames.key(e.name));
      if (found == null || found.size() != 1) found = byTitle.get(LibraryNames.titleKey(e.name));
      files.add(new Item(e.uri, e.name, found != null && found.size() == 1 ? found.get(0) : null));
    }
    files.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
    return files;
  }
  static String readLrc(Context ctx, Uri uri) throws IOException {
    byte[] bytes;
    try (InputStream in = ctx.getContentResolver().openInputStream(uri);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new IOException("无法读取 LRC");
      byte[] buf = new byte[8192]; int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > 2 * 1024 * 1024) throw new IOException("LRC 超过 2 MB");
        out.write(buf, 0, n);
      }
      bytes = out.toByteArray();
    }
    if (bytes.length >= 2 && (
        (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254 ||
        (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255))
      return new String(bytes, "UTF-16");
    try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
    catch (CharacterCodingException e) { return new String(bytes, "GB18030"); }
  }
  static Imported importPair(Context ctx, Uri tree, Uri sourceAudio, String audioName,
      Uri sourceLrc, String lrcName) throws IOException {
    if (!LibraryNames.isAudio(audioName) || !lrcName.toLowerCase(Locale.ROOT).endsWith(".lrc"))
      throw new IOException("请选择有效的伴奏文件和 .lrc 文件");
    ContentResolver resolver = ctx.getContentResolver();
    if (exists(ctx, tree, audioName) || exists(ctx, tree, lrcName))
      throw new IOException("曲库文件夹已有同名文件，已拒绝覆盖");
    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    Uri copiedAudio = null, copiedLrc = null;
    boolean success = false;
    try {
      copiedAudio = DocumentsContract.createDocument(resolver, parent, mime(resolver,sourceAudio,audioName), audioName);
      if (copiedAudio == null) throw new IOException("无法创建目标伴奏");
      copy(resolver,sourceAudio,copiedAudio);
      copiedLrc = DocumentsContract.createDocument(resolver,parent,"text/plain",lrcName);
      if (copiedLrc == null) throw new IOException("无法创建目标歌词");
      copy(resolver,sourceLrc,copiedLrc);
      success = true;
    } catch (Exception e) {
      throw e instanceof IOException ? (IOException)e : new IOException("复制曲库文件失败",e);
    } finally {
      if (!success) { delete(resolver,copiedLrc); delete(resolver,copiedAudio); }
    }
    int retained = (delete(resolver,sourceAudio)?0:1)+(delete(resolver,sourceLrc)?0:1);
    return new Imported(copiedAudio,
        retained == 0 ? "" : "已复制，但有"+retained+"个原文件无法删除，请手动清理");
  }
  private static boolean exists(Context ctx, Uri tree, String filename) throws IOException {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree));
    try (Cursor c = ctx.getContentResolver().query(children,
        new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
      if (c == null) throw new IOException("无法检查目标文件夹");
      while (c.moveToNext()) if (filename.equalsIgnoreCase(c.getString(0))) return true;
    }
    return false;
  }
  private static String mime(ContentResolver r,Uri uri,String name) {
    String type = r.getType(uri);
    if (type != null && !"application/octet-stream".equals(type)) return type;
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
    if (n.endsWith(".mp3")) return "audio/mpeg";
    if (n.endsWith(".wav")) return "audio/wav";
    if (n.endsWith(".aac")) return "audio/aac";
    if (n.endsWith(".mp4")) return "video/mp4";
    return "audio/mp4";
  }
  private static void copy(ContentResolver r, Uri src, Uri dest) throws IOException {
    try (InputStream in = r.openInputStream(src); OutputStream out = r.openOutputStream(dest,"w")) {
      if (in == null || out == null) throw new IOException("源或目标文件无法打开");
      byte[] buf = new byte[65536]; int n;
      while ((n = in.read(buf)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("复制被取消");
        out.write(buf,0,n);
      }
      out.flush();
    }
  }
  private static boolean delete(ContentResolver r,Uri uri) {
    if (uri == null) return false;
    try { return DocumentsContract.deleteDocument(r,uri); }
    catch (Exception ignored) { return false; }
  }
}
