package dev.jacob.solitaire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;
import java.util.function.Consumer;

/** Draws the Klondike table and turns taps into {@link Target}s. No dragging: it smears on e-ink. */
final class TableView extends View {
  enum Area { STOCK, WASTE, FOUNDATION, TABLEAU }

  static final class Target {
    final Area area;
    /** Foundation or tableau pile number. */
    final int pile;
    /** Card index within a tableau pile, or -1 for the empty space. */
    final int index;
    Target(Area area, int pile, int index) { this.area = area; this.pile = pile; this.index = index; }
    boolean same(Target o) { return o != null && o.area == area && o.pile == pile && o.index == index; }
  }

  Klondike game;
  Target selected;
  Consumer<Target> onTap;

  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), paper = new Paint(), ink = new Paint();
  private final Paint back = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG), pip = new Paint(Paint.ANTI_ALIAS_FLAG);
  private float cw, ch, gap, tableauTop;

  TableView(Context c) {
    super(c);
    line.setStyle(Paint.Style.STROKE);
    line.setColor(0xFF000000);
    paper.setColor(0xFFFFFFFF);
    ink.setColor(0xFF000000);
    back.setColor(0xFF8A8A8A);
    label.setTypeface(Typeface.DEFAULT_BOLD);
    pip.setTextAlign(Paint.Align.CENTER);
  }

  private void measure() {
    gap = getWidth() * 0.015f;
    cw = (getWidth() - 8 * gap) / 7;
    ch = cw * 1.4f;
    tableauTop = gap * 2 + ch;
    line.setStrokeWidth(Math.max(2, cw * 0.025f));
  }

  private float colX(int col) { return gap + col * (cw + gap); }

  /** Vertical offsets for face-down and face-up cards in a pile, squeezed to fit the screen. */
  private float[] offsets(int pile) {
    List<Integer> p = game.tableau[pile];
    int down = Math.min(game.hidden[pile], p.size()), up = p.size() - down;
    float d = ch * 0.12f, u = ch * 0.3f;
    float need = down * d + Math.max(0, up - 1) * u + ch, room = getHeight() - tableauTop - gap;
    if (need > room && need > ch) {
      float scale = (room - ch) / (need - ch);
      d *= scale;
      u *= scale;
    }
    return new float[]{d, u};
  }

  private float cardY(int pile, int index) {
    float[] o = offsets(pile);
    int down = game.hidden[pile];
    float y = tableauTop;
    for (int i = 0; i < index; i++) y += i < down ? o[0] : o[1];
    return y;
  }

  @Override protected void onDraw(Canvas c) {
    if (game == null) return;
    measure();
    // Stock: a card back while it has cards, an outlined "↻" when it can be turned over.
    if (!game.stock.isEmpty()) drawBack(c, colX(0), gap);
    else slot(c, colX(0), gap, game.waste.isEmpty() ? "" : "↻");

    // Waste: show the last few cards fanned when drawing three.
    int show = Math.min(game.waste.size(), game.drawCount == 3 ? 3 : 1);
    if (show == 0) slot(c, colX(1), gap, "");
    for (int i = 0; i < show; i++) {
      int card = game.waste.get(game.waste.size() - show + i);
      boolean sel = i == show - 1 && selected != null && selected.area == Area.WASTE;
      drawCard(c, colX(1) + i * cw * 0.35f, gap, card, sel);
    }

    for (int f = 0; f < 4; f++) {
      int top = Klondike.top(game.foundations[f]);
      if (top < 0) slot(c, colX(3 + f), gap, "A");
      else drawCard(c, colX(3 + f), gap, top, selected != null && selected.area == Area.FOUNDATION && selected.pile == f);
    }

    for (int p = 0; p < 7; p++) {
      List<Integer> pile = game.tableau[p];
      if (pile.isEmpty()) { slot(c, colX(p), tableauTop, "K"); continue; }
      for (int i = 0; i < pile.size(); i++) {
        float y = cardY(p, i);
        if (i < game.hidden[p]) drawBack(c, colX(p), y);
        else {
          boolean sel = selected != null && selected.area == Area.TABLEAU && selected.pile == p && i >= selected.index;
          drawCard(c, colX(p), y, pile.get(i), sel);
        }
      }
    }
  }

  private void slot(Canvas c, float x, float y, String hint) {
    RectF r = new RectF(x, y, x + cw, y + ch);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, paper);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, line);
    if (!hint.isEmpty()) {
      pip.setColor(0xFF666666);
      pip.setTextSize(cw * 0.4f);
      c.drawText(hint, r.centerX(), r.centerY() + cw * 0.14f, pip);
    }
  }

  private void drawBack(Canvas c, float x, float y) {
    RectF r = new RectF(x, y, x + cw, y + ch);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, back);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, line);
  }

  private void drawCard(Canvas c, float x, float y, int card, boolean selected) {
    RectF r = new RectF(x, y, x + cw, y + ch);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, selected ? ink : paper);
    c.drawRoundRect(r, cw * 0.08f, cw * 0.08f, line);
    int fg = selected ? 0xFFFFFFFF : 0xFF000000;
    label.setColor(fg);
    label.setTextSize(cw * 0.3f);
    c.drawText(Klondike.name(card), x + cw * 0.08f, y + cw * 0.32f, label);
    pip.setColor(fg);
    pip.setTextSize(cw * 0.62f);
    c.drawText(Klondike.SUITS[Klondike.suit(card)], r.centerX(), y + ch * 0.72f, pip);
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() != MotionEvent.ACTION_UP || game == null) return true;
    measure();
    Target t = hit(e.getX(), e.getY());
    if (t != null && onTap != null) onTap.accept(t);
    performClick();
    return true;
  }

  @Override public boolean performClick() { return super.performClick(); }

  private Target hit(float x, float y) {
    int col = (int) ((x - gap / 2) / (cw + gap));
    if (col < 0 || col > 6) return null;
    if (y < tableauTop - gap / 2) {
      if (col == 0) return new Target(Area.STOCK, 0, -1);
      if (col == 1 || col == 2) return new Target(Area.WASTE, 0, -1);
      if (col >= 3) return new Target(Area.FOUNDATION, col - 3, -1);
      return null;
    }
    List<Integer> pile = game.tableau[col];
    for (int i = pile.size() - 1; i >= 0; i--) {
      float top = cardY(col, i);
      if (y >= top && y <= top + ch) return new Target(Area.TABLEAU, col, i);
    }
    return new Target(Area.TABLEAU, col, -1);
  }
}
