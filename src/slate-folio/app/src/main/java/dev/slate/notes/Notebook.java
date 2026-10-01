package dev.slate.notes;

import java.io.*;
import java.util.*;

/** A notebook is a folder: meta.properties plus one file per page (p0001.bin, ...). */
final class Notebook {
  final File dir;
  String name;
  String template;   // Templates key, e.g. "lined" or "img:cornell.png"
  int lastPage;
  long modified;
  private int count;  // cached page-file count

  private Notebook(File dir) { this.dir = dir; }

  /** Notebooks always live with Slate, so pages sent from Folio show up there. */
  static File root() {
    if (Storage.hasAccess(App.ctx)) { File r = new File(Storage.slateRoot(), "Notebooks"); r.mkdirs(); return r; }
    return App.dir("Notebooks");
  }

  static List<Notebook> list() {
    List<Notebook> out = new ArrayList<>();
    File[] dirs = root().listFiles(File::isDirectory);
    if (dirs != null) for (File d : dirs) {
      Notebook nb = open(d);
      if (nb != null) out.add(nb);
    }
    out.sort((a, b) -> Long.compare(b.modified, a.modified));
    return out;
  }

  static Notebook open(File d) {
    File meta = new File(d, "meta.properties");
    if (!meta.exists()) return null;
    Properties p = new Properties();
    try (InputStream in = new FileInputStream(meta)) { p.load(in); } catch (IOException e) { return null; }
    Notebook nb = new Notebook(d);
    nb.name = p.getProperty("name", d.getName());
    nb.template = Templates.normalize(p.getProperty("template", "blank"));
    nb.lastPage = parse(p.getProperty("last"), 0);
    long newest = meta.lastModified();
    File[] fs = d.listFiles();
    if (fs != null) for (File f : fs) {
      newest = Math.max(newest, f.lastModified());
      if (f.getName().matches("p\\d{4}\\.bin")) nb.count++;
    }
    nb.modified = newest;
    return nb;
  }

  static Notebook create(String name, String template) throws IOException {
    File d = new File(root(), "nb" + System.currentTimeMillis());
    while (d.exists()) d = new File(root(), "nb" + System.nanoTime());
    if (!d.mkdirs()) throw new IOException("mkdir failed");
    Notebook nb = new Notebook(d);
    nb.name = name; nb.template = template; nb.lastPage = 0;
    nb.saveMeta();
    return nb;
  }

  void saveMeta() {
    Properties p = new Properties();
    p.setProperty("name", name);
    p.setProperty("template", template);
    p.setProperty("last", String.valueOf(lastPage));
    try (OutputStream out = new FileOutputStream(new File(dir, "meta.properties"))) { p.store(out, "Slate"); }
    catch (IOException ignored) {}
  }

  /** Pages shown to the user; an unsaved first page still counts as one. */
  int pageCount() { return Math.max(1, count); }

  File pageFile(int i) { return new File(dir, String.format(Locale.US, "p%04d.bin", i + 1)); }

  /** Optional per-page background (pages sent over from books use this). */
  File pageBg(int i) { return new File(dir, String.format(Locale.US, "p%04d.png", i + 1)); }

  private void move(int from, int to) {
    pageFile(from).renameTo(pageFile(to));
    File bg = pageBg(from);
    if (bg.exists()) bg.renameTo(pageBg(to));
  }

  /** Called after a page file is written, so a brand-new file is counted. */
  void noteSaved(int i) { if (i >= count) count = i + 1; }

  /** Makes room for a new page at index i by shifting later page files up one. */
  void insertPageAt(int i) {
    for (int k = count - 1; k >= i; k--) move(k, k + 1);
    if (i <= count) count++;
  }

  void deletePage(int i) {
    if (i >= count) return;
    pageFile(i).delete();
    pageBg(i).delete();
    for (int k = i + 1; k < count; k++) move(k, k - 1);
    count--;
  }

  void deleteAll() { Storage.deleteTree(dir); }

  private static int parse(String s, int def) {
    try { return s == null ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
  }
}
