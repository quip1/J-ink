package dev.jacob.dice;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends InkActivity {
  private static final String SAVED = "saved.json", HISTORY = "history.json";
  private static final int HISTORY_MAX = 50;
  private static final int[] DICE = {4, 6, 8, 10, 12, 20, 100};

  private final DiceRoller roller = new DiceRoller(new SecureRandom());
  private TextView total, flag, detail, modBtn;
  private EditText input;
  private int modifier;
  private Pager<JSONObject> savedPager;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout page = Ui.column(this);
    page.addView(header("Dice", Ui.button(this, "History", v -> showHistory()), refreshButton()), Ui.fill());

    total = Ui.text(this, "—", Ui.large(this) ? 5f : 4f);
    total.setTypeface(Typeface.DEFAULT_BOLD);
    total.setGravity(Gravity.CENTER);
    flag = Ui.title(this, "");
    flag.setGravity(Gravity.CENTER);
    detail = Ui.muted(this, "Tap a die or type an expression");
    detail.setGravity(Gravity.CENTER);
    Ui.add(page, total, 4);
    page.addView(flag, Ui.fill());
    page.addView(detail, Ui.fill());

    input = Ui.input(this, "e.g. 1d20+5, 4d6dl1, adv+3", "");
    input.setImeOptions(EditorInfo.IME_ACTION_GO);
    input.setOnEditorActionListener((v, id, e) -> {
      boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
      if (id == EditorInfo.IME_ACTION_GO || enter) { rollExpression(input.getText().toString()); return true; }
      return false;
    });
    LinearLayout entry = Ui.row(this);
    entry.addView(input, Ui.weight(1));
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
    rp.leftMargin = Ui.dp(this, 6);
    entry.addView(Ui.button(this, "Roll", v -> rollExpression(input.getText().toString())), rp);
    entry.addView(Ui.button(this, "Save", v -> saveCurrent()), new LinearLayout.LayoutParams(rp));
    Ui.add(page, entry, 12);

    TextView[] firstRow = new TextView[4], secondRow = new TextView[4];
    for (int i = 0; i < DICE.length; i++) {
      int sides = DICE[i];
      TextView t = Ui.button(this, "d" + sides, v -> rollQuick("1d" + sides));
      if (i < 4) firstRow[i] = t; else secondRow[i - 4] = t;
    }
    secondRow[3] = Ui.button(this, "4d6↓", v -> rollExpression("6x 4d6dl1"));
    Ui.add(page, Ui.buttons(this, firstRow), 10);
    Ui.add(page, Ui.buttons(this, secondRow), 6);

    modBtn = Ui.button(this, "", v -> Dialogs.number(this, "Modifier for quick rolls", modifier, n -> { modifier = n; showMod(); }));
    TextView adv = Ui.button(this, "Adv", v -> rollQuick("adv"));
    TextView dis = Ui.button(this, "Dis", v -> rollQuick("dis"));
    TextView minus = Ui.button(this, "−", v -> { modifier--; showMod(); });
    TextView plus = Ui.button(this, "+", v -> { modifier++; showMod(); });
    Ui.add(page, Ui.buttons(this, adv, dis, minus, modBtn, plus), 6);
    showMod();

    Ui.add(page, Ui.muted(this, "Saved rolls — tap to roll, hold to delete"), 14);
    savedPager = new Pager<>(this, Ui.large(this) ? 6 : 3, "Nothing saved yet. Type a roll and tap Save.", this::savedRow);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
    sp.topMargin = Ui.dp(this, 4);
    page.addView(savedPager.view(), sp);
    loadSaved();

    setPage(page);
  }

  @Override protected boolean onPageKey(int dir) { return savedPager.turn(dir); }

  private void showMod() { modBtn.setText(modifier >= 0 ? "+" + modifier : String.valueOf(modifier)); }

  private void rollQuick(String base) {
    rollExpression(modifier == 0 ? base : base + (modifier > 0 ? "+" : "") + modifier);
  }

  private void rollExpression(String expr) {
    DiceRoller.Result r;
    try {
      r = roller.roll(expr);
    } catch (IllegalArgumentException e) {
      total.setText("?");
      flag.setText("");
      detail.setText(e.getMessage());
      return;
    }
    total.setText(r.totalText());
    flag.setText(r.flag == null ? "" : r.flag);
    detail.setText(r.expression + "\n" + r.detail);
    addHistory(r);
  }

  // ---- saved rolls ----

  private View savedRow(JSONObject o) {
    String name = o.optString("name"), expr = o.optString("expr");
    LinearLayout row = Ui.row(this);
    int p = Ui.dp(this, 10);
    row.setPadding(p, p, p, p);
    TextView n = Ui.text(this, name);
    n.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(n, Ui.weight(1));
    row.addView(Ui.text(this, expr));
    row.setOnClickListener(v -> rollExpression(expr));
    row.setOnLongClickListener(v -> {
      Dialogs.confirm(this, "Delete \"" + name + "\"?", "Delete", () -> deleteSaved(o));
      return true;
    });
    return row;
  }

  private void saveCurrent() {
    String expr = input.getText().toString().trim();
    if (expr.isEmpty()) { toast("Type a roll first, then Save"); return; }
    try { roller.roll(expr); } catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
    Dialogs.prompt(this, "Name this roll (e.g. Longsword)", "", name -> {
      JSONArray a = Store.readArray(this, SAVED);
      try { a.put(new JSONObject().put("name", name.isEmpty() ? expr : name).put("expr", expr)); }
      catch (JSONException ignored) { return; }
      Store.write(this, SAVED, a);
      loadSaved();
    });
  }

  private void deleteSaved(JSONObject target) {
    JSONArray a = Store.readArray(this, SAVED), out = new JSONArray();
    boolean removed = false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (!removed && o != null && o.optString("name").equals(target.optString("name"))
          && o.optString("expr").equals(target.optString("expr"))) { removed = true; continue; }
      out.put(o);
    }
    Store.write(this, SAVED, out);
    loadSaved();
  }

  private void loadSaved() {
    JSONArray a = Store.readArray(this, SAVED);
    List<JSONObject> list = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) list.add(a.optJSONObject(i));
    savedPager.setItems(list);
  }

  // ---- history ----

  private void addHistory(DiceRoller.Result r) {
    JSONArray a = Store.readArray(this, HISTORY), out = new JSONArray();
    try {
      out.put(new JSONObject().put("expr", r.expression).put("total", r.totalText()).put("detail", r.detail)
          .put("flag", r.flag == null ? "" : r.flag));
    } catch (JSONException ignored) { return; }
    for (int i = 0; i < a.length() && out.length() < HISTORY_MAX; i++) out.put(a.opt(i));
    Store.write(this, HISTORY, out);
  }

  private void showHistory() {
    JSONArray a = Store.readArray(this, HISTORY);
    if (a.length() == 0) { Dialogs.message(this, "History", "No rolls yet."); return; }
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      b.append(o.optString("total")).append("   ").append(o.optString("expr"));
      if (!o.optString("flag").isEmpty()) b.append("   ").append(o.optString("flag"));
      b.append('\n');
    }
    Dialogs.builder(this).setTitle("Last " + a.length() + " rolls").setMessage(b.toString().trim())
        .setPositiveButton("OK", null)
        .setNeutralButton("Clear", (d, w) -> Store.write(this, HISTORY, new JSONArray()))
        .show();
  }
}
