package dev.jacob.lists;

import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Several checklists: to-dos, groceries, packing. Tick to sink an item to the bottom. */
public class MainActivity extends InkActivity {
  private static final String FILE = "lists.json";

  private final List<Checklist> lists = new ArrayList<>();
  private Checklist open;
  private Pager<?> pager;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    load();
    String last = getSharedPreferences("lists", MODE_PRIVATE).getString("open", null);
    for (Checklist c : lists) if (c.id.equals(last)) open = c;
    Intent in = getIntent();
    if (Intent.ACTION_SEND.equals(in.getAction()) && in.getStringExtra(Intent.EXTRA_TEXT) != null) {
      importShared(in.getStringExtra(Intent.EXTRA_TEXT));
    }
    if (open != null) showList(open); else showLists();
  }

  @Override protected void onPause() {
    super.onPause();
    save();
    getSharedPreferences("lists", MODE_PRIVATE).edit().putString("open", open == null ? null : open.id).apply();
  }

  @Override protected boolean onPageKey(int dir) { return pager != null && pager.turn(dir); }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (open != null) showLists(); else super.onBackPressed();
  }

  // ---- all lists ----

  private void showLists() {
    open = null;
    LinearLayout page = Ui.column(this);
    page.addView(header("Lists", Ui.button(this, "+ New", v -> Dialogs.prompt(this, "New list", "", n -> {
      if (n.trim().isEmpty()) return;
      Checklist c = new Checklist(n.trim());
      lists.add(0, c);
      save();
      showList(c);
    })), refreshButton()), Ui.fill());
    Pager<Checklist> p = new Pager<>(this, Pager.fit(this, 64, 180), "No lists yet. Tap + New.", c -> {
      LinearLayout row = Ui.row(this);
      int pad = Ui.dp(this, 12);
      row.setPadding(pad, pad, pad, pad);
      TextView name = Ui.title(this, c.name);
      row.addView(name, Ui.weight(1));
      int left = c.remaining();
      row.addView(Ui.muted(this, left == 0 && !c.items.isEmpty() ? "all done" : left + " to do"));
      row.setOnClickListener(v -> showList(c));
      row.setOnLongClickListener(v -> { listMenu(c); return true; });
      return row;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(p.view(), lp);
    p.setItems(lists);
    Ui.add(page, Ui.muted(this, "Hold a list to rename, share or delete it."), 6);
    setPage(page);
  }

  private void listMenu(Checklist c) {
    Dialogs.choose(this, c.name, new String[]{"Rename…", "Share as text…", "Delete"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Rename", c.name, n -> { if (!n.trim().isEmpty()) { c.name = n.trim(); save(); refresh(); } });
      else if (i == 1) share(c);
      else Dialogs.confirm(this, "Delete “" + c.name + "” and all its items?", "Delete", () -> {
        lists.remove(c);
        save();
        showLists();
      });
    });
  }

  // ---- one list ----

  private void showList(Checklist c) {
    open = c;
    LinearLayout page = Ui.column(this);
    page.addView(header(c.name, Ui.button(this, "←", v -> showLists()), Ui.button(this, "⋯", v -> openMenu()),
        refreshButton()), Ui.fill());
    EditText input = Ui.input(this, "Add an item…", "");
    input.setImeOptions(EditorInfo.IME_ACTION_DONE);
    Runnable add = () -> {
      String t = input.getText().toString();
      if (t.trim().isEmpty()) return;
      c.add(t);
      input.setText("");
      save();
      pager.turn(-pager.page());
      refresh();
    };
    // Enter adds the item and keeps the keyboard open for the next one.
    input.setOnEditorActionListener((v, id, e) -> {
      boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
      if (id == EditorInfo.IME_ACTION_DONE || enter) { add.run(); return true; }
      return false;
    });
    LinearLayout entry = Ui.row(this);
    entry.addView(input, Ui.weight(1));
    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
    ap.leftMargin = Ui.dp(this, 6);
    entry.addView(Ui.button(this, "Add", v -> add.run()), ap);
    Ui.add(page, entry, 8);
    Pager<Checklist.Item> p = new Pager<>(this, Pager.fit(this, 58, 240), "Nothing here yet.", this::itemRow);
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(p.view(), lp);
    p.setItems(c.items);
    setPage(page);
  }

  private View itemRow(Checklist.Item it) {
    LinearLayout row = Ui.row(this);
    int pad = Ui.dp(this, 6);
    row.setPadding(0, pad, 0, pad);
    TextView box = Ui.button(this, it.done ? "✓" : "", v -> tick(it));
    Ui.setActive(box, it.done);
    row.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
    TextView t = Ui.text(this, it.text);
    if (it.done) {
      t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
      t.setTextColor(Ui.MUTED);
    } else {
      t.setTypeface(Typeface.DEFAULT);
    }
    LinearLayout.LayoutParams tp = Ui.weight(1);
    tp.leftMargin = Ui.dp(this, 10);
    row.addView(t, tp);
    row.setOnClickListener(v -> tick(it));
    row.setOnLongClickListener(v -> { itemMenu(it); return true; });
    return row;
  }

  private void tick(Checklist.Item it) {
    open.toggle(it);
    save();
    refresh();
  }

  private void itemMenu(Checklist.Item it) {
    Dialogs.choose(this, it.text, new String[]{"Edit…", "Move up", "Move down", "Delete"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Edit item", it.text, n -> { if (!n.trim().isEmpty()) it.text = n.trim(); save(); refresh(); });
      else if (i == 1) { open.move(it, -1); save(); refresh(); }
      else if (i == 2) { open.move(it, 1); save(); refresh(); }
      else { open.items.remove(it); save(); refresh(); }
    });
  }

  private void openMenu() {
    Checklist c = open;
    Dialogs.choose(this, c.name, new String[]{"Clear finished items", "Untick everything (reuse list)",
        "Paste several items…", "Share as text…", "Rename…"}, i -> {
      switch (i) {
        case 0: toast("Removed " + c.clearDone()); break;
        case 1: c.uncheckAll(); break;
        case 2: Dialogs.prompt(this, "One item per line", "", android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE, t -> { toast("Added " + c.addLines(t)); save(); refresh(); }); return;
        case 3: share(c); return;
        default: Dialogs.prompt(this, "Rename", c.name, n -> { if (!n.trim().isEmpty()) { c.name = n.trim(); save(); showList(c); } }); return;
      }
      save();
      refresh();
    });
  }

  @SuppressWarnings("unchecked")
  private void refresh() {
    if (open != null) ((Pager<Checklist.Item>) pager).setItems(open.items);
    else ((Pager<Checklist>) pager).setItems(lists);
  }

  private void share(Checklist c) {
    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, c.toText()), "Share list"));
  }

  /** Text shared from another app becomes a new list, one item per line. */
  private void importShared(String text) {
    Checklist c = new Checklist("Shared list");
    c.addLines(text);
    if (c.items.isEmpty()) return;
    lists.add(0, c);
    open = c;
    save();
  }

  // ---- storage ----

  private void load() {
    JSONArray a = Store.readArray(this, FILE);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Checklist c = new Checklist(o.optString("name", "List"));
      c.id = o.optString("id", c.id);
      JSONArray items = o.optJSONArray("items");
      for (int k = 0; items != null && k < items.length(); k++) {
        JSONObject it = items.optJSONObject(k);
        if (it != null) c.items.add(new Checklist.Item(it.optString("t"), it.optBoolean("d")));
      }
      lists.add(c);
    }
  }

  private void save() {
    JSONArray a = new JSONArray();
    try {
      for (Checklist c : lists) {
        JSONArray items = new JSONArray();
        for (Checklist.Item it : c.items) items.put(new JSONObject().put("t", it.text).put("d", it.done));
        a.put(new JSONObject().put("id", c.id).put("name", c.name).put("items", items));
      }
    } catch (JSONException e) {
      return;
    }
    if (!Store.write(this, FILE, a)) toast("Couldn't save");
  }
}
