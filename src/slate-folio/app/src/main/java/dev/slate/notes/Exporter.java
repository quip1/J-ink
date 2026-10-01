package dev.slate.notes;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Exports selected pages to PDF (vector ink, real text where the source has it)
 * or to a .slnote package: a zipped Slate notebook that re-imports with pages,
 * ink, text boxes and backgrounds intact.
 */
final class Exporter {
  static File exportsDir() { return App.dir("Exports"); }

  static String safe(String n) { return n.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); }

  static File unique(File dir, String base, String ext) {
    File f = new File(dir, base + ext);
    for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
    return f;
  }

  /** Parses "1-3, 7, 10-12" (1-based) into sorted 0-based indices within [0, count). */
  static List<Integer> parseRange(String s, int count) {
    TreeSet<Integer> out = new TreeSet<>();
    for (String part : s.split(",")) {
      part = part.trim();
      if (part.isEmpty()) continue;
      try {
        if (part.contains("-")) {
          String[] ab = part.split("-", 2);
          int a = Integer.parseInt(ab[0].trim()), b = ab[1].trim().isEmpty() ? count : Integer.parseInt(ab[1].trim());
          for (int i = Math.min(a, b); i <= Math.max(a, b); i++) if (i >= 1 && i <= count) out.add(i - 1);
        } else {
          int i = Integer.parseInt(part);
          if (i >= 1 && i <= count) out.add(i - 1);
        }
      } catch (NumberFormatException ignored) {}
    }
    return new ArrayList<>(out);
  }

  static File pdf(Source src, List<Integer> pages, int w, int h) throws IOException {
    PdfDocument doc = new PdfDocument();
    try {
      int n = 0;
      for (int i : pages) {
        Page p = Page.load(src.annotationFile(i), w, h);
        int pw = 595, ph = Math.round(pw * (h / (float) w));
        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, ++n).create());
        Canvas c = pg.getCanvas();
        float s = pw / (float) w;
        c.scale(s, s);
        src.renderBackground(i, c, w, h);
        // Annotations were written at the page's own size; scale if the screen size changed since.
        c.save();
        if (p.w > 0 && p.w != w) { float k = w / (float) p.w; c.scale(k, k); }
        p.drawContent(c);
        c.restore();
        doc.finishPage(pg);
      }
      File f = unique(exportsDir(), safe(src.title()) + suffix(pages, src.pageCount()), ".pdf");
      try (OutputStream out = new FileOutputStream(f)) { doc.writeTo(out); }
      return f;
    } finally {
      doc.close();
    }
  }

  private static String suffix(List<Integer> pages, int count) {
    if (pages.size() == count) return "";
    if (pages.size() == 1) return " - p" + (pages.get(0) + 1);
    return " - " + pages.size() + " pages";
  }

  /** Books get each page's rendered background baked in as a PNG so the package stands alone. */
  static File slnote(Source src, List<Integer> pages, int w, int h) throws IOException {
    File f = unique(exportsDir(), safe(src.title()) + suffix(pages, src.pageCount()), ".slnote");
    try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
      Properties meta = new Properties();
      meta.setProperty("name", src.title());
      String template = "blank";
      if (src instanceof NotebookSource) {
        Notebook nb = ((NotebookSource) src).nb;
        template = nb.template;
        if (template.startsWith("img:")) {
          File t = new File(Templates.dir(), template.substring(4));
          if (t.exists()) { putFile(z, "template.png", t); template = "file:template.png"; }
          else template = "blank";
        }
      }
      meta.setProperty("template", template);
      meta.setProperty("last", "0");
      z.putNextEntry(new ZipEntry("meta.properties"));
      meta.store(z, "Slate package");
      z.closeEntry();
      int k = 0;
      for (int i : pages) {
        k++;
        String stem = String.format(Locale.US, "p%04d", k);
        File ann = src.annotationFile(i);
        if (ann.exists()) putFile(z, stem + ".bin", ann);
        else {
          File tmp = File.createTempFile("pg", ".bin", App.ctx.getCacheDir());
          new Page(w, h).save(tmp);
          putFile(z, stem + ".bin", tmp);
          tmp.delete();
        }
        File pageBg = src instanceof NotebookSource ? ((NotebookSource) src).nb.pageBg(i) : null;
        if (pageBg != null && pageBg.exists()) putFile(z, stem + ".png", pageBg);
        else if (!(src instanceof NotebookSource)) {
          Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
          src.renderBackground(i, new Canvas(b), w, h);
          z.putNextEntry(new ZipEntry(stem + ".png"));
          b.compress(Bitmap.CompressFormat.PNG, 100, z);
          z.closeEntry();
          b.recycle();
        }
      }
    }
    return f;
  }

  private static void putFile(ZipOutputStream z, String name, File f) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    try (InputStream in = new FileInputStream(f)) { Storage.pipe(in, z); }
    z.closeEntry();
  }

  /** Unpacks a .slnote into a new notebook. */
  static Notebook importPackage(InputStream in) throws IOException {
    File tmp = new File(App.ctx.getCacheDir(), "import" + System.nanoTime());
    tmp.mkdirs();
    try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(in))) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        String name = new File(e.getName()).getName(); // flatten, and no path tricks
        if (e.isDirectory() || !name.matches("meta\\.properties|template\\.png|p\\d{4}\\.(bin|png)")) continue;
        try (OutputStream out = new FileOutputStream(new File(tmp, name))) { Storage.pipe(z, out); }
      }
    }
    Properties meta = new Properties();
    File mf = new File(tmp, "meta.properties");
    if (!mf.exists()) { Storage.deleteTree(tmp); throw new IOException("Not a Slate package"); }
    try (InputStream mi = new FileInputStream(mf)) { meta.load(mi); }
    String template = meta.getProperty("template", "blank");
    if (template.startsWith("file:")) {
      File t = new File(tmp, "template.png");
      if (t.exists()) {
        File dest = unique(Templates.dir(), Storage.stripExt(safe(meta.getProperty("name", "template"))), ".png");
        Storage.copy(t, dest);
        template = "img:" + dest.getName();
      } else template = "blank";
    }
    Notebook nb = Notebook.create(meta.getProperty("name", "Imported"), Templates.normalize(template));
    File[] fs = tmp.listFiles();
    if (fs != null) for (File f : fs)
      if (f.getName().matches("p\\d{4}\\.(bin|png)")) Storage.copy(f, new File(nb.dir, f.getName()));
    Storage.deleteTree(tmp);
    return Notebook.open(nb.dir);
  }
}
