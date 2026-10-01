package dev.jacob.jink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.function.Consumer;

/**
 * Base for every J-ink screen: white page, no transitions, content held to a readable width,
 * a header bar, a manual full refresh, volume-key paging and file pickers.
 */
public abstract class InkActivity extends Activity {
  private static final int REQ_OPEN = 7101, REQ_CREATE = 7102;
  private Consumer<Uri> pendingOpen, pendingCreate;
  private FrameLayout frame;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setWindowAnimations(0);
    frame = new FrameLayout(this);
    frame.setBackgroundColor(Ui.PAPER);
    setContentView(frame);
  }

  /** Replaces the screen's content. Wide screens get a centred column instead of very long lines. */
  public void setPage(View content) {
    frame.removeAllViews();
    MaxWidth holder = new MaxWidth(this, Ui.dp(this, 820));
    holder.addView(content, new FrameLayout.LayoutParams(-1, -1));
    int pad = Ui.dp(this, Ui.large(this) ? 24 : 12);
    holder.setPadding(pad, pad, pad, pad);
    frame.addView(holder, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
  }

  /** A title row with optional action buttons on the right and a rule underneath. */
  public LinearLayout header(String title, View... actions) {
    LinearLayout col = Ui.column(this);
    LinearLayout row = Ui.row(this);
    TextView t = Ui.title(this, title);
    t.setSingleLine(true);
    row.addView(t, Ui.weight(1));
    for (View a : actions) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = Ui.dp(this, 6);
      row.addView(a, lp);
    }
    col.addView(row, Ui.fill());
    LinearLayout.LayoutParams rp = Ui.fill();
    rp.topMargin = Ui.dp(this, 8);
    rp.height = Math.max(2, Ui.dp(this, 1));
    col.addView(Ui.rule(this), rp);
    return col;
  }

  /** A header button with a back arrow that closes this screen. */
  public TextView backButton() {
    TextView b = Ui.button(this, "←", v -> finish());
    b.setTypeface(Typeface.DEFAULT_BOLD);
    return b;
  }

  /**
   * Flashes the screen black then white. Most e-ink controllers treat a whole-screen change as a
   * full refresh, which clears ghosting left by many fast partial updates.
   */
  public void fullRefresh() {
    View flash = new View(this);
    flash.setBackgroundColor(Ui.INK);
    flash.setClickable(true);
    frame.addView(flash, new FrameLayout.LayoutParams(-1, -1));
    frame.postDelayed(() -> {
      flash.setBackgroundColor(Ui.PAPER);
      frame.postDelayed(() -> frame.removeView(flash), 120);
    }, 160);
  }

  public TextView refreshButton() { return Ui.button(this, "↻", v -> fullRefresh()); }

  /** Called for volume up (-1) and volume down (+1). Return true to consume the key. */
  protected boolean onPageKey(int direction) { return false; }

  @Override public boolean onKeyDown(int code, KeyEvent e) {
    if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_PAGE_UP) {
      if (onPageKey(-1)) return true;
    } else if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN) {
      if (onPageKey(1)) return true;
    }
    return super.onKeyDown(code, e);
  }

  @SuppressWarnings("deprecation")
  @Override public void startActivity(Intent i) {
    super.startActivity(i);
    overridePendingTransition(0, 0);
  }

  @SuppressWarnings("deprecation")
  @Override public void finish() {
    super.finish();
    overridePendingTransition(0, 0);
  }

  public void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

  /** Opens the system file picker. {@code mimes} may hold several types. */
  public void openDocument(String[] mimes, Consumer<Uri> cb) {
    pendingOpen = cb;
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mimes.length == 1 ? mimes[0] : "*/*");
    if (mimes.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
    launch(i, REQ_OPEN);
  }

  /** Asks the user where to save a new file, then hands back its Uri for writing. */
  public void createDocument(String mime, String suggestedName, Consumer<Uri> cb) {
    pendingCreate = cb;
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mime)
        .putExtra(Intent.EXTRA_TITLE, suggestedName);
    launch(i, REQ_CREATE);
  }

  @SuppressWarnings("deprecation")
  private void launch(Intent i, int req) {
    try { startActivityForResult(i, req); }
    catch (ActivityNotFoundException e) { toast("No file picker available"); }
  }

  @SuppressWarnings("deprecation")
  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    Consumer<Uri> cb;
    if (req == REQ_OPEN) { cb = pendingOpen; pendingOpen = null; }
    else if (req == REQ_CREATE) { cb = pendingCreate; pendingCreate = null; }
    else return;
    if (res == RESULT_OK && data != null && data.getData() != null && cb != null) cb.accept(data.getData());
  }

  /** A FrameLayout that never measures wider than {@code max} pixels. */
  static final class MaxWidth extends FrameLayout {
    private final int max;
    MaxWidth(android.content.Context c, int max) { super(c); this.max = max; }
    @Override protected void onMeasure(int w, int h) {
      int size = MeasureSpec.getSize(w);
      if (size > max) w = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
      super.onMeasure(w, h);
    }
  }
}
