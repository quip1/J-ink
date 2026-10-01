package dev.slate.notes;

import java.io.*;
import java.util.*;

/** Shared bits for books: annotation folder keyed by file path, reading position. */
abstract class BookSource extends Source {
  final File file;
  final File annRoot;
  final Properties meta = new Properties();

  BookSource(File f) {
    file = f;
    annRoot = new File(App.dir("Annotations"), Storage.sha1(f.getAbsolutePath()));
    try (InputStream in = new FileInputStream(new File(annRoot, "meta.properties"))) { meta.load(in); }
    catch (IOException ignored) {}
  }

  @Override String title() {
    String n = file.getName();
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  /** Reflowable books namespace annotations by layout, since pages move when the font changes. */
  File annDir() { return annRoot; }

  @Override File annotationFile(int i) {
    File d = annDir();
    d.mkdirs();
    return new File(d, String.format(Locale.US, "p%04d.bin", i + 1));
  }

  @Override int lastPage() {
    try { return Integer.parseInt(meta.getProperty("page", "0")); } catch (NumberFormatException e) { return 0; }
  }

  @Override void rememberPage(int i) {
    meta.setProperty("page", String.valueOf(i));
    int n = pageCount();
    meta.setProperty("progress", String.valueOf(n <= 1 ? 1f : i / (float) (n - 1)));
    meta.setProperty("opened", String.valueOf(System.currentTimeMillis()));
    meta.setProperty("path", file.getAbsolutePath());
    saveMeta();
  }

  void saveMeta() {
    annRoot.mkdirs();
    try (OutputStream out = new FileOutputStream(new File(annRoot, "meta.properties"))) { meta.store(out, "Slate"); }
    catch (IOException ignored) {}
  }

  @Override List<String> bookmarks() {
    String b = meta.getProperty("bookmarks", "");
    List<String> out = new ArrayList<>();
    for (String s : b.split("\\|")) if (!s.isEmpty()) out.add(s);
    return out;
  }

  @Override void setBookmarks(List<String> b) {
    meta.setProperty("bookmarks", String.join("|", b));
    saveMeta();
  }
}
