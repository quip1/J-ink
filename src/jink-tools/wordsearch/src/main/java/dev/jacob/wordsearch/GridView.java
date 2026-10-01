package dev.jacob.wordsearch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.BiConsumer;

/** Letter grid. Found words get an outlined capsule; the first tapped letter is shown inverted. */
final class GridView extends View {
  Puzzle puzzle;
  int pickX = -1, pickY = -1;
  BiConsumer<Integer, Integer> onTap;

  private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG), inner = new Paint(Paint.ANTI_ALIAS_FLAG);

  GridView(Context c) {
    super(c);
    letters.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    letters.setTextAlign(Paint.Align.CENTER);
    ink.setColor(0xFF000000);
    outline.setColor(0xFF000000);
    outline.setStrokeCap(Paint.Cap.ROUND);
    inner.setColor(0xFFFFFFFF);
    inner.setStrokeCap(Paint.Cap.ROUND);
  }

  @Override protected void onMeasure(int w, int h) {
    int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
    if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) size = MeasureSpec.getSize(w);
    setMeasuredDimension(size, size);
  }

  private float cell() { return Math.min(getWidth(), getHeight()) / (float) puzzle.size; }

  @Override protected void onDraw(Canvas c) {
    if (puzzle == null) return;
    float s = cell();
    outline.setStrokeWidth(s * 0.82f);
    inner.setStrokeWidth(s * 0.82f - Math.max(4, s * 0.12f));
    // Two passes so overlapping capsules don't erase each other's outlines.
    for (Puzzle.Placement p : puzzle.words) if (p.found) capsule(c, p, s, outline);
    for (Puzzle.Placement p : puzzle.words) if (p.found) capsule(c, p, s, inner);
    letters.setTextSize(s * 0.6f);
    for (int y = 0; y < puzzle.size; y++) {
      for (int x = 0; x < puzzle.size; x++) {
        boolean picked = x == pickX && y == pickY;
        if (picked) c.drawRect(x * s, y * s, (x + 1) * s, (y + 1) * s, ink);
        letters.setColor(picked ? 0xFFFFFFFF : 0xFF000000);
        c.drawText(String.valueOf(puzzle.grid[y][x]), x * s + s / 2, y * s + s * 0.72f, letters);
      }
    }
  }

  private void capsule(Canvas c, Puzzle.Placement p, float s, Paint paint) {
    c.drawLine(p.x * s + s / 2, p.y * s + s / 2, p.endX() * s + s / 2, p.endY() * s + s / 2, paint);
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() != MotionEvent.ACTION_UP || puzzle == null) return true;
    float s = cell();
    int x = (int) (e.getX() / s), y = (int) (e.getY() / s);
    if (x >= 0 && x < puzzle.size && y >= 0 && y < puzzle.size && onTap != null) onTap.accept(x, y);
    performClick();
    return true;
  }

  @Override public boolean performClick() { return super.performClick(); }
}
