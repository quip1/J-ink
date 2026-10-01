package dev.slate.notes;

import android.graphics.*;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Comic archives: one image per page, in natural name order. */
final class CbzSource extends BookSource {
  private ZipFile zip;
  private final List<ZipEntry> pages = new ArrayList<>();

  CbzSource(File f) { super(f); }

  @Override synchronized void prepare(int w, int h) throws Exception {
    if (zip != null) return;
    zip = new ZipFile(file);
    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
      ZipEntry z = e.nextElement();
      String n = z.getName().toLowerCase(Locale.US);
      if (!z.isDirectory() && !n.contains("__macosx") && (n.endsWith(".jpg") || n.endsWith(".jpeg")
          || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif"))) pages.add(z);
    }
    pages.sort((a, b) -> natural(a.getName(), b.getName()));
  }

  static int natural(String a, String b) {
    String[] pa = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)"), pb = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
    for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
      int c;
      if (pa[i].matches("\\d+") && pb[i].matches("\\d+")) c = Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]));
      else c = pa[i].compareToIgnoreCase(pb[i]);
      if (c != 0) return c;
    }
    return Integer.compare(pa.length, pb.length);
  }

  @Override int pageCount() { return Math.max(1, pages.size()); }
  @Override String bgKey(int i, int w, int h) { return "cbz:" + file.getPath() + "#" + i + "@" + w + "x" + h; }

  @Override synchronized void renderBackground(int i, Canvas c, int w, int h) {
    if (zip == null || i >= pages.size()) { c.drawColor(Color.WHITE); return; }
    try {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (InputStream in = zip.getInputStream(pages.get(i))) { BitmapFactory.decodeStream(in, null, o); }
      int sample = 1;
      while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
      o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      Bitmap b;
      try (InputStream in = zip.getInputStream(pages.get(i))) { b = BitmapFactory.decodeStream(in, null, o); }
      drawFit(c, b, w, h);
      if (b != null) b.recycle();
    } catch (Exception e) {
      c.drawColor(Color.WHITE);
    }
  }

  @Override synchronized void close() {
    try { if (zip != null) zip.close(); } catch (Exception ignored) {}
  }
}
