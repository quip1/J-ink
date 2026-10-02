package dev.jacob.timer;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A stopwatch with laps and any number of named countdowns. */
public class MainActivity extends InkActivity {
  private static final int[] PRESET_MIN = {1, 3, 5, 10, 15, 30, 60};

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Clocks.Stopwatch watch = new Clocks.Stopwatch();
  private final List<Clocks.Countdown> timers = new ArrayList<>();
  private SharedPreferences prefs;
  private boolean stopwatchTab;
  private TextView tabTimers, tabWatch;
  private LinearLayout body;
  private TextView watchLabel, watchBtn;
  private LinearLayout lapList;
  private final List<TextView> timerLabels = new ArrayList<>();
  private int nextId = 100;

  private final Runnable tick = new Runnable() {
    @Override public void run() {
      update();
      // Once a second: faster than that just smears an e-ink screen.
      handler.postDelayed(this, 1000);
    }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("timer", MODE_PRIVATE);
    load();
    stopwatchTab = prefs.getBoolean("watchTab", false);
    LinearLayout page = Ui.column(this);
    tabTimers = Ui.button(this, "Timers", v -> setTab(false));
    tabWatch = Ui.button(this, "Stopwatch", v -> setTab(true));
    page.addView(header("Timer", tabTimers, tabWatch, refreshButton()), Ui.fill());
    body = Ui.column(this);
    page.addView(Ui.scroll(this, body), new LinearLayout.LayoutParams(-1, 0, 1));
    setPage(page);
    render();
  }

