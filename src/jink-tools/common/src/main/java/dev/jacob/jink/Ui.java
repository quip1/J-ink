package dev.jacob.jink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Flat, high-contrast controls. No ripples, fades or overscroll glows: every one of those
 * becomes a smear of partial refreshes on e-ink.
 */
public final class Ui {
  private Ui() {}

  public static final int INK = Color.BLACK;
  public static final int PAPER = Color.WHITE;
  /** The one grey we use, for secondary text. Dark enough to stay crisp in 16-level greyscale. */
  public static final int MUTED = 0xFF444444;

  public static int dp(Context c, float v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }

  /** True on tablet-sized screens like the Note Air; false on phone-sized ones like the Palma. */
  public static boolean large(Context c) {
    return c.getResources().getConfiguration().smallestScreenWidthDp >= 600;
  }

  /** Body text size in sp, scaled for the screen. */
  public static float body(Context c) { return large(c) ? 21 : 18; }

  public static TextView text(Context c, CharSequence s) { return text(c, s, 1f); }

  public static TextView text(Context c, CharSequence s, float scale) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextColor(INK);
    t.setTextSize(body(c) * scale);
    t.setLineSpacing(0, 1.15f);
    return t;
  }

  public static TextView muted(Context c, CharSequence s) {
    TextView t = text(c, s, 0.85f);
    t.setTextColor(MUTED);
    return t;
  }

  public static TextView title(Context c, CharSequence s) {
    TextView t = text(c, s, 1.3f);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  /** A boxed button. Black-on-white, or white-on-black when active. */
  public static TextView button(Context c, String label, View.OnClickListener l) {
    TextView t = new TextView(c);
    t.setText(label);
    t.setTextSize(body(c));
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setGravity(Gravity.CENTER);
    int p = dp(c, 12);
    t.setPadding(p, dp(c, 6), p, dp(c, 6));
    t.setMinHeight(dp(c, 52));
    t.setMinWidth(dp(c, 52));
    t.setOnClickListener(l);
    setActive(t, false);
    return t;
  }

  public static void setActive(TextView t, boolean on) {
    t.setTextColor(on ? PAPER : INK);
    t.setBackground(box(t.getContext(), on ? INK : PAPER));
  }

  public static GradientDrawable box(Context c, int fill) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setStroke(Math.max(2, dp(c, 1.5f)), INK);
    return g;
  }

  public static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  /** A row of equal-width children with a small gap between them. */
  public static LinearLayout buttons(Context c, View... views) {
    LinearLayout r = row(c);
    for (int i = 0; i < views.length; i++) {
      LinearLayout.LayoutParams lp = weight(1);
      if (i > 0) lp.leftMargin = dp(c, 6);
      r.addView(views[i], lp);
    }
    return r;
  }

  public static LinearLayout.LayoutParams weight(float w) {
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
  }

  public static LinearLayout.LayoutParams fill() {
    return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  /** Adds a child below the previous one with a gap, full width. */
  public static <V extends View> V add(LinearLayout parent, V child, int gapDp) {
    LinearLayout.LayoutParams lp = fill();
    lp.topMargin = dp(parent.getContext(), gapDp);
    parent.addView(child, lp);
    return child;
  }

  public static View rule(Context c) {
    View v = new View(c);
    v.setBackgroundColor(INK);
    v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(c, 1))));
    return v;
  }

  public static EditText input(Context c, String hint, String value) {
    EditText e = new EditText(c);
    e.setHint(hint);
    e.setText(value);
    e.setTextColor(INK);
    e.setHintTextColor(0xFF777777);
    e.setTextSize(body(c));
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    e.setBackground(box(c, PAPER));
    int p = dp(c, 10);
    e.setPadding(p, p, p, p);
    return e;
  }

  public static EditText numberInput(Context c, String hint, String value) {
    EditText e = input(c, hint, value);
    e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    return e;
  }

  /** A scroll view with the fading edges, glow and fading scrollbar switched off. */
  public static ScrollView scroll(Context c, View child) {
    ScrollView s = new ScrollView(c);
    s.setOverScrollMode(View.OVER_SCROLL_NEVER);
    s.setVerticalFadingEdgeEnabled(false);
    s.setScrollbarFadingEnabled(false);
    s.setFillViewport(true);
    s.addView(child);
    return s;
  }

  /** A solid black bar in a box: no animation, so it redraws once when the value changes. */
  public static View progress(Context c, int percent) {
    LinearLayout bar = row(c);
    bar.setBackground(box(c, PAPER));
    int b = Math.max(2, dp(c, 1.5f));
    bar.setPadding(b, b, b, b);
    View fill = new View(c);
    fill.setBackgroundColor(INK);
    int p = Math.max(0, Math.min(100, percent));
    bar.addView(fill, new LinearLayout.LayoutParams(0, dp(c, 12), p));
    bar.addView(new View(c), new LinearLayout.LayoutParams(0, dp(c, 12), 100 - p));
    return bar;
  }

  public static int parseInt(String s, int fallback) {
    try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return fallback; }
  }
}
