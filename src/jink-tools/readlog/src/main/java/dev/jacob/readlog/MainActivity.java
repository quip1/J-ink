package dev.jacob.readlog;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Book list, book details, and a reading-session timer. */
public class MainActivity extends InkActivity {
  private static final String FILE = "books.json";

  private final List<Books.Book> books = new ArrayList<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  private Books.Status filter = Books.Status.READING;
  private Books.Book openBook;
  private Pager<?> pager;
  private TextView elapsed;
  private final Runnable minuteTicker = new Runnable() {
    @Override public void run() {
      updateElapsed();
      handler.postDelayed(this, 15_000);
    }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("readlog", MODE_PRIVATE);
    load();
    String active = prefs.getString("sessionBook", null);
    Books.Book resume = active == null ? null : find(active);
    if (resume != null) showTimer(resume); else showList();
  }

  @Override protected void onPause() {
    super.onPause();
    handler.removeCallbacks(minuteTicker);
  }

  @Override protected void onResume() {
    super.onResume();
    if (elapsed != null) handler.post(minuteTicker);
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (elapsed != null) return; // finish or cancel the session first
    if (openBook != null) showList();
    else super.onBackPressed();
  }

  @Override protected boolean onPageKey(int dir) { return pager != null && pager.turn(dir); }

  // ---- list ----

  private void showList() {
    openBook = null;
    elapsed = null;
    LinearLayout page = Ui.column(this);
    page.addView(header("Read Log", Ui.button(this, "+ Book", v -> editBook(null)), refreshButton()), Ui.fill());

    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.now(zone);
    int streak = Books.streak(books, today, zone);
    int[] week = Books.lastSevenDays(books, today, zone);
    TextView summary = Ui.text(this, "Streak: " + streak + " day" + (streak == 1 ? "" : "s") + "   ·   Last 7 days: "
        + week[0] + " pages, " + Books.duration(week[1]));
    Ui.add(page, summary, 8);

    TextView[] tabs = new TextView[Books.Status.values().length];
    for (Books.Status s : Books.Status.values()) {
      tabs[s.ordinal()] = Ui.button(this, s.label, v -> { filter = s; showList(); });
      Ui.setActive(tabs[s.ordinal()], s == filter);
    }
    Ui.add(page, Ui.buttons(this, tabs), 10);

    List<Books.Book> shown = new ArrayList<>();
    for (Books.Book bk : books) if (bk.status == filter) shown.add(bk);
    Pager<Books.Book> p = new Pager<>(this, Pager.fit(this, 96, 260), filter == Books.Status.READING
        ? "Nothing on the go. Tap + Book to add one." : "Nothing here yet.", this::bookRow);
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(p.view(), lp);
    p.setItems(shown);
    setPage(page);
  }

  private View bookRow(Books.Book bk) {
    LinearLayout row = Ui.column(this);
    int p = Ui.dp(this, 10);
    row.setPadding(p, p, p, p);
    TextView t = Ui.title(this, bk.title);
    t.setSingleLine(true);
    row.addView(t);
    if (!bk.author.isEmpty()) row.addView(Ui.muted(this, bk.author));
    if (bk.totalPages > 0 && bk.status != Books.Status.WANT) {
      Ui.add(row, Ui.progress(this, bk.percent()), 6);
      row.addView(Ui.muted(this, "p. " + bk.currentPage + " of " + bk.totalPages + " · " + bk.percent() + "%"));
    }
    row.setOnClickListener(v -> showBook(bk));
    return row;
  }

  // ---- one book ----

