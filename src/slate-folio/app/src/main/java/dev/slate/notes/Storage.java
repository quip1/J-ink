package dev.slate.notes;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.*;

/** Where things live. With all-files access: /Documents/Slate and /Fonts. Without: app-private storage. */
final class Storage {
  static File external() { return Environment.getExternalStorageDirectory(); }
  static boolean reader() { return BuildConfig.READER; }
  static File slateRoot() { return new File(external(), "Documents/Slate"); }
  static File folioRoot() { return new File(external(), "Documents/Folio"); }
  /** This app's own public folder: Documents/Slate or Documents/Folio. */
  static File publicRoot() { return reader() ? folioRoot() : slateRoot(); }

  static boolean hasAccess(Context c) {
    if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
    return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
  }

  static void requestAccess(Activity a) {
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + a.getPackageName())));
      } catch (Exception e) {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      }
    } else {
      a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
          Manifest.permission.READ_EXTERNAL_STORAGE}, 5);
    }
  }

  /** Folders scanned for books. */
  static File[] bookRoots() {
    File e = external();
    return new File[]{new File(e, "Books"), new File(e, "Documents"), new File(e, "Download")};
  }

  static File fontsDir(Context c) {
    File d = hasAccess(c) ? new File(external(), "Fonts") : new File(privateBase(c), "fonts");
    d.mkdirs();
    return d;
  }

  static File privateBase(Context c) {
    File b = c.getExternalFilesDir(null);
    return b != null ? b : c.getFilesDir();
  }

  /** Moves data from app-private storage into the public folders once access is granted. */
  static void migrate(Context c) {
    File old = privateBase(c);
    File root = publicRoot();
    for (String n : new String[]{"Notebooks", "Templates", "Annotations", "Exports"}) {
      moveChildren(new File(old, n.toLowerCase(java.util.Locale.US)), new File(root, n));
      moveChildren(new File(old, n), new File(root, n));
    }
    moveChildren(new File(old, "fonts"), new File(external(), "Fonts"));
    // Books annotated in Slate 2.0 move over to the reader.
    if (reader()) moveChildren(new File(slateRoot(), "Annotations"), new File(folioRoot(), "Annotations"));
  }

  private static void moveChildren(File from, File to) {
    File[] fs = from.listFiles();
    if (fs == null) return;
    to.mkdirs();
    for (File f : fs) {
      File dest = new File(to, f.getName());
      if (dest.exists()) continue;
      if (!f.renameTo(dest)) { try { copy(f, dest); deleteTree(f); } catch (IOException ignored) {} }
    }
  }

  static void copy(File from, File to) throws IOException {
    if (from.isDirectory()) {
      to.mkdirs();
      File[] fs = from.listFiles();
      if (fs != null) for (File f : fs) copy(f, new File(to, f.getName()));
      return;
    }
    try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { pipe(in, out); }
  }

  static void pipe(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[1 << 16];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
  }

  static void deleteTree(File f) {
    File[] fs = f.listFiles();
    if (fs != null) for (File c : fs) deleteTree(c);
    f.delete();
  }

  static java.util.Properties bookMeta(File book) {
    java.util.Properties p = new java.util.Properties();
    File m = new File(new File(App.dir("Annotations"), sha1(book.getAbsolutePath())), "meta.properties");
    try (InputStream in = new FileInputStream(m)) { p.load(in); } catch (IOException ignored) {}
    return p;
  }

  static String stripExt(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

  static String sha1(String s) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
      return b.toString();
    } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
  }
}
