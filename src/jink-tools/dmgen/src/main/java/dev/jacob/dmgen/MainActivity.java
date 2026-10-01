package dev.jacob.dmgen;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends InkActivity {
  private static final String KEPT = "kept.json";

  private final Generators gen = new Generators(new SecureRandom());
  private Generators.Kind kind = Generators.Kind.NPC;
  private Generators.Style style = Generators.Style.ANY;
  private Generators.Result current;
  private TextView title, body, styleBtn, keptBtn;
  private final TextView[] kindButtons = new TextView[Generators.Kind.values().length];
  private Pager<JSONObject> keptPager;
  private boolean showingKept;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    style = Generators.Style.values()[Math.max(0, Math.min(Generators.Style.values().length - 1,
        getSharedPreferences("dmgen", MODE_PRIVATE).getInt("style", 0)))];
    showMain();
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (showingKept) showMain();
    else super.onBackPressed();
  }

  @Override protected boolean onPageKey(int dir) { return showingKept && keptPager.turn(dir); }

  private void showMain() {
    showingKept = false;
    LinearLayout page = Ui.column(this);
    keptBtn = Ui.button(this, "", v -> showKept());
    page.addView(header("DM Tools", keptBtn, refreshButton()), Ui.fill());

    Generators.Kind[] kinds = Generators.Kind.values();
    int perRow = Ui.large(this) ? 7 : 4;
    for (int start = 0; start < kinds.length; start += perRow) {
      int n = Math.min(perRow, kinds.length - start);
      TextView[] row = new TextView[perRow];
      for (int i = 0; i < perRow; i++) {
        if (i >= n) { row[i] = new TextView(this); continue; }
        Generators.Kind k = kinds[start + i];
        row[i] = kindButtons[k.ordinal()] = Ui.button(this, k.label, v -> { kind = k; roll(); });
      }
      Ui.add(page, Ui.buttons(this, row), start == 0 ? 10 : 4);
    }
    styleBtn = Ui.button(this, "", v -> {
      Generators.Style[] all = Generators.Style.values();
      String[] labels = new String[all.length];
      for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
      Dialogs.choose(this, "Name style for NPCs and names", labels, i -> {
        style = all[i];
        getSharedPreferences("dmgen", MODE_PRIVATE).edit().putInt("style", i).apply();
        showStyle();
      });
    });
    Ui.add(page, styleBtn, 4);

    LinearLayout card = Ui.column(this);
    int p = Ui.dp(this, 14);
    card.setPadding(p, p, p, p);
    card.setBackground(Ui.box(this, Ui.PAPER));
    title = Ui.title(this, "");
    body = Ui.text(this, "");
    body.setTextIsSelectable(true);
    card.addView(title, Ui.fill());
    Ui.add(card, body, 8);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 10);
    page.addView(Ui.scroll(this, card), lp);

    Ui.add(page, Ui.buttons(this, Ui.button(this, "Again", v -> roll()), Ui.button(this, "Keep", v -> keep())), 8);
    setPage(page);
    showStyle();
    updateKeptCount();
    if (current == null) roll(); else show();
  }

  private void showStyle() { styleBtn.setText("Name style: " + style.label); }

  private void roll() {
    current = gen.make(kind, style);
    show();
  }

  private void show() {
    for (Generators.Kind k : Generators.Kind.values()) Ui.setActive(kindButtons[k.ordinal()], k == kind);
    title.setText(current.title);
    body.setText(current.body);
  }

  // ---- kept results ----

  private void keep() {
    if (current == null) return;
    JSONArray a = Store.readArray(this, KEPT), out = new JSONArray();
    try { out.put(new JSONObject().put("title", current.title).put("body", current.body).put("kind", kind.label)); }
    catch (JSONException e) { return; }
    for (int i = 0; i < a.length(); i++) out.put(a.opt(i));
    Store.write(this, KEPT, out);
    toast("Kept “" + current.title + "”");
    updateKeptCount();
  }

  private void updateKeptCount() { keptBtn.setText("Kept (" + Store.readArray(this, KEPT).length() + ")"); }

  private void showKept() {
    showingKept = true;
    LinearLayout page = Ui.column(this);
    page.addView(header("Kept", backButtonToMain(), refreshButton()), Ui.fill());
    keptPager = new Pager<>(this, Pager.fit(this, 76, 200), "Nothing kept yet. Tap Keep on a result you like.",
        this::keptRow);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(keptPager.view(), lp);
    Ui.add(page, Ui.muted(this, "Tap to read, hold to delete."), 6);
    setPage(page);
    loadKept(0);
  }

  private TextView backButtonToMain() { return Ui.button(this, "←", v -> showMain()); }

  private View keptRow(JSONObject o) {
    LinearLayout row = Ui.column(this);
    int p = Ui.dp(this, 10);
    row.setPadding(p, p, p, p);
    row.addView(Ui.title(this, o.optString("title")));
    String first = o.optString("body").split("\n")[0];
    TextView sub = Ui.muted(this, o.optString("kind") + " · " + first);
    sub.setSingleLine(true);
    row.addView(sub);
    row.setOnClickListener(v -> Dialogs.message(this, o.optString("title"), o.optString("body")));
    row.setOnLongClickListener(v -> {
      Dialogs.confirm(this, "Delete “" + o.optString("title") + "”?", "Delete", () -> deleteKept(o));
      return true;
    });
    return row;
  }

  private void deleteKept(JSONObject target) {
    JSONArray a = Store.readArray(this, KEPT), out = new JSONArray();
    boolean removed = false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      if (!removed && o.optString("title").equals(target.optString("title"))
          && o.optString("body").equals(target.optString("body"))) { removed = true; continue; }
      out.put(o);
    }
    Store.write(this, KEPT, out);
    loadKept(keptPager.page());
  }

  private void loadKept(int page) {
    JSONArray a = Store.readArray(this, KEPT);
    List<JSONObject> list = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) list.add(a.optJSONObject(i));
    keptPager.setItems(list, page);
  }
}
