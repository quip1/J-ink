package dev.jacob.filer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Creating, reading and editing zip files. Editing (add / rename / delete entries) rewrites the
 * zip to a temporary file and swaps it in at the end, so a failure never damages the original.
 * Plain Java so it can be unit tested.
 */
final class Zips {
  private Zips() {}

  /** One file or folder inside a zip, as shown when browsing it. */
  static final class Item {
    final String path; // full path inside the zip, folders end with "/"
    final String name;
    final boolean folder;
    final long size;
    Item(String path, String name, boolean folder, long size) {
      this.path = path; this.name = name; this.folder = folder; this.size = size;
    }
  }

  /** Every entry path in the zip, including folders that only exist implicitly (e.g. "a/" for "a/b.txt"). */
  static Map<String, Long> entries(File zip) throws IOException {
    Map<String, Long> out = new LinkedHashMap<>();
    try (ZipFile z = new ZipFile(zip)) {
      Enumeration<? extends ZipEntry> en = z.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        String name = clean(e.getName());
        if (name.isEmpty()) continue;
        out.put(name, e.isDirectory() ? -1L : Math.max(0, e.getSize()));
        // Register parent folders that have no entry of their own.
        int slash = name.lastIndexOf('/', name.endsWith("/") ? name.length() - 2 : name.length() - 1);
        while (slash > 0) {
          String parent = name.substring(0, slash + 1);
          if (!out.containsKey(parent)) out.put(parent, -1L);
          slash = parent.lastIndexOf('/', parent.length() - 2);
        }
      }
    }
    return out;
  }

  /** The direct children of {@code folder} ("" for the top level): folders first, then by name. */
  static List<Item> children(Map<String, Long> entries, String folder) {
    List<Item> out = new ArrayList<>();
    for (Map.Entry<String, Long> e : entries.entrySet()) {
      String p = e.getKey();
      if (!p.startsWith(folder) || p.equals(folder)) continue;
      String rest = p.substring(folder.length());
      int slash = rest.indexOf('/');
      boolean isFolder = slash >= 0;
      if (isFolder && slash != rest.length() - 1) continue; // deeper than one level
      String name = isFolder ? rest.substring(0, slash) : rest;
      out.add(new Item(p, name, isFolder, isFolder ? 0 : e.getValue()));
    }
    out.sort((a, b) -> a.folder != b.folder ? (a.folder ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
    return out;
  }

  /** Zips files and folders into {@code out}, with each source at the top level of the zip. */
  static void create(List<File> sources, File out, FileOps.Progress p) throws IOException {
    File tmp = new File(out.getPath() + ".part");
    try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
      for (File src : sources) addTree(z, src, src.getName(), p);
    } catch (IOException e) {
      tmp.delete();
      throw e;
    }
    replace(tmp, out);
  }

  private static void addTree(ZipOutputStream z, File src, String path, FileOps.Progress p) throws IOException {
    p.step(src.getName());
    if (src.isDirectory()) {
      ZipEntry dir = new ZipEntry(path + "/");
      dir.setTime(src.lastModified());
      z.putNextEntry(dir);
      z.closeEntry();
      File[] kids = src.listFiles();
      if (kids == null) throw new IOException("Can't read " + src.getName());
      for (File k : kids) addTree(z, k, path + "/" + k.getName(), p);
    } else {
      ZipEntry e = new ZipEntry(path);
      e.setTime(src.lastModified());
      z.putNextEntry(e);
      try (InputStream in = new BufferedInputStream(new FileInputStream(src))) { FileOps.pipe(in, z); }
      z.closeEntry();
    }
  }

  /**
   * Extracts the entries under any of {@code paths} (all entries when null) into {@code destDir}.
   * Entries are placed relative to {@code base} (e.g. extracting "a/b/" with base "a/" gives "b/").
   * Paths that would escape {@code destDir} ("../" tricks) are refused.
   */
  static int extract(File zip, Set<String> paths, String base, File destDir, FileOps.Progress p) throws IOException {
    String root = destDir.getCanonicalPath() + File.separator;
    int n = 0;
    try (ZipFile z = new ZipFile(zip)) {
      Enumeration<? extends ZipEntry> en = z.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        String name = clean(e.getName());
        if (name.isEmpty() || !selected(name, paths) || !name.startsWith(base)) continue;
        String rel = name.substring(base.length());
        if (rel.isEmpty()) continue;
        File out = new File(destDir, rel);
        if (!(out.getCanonicalPath() + (e.isDirectory() ? File.separator : "")).startsWith(root))
          throw new IOException("Unsafe path in zip: " + name);
        p.step(rel);
        if (e.isDirectory()) {
          if (!out.mkdirs() && !out.isDirectory()) throw new IOException("Couldn't create " + rel);
          continue;
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Couldn't create " + rel);
        try (InputStream in = z.getInputStream(e); OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
          FileOps.pipe(in, o);
        }
        if (e.getTime() > 0) out.setLastModified(e.getTime());
        n++;
      }
    }
    return n;
  }

  /** Adds files/folders into {@code folder} inside the zip ("" for the top), replacing same-named entries. */
  static void add(File zip, List<File> sources, String folder, FileOps.Progress p) throws IOException {
    Set<String> incoming = new TreeSet<>();
    for (File s : sources) incoming.add(folder + s.getName());
    rewrite(zip, (name, z) -> {
      for (String in : incoming) if (name.equals(in) || name.startsWith(in + "/")) return null; // replaced
      return name;
    }, z -> { for (File s : sources) addTree(z, s, folder + s.getName(), p); });
  }

  /** Deletes entries; a folder path ("x/") removes everything under it. */
  static void remove(File zip, Set<String> paths) throws IOException {
    rewrite(zip, (name, z) -> selected(name, paths) ? null : name, z -> { });
  }

  /** Renames a file or folder inside the zip. {@code newName} is just the last part, not a path. */
  static void rename(File zip, String path, String newName) throws IOException {
    if (newName.isEmpty() || newName.contains("/")) throw new IOException("Names can't be empty or contain /");
    boolean folder = path.endsWith("/");
    String trimmed = folder ? path.substring(0, path.length() - 1) : path;
    int slash = trimmed.lastIndexOf('/');
    String target = trimmed.substring(0, slash + 1) + newName + (folder ? "/" : "");
    if (entries(zip).containsKey(target)) throw new IOException("Something called “" + newName + "” is already there");
    rewrite(zip, (name, z) -> name.startsWith(path) ? target + name.substring(path.length()) : name, z -> { });
  }

  // ---- internals ----

  private interface Renamer { String apply(String name, ZipOutputStream z) throws IOException; }

  private interface Extra { void write(ZipOutputStream z) throws IOException; }

  /**
   * Copies every entry through {@code renamer} (null = drop it), then lets {@code extra} append
   * new entries, writing to a temp file that replaces the zip only once everything succeeded.
   */
  private static void rewrite(File zip, Renamer renamer, Extra extra) throws IOException {
    File tmp = new File(zip.getPath() + ".part");
    try (ZipFile src = new ZipFile(zip);
         ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
      Set<String> written = new TreeSet<>();
      Enumeration<? extends ZipEntry> en = src.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        String name = renamer.apply(clean(e.getName()), out);
        if (name == null || name.isEmpty() || !written.add(name)) continue;
        ZipEntry copy = new ZipEntry(name);
        copy.setTime(e.getTime());
        if (e.getComment() != null) copy.setComment(e.getComment());
        out.putNextEntry(copy);
        if (!e.isDirectory()) try (InputStream in = src.getInputStream(e)) { FileOps.pipe(in, out); }
        out.closeEntry();
      }
      extra.write(out);
    } catch (IOException | RuntimeException e) {
      tmp.delete();
      throw e;
    }
    replace(tmp, zip);
  }

  private static void replace(File tmp, File target) throws IOException {
    File backup = new File(target.getPath() + ".bak");
    if (target.exists() && !target.renameTo(backup)) { tmp.delete(); throw new IOException("Couldn't update " + target.getName()); }
    if (!tmp.renameTo(target)) {
      backup.renameTo(target);
      throw new IOException("Couldn't update " + target.getName());
    }
    backup.delete();
  }

  /** Zip paths always use "/", never start with "/", and drop "./" prefixes. */
  static String clean(String name) {
    String n = name.replace('\\', '/');
    while (n.startsWith("/") || n.startsWith("./")) n = n.substring(n.startsWith("/") ? 1 : 2);
    return n;
  }

  private static boolean selected(String name, Set<String> paths) {
    if (paths == null) return true;
    for (String p : paths) if (name.equals(p) || (p.endsWith("/") && name.startsWith(p))) return true;
    return false;
  }

  static List<String> sorted(Set<String> s) {
    List<String> l = new ArrayList<>(s);
    Collections.sort(l);
    return l;
  }
}