  @Override protected void onResume() {
    super.onResume();
    handler.post(tick);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override protected void onPause() {
    super.onPause();
    handler.removeCallbacks(tick);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    save();
  }

  private void setTab(boolean watchTab) {
    stopwatchTab = watchTab;
    prefs.edit().putBoolean("watchTab", watchTab).apply();
    render();
  }

  private void render() {
    Ui.setActive(tabTimers, !stopwatchTab);
    Ui.setActive(tabWatch, stopwatchTab);
    body.removeAllViews();
    timerLabels.clear();
    if (stopwatchTab) buildWatch(); else buildTimers();
    update();
  }

  // ---- stopwatch ----

  private void buildWatch() {
    watchLabel = Ui.text(this, "", Ui.large(this) ? 6f : 4.5f);
    watchLabel.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    watchLabel.setGravity(Gravity.CENTER);
    Ui.add(body, watchLabel, 30);
    watchBtn = Ui.button(this, "", v -> {
      long now = System.currentTimeMillis();
      if (watch.running()) watch.stop(now); else watch.start(now);
      save();
      render();
    });
    TextView lap = Ui.button(this, "Lap", v -> { watch.lap(System.currentTimeMillis()); save(); render(); });
    TextView reset = Ui.button(this, "Reset", v -> { watch.reset(); save(); render(); });
    Ui.add(body, Ui.buttons(this, watchBtn), 24);
    Ui.add(body, Ui.buttons(this, lap, reset), 6);
    lapList = Ui.column(this);
    Ui.add(body, lapList, 16);
    for (int i = watch.laps.size() - 1; i >= 0; i--) {
      LinearLayout r = Ui.row(this);
      r.addView(Ui.text(this, "Lap " + (i + 1)), Ui.weight(1));
      TextView t = Ui.text(this, Clocks.format(watch.lapTime(i), true) + "   " + Clocks.format(watch.laps.get(i), true));
      t.setTypeface(Typeface.MONOSPACE);
      r.addView(t);
      lapList.addView(r, Ui.fill());
      lapList.addView(Ui.rule(this));
    }
  }

  // ---- countdowns ----

  private void buildTimers() {
    Ui.add(body, Ui.muted(this, "Quick start"), 8);
    TextView[] row1 = new TextView[4], row2 = new TextView[4];
    for (int i = 0; i < PRESET_MIN.length; i++) {
      int m = PRESET_MIN[i];
      TextView b = Ui.button(this, m < 60 ? m + " min" : "1 hour", v -> addTimer(m + " min", m * 60_000L, true));
      if (i < 4) row1[i] = b; else row2[i - 4] = b;
    }
    row2[3] = Ui.button(this, "Custom…", v -> customTimer());
    Ui.add(body, Ui.buttons(this, row1), 4);
    Ui.add(body, Ui.buttons(this, row2), 4);
    if (timers.isEmpty()) {
      TextView none = Ui.muted(this, "No timers. Start one above.");
      none.setGravity(Gravity.CENTER);
      Ui.add(body, none, 30);
    }
    for (Clocks.Countdown c : timers) {
      LinearLayout card = Ui.column(this);
      card.setBackground(Ui.box(this, Ui.PAPER));
      int p = Ui.dp(this, 12);
      card.setPadding(p, p, p, p);
      LinearLayout top = Ui.row(this);
      TextView name = Ui.text(this, c.name);
      name.setTypeface(Typeface.DEFAULT_BOLD);
      top.addView(name, Ui.weight(1));
      TextView left = Ui.text(this, "", 2.2f);
      left.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
      left.setTag(c);
      timerLabels.add(left);
      top.addView(left);
      card.addView(top, Ui.fill());
      long now = System.currentTimeMillis();
      TextView go = Ui.button(this, c.running() ? "Pause" : c.left(now) < c.duration ? "Resume" : "Start", v -> {
        long t = System.currentTimeMillis();
        if (c.running()) { c.pause(t); AlarmReceiver.cancel(this, c.id); }
        else { c.start(t); AlarmReceiver.schedule(this, c.id, c.endsAt); }
        save();
        render();
      });
      TextView reset = Ui.button(this, "Reset", v -> { c.reset(); AlarmReceiver.cancel(this, c.id); save(); render(); });
      TextView remove = Ui.button(this, "Remove", v -> { AlarmReceiver.cancel(this, c.id); timers.remove(c); save(); render(); });
      Ui.add(card, Ui.buttons(this, go, reset, remove), 8);
      name.setOnClickListener(v -> Dialogs.prompt(this, "Name this timer", c.name, n -> {
        if (!n.trim().isEmpty()) c.name = n.trim();
        save();
        render();
      }));
      Ui.add(body, card, 10);
    }
  }

  private void customTimer() {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText name = Ui.input(this, "Name (optional)", "");
    EditText h = Ui.numberInput(this, "Hours", "");
    EditText m = Ui.numberInput(this, "Minutes", "");
    EditText s = Ui.numberInput(this, "Seconds", "");
    form.addView(name, Ui.fill());
    Ui.add(form, Ui.buttons(this, h, m, s), 6);
    Ui.add(form, Ui.muted(this, "Hours / minutes / seconds"), 4);
    Dialogs.builder(this).setTitle("New timer").setView(form).setPositiveButton("Start", (d, w) -> {
      long ms = (Ui.parseInt(h.getText().toString(), 0) * 3600L + Ui.parseInt(m.getText().toString(), 0) * 60L
          + Ui.parseInt(s.getText().toString(), 0)) * 1000;
      if (ms <= 0) { toast("Set a time first"); return; }
      String n = name.getText().toString().trim();
      addTimer(n.isEmpty() ? Clocks.format(ms, false) : n, ms, true);
    }).setNegativeButton("Cancel", null).show();
  }

  private void addTimer(String name, long ms, boolean start) {
    Clocks.Countdown c = new Clocks.Countdown(nextId++, name, ms);
    if (start) {
      c.start(System.currentTimeMillis());
      AlarmReceiver.schedule(this, c.id, c.endsAt);
    }
    timers.add(0, c);
    save();
    render();
  }

  // ---- ticking: only touch text that changed ----

  private void update() {
    long now = System.currentTimeMillis();
    if (stopwatchTab) {
      if (watchLabel == null) return;
      // Tenths only once stopped; while running they'd change faster than e-ink can draw.
      set(watchLabel, Clocks.format(watch.elapsed(now), !watch.running()));
      set(watchBtn, watch.running() ? "Stop" : watch.elapsed(now) > 0 ? "Continue" : "Start");
      return;
    }
    boolean anyFinished = false;
    for (TextView t : timerLabels) {
      Clocks.Countdown c = (Clocks.Countdown) t.getTag();
      if (c.finished(now)) {
        anyFinished = true;
        c.reset();
        toast("“" + c.name + "” is done");
      }
      set(t, Clocks.formatLeft(c.left(now)));
    }
    if (anyFinished) { save(); render(); fullRefresh(); }
  }

  private static void set(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }

  // ---- storage ----

  private void load() {
    watch.startedAt = prefs.getLong("w.start", -1);
    watch.banked = prefs.getLong("w.banked", 0);
    try {
      JSONArray laps = new JSONArray(prefs.getString("w.laps", "[]"));
      for (int i = 0; i < laps.length(); i++) watch.laps.add(laps.getLong(i));
      JSONArray a = new JSONArray(prefs.getString("timers", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        Clocks.Countdown c = new Clocks.Countdown(o.getInt("id"), o.optString("name"), o.getLong("dur"));
        c.endsAt = o.optLong("ends", -1);
        c.pausedLeft = o.optLong("paused", -1);
        timers.add(c);
        nextId = Math.max(nextId, c.id + 1);
      }
    } catch (JSONException ignored) {
      // Start fresh if the saved state is damaged.
    }
  }

  private void save() {
    JSONArray laps = new JSONArray(), a = new JSONArray();
    for (long l : watch.laps) laps.put(l);
    try {
      for (Clocks.Countdown c : timers)
        a.put(new JSONObject().put("id", c.id).put("name", c.name).put("dur", c.duration).put("ends", c.endsAt)
            .put("paused", c.pausedLeft));
    } catch (JSONException e) {
      return;
    }
    prefs.edit().putLong("w.start", watch.startedAt).putLong("w.banked", watch.banked).putString("w.laps", laps.toString())
        .putString("timers", a.toString()).apply();
  }
}
