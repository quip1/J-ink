package dev.slate.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Flat, high-contrast controls: no ripples or animations, which smear on e-ink. */
final class Ui {
  static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

  static TextView button(Context c, String text, View.OnClickListener l) {
    TextView t = new TextView(c);
    t.setText(text);
    t.setTextSize(18);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setGravity(Gravity.CENTER);
    int p = dp(c, 14);
    t.setPadding(p, 0, p, 0);
    t.setMinWidth(dp(c, 48));
    t.setOnClickListener(l);
    setActive(t, false);
    return t;
  }

  static void setActive(TextView t, boolean on) {
    t.setTextColor(on ? Color.WHITE : Color.BLACK);
    t.setBackgroundColor(on ? Color.BLACK : Color.WHITE);
  }

  static GradientDrawable bottomRule() {
    GradientDrawable g = new GradientDrawable();
    g.setColor(Color.WHITE);
    g.setStroke(2, Color.BLACK);
    return g;
  }

  static View rule(Context c) {
    View v = new View(c);
    v.setBackgroundColor(Color.BLACK);
    v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, 2));
    return v;
  }
}
