package dev.jacob.sudoku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.IntConsumer;

/** Draws the grid in pure black and white, plus one light grey for the selected cell. */
final class BoardView extends View {
  int[] givens = new int[81], values = new int[81], notes = new int[81];
  boolean[] wrong = new boolean[81];
  int selected = -1;
  IntConsumer onTap;

  private final Paint thin = new Paint(), thick = new Paint(), fill = new Paint();
  private final Paint given = new Paint(Paint.ANTI_ALIAS_FLAG), entry = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);

  BoardView(Context c) {
    super(c);
    thin.setColor(0xFF000000);
    thick.setColor(0xFF000000);
    fill.setColor(0xFFC8C8C8);
    given.setTypeface(Typeface.DEFAULT_BOLD);
    given.setTextAlign(Paint.Align.CENTER);
    entry.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
    entry.setTextAlign(Paint.Align.CENTER);
    note.setTextAlign(Paint.Align.CENTER);
  }

  @Override protected void onMeasure(int w, int h) {
    int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
    if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) size = MeasureSpec.getSize(w);
    setMeasuredDimension(size, size);
  }

  @Override protected void onDraw(Canvas c) {
    float size = Math.min(getWidth(), getHeight());
    float pad = Math.max(2, size * 0.006f);
    float cell = (size - 2 * pad) / 9;
    float x0 = pad, y0 = pad;
    int sel = selected >= 0 ? (values[selected] != 0 ? values[selected] : 0) : 0;

    if (selected >= 0) {
      float sx = x0 + Sudoku.col(selected) * cell, sy = y0 + Sudoku.row(selected) * cell;
      c.drawRect(sx, sy, sx + cell, sy + cell, fill);
    }
    given.setTextSize(cell * 0.62f);
    entry.setTextSize(cell * 0.62f);
    note.setTextSize(cell * 0.27f);
    for (int i = 0; i < 81; i++) {
      float cx = x0 + Sudoku.col(i) * cell + cell / 2, cy = y0 + Sudoku.row(i) * cell + cell / 2;
      int v = values[i];
      if (v != 0) {
        Paint p = givens[i] != 0 ? given : entry;
        // The same digit as the selected cell is underlined, to spot it across the grid.
        c.drawText(String.valueOf(v), cx, cy + cell * 0.22f, p);
        if (sel != 0 && v == sel && i != selected) {
          c.drawRect(cx - cell * 0.18f, cy + cell * 0.3f, cx + cell * 0.18f, cy + cell * 0.34f, thick);
        }
        if (wrong[i]) {
          thin.setStrokeWidth(Math.max(2, cell * 0.05f));
          c.drawLine(cx - cell * 0.32f, cy + cell * 0.32f, cx + cell * 0.32f, cy - cell * 0.32f, thin);
        }
      } else if (notes[i] != 0) {
        for (int d = 1; d <= 9; d++) {
          if ((notes[i] & (1 << d)) == 0) continue;
          float nx = cx + ((d - 1) % 3 - 1) * cell * 0.3f, ny = cy + ((d - 1) / 3 - 1) * cell * 0.3f + cell * 0.1f;
          c.drawText(String.valueOf(d), nx, ny, note);
        }
      }
    }
    for (int i = 0; i <= 9; i++) {
      Paint p = i % 3 == 0 ? thick : thin;
      p.setStrokeWidth(i % 3 == 0 ? Math.max(3, cell * 0.06f) : Math.max(1, cell * 0.015f));
      c.drawLine(x0 + i * cell, y0, x0 + i * cell, y0 + 9 * cell, p);
      c.drawLine(x0, y0 + i * cell, x0 + 9 * cell, y0 + i * cell, p);
    }
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() != MotionEvent.ACTION_UP) return true;
    float size = Math.min(getWidth(), getHeight());
    float pad = Math.max(2, size * 0.006f);
    float cell = (size - 2 * pad) / 9;
    int col = (int) ((e.getX() - pad) / cell), row = (int) ((e.getY() - pad) / cell);
    if (col >= 0 && col < 9 && row >= 0 && row < 9 && onTap != null) onTap.accept(row * 9 + col);
    performClick();
    return true;
  }

  @Override public boolean performClick() { return super.performClick(); }
}
