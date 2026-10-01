package dev.slate.notes;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class LibraryActivity extends BaseActivity {
  private static final class Book { File file; String title, info; long opened; }

  private final List<Notebook> books = new ArrayList<>();
  private final List<Book> reads = new ArrayList<>();
  private boolean showBooks = true;
  private BaseAdapter adapter;
  private TextView empty, booksTab, notesTab, action;
  private LinearLayout banner;
  private int scanGen;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    showBooks = BuildConfig.READER;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(Ui.dp(this, 16), 0, 0, 0);
    TextView title = new TextView(this);
    title.setText(BuildConfig.READER ? "Folio" : "Slate");
    title.setTextSize(26);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(Color.BLACK);
    bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-2, -1);
    action = Ui.button(this, "", v -> { if (showBooks) scanBooks(); else newMenu(); });
    bar.addView(action, wrap);
    bar.addView(Ui.button(this, "⚙", v -> settings()), wrap);
    root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

    LinearLayout tabs = new LinearLayout(this);
    booksTab = Ui.button(this, "Books", v -> setTab(true));
    notesTab = Ui.button(this, "Notebooks", v -> setTab(false));
    tabs.addView(booksTab, new LinearLayout.LayoutParams(0, -1, 1));
    tabs.addView(notesTab, new LinearLayout.LayoutParams(0, -1, 1));
    // One app, one kind of thing: Folio shows books, Slate shows notebooks.
    tabs.setVisibility(View.GONE);
    root.addView(tabs, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    root.addView(Ui.rule(this));

    banner = new LinearLayout(this);
    banner.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 16);
    banner.setPadding(p, p, p, p);
    TextView msg = new TextView(this);
    msg.setText(BuildConfig.READER
        ? "Allow all-files access so Folio can find your books, use the /Fonts folder, and keep notes and exports in Documents/Folio."
        : "Allow all-files access so Slate can use the /Fonts folder and keep your notebooks in Documents/Slate.");
    msg.setTextSize(16);
    msg.setTextColor(Color.BLACK);
    banner.addView(msg);
    TextView allow = Ui.button(this, "Allow access", v -> Storage.requestAccess(this));
    allow.setPadding(0, p / 2, 0, 0);
    allow.setGravity(Gravity.START);
    banner.addView(allow, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
    root.addView(banner);

    FrameLayout body = new FrameLayout(this);
    ListView list = new ListView(this);
    list.setDivider(new android.graphics.drawable.ColorDrawable(0xFF888888));
    list.setDividerHeight(1);
    list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    list.setOverScrollMode(View.OVER_SCROLL_NEVER);
    list.setVerticalScrollBarEnabled(false);
    adapter = new BaseAdapter() {
      public int getCount() { return showBooks ? reads.size() : books.size(); }
      public Object getItem(int i) { return i; }
      public long getItemId(int i) { return i; }
      public View getView(int i, View cv, ViewGroup parent) {
        LinearLayout row = cv instanceof LinearLayout ? (LinearLayout) cv : makeRow();
        TextView name = (TextView) row.getChildAt(0), info = (TextView) row.getChildAt(1);
        if (showBooks) {
          Book bk = reads.get(i);
          name.setText(bk.title);
          info.setText(bk.info);
        } else {
          Notebook nb = books.get(i);
          name.setText(nb.name);
          int n = nb.pageCount();
          info.setText(n + (n == 1 ? " page" : " pages") + "  ·  " + Templates.name(nb.template)
              + "  ·  " + DateFormat.format("MMM d, h:mm a", nb.modified));
        }
        return row;
      }
    };
    list.setAdapter(adapter);
    list.setOnItemClickListener((par, v, i, id) -> {
      if (showBooks) startActivity(new Intent(this, NoteActivity.class).putExtra("book", reads.get(i).file.getPath()));
      else startActivity(new Intent(this, NoteActivity.class).putExtra("dir", books.get(i).dir.getPath()));
    });
    list.setOnItemLongClickListener((par, v, i, id) -> {
      if (!showBooks) bookMenu(books.get(i));
      return true;
    });
    body.addView(list);
    empty = new TextView(this);
    empty.setTextSize(18);
    empty.setTextColor(Color.BLACK);
    empty.setGravity(Gravity.CENTER);
    int ep = Ui.dp(this, 32);
    empty.setPadding(ep, ep, ep, ep);
    body.addView(empty);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    handleIncoming(getIntent());
  }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIncoming(i); }

  /** Opening a .slnote from a file manager imports it. */
  private void handleIncoming(Intent i) {
    if (i == null || i.getData() == null || !Intent.ACTION_VIEW.equals(i.getAction())) return;
    importPackage(i.getData());
    setIntent(new Intent());
  }

  private LinearLayout makeRow() {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 16);
    row.setPadding(p, p, p, p);
    TextView name = new TextView(this);
    name.setTextSize(20);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    name.setTextColor(Color.BLACK);
    name.setMaxLines(2);
    TextView info = new TextView(this);
    info.setTextSize(14);
    info.setTextColor(0xFF444444);
    row.addView(name);
    row.addView(info);
    return row;
  }

  @Override protected void onResume() {
    super.onResume();
    App.refreshBase(this);
    banner.setVisibility(Storage.hasAccess(this) ? View.GONE : View.VISIBLE);
    setTab(showBooks);
  }

  private void setTab(boolean bks) {
    showBooks = bks;
    Ui.setActive(booksTab, bks);
    Ui.setActive(notesTab, !bks);
    action.setText(bks ? "Rescan" : "+ New");
    if (bks) scanBooks(); else refreshNotebooks();
  }

  private void refreshNotebooks() {
    books.clear();
    books.addAll(Notebook.list());
    adapter.notifyDataSetChanged();
    empty.setText("No notebooks yet.\nTap “+ New” to start one.");
    empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
  }

  // ---------------------------------------------------------------- books

  private void scanBooks() {
    if (!Storage.hasAccess(this)) {
      reads.clear();
      adapter.notifyDataSetChanged();
      empty.setText("Books appear here once Slate has file access.");
      empty.setVisibility(View.VISIBLE);
      return;
    }
    int gen = ++scanGen;
    if (reads.isEmpty()) { empty.setText("Looking for books…"); empty.setVisibility(View.VISIBLE); }
    new Thread(() -> {
      List<Book> found = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      Set<File> skip = new HashSet<>(Arrays.asList(Storage.slateRoot(), Storage.folioRoot()));
      for (File root : Storage.bookRoots()) walk(root, 0, found, seen, skip);
      found.sort((a, c) -> a.opened != c.opened ? Long.compare(c.opened, a.opened) : a.title.compareToIgnoreCase(c.title));
      runOnUiThread(() -> {
        if (gen != scanGen || !showBooks) return;
        reads.clear();
        reads.addAll(found);
        adapter.notifyDataSetChanged();
        empty.setText("No books found.\nPut PDF, EPUB, TXT, Markdown, HTML, FB2 or CBZ files in Books, Documents or Download.");
        empty.setVisibility(reads.isEmpty() ? View.VISIBLE : View.GONE);
      });
    }).start();
  }

  private void walk(File dir, int depth, List<Book> out, Set<String> seen, Set<File> skip) {
    if (depth > 6 || skip.contains(dir)) return;
    File[] fs = dir.listFiles();
    if (fs == null) return;
    for (File f : fs) {
      if (f.getName().startsWith(".")) continue;
      if (f.isDirectory()) { walk(f, depth + 1, out, seen, skip); continue; }
      if (!Source.isBook(f.getName())) continue;
      String key;
      try { key = f.getCanonicalPath(); } catch (IOException e) { key = f.getAbsolutePath(); }
      if (!seen.add(key)) continue;
      Book b = new Book();
      b.file = f;
      b.title = Storage.stripExt(f.getName());
      java.util.Properties m = Storage.bookMeta(f);
      try { b.opened = Long.parseLong(m.getProperty("opened", "0")); } catch (NumberFormatException e) { b.opened = 0; }
      String ext = f.getName().substring(f.getName().lastIndexOf('.') + 1).toUpperCase(Locale.US);
      String prog = "";
      try {
        if (m.getProperty("progress") != null) prog = "  ·  " + Math.round(Float.parseFloat(m.getProperty("progress")) * 100) + "%";
      } catch (NumberFormatException ignored) {}
      b.info = ext + "  ·  " + f.getParentFile().getName() + prog;
      out.add(b);
    }
  }

  // ---------------------------------------------------------------- notebooks

  private void newMenu() {
    new AlertDialog.Builder(this)
        .setItems(new String[]{"New notebook", "Import .slnote package"}, (d, w) -> {
          if (w == 0) newNotebook();
          else openDocument(new String[]{"*/*"}, this::importPackage);
        }).show();
  }

  private void importPackage(android.net.Uri uri) {
    toast("Importing…");
    new Thread(() -> {
      String msg;
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        Notebook nb = Exporter.importPackage(in);
        msg = "Imported “" + (nb != null ? nb.name : "notebook") + "”";
      } catch (Exception e) {
        msg = "Import failed: " + e.getMessage();
      }
      String m = msg;
      runOnUiThread(() -> { toast(m); if (!showBooks) refreshNotebooks(); else setTab(false); });
    }).start();
  }

  private void newNotebook() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 20);
    box.setPadding(p, p / 2, p, 0);
    EditText name = new EditText(this);
    name.setHint("Notebook name");
    name.setSingleLine(true);
    box.addView(name);
    String[] chosen = {"lined"};
    TextView tpl = new TextView(this);
    tpl.setTextSize(18);
    tpl.setTextColor(Color.BLACK);
    tpl.setPadding(0, p / 2, 0, p / 2);
    tpl.setText("Template: " + Templates.name(chosen[0]) + "  ▸");
    tpl.setOnClickListener(v -> pickTemplate(chosen[0], k -> {
      chosen[0] = k;
      tpl.setText("Template: " + Templates.name(k) + "  ▸");
    }, null));
    box.addView(tpl);
    new AlertDialog.Builder(this)
        .setTitle("New notebook")
        .setView(box)
        .setPositiveButton("Create", (d, w) -> {
          String n = name.getText().toString().trim();
          if (n.isEmpty()) n = "Notebook " + DateFormat.format("MMM d", System.currentTimeMillis());
          try {
            Notebook nb = Notebook.create(n, chosen[0]);
            startActivity(new Intent(this, NoteActivity.class).putExtra("dir", nb.dir.getPath()));
          } catch (IOException e) { toast("Couldn't create notebook"); }
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void bookMenu(Notebook nb) {
    new AlertDialog.Builder(this)
        .setTitle(nb.name)
        .setItems(new String[]{"Rename", "Change template", "Delete"}, (d, which) -> {
          if (which == 0) rename(nb);
          else if (which == 1) pickTemplate(nb.template, k -> { nb.template = k; nb.saveMeta(); refreshNotebooks(); }, null);
          else confirmDelete(nb);
        })
        .show();
  }

  private void rename(Notebook nb) {
    EditText e = new EditText(this);
    e.setText(nb.name);
    e.setSingleLine(true);
    new AlertDialog.Builder(this).setTitle("Rename").setView(e)
        .setPositiveButton("Save", (d, w) -> {
          String n = e.getText().toString().trim();
          if (!n.isEmpty()) { nb.name = n; nb.saveMeta(); refreshNotebooks(); }
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void confirmDelete(Notebook nb) {
    new AlertDialog.Builder(this)
        .setTitle("Delete “" + nb.name + "”?")
        .setMessage("This removes every page. It can't be undone.")
        .setPositiveButton("Delete", (d, w) -> { nb.deleteAll(); refreshNotebooks(); })
        .setNegativeButton("Cancel", null).show();
  }

  // ---------------------------------------------------------------- settings

  private void settings() {
    SharedPreferences sp = getSharedPreferences("slate", MODE_PRIVATE);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 20);
    box.setPadding(p, p / 2, p, 0);
    RadioGroup fingers = group(box, "Turn pages with", new String[]{"1 finger", "2 fingers", "3 fingers"},
        Math.max(1, Math.min(3, sp.getInt("turnFingers", 1))) - 1);
    RadioGroup mode = group(box, "Gesture", new String[]{"Tap (left third = back)", "Swipe", "Tap or swipe"},
        Math.max(0, Math.min(2, sp.getInt("turnMode", 2))));
    TextView note = new TextView(this);
    note.setText("Two-finger double tap always shows or hides the toolbar.\n\nFiles: "
        + App.base.getPath() + "\nFonts: " + Fonts.dir().getPath());
    note.setTextColor(0xFF555555);
    note.setTextSize(13);
    note.setPadding(0, p / 2, 0, 0);
    box.addView(note);
    ScrollView sv = new ScrollView(this);
    sv.addView(box);
    new AlertDialog.Builder(this).setTitle("Settings").setView(sv)
        .setPositiveButton("Save", (d, w) -> sp.edit()
            .putInt("turnFingers", fingers.getCheckedRadioButtonId() + 1)
            .putInt("turnMode", mode.getCheckedRadioButtonId()).apply())
        .setNegativeButton("Cancel", null).show();
  }

  private RadioGroup group(LinearLayout box, String label, String[] opts, int checked) {
    TextView t = new TextView(this);
    t.setText(label);
    t.setTextColor(0xFF555555);
    t.setPadding(0, Ui.dp(this, 8), 0, 0);
    box.addView(t);
    RadioGroup g = new RadioGroup(this);
    for (int i = 0; i < opts.length; i++) {
      RadioButton rb = new RadioButton(this);
      rb.setText(opts[i]);
      rb.setId(i);
      g.addView(rb);
    }
    g.check(checked);
    box.addView(g);
    return g;
  }
}
