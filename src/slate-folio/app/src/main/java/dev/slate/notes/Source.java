package dev.slate.notes;

import android.content.SharedPreferences;
import android.graphics.*;
import java.io.*;
import java.util.*;

/** Anything the editor can show page by page: a notebook, or a book with an annotation layer. */
abstract class Source {
  abstract String title();
  abstract int pageCount();
  /** Changes whenever the background of page i would look different. */
  abstract String bgKey(int i, int w, int h);
  abstract void renderBackground(int i, Canvas c, int w, int h);
  abstract File annotationFile(int i);
  abstract int lastPage();
  abstract void rememberPage(int i);

  /** Heavy setup (opening, parsing, paginating). Runs off the UI thread. */
  void prepare(int w, int h) throws Exception {}
  boolean isNotebook() { return false; }
  boolean reflowable() { return false; }
  /** Plain text of a page for speech; null if the format has none. May be slow. */
  String pageText(int i) { return null; }
  /** Screen rects covering chars [start,end) of pageText(i), or null if unknown. */
  List<RectF> textRects(int i, int start, int end) { return null; }
  /** {title, page index} pairs, or null. */
  List<Object[]> chapters() { return null; }
  void close() {}

  /** A position that survives re-pagination (reflowable books override). */
  String anchor(int page) { return String.valueOf(page); }
  int pageForAnchor(String a) {
    try { return Math.max(0, Math.min(pageCount() - 1, Integer.parseInt(a))); } catch (Exception e) { return 0; }
  }
  /** Bookmark anchors, or null if this source doesn't keep bookmarks. */
  List<String> bookmarks() { return null; }
  void setBookmarks(List<String> b) {}
  /** PDF margin cropping. */
  boolean canCrop() { return false; }
  boolean cropped() { return false; }
  void setCropped(boolean on) {}

  static final String[] BOOK_EXT = {".pdf", ".epub", ".txt", ".md", ".markdown", ".html", ".htm", ".xhtml", ".fb2", ".cbz"};

  static SharedPreferences readerPrefs() { return App.ctx.getSharedPreferences("reader", 0); }

  static boolean isBook(String name) {
    String n = name.toLowerCase(Locale.US);
    for (String e : BOOK_EXT) if (n.endsWith(e)) return true;
    return false;
  }

  static final Paint IMG = new Paint(Paint.FILTER_BITMAP_FLAG);

  /** Draws b fitted and centred in w×h on white. */
  static void drawFit(Canvas c, Bitmap b, int w, int h) {
    c.drawColor(Color.WHITE);
    if (b == null) return;
    float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
    float dw = b.getWidth() * s, dh = b.getHeight() * s;
    c.drawBitmap(b, null, new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), IMG);
  }
}

/** A Slate notebook: templates plus optional per-page background images. */
final class NotebookSource extends Source {
  final Notebook nb;
  NotebookSource(Notebook nb) { this.nb = nb; }
  @Override String title() { return nb.name; }
  @Override int pageCount() { return nb.pageCount(); }
  @Override boolean isNotebook() { return true; }
  @Override File annotationFile(int i) { return nb.pageFile(i); }
  @Override int lastPage() { return nb.lastPage; }

  /** Typed text boxes are searchable. */
  @Override String pageText(int i) {
    Page p = Page.load(nb.pageFile(i), 1, 1);
    if (p.texts.isEmpty()) return null;
    StringBuilder b = new StringBuilder();
    for (TextItem t : p.texts) b.append(t.text).append('\n');
    return b.toString();
  }
  @Override void rememberPage(int i) { nb.lastPage = i; nb.saveMeta(); }

  @Override String bgKey(int i, int w, int h) {
    File bg = nb.pageBg(i);
    if (bg.exists()) return "nbpage:" + bg.getPath() + ":" + bg.lastModified() + "@" + w + "x" + h;
    return "tpl:" + nb.template + "@" + w + "x" + h;
  }

  @Override synchronized void renderBackground(int i, Canvas c, int w, int h) {
    File bg = nb.pageBg(i);
    if (bg.exists()) {
      Bitmap b = BitmapFactory.decodeFile(bg.getPath());
      drawFit(c, b, w, h);
      if (b != null) b.recycle();
    } else {
      Templates.draw(c, nb.template, w, h, 32 * App.ctx.getResources().getDisplayMetrics().density);
    }
  }
}

