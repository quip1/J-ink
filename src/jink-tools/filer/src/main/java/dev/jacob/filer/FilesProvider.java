package dev.jacob.filer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands single files to other apps ("Open with", "Share") as read-only content:// links. It isn't
 * exported: another app can only read a file after Filer grants it that one link.
 */
public class FilesProvider extends ContentProvider {
  static final String AUTHORITY = "dev.jacob.filer.files";

  static Uri uriFor(File f) {
    return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
  }

  static String mimeOf(String name) {
    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileOps.extension(name));
    return m != null ? m : "application/octet-stream";
  }

  private static File fileOf(Uri uri) throws FileNotFoundException {
    String path = uri.getPath();
    if (path == null) throw new FileNotFoundException("No path");
    File f = new File(path);
    if (!f.isFile()) throw new FileNotFoundException(path);
    return f;
  }

  @Override public boolean onCreate() { return true; }

  @Override public String getType(Uri uri) { return mimeOf(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment()); }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
    File f;
    try { f = fileOf(uri); } catch (FileNotFoundException e) { return null; }
    String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
    MatrixCursor c = new MatrixCursor(cols);
    Object[] row = new Object[cols.length];
    for (int i = 0; i < cols.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
      else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
    }
    c.addRow(row);
    return c;
  }

  @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }

  @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }

  @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
