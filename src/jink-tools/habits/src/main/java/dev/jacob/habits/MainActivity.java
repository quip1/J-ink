package dev.jacob.habits;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Tick off daily habits; see streaks, the last week at a glance, and a calendar per habit. */
public class MainActivity extends InkActivity {
  private static final String FILE = "habits.json";

  private final List<Habit> habits = new ArrayList<>();
  private Pager<Habit> pager;
  private Habit open;
  private YearMonth month = YearMonth.now();

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    load();
    showAll();
  }

  @Override protected void onResume() {
    super.onResume();
    // The day may have changed while the app was in the background.
    if (open == null && pager != null) pager.setItems(habits);
  }

  @Override protected boolean onPageKey(int dir) {
    if (open != null) { month = month.plusMonths(dir); showHabit(open); return true; }
    return pager != null && pager.turn(dir);
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (open != null) showAll(); else super.onBackPressed();
  }

  private void showAll() {
    open = null;
    LinearLayout page = Ui.column(this);
    page.addView(header("Habits", Ui.button(this, "+ New", v -> Dialogs.prompt(this, "New habit (e.g. Read 20 pages)", "", n -> {
      if (n.trim().isEmpty()) return;
      habits.add(new Habit(n.trim()));
      save();
      pager.setItems(habits, pager.pages());
    })), refreshButton()), Ui.fill());
    LocalDate today = LocalDate.now();
    int done = 0;
    for (Habit h : habits) if (h.done(today)) done++;
    Ui.add(page, Ui.muted(this, today.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + " · " + done + " of "
        + habits.size() + " done today"), 6);
    pager = new Pager<>(this, Pager.fit(this, 84, 200), "No habits yet. Tap + New to add one.", this::row);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(pager.view(), lp);
    pager.setItems(habits);
    Ui.add(page, Ui.muted(this, "Tap a habit for its calendar. Squares are the last 7 days."), 6);
    setPage(page);
  }

  private View row(Habit h) {
    LocalDate today = LocalDate.now();
    LinearLayout row = Ui.row(this);
    int pad = Ui.dp(this, 8);
    row.setPadding(0, pad, 0, pad);
    TextView check = Ui.button(this, h.done(today) ? "✓" : "", v -> {
      h.toggle(LocalDate.now());
      save();
      showAll();
    });
    Ui.setActive(check, h.done(today));
    check.setTextSize(Ui.body(this) * 1.4f);
    row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
    LinearLayout text = Ui.column(this);
    TextView name = Ui.text(this, h.name);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    text.addView(name);
    int s = h.streak(today);
    text.addView(Ui.muted(this, h.recent(today, 7) + "   " + (s == 0 ? "no streak" : s + "-day streak")));
    LinearLayout.LayoutParams tp = Ui.weight(1);
    tp.leftMargin = Ui.dp(this, 12);
    row.addView(text, tp);
    text.setOnClickListener(v -> { month = YearMonth.now(); showHabit(h); });
    return row;
  }

  private void showHabit(Habit h) {
    open = h;
    LocalDate today = LocalDate.now();
    LinearLayout page = Ui.column(this);
    page.addView(header(h.name, Ui.button(this, "←", v -> showAll()), Ui.button(this, "⋯", v -> habitMenu(h))), Ui.fill());
    Ui.add(page, Ui.text(this, "Current streak: " + h.streak(today) + " days\nBest streak: " + h.bestStreak()
        + " days\nLast 30 days: " + h.percent(today, 30) + "%"), 10);

    LinearLayout nav = Ui.row(this);
    nav.addView(Ui.button(this, "←", v -> { month = month.minusMonths(1); showHabit(h); }));
    TextView title = Ui.title(this, month.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
    title.setGravity(Gravity.CENTER);
    nav.addView(title, Ui.weight(1));
    nav.addView(Ui.button(this, "→", v -> { month = month.plusMonths(1); showHabit(h); }));
    Ui.add(page, nav, 16);

    DayOfWeek first = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    LinearLayout head = Ui.row(this);
    for (int i = 0; i < 7; i++) {
      String n = first.plus(i).toString();
      TextView t = Ui.muted(this, n.charAt(0) + n.substring(1, 2).toLowerCase(Locale.ROOT));
      t.setGravity(Gravity.CENTER);
      head.addView(t, Ui.weight(1));
    }
    Ui.add(page, head, 8);
    int lead = Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
    int days = month.lengthOfMonth(), cells = ((lead + days + 6) / 7) * 7;
    LinearLayout r = null;
    for (int i = 0; i < cells; i++) {
      if (i % 7 == 0) { r = Ui.row(this); Ui.add(page, r, 4); }
      int d = i - lead + 1;
      if (d < 1 || d > days) { r.addView(new View(this), new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1)); continue; }
      LocalDate date = month.atDay(d);
      TextView cell = Ui.button(this, String.valueOf(d), null);
      cell.setTextSize(Ui.body(this) * 0.9f);
      Ui.setActive(cell, h.done(date));
      if (date.isAfter(today)) cell.setTextColor(0xFF999999);
      else cell.setOnClickListener(v -> { h.toggle(date); save(); showHabit(h); });
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
      cp.leftMargin = i % 7 == 0 ? 0 : Ui.dp(this, 3);
      r.addView(cell, cp);
    }
    Ui.add(page, Ui.muted(this, "Tap a past day to fix it. Volume keys change month."), 10);
    setPage(page);
  }

  private void habitMenu(Habit h) {
    Dialogs.choose(this, h.name, new String[]{"Rename…", "Delete"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Rename", h.name, n -> { if (!n.trim().isEmpty()) h.name = n.trim(); save(); showHabit(h); });
      else Dialogs.confirm(this, "Delete “" + h.name + "” and its history?", "Delete", () -> {
        habits.remove(h);
        save();
        showAll();
      });
    });
  }

  // ---- storage ----

  private void load() {
    JSONArray a = Store.readArray(this, FILE);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Habit h = new Habit(o.optString("name", "Habit"));
      h.id = o.optString("id", h.id);
      JSONArray d = o.optJSONArray("days");
      for (int k = 0; d != null && k < d.length(); k++) h.days.add(d.optLong(k));
      habits.add(h);
    }
  }

  private void save() {
    JSONArray a = new JSONArray();
    try {
      for (Habit h : habits) {
        JSONArray d = new JSONArray();
        for (long day : h.days) d.put(day);
        a.put(new JSONObject().put("id", h.id).put("name", h.name).put("days", d));
      }
    } catch (JSONException e) {
      return;
    }
    if (!Store.write(this, FILE, a)) toast("Couldn't save");
  }
}
