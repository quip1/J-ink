package dev.slate.notes;

import android.content.SharedPreferences;
import android.graphics.*;
import android.text.*;
import java.io.*;
import java.util.*;

/**
 * EPUB, FB2, TXT, Markdown and HTML, laid out into pages with the reader's settings.
 * The page table is cached per layout, so reopening a book doesn't re-lay-out every
 * chapter; only the chapters actually on screen are parsed and laid out, a few at a time.
 */
final class ReflowSource extends BookSource {
  static final float[] SPACINGS = {1.0f, 1.2f, 1.4f, 1.6f};
  static final int[] MARGINS_DP = {16, 32, 56};
  private static final int CACHE_VERSION = 2;

  private Formats.Book book;
  private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint footer = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private float spacingMul = 1.2f;
  private int w, h, margin, top, bottom, textW;
  private String sig = "";

  // page table: chapter, first line, end line (exclusive), first char, end char
  private int[] pgCh = new int[0], pgL0 = new int[0], pgL1 = new int[0], pgC0 = new int[0], pgC1 = new int[0];
  private String[] titles = new String[0];

  private final LinkedHashMap<Integer, StaticLayout> layouts = new LinkedHashMap<Integer, StaticLayout>(4, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Integer, StaticLayout> e) { return size() > 3; }
  };

  ReflowSource(File f) { super(f); }

  @Override boolean reflowable() { return true; }
  @Override File annDir() { return new File(annRoot, sig); }

  @Override synchronized void prepare(int width, int height) throws Exception {
    float d = App.ctx.getResources().getDisplayMetrics().density;
    SharedPreferences p = readerPrefs();
    String font = p.getString("font", "serif");
    int sizeSp = p.getInt("size", 20);
    int marginIdx = Math.max(0, Math.min(2, p.getInt("margin", 1)));
    int spacingIdx = Math.max(0, Math.min(3, p.getInt("spacing", 1)));

    w = width; h = height;
    margin = Math.round(MARGINS_DP[marginIdx] * d);
    top = Math.round(64 * d);
    bottom = Math.round(40 * d);
    textW = Math.max(100, w - 2 * margin);
    Formats.maxImgW = textW;
    Formats.maxImgH = Math.max(100, Math.round((h - top - bottom) * 0.95f));
    if (book == null) book = Formats.open(file);

    paint.setColor(Color.BLACK);
    paint.setTypeface(Fonts.typeface(font));
    paint.setTextSize(sizeSp * d);
    spacingMul = SPACINGS[spacingIdx];
    footer.setColor(0xFF666666);
    footer.setTextSize(13 * d);
    footer.setTypeface(Typeface.SANS_SERIF);
    layouts.clear();

    sig = "L" + Integer.toHexString(Objects.hash(font, sizeSp, marginIdx, spacingIdx, w, h));
    if (!loadTable()) paginate();
  }

  private StaticLayout layout(int ch) {
    StaticLayout L = layouts.get(ch);
    if (L != null) return L;
    CharSequence t = book.text(ch);
    // Simple line breaking without hyphenation: several times faster, and ragged-right text doesn't need it.
    L = StaticLayout.Builder.obtain(t, 0, t.length(), paint, textW)
        .setLineSpacing(0, spacingMul)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build();
    layouts.put(ch, L);
    return L;
  }

  private void paginate() {
    int pageH = h - top - bottom;
    List<int[]> pages = new ArrayList<>();
    titles = new String[book.size()];
    for (int c = 0; c < book.size(); c++) {
      StaticLayout L = layout(c);
      String toc = book.tocTitle(c);
      titles[c] = toc != null ? toc : Formats.titleOf(L.getText(), "Section " + (c + 1));
      int n = L.getLineCount(), s = 0;
      if (L.getText().length() == 0) continue;
      while (s < n) {
        int t0 = L.getLineTop(s), e = s;
        while (e < n && L.getLineBottom(e) - t0 <= pageH) e++;
        if (e == s) e = s + 1;
        pages.add(new int[]{c, s, e, L.getLineStart(s), L.getLineEnd(e - 1)});
        s = e;
      }
    }
    int n = pages.size();
    pgCh = new int[n]; pgL0 = new int[n]; pgL1 = new int[n]; pgC0 = new int[n]; pgC1 = new int[n];
    for (int i = 0; i < n; i++) {
      int[] x = pages.get(i);
      pgCh[i] = x[0]; pgL0[i] = x[1]; pgL1[i] = x[2]; pgC0[i] = x[3]; pgC1[i] = x[4];
    }
    saveTable();
  }

  private File tableFile() { return new File(annRoot, "pages-" + sig + ".bin"); }

  private void saveTable() {
    annRoot.mkdirs();
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tableFile())))) {
      out.writeInt(CACHE_VERSION);
      out.writeLong(file.length());
      out.writeLong(file.lastModified());
      out.writeInt(titles.length);
      for (String t : titles) out.writeUTF(t == null ? "" : t);
      out.writeInt(pgCh.length);
      for (int i = 0; i < pgCh.length; i++) {
        out.writeInt(pgCh[i]); out.writeInt(pgL0[i]); out.writeInt(pgL1[i]); out.writeInt(pgC0[i]); out.writeInt(pgC1[i]);
      }
    } catch (IOException ignored) {}
  }

  private boolean loadTable() {
    File f = tableFile();
    if (!f.exists()) return false;
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
      if (in.readInt() != CACHE_VERSION || in.readLong() != file.length() || in.readLong() != file.lastModified()) return false;
      int nt = in.readInt();
      if (nt != book.size()) return false;
      titles = new String[nt];
      for (int i = 0; i < nt; i++) titles[i] = in.readUTF();
      int n = in.readInt();
      pgCh = new int[n]; pgL0 = new int[n]; pgL1 = new int[n]; pgC0 = new int[n]; pgC1 = new int[n];
      for (int i = 0; i < n; i++) {
        pgCh[i] = in.readInt(); pgL0[i] = in.readInt(); pgL1[i] = in.readInt(); pgC0[i] = in.readInt(); pgC1[i] = in.readInt();
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Override int pageCount() { return Math.max(1, pgCh.length); }

  @Override String bgKey(int i, int w, int h) { return "flow:" + file.getPath() + ":" + sig + "#" + i; }

  @Override synchronized void renderBackground(int i, Canvas c, int cw, int ch) {
    c.drawColor(Color.WHITE);
    if (book == null || i >= pgCh.length) return;
    StaticLayout L = layout(pgCh[i]);
    int s = Math.min(pgL0[i], L.getLineCount() - 1), e = Math.min(pgL1[i], L.getLineCount());
    int t0 = L.getLineTop(s), b0 = L.getLineBottom(e - 1);
    c.save();
    c.translate(margin, top - t0);
    c.clipRect(0, t0, L.getWidth(), b0);
    L.draw(c);
    c.restore();
    String title = titles[pgCh[i]];
    String num = (i + 1) + " / " + pgCh.length;
    float y = ch - bottom / 2f + footer.getTextSize() / 3f;
    float numW = footer.measureText(num);
    CharSequence t = TextUtils.ellipsize(title == null ? "" : title, footer, Math.max(0, cw - 2 * margin - numW - 24), TextUtils.TruncateAt.END);
    c.drawText(t, 0, t.length(), margin, y, footer);
    c.drawText(num, cw - margin - numW, y, footer);
  }

  @Override synchronized String pageText(int i) {
    if (book == null || i >= pgCh.length) return null;
    CharSequence t = book.text(pgCh[i]);
    int a = Math.min(pgC0[i], t.length()), b = Math.min(pgC1[i], t.length());
    return t.subSequence(a, b).toString().replace('\uFFFC', ' ');
  }

  @Override synchronized List<RectF> textRects(int i, int start, int end) {
    if (book == null || i >= pgCh.length || end <= start) return null;
    StaticLayout L = layout(pgCh[i]);
    int base = pgC0[i];
    int l0 = Math.max(L.getLineForOffset(base + start), pgL0[i]);
    int l1 = Math.min(L.getLineForOffset(base + end - 1), pgL1[i] - 1);
    int t0 = L.getLineTop(pgL0[i]);
    List<RectF> out = new ArrayList<>();
    for (int l = l0; l <= l1; l++)
      out.add(new RectF(margin + L.getLineLeft(l), top + L.getLineTop(l) - t0, margin + L.getLineRight(l), top + L.getLineBottom(l) - t0));
    return out;
  }

  @Override List<Object[]> chapters() {
    List<Object[]> out = new ArrayList<>();
    int last = -1;
    for (int i = 0; i < pgCh.length; i++)
      if (pgCh[i] != last) { last = pgCh[i]; out.add(new Object[]{titles[last], i}); }
    return out;
  }

  int pageFor(int ch, int off) {
    for (int i = 0; i < pgCh.length; i++) if (pgCh[i] == ch && pgC1[i] > off) return i;
    for (int i = pgCh.length - 1; i >= 0; i--) if (pgCh[i] <= ch) return i;
    return 0;
  }

  @Override String anchor(int i) {
    if (i >= pgCh.length) return "0:0";
    return pgCh[i] + ":" + pgC0[i];
  }

  @Override int pageForAnchor(String a) {
    try { String[] x = a.split(":"); return pageFor(Integer.parseInt(x[0]), Integer.parseInt(x[1])); }
    catch (Exception e) { return 0; }
  }

  @Override int lastPage() {
    String ch = meta.getProperty("chapter"), off = meta.getProperty("offset");
    return ch == null ? 0 : pageForAnchor(ch + ":" + (off == null ? "0" : off));
  }

  @Override void rememberPage(int i) {
    if (i < pgCh.length) {
      meta.setProperty("chapter", String.valueOf(pgCh[i]));
      meta.setProperty("offset", String.valueOf(pgC0[i]));
    }
    super.rememberPage(i);
  }

  @Override synchronized void close() { if (book != null) book.close(); layouts.clear(); }
}
