package dev.jacob.journal;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** One page per day, a prompt to get started, a calendar of the days you wrote, and search. */
public class MainActivity extends InkActivity {
  private static final DateTimeFormatter LONG = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL);

  private final Handler handler = new Handler(Looper.getMainLooper());
  private File dir;
  private LocalDate day = LocalDate.now();
  private YearMonth month = YearMonth.now();
  private EditText editor;
  private int mood;
  private boolean dirty;
  private int promptOffset;
  private Pager<?> pager;
  private int screen; // 0 = day, 1 = calendar, 2 = search results

  private final Runnable idleSave = this::save;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    dir = new File(getFilesDir(), "journal");
    if (!dir.isDirectory() && !dir.mkdirs()) toast("Can't create the journal folder");
    showDay(LocalDate.now());
  }

  @Override protected void onPause() {
    super.onPause();
    handler.removeCallbacks(idleSave);
    save();
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (screen != 0) showDay(day); else super.onBackPressed();
  }

  @Override protected boolean onPageKey(int dir) {
    if (screen == 0) { showDay(day.plusDays(dir)); return true; }
    if (screen == 1) { month = month.plusMonths(dir); showCalendar(); return true; }
    return pager != null && pager.turn(dir);
  }

  // ---- one day ----

  private void showDay(LocalDate d) {
    save();
    screen = 0;
    day = d;
    promptOffset = 0;
    JSONObject entry = readEntry(d);
    mood = entry.optInt("mood", 0);
    dirty = false;

    LinearLayout page = Ui.column(this);
    page.addView(header(d.equals(LocalDate.now()) ? "Today" : d.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
        Ui.button(this, "Calendar", v -> { month = YearMonth.from(day); showCalendar(); }), refreshButton()), Ui.fill());
    TextView prev = Ui.button(this, "←", v -> showDay(day.minusDays(1)));
    TextView today = Ui.button(this, "Today", v -> showDay(LocalDate.now()));
    TextView next = Ui.button(this, "→", v -> showDay(day.plusDays(1)));
    TextView dateLine = Ui.text(this, d.format(LONG));
    dateLine.setGravity(Gravity.CENTER);
    LinearLayout nav = Ui.row(this);
    nav.addView(prev);
    nav.addView(dateLine, Ui.weight(1));
    nav.addView(today);
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
    np.leftMargin = Ui.dp(this, 6);
    nav.addView(next, np);
    Ui.add(page, nav, 8);

    TextView prompt = Ui.muted(this, "");
    prompt.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
    Runnable showPrompt = () -> prompt.setText(Prompts.ALL[(Prompts.indexFor(day) + promptOffset) % Prompts.ALL.length]
        + "   (tap for another)");
    showPrompt.run();
    prompt.setOnClickListener(v -> { promptOffset++; showPrompt.run(); });
    Ui.add(page, prompt, 10);

    TextView[] moods = new TextView[Prompts.MOODS.length];
    for (int i = 0; i < moods.length; i++) {
      moods[i] = Ui.button(this, Prompts.MOODS[i], null);
      moods[i].setTextSize(Ui.body(this) * 0.8f);
    }
    for (int i = 0; i < moods.length; i++) {
      int m = i + 1;
      moods[i].setOnClickListener(v -> {
        mood = mood == m ? 0 : m;
        for (int k = 0; k < moods.length; k++) Ui.setActive(moods[k], k + 1 == mood);
        dirty = true;
        save();
      });
      Ui.setActive(moods[i], m == mood);
    }
    Ui.add(page, Ui.buttons(this, moods), 8);

    editor = new EditText(this);
    editor.setText(entry.optString("text"));
    editor.setHint("Write anything…");
    editor.setTextColor(Ui.INK);
    editor.setHintTextColor(0xFF777777);
    editor.setTypeface(Typeface.SERIF);
    editor.setTextSize(Ui.body(this));
    editor.setLineSpacing(0, 1.3f);
    editor.setGravity(Gravity.TOP | Gravity.START);
    editor.setBackground(Ui.box(this, Ui.PAPER));
    int p = Ui.dp(this, 10);
    editor.setPadding(p, p, p, p);
    editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editor.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) {
        dirty = true;
        handler.removeCallbacks(idleSave);
        handler.postDelayed(idleSave, 2000);
      }
    });
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, 0, 1);
    ep.topMargin = Ui.dp(this, 8);
    page.addView(editor, ep);
    setPage(page);
  }

  // ---- calendar ----

  private void showCalendar() {
    save();
    editor = null;
    screen = 1;
    LinearLayout page = Ui.column(this);
    page.addView(header("Calendar", Ui.button(this, "←", v -> showDay(day)),
        Ui.button(this, "Search", v -> search()), Ui.button(this, "Export", v -> exportAll())), Ui.fill());
    LinearLayout nav = Ui.row(this);
    nav.addView(Ui.button(this, "←", v -> { month = month.minusMonths(1); showCalendar(); }));
    TextView title = Ui.title(this, month.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
    title.setGravity(Gravity.CENTER);
    nav.addView(title, Ui.weight(1));
    nav.addView(Ui.button(this, "→", v -> { month = month.plusMonths(1); showCalendar(); }));
    Ui.add(page, nav, 10);

    DayOfWeek first = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    LinearLayout head = Ui.row(this);
    for (int i = 0; i < 7; i++) {
      String n = first.plus(i).toString();
      TextView t = Ui.muted(this, n.charAt(0) + n.substring(1, 2).toLowerCase(Locale.ROOT));
      t.setGravity(Gravity.CENTER);
      head.addView(t, Ui.weight(1));
    }
    Ui.add(page, head, 10);
    int lead = Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
    int days = month.lengthOfMonth();
    int cells = ((lead + days + 6) / 7) * 7;
    int written = 0;
    LinearLayout row = null;
    for (int i = 0; i < cells; i++) {
      if (i % 7 == 0) { row = Ui.row(this); page.addView(row, Ui.fill()); }
      int dnum = i - lead + 1;
      TextView t = Ui.text(this, dnum >= 1 && dnum <= days ? String.valueOf(dnum) : "");
      t.setGravity(Gravity.CENTER);
      int pad = Ui.dp(this, Ui.large(this) ? 14 : 9);
      t.setPadding(0, pad, 0, pad);
      if (dnum >= 1 && dnum <= days) {
        LocalDate d = month.atDay(dnum);
        boolean has = entryFile(d).isFile();
        if (has) {
          written++;
          // Days you wrote are bold and boxed; today is underlined.
          t.setTypeface(Typeface.DEFAULT_BOLD);
          t.setBackground(Ui.box(this, Ui.PAPER));
        }
        if (d.equals(LocalDate.now())) t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        t.setOnClickListener(v -> showDay(d));
      }
      row.addView(t, Ui.weight(1));
    }
    Ui.add(page, Ui.muted(this, written + " day" + (written == 1 ? "" : "s") + " written this month. Boxed days have entries."), 12);
    setPage(page);
  }

  // ---- search ----

  private void search() {
    Dialogs.prompt(this, "Search your journal", "", q -> {
      if (q.trim().isEmpty()) return;
      List<String[]> hits = new ArrayList<>();
      for (File f : sortedFiles(true)) {
        String text = readEntry(f).optString("text");
        String s = Prompts.snippet(text, q);
        if (s != null) hits.add(new String[]{f.getName().replace(".json", ""), s});
      }
      screen = 2;
      LinearLayout page = Ui.column(this);
      page.addView(header("“" + q.trim() + "”", Ui.button(this, "←", v -> showCalendar())), Ui.fill());
      Pager<String[]> p = new Pager<>(this, Pager.fit(this, 80, 200), "No entries mention that.", h -> {
        LinearLayout r = Ui.column(this);
        int pad = Ui.dp(this, 10);
        r.setPadding(pad, pad, pad, pad);
        LocalDate d = LocalDate.parse(h[0]);
        TextView date = Ui.text(this, d.format(LONG));
        date.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(date);
        r.addView(Ui.muted(this, h[1]));
        r.setOnClickListener(v -> showDay(d));
        return r;
      });
      pager = p;
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
      lp.topMargin = Ui.dp(this, 8);
      page.addView(p.view(), lp);
      p.setItems(hits);
      setPage(page);
    });
  }

  // ---- export ----

  private void exportAll() {
    List<File> files = sortedFiles(false);
    if (files.isEmpty()) { toast("Nothing to export yet"); return; }
    StringBuilder md = new StringBuilder("# Journal\n");
    for (File f : files) {
      JSONObject e = readEntry(f);
      LocalDate d = LocalDate.parse(f.getName().replace(".json", ""));
      md.append("\n## ").append(d.format(LONG));
      int m = e.optInt("mood", 0);
      if (m > 0) md.append(" · ").append(Prompts.MOODS[m - 1]);
      md.append("\n\n").append(e.optString("text").trim()).append('\n');
    }
    createDocument("text/markdown", "journal.md", uri -> {
      try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
        if (out == null) throw new IOException("Can't write there");
        out.write(md.toString().getBytes(StandardCharsets.UTF_8));
        toast("Exported " + files.size() + " entries");
      } catch (IOException ex) {
        toast("Export failed: " + ex.getMessage());
      }
    });
  }

  // ---- storage: one small JSON file per day ----

  private File entryFile(LocalDate d) { return new File(dir, d + ".json"); }

  private JSONObject readEntry(LocalDate d) { return readEntry(entryFile(d)); }

  private JSONObject readEntry(File f) {
    try { return new JSONObject(Store.readText(f)); }
    catch (IOException | JSONException e) { return new JSONObject(); }
  }

  private List<File> sortedFiles(boolean newestFirst) {
    File[] fs = dir.listFiles((d, n) -> n.matches("\\d{4}-\\d{2}-\\d{2}\\.json"));
    List<File> list = new ArrayList<>(fs == null ? new ArrayList<>() : Arrays.asList(fs));
    list.sort((a, b) -> newestFirst ? b.getName().compareTo(a.getName()) : a.getName().compareTo(b.getName()));
    return list;
  }

  private void save() {
    if (editor == null || !dirty) return;
    String text = editor.getText().toString();
    File f = entryFile(day);
    try {
      if (text.trim().isEmpty() && mood == 0) {
        // An emptied day doesn't count as written.
        if (f.isFile() && !f.delete()) toast("Couldn't clear the entry");
      } else {
        Store.writeText(f, new JSONObject().put("text", text).put("mood", mood).toString());
      }
      dirty = false;
    } catch (IOException | JSONException e) {
      toast("Couldn't save: " + e.getMessage());
    }
  }
}
