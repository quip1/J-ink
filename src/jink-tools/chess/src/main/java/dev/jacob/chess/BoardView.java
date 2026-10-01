package dev.jacob.chess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The board in black, white and one grey. White pieces are drawn as a white-filled shape with a
 * black outline so they read clearly on both square colours.
 */
final class BoardView extends View {
  // Solid glyphs (black set) and outline glyphs (white set); U+FE0E asks for text, not emoji.
  private static final String[] SOLID = {"", "♟︎", "♞︎", "♝︎", "♜︎", "♛︎", "♚︎"};
  private static final String[] OUTLINE = {"", "♙︎", "♘︎", "♗︎", "♖︎", "♕︎", "♔︎"};

  Chess game;
  boolean flipped;
  int selected = -1, lastFrom = -1, lastTo = -1;
  List<Integer> targets = new ArrayList<>();
  IntConsumer onTap;

  private final Paint light = new Paint(), dark = new Paint(), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), piece = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint coord = new Paint(Paint.ANTI_ALIAS_FLAG);

  BoardView(Context c) {
    super(c);
    light.setColor(0xFFFFFFFF);
    dark.setColor(0xFFB4B4B4);
    ink.setColor(0xFF000000);
    stroke.setColor(0xFF000000);
    stroke.setStyle(Paint.Style.STROKE);
    piece.setTextAlign(Paint.Align.CENTER);
    coord.setColor(0xFF000000);
  }

  @Override protected void onMeasure(int w, int h) {
    int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
    if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) size = MeasureSpec.getSize(w);
    setMeasuredDimension(size, size);
  }

  private float cell() { return Math.min(getWidth(), getHeight()) / 8f; }

  /** Screen column/row (0..7 from top-left) for a square, honouring the flip. */
  private int col(int sq) { return flipped ? 7 - Chess.file(sq) : Chess.file(sq); }

  private int row(int sq) { return flipped ? Chess.rank(sq) : 7 - Chess.rank(sq); }

  @Override protected void onDraw(Canvas c) {
    if (game == null) return;
    float s = cell();
    piece.setTextSize(s * 0.82f);
    coord.setTextSize(s * 0.18f);
    for (int sq = 0; sq < 64; sq++) {
      float x = col(sq) * s, y = row(sq) * s;
      boolean isDark = (Chess.file(sq) + Chess.rank(sq)) % 2 == 0;
      c.drawRect(x, y, x + s, y + s, isDark ? dark : light);
      if (sq == lastFrom || sq == lastTo) {
        // Last move: corner brackets, which survive e-ink better than a tint.
        stroke.setStrokeWidth(Math.max(2, s * 0.04f));
        float k = s * 0.25f, i = s * 0.06f;
        c.drawLine(x + i, y + i, x + i + k, y + i, stroke);
        c.drawLine(x + i, y + i, x + i, y + i + k, stroke);
        c.drawLine(x + s - i, y + s - i, x + s - i - k, y + s - i, stroke);
        c.drawLine(x + s - i, y + s - i, x + s - i, y + s - i - k, stroke);
      }
      int p = game.board[sq];
      if (p != 0) {
        int t = Math.abs(p);
        float baseline = y + s * 0.8f;
        if (p > 0) {
          piece.setColor(0xFFFFFFFF);
          c.drawText(SOLID[t], x + s / 2, baseline, piece);
          piece.setColor(0xFF000000);
          c.drawText(OUTLINE[t], x + s / 2, baseline, piece);
        } else {
          piece.setColor(0xFF000000);
          c.drawText(SOLID[t], x + s / 2, baseline, piece);
        }
      }
      if (sq == selected) {
        stroke.setStrokeWidth(Math.max(3, s * 0.08f));
        c.drawRect(x + s * 0.04f, y + s * 0.04f, x + s * 0.96f, y + s * 0.96f, stroke);
      }
      if (targets.contains(sq)) {
        if (p != 0) {
          stroke.setStrokeWidth(Math.max(2, s * 0.05f));
          c.drawCircle(x + s / 2, y + s / 2, s * 0.44f, stroke);
        } else {
          c.drawCircle(x + s / 2, y + s / 2, s * 0.12f, ink);
        }
      }
      if (row(sq) == 7) c.drawText(String.valueOf((char) ('a' + Chess.file(sq))), x + s * 0.82f, y + s * 0.95f, coord);
      if (col(sq) == 0) c.drawText(String.valueOf((char) ('1' + Chess.rank(sq))), x + s * 0.05f, y + s * 0.2f, coord);
    }
    stroke.setStrokeWidth(Math.max(2, s * 0.03f));
    c.drawRect(0, 0, 8 * s, 8 * s, stroke);
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() != MotionEvent.ACTION_UP) return true;
    float s = cell();
    int col = (int) (e.getX() / s), row = (int) (e.getY() / s);
    if (col >= 0 && col < 8 && row >= 0 && row < 8 && onTap != null) {
      int file = flipped ? 7 - col : col, rank = flipped ? row : 7 - row;
      onTap.accept(rank * 8 + file);
    }
    performClick();
    return true;
  }

  @Override public boolean performClick() { return super.performClick(); }
}
