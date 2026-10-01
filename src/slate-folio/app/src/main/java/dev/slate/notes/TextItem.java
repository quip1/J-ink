package dev.slate.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** A typed text box. Wraps at width w, drawn with the same metrics as the live editor. */
final class TextItem {
  final float x, y, w, size;
  final int color;
  final String font, text;
  private StaticLayout layout;

  TextItem(float x, float y, float w, float size, int color, String font, String text) {
    this.x = x; this.y = y; this.w = w; this.size = size; this.color = color; this.font = font; this.text = text;
  }

  static TextPaint paint(float size, int color, String font) {
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    p.setTextSize(size);
    p.setColor(color);
    p.setTypeface(Fonts.typeface(font));
    return p;
  }

  StaticLayout layout() {
    if (layout == null) {
      layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint(size, color, font), Math.max(1, (int) w))
          .setAlignment(Layout.Alignment.ALIGN_NORMAL)
          .setIncludePad(true)
          .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
          .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
          .build();
    }
    return layout;
  }

  TextItem moved(float dx, float dy) { return new TextItem(x + dx, y + dy, w, size, color, font, text); }
  TextItem recolored(int c) { return new TextItem(x, y, w, size, c, font, text); }

  RectF bounds() { return new RectF(x, y, x + w, y + layout().getHeight()); }

  boolean contains(float px, float py, float pad) {
    RectF b = bounds();
    return px >= b.left - pad && px <= b.right + pad && py >= b.top - pad && py <= b.bottom + pad;
  }

  void draw(Canvas c) {
    c.save();
    c.translate(x, y);
    layout().draw(c);
    c.restore();
  }
}
