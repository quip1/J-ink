package dev.jacob.typewriter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Distraction-free drafting. The status line only updates when you pause typing, so the screen
 * isn't refreshing a word counter on every keystroke.
 */
public class MainActivity extends InkActivity {
  private static final int[] SIZES = {16, 18, 20, 22, 26, 30};
  private static final long IDLE_MS = 2000;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  private File dir, openFile;
  private EditText editor;
  private TextView status;
  private int startWords;
  private boolean dirty;
  private Pager<File> pager;

  private final Runnable idle = () -> { save(); updateStatus(); };

  /** Blocks deletions (backspace, cut) while still allowing new text and autocorrect replacements. */
  private static final InputFilter NO_DELETE = (source, start, end, dest, dstart, dend) ->
      end - start == 0 && dend > dstart ? dest.subSequence(dstart, dend) : null;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("typewriter", MODE_PRIVATE);
    dir = new File(getFilesDir(), "drafts");
    if (!dir.isDirectory() && !dir.mkdirs()) toast("Can't create the drafts folder");
    String last = prefs.getString("open", null);
    if (last != null && new File(dir, last).isFile()) openDraft(new File(dir, last)); else showList();
  }

  @Override protected void onPause() {
    super.onPause();
    handler.removeCallbacks(idle);
    save();
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (editor != null) closeEditor(); else super.onBackPressed();
  }

  @Override protected boolean onPageKey(int dir) { return editor == null && pager != null && pager.turn(dir); }

  // ---- drafts list ----

  private void showList() {
    editor = null;
    openFile = null;
    prefs.edit().remove("open").apply();
    LinearLayout page = Ui.column(this);
    page.addView(header("Typewriter", Ui.button(this, "Import", v -> importFile()),
        Ui.button(this, "+ New", v -> newDraft("")), refreshButton()), Ui.fill());
    File[] files = dir.listFiles((d, n) -> n.endsWith(".md"));
    List<File> list = new ArrayList<>(files == null ? Collections.<File>emptyList() : Arrays.asList(files));
    list.sort((a, c) -> Long.compare(c.lastModified(), a.lastModified()));
    DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    pager = new Pager<>(this, Pager.fit(this, 76, 200), "No drafts yet. Tap + New and start typing.", f -> {
      String text = read(f);
      LinearLayout row = Ui.column(this);
      int p = Ui.dp(this, 10);
      row.setPadding(p, p, p, p);
      TextView t = Ui.title(this, Words.title(text));
      t.setSingleLine(true);
      row.addView(t);
      row.addView(Ui.muted(this, Words.thousands(Words.count(text)) + " words · " + df.format(new Date(f.lastModified()))));
      row.setOnClickListener(v -> openDraft(f));
      row.setOnLongClickListener(v -> {
        Dialogs.choose(this, Words.title(text), new String[]{"Export as file…", "Share…", "Delete"}, i -> {
          if (i == 0) export(f);
          else if (i == 1) share(read(f));
          else Dialogs.confirm(this, "Delete “" + Words.title(text) + "”?", "Delete", () -> {
            if (!f.delete()) toast("Couldn't delete");
            showList();
          });
        });
        return true;
      });
      return row;
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(pager.view(), lp);
    pager.setItems(list);
    Ui.add(page, Ui.muted(this, "Hold a draft to export, share or delete it."), 6);
    setPage(page);
  }

  private void newDraft(String text) {
    File f = new File(dir, UUID.randomUUID() + ".md");
    try { Store.writeText(f, text); } catch (IOException e) { toast("Couldn't create a draft"); return; }
    openDraft(f);
  }

  // ---- editor ----

  private void openDraft(File f) {
    openFile = f;
    prefs.edit().putString("open", f.getName()).apply();
    String text = read(f);
    startWords = Words.count(text);
    dirty = false;

    LinearLayout page = Ui.column(this);
    LinearLayout bar = Ui.row(this);
    bar.addView(Ui.button(this, "←", v -> closeEditor()));
    status = Ui.muted(this, "");
    status.setGravity(Gravity.CENTER);
    status.setSingleLine(true);
    bar.addView(status, Ui.weight(1));
    bar.addView(Ui.button(this, "Aa", v -> chooseSize()));
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
    mp.leftMargin = Ui.dp(this, 6);
    bar.addView(Ui.button(this, "⋯", v -> menu()), mp);
    page.addView(bar, Ui.fill());
    LinearLayout.LayoutParams rp = Ui.fill();
    rp.topMargin = Ui.dp(this, 6);
    rp.height = Math.max(2, Ui.dp(this, 1));
    page.addView(Ui.rule(this), rp);

    editor = new EditText(this);
    editor.setText(text);
    editor.setBackground(null);
    editor.setTextColor(Ui.INK);
    editor.setTypeface(Typeface.SERIF);
    editor.setLineSpacing(0, 1.35f);
    editor.setGravity(Gravity.TOP | Gravity.START);
    editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editor.setOverScrollMode(View.OVER_SCROLL_NEVER);
    editor.setVerticalScrollBarEnabled(false);
    int pad = Ui.dp(this, Ui.large(this) ? 24 : 8);
    editor.setPadding(pad, pad, pad, pad);
    applyPrefs();
    editor.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) {
        dirty = true;
        handler.removeCallbacks(idle);
        handler.postDelayed(idle, IDLE_MS);
      }
    });
    page.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
    setPage(page);
    editor.setSelection(editor.length());
    editor.requestFocus();
    updateStatus();
  }

  private void closeEditor() {
    handler.removeCallbacks(idle);
    save();
    showList();
  }

  private void applyPrefs() {
    editor.setTextSize(prefs.getInt("size", Ui.large(this) ? 22 : 18));
    editor.setCursorVisible(!prefs.getBoolean("hideCursor", false));
    editor.setFilters(prefs.getBoolean("noDelete", false) ? new InputFilter[]{NO_DELETE} : new InputFilter[0]);
  }

  private void updateStatus() {
    if (editor == null) return;
    int words = Words.count(editor.getText());
    int goal = prefs.getInt("goal", 0);
    int session = words - startWords;
    StringBuilder s = new StringBuilder(Words.thousands(words)).append(" words");
    s.append(" · ").append(session >= 0 ? "+" : "").append(session).append(" this session");
    if (goal > 0) s.append(session >= goal ? " · goal reached" : " · " + (goal - session) + " to goal");
    if (prefs.getBoolean("noDelete", false)) s.append(" · no-delete");
    String text = s.toString();
    if (!text.contentEquals(status.getText())) status.setText(text);
  }

  private void chooseSize() {
    String[] labels = new String[SIZES.length];
    for (int i = 0; i < SIZES.length; i++) labels[i] = SIZES[i] + " pt";
    Dialogs.choose(this, "Text size", labels, i -> {
      prefs.edit().putInt("size", SIZES[i]).apply();
      applyPrefs();
    });
  }

  private void menu() {
    boolean hide = prefs.getBoolean("hideCursor", false), noDel = prefs.getBoolean("noDelete", false);
    int goal = prefs.getInt("goal", 0);
    String[] items = {
        "Session word goal" + (goal > 0 ? " (" + goal + ")" : "") + "…",
        hide ? "Show cursor" : "Hide cursor (stops it blinking)",
        noDel ? "Allow deleting again" : "No-delete mode (first drafts: keep going)",
        "Export as file…", "Share…", "Full refresh", "Delete this draft"};
    Dialogs.choose(this, "Options", items, i -> {
      switch (i) {
        case 0: Dialogs.number(this, "Words to write this session (0 = none)", goal, n -> {
          prefs.edit().putInt("goal", Math.max(0, n)).apply();
          updateStatus();
        }); break;
        case 1: prefs.edit().putBoolean("hideCursor", !hide).apply(); applyPrefs(); break;
        case 2: prefs.edit().putBoolean("noDelete", !noDel).apply(); applyPrefs(); updateStatus(); break;
        case 3: save(); export(openFile); break;
        case 4: share(editor.getText().toString()); break;
        case 5: fullRefresh(); break;
        default: Dialogs.confirm(this, "Delete this draft? This can't be undone.", "Delete", () -> {
          handler.removeCallbacks(idle);
          File f = openFile;
          editor = null;
          if (!f.delete()) toast("Couldn't delete");
          showList();
        });
      }
    });
  }

  // ---- files ----

  private void save() {
    if (editor == null || openFile == null || !dirty) return;
    try {
      Store.writeText(openFile, editor.getText().toString());
      dirty = false;
    } catch (IOException e) {
      toast("Couldn't save: " + e.getMessage());
    }
  }

  private String read(File f) {
    try { return Store.readText(f); } catch (IOException e) { return ""; }
  }

  private void export(File f) {
    String text = read(f);
    String name = Words.title(text).replaceAll("[\\\\/:*?\"<>|]", "-") + ".md";
    createDocument("text/markdown", name, uri -> {
      try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
        if (out == null) throw new IOException("Can't write there");
        out.write(text.getBytes(StandardCharsets.UTF_8));
        toast("Exported");
      } catch (IOException e) {
        toast("Export failed: " + e.getMessage());
      }
    });
  }

  private void share(String text) {
    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
    startActivity(Intent.createChooser(i, "Share draft"));
  }

  private void importFile() {
    openDocument(new String[]{"text/*"}, (Uri uri) -> {
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("Can't open that file");
        newDraft(Store.readAll(in));
      } catch (IOException e) {
        toast("Import failed: " + e.getMessage());
      }
    });
  }
}
