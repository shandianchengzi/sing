package com.shandian.sing;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** SAF user-selected folder; no broad external storage permissions. */
final class FolderLibrary {
  static class Item {
    final Uri folder, audio, lyrics;
    final String name;
    Item(Uri folder, Uri audio, String name, Uri lyrics) {
      this.folder = folder; this.audio = audio; this.name = name; this.lyrics = lyrics;
    }
  }
  static class Imported {
    final Uri audio, lyrics;
    final String warning;
    Imported(Uri audio, Uri lyrics, String warning) {
      this.audio = audio; this.lyrics = lyrics; this.warning = warning;
    }
  }
  private static class Entry {
    final Uri uri;
    final String name;
    Entry(Uri uri, String name) { this.uri = uri; this.name = name; }
  }
  /** Show all direct files, including LRC and files outside the library naming rules. */
  static ArrayList<String> listFiles(Context ctx, Uri tree) throws IOException {
    ArrayList<String> files=new ArrayList<>();
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
    try(Cursor c=ctx.getContentResolver().query(children,new String[]{
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
      if(c==null) throw new IOException("无法读取曲库文件夹");
      while(c.moveToNext()) {
        String name=c.getString(0);
        if(name!=null && !DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1))) files.add(name);
      }
    } catch(Exception e) { throw new IOException("无法读取文件列表，请检查文件夹授权",e); }
    files.sort(String.CASE_INSENSITIVE_ORDER);
    return files;
  }
  static ArrayList<Item> scan(Context ctx, Uri tree) throws IOException { return scan(ctx, tree, false); }
  static ArrayList<Item> scan(Context ctx, Uri tree, boolean unmatched) throws IOException {
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
        if (!LibraryNames.lyricsTitle(name).isEmpty()) lyrics.add(e);
        else if (LibraryNames.isAudio(name)
            && (unmatched == LibraryNames.audioTitle(name).isEmpty())) audio.add(e);
      }
    } catch (Exception e) { throw new IOException("无法扫描已授权的曲库文件夹", e); }
    Map<String, List<Uri>> exact = new HashMap<>();
    for (Entry e : lyrics) {
      exact.computeIfAbsent(LibraryNames.key(e.name), k -> new ArrayList<>()).add(e.uri);
    }
    ArrayList<Item> files = new ArrayList<>();
    for (Entry e : audio) {
      List<Uri> found = exact.get(LibraryNames.key(e.name));

      files.add(new Item(tree, e.uri, e.name, found != null && found.size() == 1 ? found.get(0) : null));
    }
    files.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
    return files;
  }
  static Uri rename(Context ctx, Uri source, String filename) throws IOException {
    try {
      try(Cursor c=ctx.getContentResolver().query(source,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
        if(c!=null && c.moveToFirst() && filename.equals(c.getString(0))) return source;
      }
      Uri renamed=DocumentsContract.renameDocument(ctx.getContentResolver(), source, filename);
      if(renamed==null) throw new IOException("文件提供方不支持改名");
      return renamed;
    } catch(Exception e) { throw new IOException("无法改名："+e.getMessage(),e); }
  }
  static void remove(Context ctx, Uri source) throws IOException {
    try {
      if(!DocumentsContract.deleteDocument(ctx.getContentResolver(),source)) throw new IOException("文件未删除");
    } catch(Exception e) { throw new IOException("无法删除源文件："+e.getMessage(),e); }
  }
  static Uri saveLyrics(Context ctx, Uri folder, String title, String text) throws IOException {
    if(!LibraryNames.validTitle(title)) throw new IOException("歌名无效");
    Uri parent=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
    Uri previous=findFile(ctx,folder,title+".lrc");
    Uri temporary=null;
    try {
      temporary=DocumentsContract.createDocument(ctx.getContentResolver(),parent,"application/octet-stream",
          ".sing-lyrics-"+java.util.UUID.randomUUID()+".tmp");
      if(temporary==null) throw new IOException("无法创建歌词文件");
      writeVerified(ctx.getContentResolver(),text,temporary);
      if(previous!=null) {
        // Back up the old lyrics until the replacement has been verified and renamed.
        Uri backup=rename(ctx,previous,".sing-backup-"+java.util.UUID.randomUUID()+".lrc");
        try { temporary=rename(ctx,temporary,title+".lrc"); }
        catch(IOException e) { rename(ctx,backup,title+".lrc"); throw e; }
        try { remove(ctx,backup); } catch(IOException ignored) {}
      } else temporary=rename(ctx,temporary,title+".lrc");
      Uri result=temporary; temporary=null; return result;
    } finally { if(temporary!=null) try { remove(ctx,temporary); } catch(IOException ignored) {} }
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
  /** Copy and independently verify before updating any saved references or deleting sources. */
  /** Reuse exact duplicates; never overwrite a different file sharing the same name. */
  static Imported copyPair(Context ctx, Uri tree, Uri sourceAudio, String audioName,
      Uri sourceLrc, String lrcName, String generatedLrc) throws IOException {
    if (LibraryNames.audioTitle(audioName).isEmpty()) throw new IOException("伴奏必须命名为 歌名-伴奏.扩展名");
    if ((sourceLrc != null || generatedLrc != null)
        && (lrcName == null || !lrcName.toLowerCase(Locale.ROOT).endsWith(".lrc")))
      throw new IOException("歌词文件必须是 .lrc");
    ContentResolver resolver = ctx.getContentResolver();
    Uri parent = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree));
    Uri targetAudio = findFile(ctx, tree, audioName);
    Uri targetLrc = lrcName == null ? null : findFile(ctx, tree, lrcName);
    // Verify existing duplicates BEFORE writing any new file, avoiding partial merge.
    if (targetAudio != null) ensureSame(resolver, sourceAudio, null, targetAudio);
    if (targetLrc != null) ensureSame(resolver, sourceLrc, generatedLrc, targetLrc);
    boolean createdAudio = false, createdLrc = false, success = false;
    try {
      if (targetAudio == null) {
        targetAudio = DocumentsContract.createDocument(
            resolver, parent, mime(resolver, sourceAudio, audioName), audioName);
        if (targetAudio == null) throw new IOException("无法创建目标伴奏");
        createdAudio = true;
        targetAudio = rename(ctx,targetAudio,audioName);
        copyVerified(resolver, sourceAudio, targetAudio);
      }
      if (lrcName != null && targetLrc == null) {
        targetLrc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", lrcName);
        if (targetLrc == null) throw new IOException("无法创建目标歌词");
        createdLrc = true;
        targetLrc = rename(ctx,targetLrc,lrcName);
        if (sourceLrc != null) copyVerified(resolver, sourceLrc, targetLrc);
        else writeVerified(resolver, generatedLrc, targetLrc);
      }
      success = true;
      return new Imported(targetAudio, targetLrc,
          !createdAudio ? "已合并同名且内容一致的伴奏" : "");
    } catch (Exception e) {
      throw e instanceof IOException ? (IOException)e : new IOException("保存到曲库失败", e);
    } finally {
      if (!success) {
        if (createdLrc) delete(resolver, targetLrc);
        if (createdAudio) delete(resolver, targetAudio);
      }
    }
  }

  static Imported importPair(Context ctx, Uri tree, Uri sourceAudio, String audioName,
      Uri sourceLrc, String lrcName, boolean copyOnly) throws IOException {
    if (sourceLrc == null || lrcName == null) throw new IOException("请选择 LRC 文件");
    Imported imported = copyPair(ctx, tree, sourceAudio, audioName, sourceLrc, lrcName, null);
    if (copyOnly) return imported;
    String note = deleteSources(ctx, sourceAudio, sourceLrc, imported.audio, imported.lyrics);
    return new Imported(imported.audio, imported.lyrics, note);
  }

  static String deleteSources(Context ctx, Uri sourceAudio, Uri sourceLrc,
      Uri targetAudio, Uri targetLrc) {
    ContentResolver resolver = ctx.getContentResolver();
    int failed = 0;
    // ACTION_OPEN_DOCUMENT and tree URIs can refer to the same document with
    // different strings. Never delete the destination after reusing a same-file entry.
    if (sourceAudio != null && !sameDocument(sourceAudio, targetAudio)
        && !delete(resolver, sourceAudio)) failed++;
    if (sourceLrc != null && !sameDocument(sourceLrc, targetLrc)
        && !delete(resolver, sourceLrc)) failed++;
    return failed == 0 ? "" : "已复制完成，但有" + failed + "个源文件无法删除，请手动清理";
  }

  static boolean sameDocument(Uri source, Uri target) {
    if (source == null || target == null) return false;
    if (source.equals(target)) return true;
    if (!java.util.Objects.equals(source.getAuthority(), target.getAuthority())) return false;
    try {
      return DocumentsContract.getDocumentId(source)
          .equals(DocumentsContract.getDocumentId(target));
    } catch (RuntimeException ignored) { return false; }
  }

  static Uri findFile(Context ctx, Uri tree, String name) throws IOException {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree));
    try (Cursor c = ctx.getContentResolver().query(children, new String[]{
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
      if (c == null) throw new IOException("无法检查目标文件夹");
      while (c.moveToNext())
        if (name.equalsIgnoreCase(c.getString(1)))
          return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
    } catch (RuntimeException e) {
      throw new IOException("无法检查目录中的同名文件", e);
    }
    return null;
  }

  private static void ensureSame(ContentResolver resolver, Uri source,
      String generatedText, Uri existing) throws IOException {
    byte[] content;
    if (source != null) {
      try (InputStream in = resolver.openInputStream(source)) {
        if (in == null) throw new IOException("无法读取源文件");
        content = digest(in, null);
      }
    } else {
      if (generatedText == null) throw new IOException("歌词源不可读取");
      content = digest(new ByteArrayInputStream(
          generatedText.getBytes(StandardCharsets.UTF_8)), null);
    }
    try {
      verify(resolver, existing, content);
    } catch (IOException e) {
      throw new IOException("目标目录中的同名文件内容不同，为避免覆盖请先处理重名：" + e.getMessage(), e);
    }
  }

  private static byte[] digest(InputStream in, OutputStream out) throws IOException {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[65536]; int count;
      while ((count = in.read(buf)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("复制被中断");
        hash.update(buf, 0, count);
        if (out != null) out.write(buf, 0, count);
      }
      if (out != null) out.flush();
      return hash.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("无法进行 SHA-256 文件校验", e);
    }
  }

  private static void verify(ContentResolver resolver, Uri target, byte[] expected)
      throws IOException {
    try (InputStream check = resolver.openInputStream(target)) {
      if (check == null || !Arrays.equals(expected, digest(check, null)))
        throw new IOException("复制校验失败，原始文件没有删除");
    }
  }

  private static void copyVerified(ContentResolver resolver, Uri source, Uri target)
      throws IOException {
    byte[] expected;
    try (InputStream input = resolver.openInputStream(source);
         OutputStream output = resolver.openOutputStream(target, "w")) {
      if (input == null || output == null) throw new IOException("源或目标文件不可读取");
      expected = digest(input, output);
    }
    verify(resolver, target, expected);
  }

  private static void writeVerified(ContentResolver resolver, String content, Uri target)
      throws IOException {
    if (content == null) throw new IOException("缺少歌词内容");
    byte[] expected;
    try (InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
         OutputStream output = resolver.openOutputStream(target, "w")) {
      if (output == null) throw new IOException("不能在目标曲库写入歌词");
      expected = digest(input, output);
    }
    verify(resolver, target, expected);
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
  private static boolean delete(ContentResolver r,Uri uri) {
    if (uri == null) return false;
    try { return DocumentsContract.deleteDocument(r,uri); }
    catch (Exception ignored) { return false; }
  }
}
