package dev.slate.notes;

import android.graphics.Canvas;
import android.graphics.Color;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** A page's ink and text plus the pixel size it was written at. */
final class Page {
  private static final int V1 = 0x534C5431, V2 = 0x534C5432, V3 = 0x534C5433; // "SLT1".."SLT3"
  int w, h;
  final List<Stroke> strokes = new ArrayList<>();
  final List<TextItem> texts = new ArrayList<>();

  Page(int w, int h) { this.w = w; this.h = h; }

  boolean isEmpty() { return strokes.isEmpty() && texts.isEmpty(); }

  void drawContent(Canvas c) { drawContent(c, null, null); }

  /** Draws everything except skip, and (if clip is set) only items that touch clip. */
  void drawContent(Canvas c, java.util.Set<Object> skip, android.graphics.RectF clip) {
    for (Stroke s : strokes) {
      if (skip != null && skip.contains(s)) continue;
      if (clip != null && !android.graphics.RectF.intersects(s.inkBounds(), clip)) continue;
      s.draw(c);
    }
    for (TextItem t : texts) {
      if (skip != null && skip.contains(t)) continue;
      if (clip != null && !android.graphics.RectF.intersects(t.bounds(), clip)) continue;
      t.draw(c);
    }
  }

  /** A copy that can be saved on another thread while editing continues. */
  Page snapshot() {
    Page p = new Page(w, h);
    p.strokes.addAll(strokes);
    p.texts.addAll(texts);
    return p;
  }

  static Page load(File f, int defW, int defH) {
    if (!f.exists()) return new Page(defW, defH);
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
      int magic = in.readInt();
      if (magic != V1 && magic != V2 && magic != V3) return new Page(defW, defH);
      Page p = new Page(in.readInt(), in.readInt());
      int n = in.readInt();
      for (int i = 0; i < n; i++) {
        float width = in.readFloat();
        int color = magic == V1 ? Color.BLACK : in.readInt();
        int m = in.readInt();
        float[] pts = new float[m];
        for (int j = 0; j < m; j++) pts[j] = in.readFloat();
        float[] wm = null;
        if (magic == V3 && in.readBoolean()) {
          wm = new float[m / 2];
          for (int j = 0; j < wm.length; j++) wm[j] = in.readFloat();
        }
        p.strokes.add(new Stroke(width, color, pts, wm));
      }
      if (magic != V1) {
        int t = in.readInt();
        for (int i = 0; i < t; i++) {
          float x = in.readFloat(), y = in.readFloat(), w = in.readFloat(), size = in.readFloat();
          int color = in.readInt();
          String font = in.readUTF(), text = in.readUTF();
          p.texts.add(new TextItem(x, y, w, size, color, font, text));
        }
      }
      return p;
    } catch (IOException e) {
      return new Page(defW, defH);
    }
  }

  void save(File f) throws IOException {
    File tmp = new File(f.getPath() + ".tmp");
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
      out.writeInt(V3); out.writeInt(w); out.writeInt(h);
      out.writeInt(strokes.size());
      for (Stroke s : strokes) {
        out.writeFloat(s.width);
        out.writeInt(s.color);
        out.writeInt(s.pts.length);
        for (float v : s.pts) out.writeFloat(v);
        out.writeBoolean(s.wm != null);
        if (s.wm != null) for (float v : s.wm) out.writeFloat(v);
      }
      out.writeInt(texts.size());
      for (TextItem t : texts) {
        out.writeFloat(t.x); out.writeFloat(t.y); out.writeFloat(t.w); out.writeFloat(t.size);
        out.writeInt(t.color);
        out.writeUTF(t.font);
        out.writeUTF(t.text);
      }
    }
    if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("rename failed"); }
  }
}
