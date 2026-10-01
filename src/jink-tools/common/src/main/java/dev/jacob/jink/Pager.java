package dev.jacob.jink;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shows a list one page at a time with Prev / Next instead of scrolling. A page turn is one clean
 * refresh on e-ink, where scrolling is dozens of ghosting partial ones.
 */
public final class Pager<T> {
  private final Context c;
  private final int perPage;
  private final Function<T, View> rowFactory;
  private final LinearLayout root, rows;
  private final TextView prev, next, label;
  private final TextView empty;
  private List<T> items = new ArrayList<>();
  private int page;

  public Pager(Context c, int perPage, String emptyText, Function<T, View> rowFactory) {
    this.c = c;
    this.perPage = Math.max(1, perPage);
    this.rowFactory = rowFactory;
    root = Ui.column(c);
    rows = Ui.column(c);
    empty = Ui.muted(c, emptyText);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(0, Ui.dp(c, 24), 0, Ui.dp(c, 24));
    prev = Ui.button(c, "← Prev", v -> turn(-1));
    next = Ui.button(c, "Next →", v -> turn(1));
    label = Ui.text(c, "");
    label.setGravity(Gravity.CENTER);
    LinearLayout nav = Ui.row(c);
    nav.addView(prev, Ui.weight(1));
    nav.addView(label, Ui.weight(1));
    nav.addView(next, Ui.weight(1));
    root.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout.LayoutParams np = Ui.fill();
    np.topMargin = Ui.dp(c, 8);
    root.addView(nav, np);
  }

  /** Rows that comfortably fit a page, given each row's height and the space taken by the rest of the screen. */
  public static int fit(Context c, int rowDp, int reservedDp) {
    int h = c.getResources().getConfiguration().screenHeightDp;
    return Math.max(3, (h - reservedDp) / rowDp);
  }

  public View view() { return root; }

  public int page() { return page; }

  public void setItems(List<T> items) { setItems(items, page); }

  public void setItems(List<T> items, int page) {
    this.items = new ArrayList<>(items);
    this.page = Math.max(0, Math.min(page, pages() - 1));
    render();
  }

  public int pages() { return Math.max(1, (items.size() + perPage - 1) / perPage); }

  /** Moves a page; returns false if already at that end. Hook this up to {@code onPageKey}. */
  public boolean turn(int dir) {
    int p = page + dir;
    if (p < 0 || p >= pages()) return false;
    page = p;
    render();
    return true;
  }

  private void render() {
    rows.removeAllViews();
    if (items.isEmpty()) rows.addView(empty, Ui.fill());
    int from = page * perPage, to = Math.min(items.size(), from + perPage);
    for (int i = from; i < to; i++) {
      if (i > from) rows.addView(Ui.rule(c));
      rows.addView(rowFactory.apply(items.get(i)), Ui.fill());
    }
    label.setText((page + 1) + " / " + pages());
    prev.setVisibility(page > 0 ? View.VISIBLE : View.INVISIBLE);
    next.setVisibility(page < pages() - 1 ? View.VISIBLE : View.INVISIBLE);
  }
}
