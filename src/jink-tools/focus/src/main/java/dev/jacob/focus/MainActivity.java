package dev.jacob.focus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.util.Calendar;
import java.util.Locale;

/**
 * A focus timer and a big desk clock. Both only redraw when the text they show actually changes,
 * which on e-ink means once a minute instead of every second.
 */
public class MainActivity extends InkActivity {
  private static final String[] WEEKDAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
  private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
      "September", "October", "November", "December"};

  private final Pomodoro pomo = new Pomodoro();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  private boolean clockMode, showSeconds, keepAwake;
  private TextView timerBtn, clockBtn;
  private TextView phaseLabel, timeLabel, unitLabel, cycleLabel, startBtn, todayLabel, settingsBtn, secondsBtn, awakeBtn;
  private TextView clockTime, clockDate;
  private LinearLayout calendarBox;
  private int calendarDay = -1;

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      updateTimer();
      handler.postDelayed(this, 1000);
    }
  };

  private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) { updateClock(); }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("focus", MODE_PRIVATE);
    load();
    clockMode = prefs.getBoolean("clockMode", false);
    build();
  }

  @Override protected void onResume() {
    super.onResume();
    load();
    catchUp();
    IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
    f.addAction(Intent.ACTION_TIME_CHANGED);
    f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(minuteTick, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(minuteTick, f);
    handler.post(ticker);
    updateClock();
  }

  @Override protected void onPause() {
    super.onPause();
    handler.removeCallbacks(ticker);
    unregisterReceiver(minuteTick);
    save();
  }

  private void build() {
    LinearLayout page = Ui.column(this);
    timerBtn = Ui.button(this, "Timer", v -> setMode(false));
    clockBtn = Ui.button(this, "Clock", v -> setMode(true));
    page.addView(header("Focus", timerBtn, clockBtn, refreshButton()), Ui.fill());
    LinearLayout content = Ui.column(this);
    content.setGravity(Gravity.CENTER_HORIZONTAL);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    page.addView(Ui.scroll(this, content), lp);
    if (clockMode) buildClock(content); else buildTimer(content);
    Ui.setActive(timerBtn, !clockMode);
    Ui.setActive(clockBtn, clockMode);
    setPage(page);
    applyKeepAwake();
  }

  private void setMode(boolean clock) {
    clockMode = clock;
    prefs.edit().putBoolean("clockMode", clock).apply();
    build();
    updateTimer();
    updateClock();
  }

  // ---- timer ----

  private void buildTimer(LinearLayout c) {
    clockTime = null;
    phaseLabel = Ui.title(this, "");
    phaseLabel.setGravity(Gravity.CENTER);
    Ui.add(c, phaseLabel, 24);
    timeLabel = Ui.text(this, "", Ui.large(this) ? 9f : 6.5f);
    timeLabel.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    timeLabel.setGravity(Gravity.CENTER);
    Ui.add(c, timeLabel, 8);
    unitLabel = Ui.muted(this, "");
    unitLabel.setGravity(Gravity.CENTER);
    c.addView(unitLabel, Ui.fill());
    cycleLabel = Ui.muted(this, "");
    cycleLabel.setGravity(Gravity.CENTER);
    Ui.add(c, cycleLabel, 8);

    startBtn = Ui.button(this, "", v -> {
      long now = System.currentTimeMillis();
      if (pomo.running) { pomo.pause(now); AlarmReceiver.cancel(this); }
      else { pomo.start(now); AlarmReceiver.schedule(this, pomo.endsAt); }
      save();
      updateTimer();
    });
    TextView reset = Ui.button(this, "Reset", v -> { pomo.reset(); AlarmReceiver.cancel(this); save(); updateTimer(); });
    TextView skip = Ui.button(this, "Skip", v -> { pomo.skip(); AlarmReceiver.cancel(this); save(); updateTimer(); });
    Ui.add(c, Ui.buttons(this, startBtn), 24);
    Ui.add(c, Ui.buttons(this, reset, skip), 6);

    settingsBtn = Ui.button(this, "", v -> editSettings());
    Ui.add(c, settingsBtn, 24);
    secondsBtn = Ui.button(this, "Show seconds", v -> {
      showSeconds = !showSeconds;
      prefs.edit().putBoolean("seconds", showSeconds).apply();
      updateTimer();
    });
    awakeBtn = Ui.button(this, "Keep screen on", v -> {
      keepAwake = !keepAwake;
      prefs.edit().putBoolean("awake", keepAwake).apply();
      applyKeepAwake();
      updateTimer();
    });
    Ui.add(c, Ui.buttons(this, secondsBtn, awakeBtn), 6);
    todayLabel = Ui.muted(this, "");
    todayLabel.setGravity(Gravity.CENTER);
    Ui.add(c, todayLabel, 16);
    updateTimer();
  }

  /** Runs every second but only touches the screen when a label's text changes. */
  private void updateTimer() {
    if (clockMode || timeLabel == null) return;
    long now = System.currentTimeMillis();
    Pomodoro.Phase finished = pomo.tick(now);
    if (finished != null) onFinished(finished);
    set(phaseLabel, pomo.phase.label + (pomo.running ? "" : pomo.remainingMs >= 0 ? " · paused" : ""));
    set(timeLabel, Pomodoro.format(pomo.remaining(now), showSeconds));
    set(unitLabel, showSeconds ? "" : "minutes left");
    set(cycleLabel, "Session " + Math.min(pomo.doneInCycle + 1, pomo.longEvery) + " of " + pomo.longEvery);
    set(startBtn, pomo.running ? "Pause" : pomo.remainingMs >= 0 ? "Resume" : "Start");
    set(settingsBtn, "Focus " + pomo.focusMin + " · Break " + pomo.shortMin + " · Long " + pomo.longMin
        + " · Every " + pomo.longEvery);
    Ui.setActive(secondsBtn, showSeconds);
    Ui.setActive(awakeBtn, keepAwake);
    int sessions = prefs.getInt(todayKey("n"), 0), minutes = prefs.getInt(todayKey("m"), 0);
    set(todayLabel, "Today: " + sessions + " focus session" + (sessions == 1 ? "" : "s") + ", " + minutes + " min");
  }

  private static void set(TextView t, String s) {
    if (!s.contentEquals(t.getText())) t.setText(s);
  }

  /** Applies a phase that ended while the app was closed. */
  private void catchUp() {
    Pomodoro.Phase finished = pomo.tick(System.currentTimeMillis());
    if (finished != null) onFinished(finished);
  }

  private void onFinished(Pomodoro.Phase finished) {
    if (finished == Pomodoro.Phase.FOCUS) {
      prefs.edit().putInt(todayKey("n"), prefs.getInt(todayKey("n"), 0) + 1)
          .putInt(todayKey("m"), prefs.getInt(todayKey("m"), 0) + pomo.focusMin).apply();
    }
    save();
    fullRefresh();
    toast(finished.label + " finished. Next: " + pomo.phase.label.toLowerCase(Locale.US) + ".");
  }

  private void editSettings() {
    String[] items = {"Focus length (" + pomo.focusMin + " min)", "Short break (" + pomo.shortMin + " min)",
        "Long break (" + pomo.longMin + " min)", "Long break every (" + pomo.longEvery + " sessions)"};
    Dialogs.choose(this, "Timer settings", items, i -> {
      int current = i == 0 ? pomo.focusMin : i == 1 ? pomo.shortMin : i == 2 ? pomo.longMin : pomo.longEvery;
      Dialogs.number(this, items[i].replaceAll(" \\(.*", ""), current, n -> {
        n = Math.max(1, Math.min(i == 3 ? 12 : 240, n));
        if (i == 0) pomo.focusMin = n;
        else if (i == 1) pomo.shortMin = n;
        else if (i == 2) pomo.longMin = n;
        else pomo.longEvery = n;
        save();
        updateTimer();
      });
    });
  }

  private String todayKey(String what) {
    Calendar c = Calendar.getInstance();
    return what + "-" + c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
  }

  // ---- clock ----

  private void buildClock(LinearLayout c) {
    timeLabel = null;
    clockTime = Ui.text(this, "", Ui.large(this) ? 10f : 7f);
    clockTime.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    clockTime.setGravity(Gravity.CENTER);
    Ui.add(c, clockTime, 24);
    clockDate = Ui.title(this, "");
    clockDate.setGravity(Gravity.CENTER);
    c.addView(clockDate, Ui.fill());
    calendarBox = Ui.column(this);
    Ui.add(c, calendarBox, 24);
    awakeBtn = Ui.button(this, "Keep screen on", v -> {
      keepAwake = !keepAwake;
      prefs.edit().putBoolean("awake", keepAwake).apply();
      applyKeepAwake();
      Ui.setActive(awakeBtn, keepAwake);
    });
    Ui.setActive(awakeBtn, keepAwake);
    Ui.add(c, awakeBtn, 24);
    calendarDay = -1;
    updateClock();
  }

  /** Called on the system's once-a-minute tick. */
  private void updateClock() {
    if (!clockMode || clockTime == null) return;
    Calendar now = Calendar.getInstance();
    set(clockTime, DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now).toString());
    set(clockDate, WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + MONTHS[now.get(Calendar.MONTH)] + " "
        + now.get(Calendar.DAY_OF_MONTH));
    if (now.get(Calendar.DAY_OF_YEAR) != calendarDay) {
      calendarDay = now.get(Calendar.DAY_OF_YEAR);
      drawMonth(now);
    }
  }

  private void drawMonth(Calendar today) {
    calendarBox.removeAllViews();
    int first = today.getFirstDayOfWeek();
    Calendar c = (Calendar) today.clone();
    c.set(Calendar.DAY_OF_MONTH, 1);
    int lead = (c.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
    int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
    TextView title = Ui.text(this, MONTHS[today.get(Calendar.MONTH)] + " " + today.get(Calendar.YEAR));
    title.setGravity(Gravity.CENTER);
    calendarBox.addView(title, Ui.fill());
    LinearLayout head = Ui.row(this);
    for (int i = 0; i < 7; i++) {
      TextView t = Ui.muted(this, WEEKDAYS[(first - 1 + i) % 7].substring(0, 2));
      t.setGravity(Gravity.CENTER);
      head.addView(t, Ui.weight(1));
    }
    Ui.add(calendarBox, head, 6);
    int cells = ((lead + days + 6) / 7) * 7;
    LinearLayout row = null;
    for (int i = 0; i < cells; i++) {
      if (i % 7 == 0) { row = Ui.row(this); calendarBox.addView(row, Ui.fill()); }
      int day = i - lead + 1;
      TextView t = Ui.text(this, day >= 1 && day <= days ? String.valueOf(day) : "");
      t.setGravity(Gravity.CENTER);
      int p = Ui.dp(this, 6);
      t.setPadding(0, p, 0, p);
      if (day == today.get(Calendar.DAY_OF_MONTH)) {
        t.setBackgroundColor(Ui.INK);
        t.setTextColor(Ui.PAPER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
      }
      row.addView(t, Ui.weight(1));
    }
  }

  private void applyKeepAwake() {
    if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  // ---- storage ----

  private void load() {
    pomo.focusMin = prefs.getInt("focusMin", 25);
    pomo.shortMin = prefs.getInt("shortMin", 5);
    pomo.longMin = prefs.getInt("longMin", 15);
    pomo.longEvery = prefs.getInt("longEvery", 4);
    pomo.phase = Pomodoro.Phase.values()[Math.max(0, Math.min(2, prefs.getInt("phase", 0)))];
    pomo.doneInCycle = prefs.getInt("done", 0);
    pomo.endsAt = prefs.getLong("endsAt", 0);
    pomo.remainingMs = prefs.getLong("remaining", -1);
    pomo.running = prefs.getBoolean("running", false);
    showSeconds = prefs.getBoolean("seconds", false);
    keepAwake = prefs.getBoolean("awake", false);
  }

  private void save() {
    prefs.edit().putInt("focusMin", pomo.focusMin).putInt("shortMin", pomo.shortMin).putInt("longMin", pomo.longMin)
        .putInt("longEvery", pomo.longEvery).putInt("phase", pomo.phase.ordinal()).putInt("done", pomo.doneInCycle)
        .putLong("endsAt", pomo.endsAt).putLong("remaining", pomo.remainingMs).putBoolean("running", pomo.running)
        .apply();
  }
}
