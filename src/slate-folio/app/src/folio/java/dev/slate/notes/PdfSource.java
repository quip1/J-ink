package dev.slate.notes;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.util.*;

final class PdfSource extends BookSource {
  private ParcelFileDescriptor fd;
  private PdfRenderer renderer;
  private PDDocument textDoc;   // opened lazily, only for speech
  private int count;
  private boolean crop;
  private final Map<Integer, RectF> boxes = new HashMap<>();   // content box per page, in page units

  PdfSource(File f) {
    super(f);
    crop = "1".equals(meta.getProperty("crop"));
  }

  @Override boolean canCrop() { return true; }
  @Override boolean cropped() { return crop; }
  @Override void setCropped(boolean on) { crop = on; meta.setProperty("crop", on ? "1" : "0"); saveMeta(); }
  /** Cropped pages have a different geometry, so their notes live in their own folder. */
  @Override File annDir() { return crop ? new File(annRoot, "crop") : annRoot; }

  /** Finds the inked area of a page from a small render; null means "use the whole page". */
  private RectF contentBox(PdfRenderer.Page p, int i) {
    if (boxes.containsKey(i)) return boxes.get(i);
    int sw = 240, sh = Math.max(1, Math.round(sw * p.getHeight() / (float) p.getWidth()));
    Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
    b.eraseColor(Color.WHITE);
    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    int[] px = new int[sw * sh];
    b.getPixels(px, 0, sw, 0, 0, sw, sh);
    b.recycle();
    int minX = sw, minY = sh, maxX = -1, maxY = -1;
    for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
      int c = px[y * sw + x];
      int lum = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
      if (lum < 225) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; }
    }
    RectF r = null;
    if (maxX > minX && maxY > minY) {
      float pad = 0.015f;
      float l = Math.max(0, minX / (float) sw - pad), t = Math.max(0, minY / (float) sh - pad);
      float rr = Math.min(1, (maxX + 1) / (float) sw + pad), bb = Math.min(1, (maxY + 1) / (float) sh + pad);
      if ((rr - l) > 0.2f && (bb - t) > 0.2f) r = new RectF(l * p.getWidth(), t * p.getHeight(), rr * p.getWidth(), bb * p.getHeight());
    }
    boxes.put(i, r);
    return r;
  }

  @Override synchronized void prepare(int w, int h) throws Exception {
    if (renderer != null) return;
    fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    renderer = new PdfRenderer(fd);
    count = renderer.getPageCount();
  }

  @Override int pageCount() { return Math.max(1, count); }

  @Override String bgKey(int i, int w, int h) { return "pdf:" + file.getPath() + (crop ? ":c" : "") + "#" + i + "@" + w + "x" + h; }

  @Override synchronized void renderBackground(int i, Canvas c, int w, int h) {
    c.drawColor(Color.WHITE);
    if (renderer == null || i >= count) return;
    try (PdfRenderer.Page p = renderer.openPage(i)) {
      RectF box = crop ? contentBox(p, i) : null;
      if (box == null) box = new RectF(0, 0, p.getWidth(), p.getHeight());
      float s = Math.min(w / box.width(), h / box.height());
      int bw = Math.max(1, Math.round(box.width() * s)), bh = Math.max(1, Math.round(box.height() * s));
      Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
      b.eraseColor(Color.WHITE);
      Matrix m = new Matrix();
      m.postScale(s, s);
      m.postTranslate(-box.left * s, -box.top * s);
      p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
      c.drawBitmap(b, (w - bw) / 2f, (h - bh) / 2f, IMG);
      b.recycle();
    }
  }

  @Override synchronized String pageText(int i) {
    try {
      if (textDoc == null) textDoc = PDDocument.load(file);
      PDFTextStripper st = new PDFTextStripper();
      st.setStartPage(i + 1);
      st.setEndPage(i + 1);
      return st.getText(textDoc).replaceAll("-\\n(?=\\p{Ll})", "").replaceAll("(?<!\\n)\\n(?!\\n)", " ");
    } catch (Throwable e) {
      return null;
    }
  }

  @Override synchronized void close() {
    try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
    try { if (fd != null) fd.close(); } catch (Exception ignored) {}
    try { if (textDoc != null) textDoc.close(); } catch (Exception ignored) {}
    renderer = null;
  }
}