  private void showBook(Books.Book bk) {
    openBook = bk;
    elapsed = null;
    LinearLayout page = Ui.column(this);
    page.addView(header(bk.title, Ui.button(this, "←", v -> showList()),
        Ui.button(this, "Edit", v -> editBook(bk))), Ui.fill());
    if (!bk.author.isEmpty()) Ui.add(page, Ui.muted(this, "by " + bk.author), 4);
    if (bk.totalPages > 0) {
      Ui.add(page, Ui.progress(this, bk.percent()), 10);
      page.addView(Ui.text(this, "Page " + bk.currentPage + " of " + bk.totalPages + " (" + bk.percent() + "%)"));
    }
    StringBuilder stats = new StringBuilder();
    if (bk.minutesRead() > 0) stats.append("Read for ").append(Books.duration(bk.minutesRead())).append(" in ")
        .append(bk.sessions.size()).append(" session").append(bk.sessions.size() == 1 ? "" : "s").append('\n');
    if (bk.pagesPerHour() > 0) stats.append("Pace: ").append(Math.round(bk.pagesPerHour())).append(" pages an hour\n");
    if (bk.minutesLeft() >= 0 && bk.status == Books.Status.READING)
      stats.append("About ").append(Books.duration(bk.minutesLeft())).append(" to go\n");
    if (stats.length() > 0) Ui.add(page, Ui.text(this, stats.toString().trim()), 8);

    if (bk.status != Books.Status.FINISHED) {
      TextView start = Ui.button(this, "Start reading", v -> startSession(bk));
      TextView log = Ui.button(this, "Log pages", v -> logManually(bk));
      Ui.add(page, Ui.buttons(this, start, log), 12);
    }
    TextView status = Ui.button(this, "Move to…", v -> {
      Books.Status[] all = Books.Status.values();
      String[] labels = new String[all.length];
      for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
      Dialogs.choose(this, "Move to", labels, i -> {
        bk.status = all[i];
        if (bk.status == Books.Status.FINISHED) { bk.finished = System.currentTimeMillis(); bk.currentPage = bk.totalPages; }
        filter = bk.status;
        save();
        showBook(bk);
      });
    });
    TextView delete = Ui.button(this, "Delete", v -> Dialogs.confirm(this, "Delete " + bk.title + " and its sessions?",
        "Delete", () -> { books.remove(bk); save(); showList(); }));
    Ui.add(page, Ui.buttons(this, status, delete), 6);

    Ui.add(page, Ui.muted(this, "Sessions"), 14);
    List<Books.Session> sessions = new ArrayList<>(bk.sessions);
    java.util.Collections.reverse(sessions);
    DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
    Pager<Books.Session> p = new Pager<>(this, Pager.fit(this, 52, 520), "No sessions yet.", s -> {
      LinearLayout r = Ui.row(this);
      int pad = Ui.dp(this, 8);
      r.setPadding(0, pad, 0, pad);
      r.addView(Ui.text(this, df.format(new Date(s.start))), Ui.weight(1));
      r.addView(Ui.text(this, s.pages() + " p · " + Books.duration(s.minutes)));
      return r;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    page.addView(p.view(), lp);
    p.setItems(sessions);
    setPage(page);
  }

  private void editBook(Books.Book existing) {
    Books.Book bk = existing != null ? existing : new Books.Book();
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText title = Ui.input(this, "Title", bk.title);
    EditText author = Ui.input(this, "Author", bk.author);
    EditText total = Ui.numberInput(this, "Total pages", bk.totalPages > 0 ? String.valueOf(bk.totalPages) : "");
    EditText current = Ui.numberInput(this, "Current page", String.valueOf(bk.currentPage));
    form.addView(title, Ui.fill());
    Ui.add(form, author, 6);
    Ui.add(form, Ui.buttons(this, total, current), 6);
    Ui.add(form, Ui.muted(this, "Total pages / current page"), 4);
    Dialogs.builder(this).setTitle(existing == null ? "Add a book" : "Edit book").setView(form)
        .setPositiveButton("Save", (d, w) -> {
          String t = title.getText().toString().trim();
          if (t.isEmpty()) { toast("A book needs a title"); return; }
          bk.title = t;
          bk.author = author.getText().toString().trim();
          bk.totalPages = Math.max(0, Ui.parseInt(total.getText().toString(), 0));
          bk.currentPage = Math.max(0, Ui.parseInt(current.getText().toString(), bk.currentPage));
          if (existing == null) { books.add(0, bk); filter = bk.status; }
          save();
          showBook(bk);
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void logManually(Books.Book bk) {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText page = Ui.numberInput(this, "Now on page", "");
    EditText minutes = Ui.numberInput(this, "Minutes read", "");
    form.addView(page, Ui.fill());
    Ui.add(form, minutes, 6);
    Dialogs.builder(this).setTitle("Log reading").setView(form)
        .setPositiveButton("Save", (d, w) -> {
          int to = Ui.parseInt(page.getText().toString(), bk.currentPage);
          finishLog(bk, System.currentTimeMillis(), Math.max(0, Ui.parseInt(minutes.getText().toString(), 0)), to);
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void finishLog(Books.Book bk, long start, int minutes, int toPage) {
    boolean done = bk.log(start, minutes, toPage);
    save();
    if (done) {
      Dialogs.confirm(this, "That's the last page! Mark “" + bk.title + "” as finished?", "Finished", () -> {
        bk.status = Books.Status.FINISHED;
        bk.finished = System.currentTimeMillis();
        save();
        showBook(bk);
      });
    }
    showBook(bk);
  }

  // ---- timer ----

  private void startSession(Books.Book bk) {
    prefs.edit().putString("sessionBook", bk.id).putLong("sessionStart", System.currentTimeMillis()).apply();
    showTimer(bk);
  }

  private void showTimer(Books.Book bk) {
    openBook = bk;
    LinearLayout page = Ui.column(this);
    page.addView(header("Reading"), Ui.fill());
    TextView t = Ui.title(this, bk.title);
    t.setGravity(Gravity.CENTER);
    Ui.add(page, t, 24);
    TextView from = Ui.muted(this, "Started on page " + bk.currentPage);
    from.setGravity(Gravity.CENTER);
    page.addView(from, Ui.fill());
    elapsed = Ui.text(this, "", Ui.large(this) ? 8f : 6f);
    elapsed.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    elapsed.setGravity(Gravity.CENTER);
    Ui.add(page, elapsed, 24);
    TextView unit = Ui.muted(this, "minutes");
    unit.setGravity(Gravity.CENTER);
    page.addView(unit, Ui.fill());
    LinearLayout spacer = Ui.column(this);
    page.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView stop = Ui.button(this, "Stop and save", v -> {
      int minutes = minutesElapsed();
      Dialogs.number(this, "What page are you on now?", bk.currentPage, n -> {
        long start = prefs.getLong("sessionStart", System.currentTimeMillis());
        endSession();
        finishLog(bk, start, minutes, n);
      });
    });
    TextView cancel = Ui.button(this, "Discard", v -> Dialogs.confirm(this, "Discard this session?", "Discard", () -> {
      endSession();
      showBook(bk);
    }));
    Ui.add(page, Ui.buttons(this, stop), 12);
    Ui.add(page, Ui.buttons(this, cancel), 6);
    setPage(page);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    updateElapsed();
    handler.removeCallbacks(minuteTicker);
    handler.post(minuteTicker);
  }

  private void endSession() {
    prefs.edit().remove("sessionBook").remove("sessionStart").apply();
    handler.removeCallbacks(minuteTicker);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    elapsed = null;
  }

  private int minutesElapsed() {
    long start = prefs.getLong("sessionStart", System.currentTimeMillis());
    return (int) Math.max(1, Math.round((System.currentTimeMillis() - start) / 60_000.0));
  }

  private void updateElapsed() {
    if (elapsed == null) return;
    String s = String.valueOf(Math.max(0, (System.currentTimeMillis() - prefs.getLong("sessionStart",
        System.currentTimeMillis())) / 60_000));
    if (!s.contentEquals(elapsed.getText())) elapsed.setText(s);
  }

  // ---- storage ----

  private Books.Book find(String id) {
    for (Books.Book b : books) if (b.id.equals(id)) return b;
    return null;
  }

  private void load() {
    JSONArray a = Store.readArray(this, FILE);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Books.Book b = new Books.Book();
      b.id = o.optString("id", b.id);
      b.title = o.optString("title");
      b.author = o.optString("author");
      b.totalPages = o.optInt("total");
      b.currentPage = o.optInt("current");
      try { b.status = Books.Status.valueOf(o.optString("status", "READING")); }
      catch (IllegalArgumentException e) { b.status = Books.Status.READING; }
      b.added = o.optLong("added", b.added);
      b.finished = o.optLong("finished");
      JSONArray ss = o.optJSONArray("sessions");
      for (int k = 0; ss != null && k < ss.length(); k++) {
        JSONObject j = ss.optJSONObject(k);
        if (j == null) continue;
        Books.Session s = new Books.Session();
        s.start = j.optLong("start");
        s.minutes = j.optInt("min");
        s.fromPage = j.optInt("from");
        s.toPage = j.optInt("to");
        b.sessions.add(s);
      }
      books.add(b);
    }
  }

  private void save() {
    JSONArray a = new JSONArray();
    try {
      for (Books.Book b : books) {
        JSONArray ss = new JSONArray();
        for (Books.Session s : b.sessions)
          ss.put(new JSONObject().put("start", s.start).put("min", s.minutes).put("from", s.fromPage).put("to", s.toPage));
        a.put(new JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("total", b.totalPages)
            .put("current", b.currentPage).put("status", b.status.name()).put("added", b.added)
            .put("finished", b.finished).put("sessions", ss));
      }
    } catch (JSONException e) {
      return;
    }
    if (!Store.write(this, FILE, a)) toast("Couldn't save");
  }
}
