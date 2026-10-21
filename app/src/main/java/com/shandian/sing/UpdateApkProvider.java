package com.shandian.sing;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the system installer read-only access to exactly one private APK. */
public final class UpdateApkProvider extends ContentProvider {
  static File apk(Context context) {
    return new File(context.getCacheDir(), "update.apk");
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  private void check(Uri uri) {
    if (!"/update.apk".equals(uri.getPath())) throw new IllegalArgumentException("Unknown file");
  }

  @Override
  public String getType(Uri uri) {
    check(uri);
    return "application/vnd.android.package-archive";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    check(uri);
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    check(uri);
    String[] columns =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    MatrixCursor cursor = new MatrixCursor(columns, 1);
    Object[] row = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "sing-update.apk";
      else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk(getContext()).length();
    }
    cursor.addRow(row);
    return cursor;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}
