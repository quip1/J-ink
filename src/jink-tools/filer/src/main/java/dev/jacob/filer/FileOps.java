package dev.jacob.filer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Listing, copying, moving, renaming and deleting with java.io.File. Plain Java so it can be unit tested. */
final class FileOps {
  private FileOps() {}

  enum Sort { NAME, DATE, SIZE }

  /** Reports progress during long operations: called with the file being worked on. */
  interface Progress { void step(String name); }

  static final Progress SILENT = name -> { };

  /** Folders first, then files, each in the chosen order. Hidden (dot) files only if asked. */
  static List<File> list(File dir, boolean showHidden, Sort sort) {
    File[] all = dir.listFiles();
    List<File> out = new ArrayList<>();
    if (all == null) return out;
    for (File f : all) if (showHidden || !f.getName().startsWith(".")) out.add(f);
    Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
    Comparator<File> order;
    switch (sort) {
      case DATE: order = Comparator.comparingLong(File::lastModified).reversed().thenComparing(byName); break;
      case SIZE: order = Comparator.comparingLong(FileOps::sizeForSort).reversed().thenComparing(byName); break;
      default: order = byName;
    }
    out.sort(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(order));
    return out;
  }

  private static long sizeForSort(File f) { return f.isDirectory() ? 0 : f.length(); }

  /** Problems with a proposed new name, or null if it's fine. */
  static String checkName(File dir, String name, File except) {
    String n = name.trim();
    if (n.isEmpty()) return "Name can't be empty";
    if (n.equals(".") || n.equals("..")) return "That name isn't allowed";
    if (n.contains("/") || n.contains("\0")) return "Names can't contain /";
    File target = new File(dir, n);
    if (target.exists() && (except == null || !target.equals(except))) {
      // Allow changing only the capitalisation of the same file.
      if (except == null || !n.equalsIgnoreCase(except.getName())) return "Something called “" + n + "” is already here";
    }
    return null;
  }

  /** "notes.txt" becomes "notes (1).txt", "notes (2).txt"... until the name is free. */
  static File uniqueName(File dir, String name) {
    File f = new File(dir, name);
    if (!f.exists()) return f;
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; ; i++) {
      f = new File(dir, base + " (" + i + ")" + ext);
      if (!f.exists()) return f;
    }
  }

  static File rename(File f, String newName) throws IOException {
    File parent = f.getParentFile();
    String err = checkName(parent, newName, f);
    if (err != null) throw new IOException(err);
    File target = new File(parent, newName.trim());
    if (!f.renameTo(target)) throw new IOException("Couldn't rename " + f.getName());
    return target;
  }

  static File mkdir(File dir, String name) throws IOException {
    String err = checkName(dir, name, null);
    if (err != null) throw new IOException(err);
    File d = new File(dir, name.trim());
    if (!d.mkdir()) throw new IOException("Couldn't create the folder");
    return d;
  }

  /** True if {@code inner} is {@code outer} or somewhere inside it. */
  static boolean isInside(File inner, File outer) throws IOException {
    String o = outer.getCanonicalPath(), i = inner.getCanonicalPath();
    return i.equals(o) || i.startsWith(o.endsWith(File.separator) ? o : o + File.separator);
  }

  /** Copies files or whole folders into {@code destDir}, renaming on clashes. Returns the copies. */
  static List<File> copy(List<File> sources, File destDir, Progress p) throws IOException {
    List<File> made = new ArrayList<>();
    for (File src : sources) {
      if (src.isDirectory() && isInside(destDir, src)) throw new IOException("Can't copy a folder into itself");
      File target = uniqueName(destDir, src.getName());
      copyTree(src, target, p);
      made.add(target);
    }
    return made;
  }

  private static void copyTree(File src, File dst, Progress p) throws IOException {
    p.step(src.getName());
    if (src.isDirectory()) {
      if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("Couldn't create " + dst.getName());
      File[] kids = src.listFiles();
      if (kids == null) throw new IOException("Can't read " + src.getName());
      for (File k : kids) copyTree(k, new File(dst, k.getName()), p);
    } else {
      try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { pipe(in, out); }
      dst.setLastModified(src.lastModified());
    }
  }

  /**
   * Moves into {@code destDir}. A rename when on the same storage (instant); otherwise copy then
   * delete, and the original is only deleted once the copy has finished.
   */
  static List<File> move(List<File> sources, File destDir, Progress p) throws IOException {
    List<File> moved = new ArrayList<>();
    for (File src : sources) {
      if (src.isDirectory() && isInside(destDir, src)) throw new IOException("Can't move a folder into itself");
      if (src.getParentFile() != null && src.getParentFile().getCanonicalFile().equals(destDir.getCanonicalFile())) {
        moved.add(src); // already here
        continue;
      }
      File target = uniqueName(destDir, src.getName());
      p.step(src.getName());
      if (!src.renameTo(target)) {
        copyTree(src, target, p);
        delete(src, p);
      }
      moved.add(target);
    }
    return moved;
  }

  static void delete(File f, Progress p) throws IOException {
    if (f.isDirectory()) {
      File[] kids = f.listFiles();
      if (kids != null) for (File k : kids) delete(k, p);
    }
    p.step(f.getName());
    if (!f.delete() && f.exists()) throw new IOException("Couldn't delete " + f.getName());
  }

  static void pipe(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
  }

  /** Total bytes and file count of a file or folder tree: {bytes, files, folders}. */
  static long[] measure(File f) {
    long[] t = new long[3];
    measure(f, t);
    return t;
  }

  private static void measure(File f, long[] t) {
    if (f.isDirectory()) {
      t[2]++;
      File[] kids = f.listFiles();
      if (kids != null) for (File k : kids) measure(k, t);
    } else {
      t[0] += f.length();
      t[1]++;
    }
  }

  static String size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    String[] units = {"KB", "MB", "GB", "TB"};
    double v = bytes;
    int u = -1;
    do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
    return String.format(Locale.US, v < 10 ? "%.1f %s" : "%.0f %s", v, units[u]);
  }

  static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
  }

  private static final List<String> TEXT = Arrays.asList("txt", "md", "markdown", "json", "csv", "tsv", "xml",
      "html", "htm", "css", "js", "ts", "java", "kt", "py", "c", "h", "cpp", "sh", "bat", "ini", "cfg", "conf",
      "yaml", "yml", "toml", "log", "properties", "gradle", "srt", "tex", "rtf", "org");

  /** Files the built-in editor opens: known text extensions, at most 2 MB. */
  static boolean isText(File f) { return TEXT.contains(extension(f.getName())) && f.length() <= 2L * 1024 * 1024; }

  static boolean isZip(File f) {
    String e = extension(f.getName());
    return e.equals("zip") || e.equals("cbz") || e.equals("epub") || e.equals("jar");
  }
}
