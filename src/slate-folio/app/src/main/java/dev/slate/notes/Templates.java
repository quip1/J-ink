package dev.slate.notes;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Page backgrounds: four built-ins plus imported images and PDF pages. */
final class Templates {
  static final String[] BUILTIN = {"blank", "lined", "grid", "dots"};
  private static final String[] BUILTIN_NAMES = {"Blank", "Lined", "Grid", "Dots"};
  private static final Paint RULE = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final Paint IMG = new Paint(Paint.FILTER_BITMAP_FLAG);
  static { RULE.setColor(0xFFB0B0B0); RULE.setStrokeWidth(1.5f); }

  private static String cachedKey;
  private static Bitmap cachedBmp;

  static File dir() { return App.dir("Templates"); }

  /** Old notebooks stored the template as a number. */
  static String normalize(String s) {
    if (s == null) return "blank";
    s = s.trim();
    if (s.matches("\\d")) { int i = Integer.parseInt(s); return i < BUILTIN.length ? BUILTIN[i] : "blank"; }
    return s.isEmpty() ? "blank" : s;
  }

  static List<String> keys() {
    List<String> out = new ArrayList<>();
    for (String b : BUILTIN) out.add(b);
    File[] fs = dir().listFiles((d, n) -> n.endsWith(".png"));
    if (fs != null) {
      java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      for (File f : fs) out.add("img:" + f.getName());
    }
    return out;
  }

  static String name(String key) {
    for (int i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].equals(key)) return BUILTIN_NAMES[i];
    if (key.startsWith("img:")) { String n = key.substring(4); return n.endsWith(".png") ? n.substring(0, n.length() - 4) : n; }
    return key;
  }

  static void draw(Canvas c, String key, int w, int h, float spacing) {
    c.drawColor(Color.WHITE);
    if (key.startsWith("img:")) {
      Bitmap b = image(key);
      if (b == null) return;
      // Fit inside the page, centred, keeping aspect ratio.
      float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
      float dw = b.getWidth() * s, dh = b.getHeight() * s;
      RectF dst = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
      c.drawBitmap(b, null, dst, IMG);
      return;
    }
    switch (key) {
      case "lined":
        for (float y = spacing * 2; y < h; y += spacing) c.drawLine(0, y, w, y, RULE);
        break;
      case "grid":
        for (float y = spacing; y < h; y += spacing) c.drawLine(0, y, w, y, RULE);
        for (float x = spacing; x < w; x += spacing) c.drawLine(x, 0, x, h, RULE);
        break;
      case "dots":
        RULE.setStrokeWidth(4f); RULE.setStrokeCap(Paint.Cap.ROUND);
        for (float y = spacing; y < h; y += spacing)
          for (float x = spacing; x < w; x += spacing) c.drawPoint(x, y, RULE);
        RULE.setStrokeWidth(1.5f); RULE.setStrokeCap(Paint.Cap.BUTT);
        break;
      default: break;
    }
  }

  private static synchronized Bitmap image(String key) {
    if (key.equals(cachedKey) && cachedBmp != null) return cachedBmp;
    Bitmap b = BitmapFactory.decodeFile(new File(dir(), key.substring(4)).getPath());
    cachedKey = key; cachedBmp = b;
    return b;
  }

  /** Imports an image, or the first page of a PDF, as a PNG template. Returns its key. */
  static String importUri(Context ctx, Uri uri) throws IOException {
    String name = displayName(ctx, uri);
    String type = ctx.getContentResolver().getType(uri);
    boolean pdf = "application/pdf".equals(type) || name.toLowerCase().endsWith(".pdf");
    Bitmap out;
    if (pdf) {
      try (ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor(uri, "r");
           PdfRenderer r = new PdfRenderer(fd)) {
        if (r.getPageCount() == 0) throw new IOException("PDF has no pages");
        try (PdfRenderer.Page p = r.openPage(0)) {
          int w = 1872, h = Math.round(w * p.getHeight() / (float) p.getWidth());
          out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
          out.eraseColor(Color.WHITE);
          p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }
      }
    } else {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (InputStream in = ctx.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
      int sample = 1;
      while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2480) sample *= 2;
      o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      try (InputStream in = ctx.getContentResolver().openInputStream(uri)) { out = BitmapFactory.decodeStream(in, null, o); }
      if (out == null) throw new IOException("Not a readable image");
    }
    String base = name.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
    if (base.isEmpty()) base = "template";
    File f = new File(dir(), base + ".png");
    for (int i = 2; f.exists(); i++) f = new File(dir(), base + " " + i + ".png");
    try (OutputStream os = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, os); }
    out.recycle();
    return "img:" + f.getName();
  }

  static String displayName(Context ctx, Uri uri) {
    try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
    } catch (Exception ignored) {}
    String p = uri.getLastPathSegment();
    return p == null ? "file" : p.substring(p.lastIndexOf('/') + 1);
  }
}
