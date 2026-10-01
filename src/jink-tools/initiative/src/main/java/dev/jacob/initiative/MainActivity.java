package dev.jacob.initiative;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends InkActivity {
  private static final String FILE = "encounter.json";

  private final Encounter enc = new Encounter();
  private Pager<Encounter.Combatant> pager;
  private TextView roundLabel;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    load();
    LinearLayout page = Ui.column(this);
    page.addView(header("Initiative", Ui.button(this, "+ Add", v -> addDialog()),
        Ui.button(this, "New", v -> newEncounter()), refreshButton()), Ui.fill());

    roundLabel = Ui.title(this, "");
    roundLabel.setGravity(Gravity.CENTER);
    TextView prev = Ui.button(this, "← Prev", v -> { enc.prev(); changed(true); });
    TextView next = Ui.button(this, "Next turn →", v -> { enc.next(); changed(true); });
    LinearLayout nav = Ui.row(this);
    nav.addView(prev, Ui.weight(1));
    nav.addView(roundLabel, Ui.weight(1));
    nav.addView(next, Ui.weight(1.4f));
    Ui.add(page, nav, 10);

    pager = new Pager<>(this, pageSize(),
        "No one in the fight yet. Tap + Add.", this::row);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 10);
    page.addView(pager.view(), lp);
    setPage(page);
    changed(true);
  }

  @Override protected boolean onPageKey(int dir) { return pager.turn(dir); }

  /** Re-sorts, saves and redraws. Jumps to the page holding whoever's turn it is when asked. */
  private void changed(boolean followTurn) {
    enc.sort();
    save();
    roundLabel.setText(enc.list.isEmpty() ? "" : "Round " + enc.round);
    int page = followTurn && enc.turnIndex() >= 0 ? enc.turnIndex() / pageSize() : pager.page();
    pager.setItems(enc.list, page);
  }

  private int pageSize() { return Pager.fit(this, Ui.large(this) ? 78 : 70, 260); }

  private View row(Encounter.Combatant c) {
    boolean turn = c.id.equals(enc.currentId);
    LinearLayout row = Ui.row(this);
    int p = Ui.dp(this, 8);
    row.setPadding(p, p, p, p);
    row.setBackgroundColor(turn ? Ui.INK : Ui.PAPER);
    int fg = turn ? Ui.PAPER : Ui.INK;

    TextView init = Ui.title(this, String.valueOf(c.initiative));
    init.setTextColor(fg);
    init.setGravity(Gravity.CENTER);
    init.setMinWidth(Ui.dp(this, 52));
    row.addView(init);

    LinearLayout mid = Ui.column(this);
    TextView name = Ui.text(this, (turn ? "▶ " : "") + c.name);
    name.setTypeface(c.player ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    name.setTextColor(fg);
    mid.addView(name);
    StringBuilder sub = new StringBuilder();
    if (c.down()) sub.append("DOWN  ");
    sub.append(String.join(", ", c.conditions));
    if (sub.length() > 0) {
      TextView conds = Ui.text(this, sub.toString().trim(), 0.8f);
      conds.setTextColor(fg);
      mid.addView(conds);
    }
    LinearLayout.LayoutParams mp = Ui.weight(1);
    mp.leftMargin = Ui.dp(this, 8);
    row.addView(mid, mp);

    String hp = c.maxHp > 0 ? c.hp + "/" + c.maxHp : (c.hp > 0 ? String.valueOf(c.hp) : "");
    if (c.tempHp > 0) hp += " +" + c.tempHp;
    TextView stats = Ui.text(this, (hp.isEmpty() ? "" : "HP " + hp) + (c.ac > 0 ? "\nAC " + c.ac : ""), 0.9f);
    stats.setTextColor(fg);
    stats.setGravity(Gravity.END);
    row.addView(stats);
    row.setOnClickListener(v -> combatantDialog(c));
    return row;
  }

  // ---- dialogs ----

  private void addDialog() {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText name = Ui.input(this, "Name (e.g. Goblin)", "");
    EditText count = Ui.numberInput(this, "How many", "1");
    EditText bonus = Ui.numberInput(this, "Init bonus", "0");
    EditText init = Ui.numberInput(this, "Initiative (blank = roll)", "");
    EditText hp = Ui.numberInput(this, "HP", "");
    EditText ac = Ui.numberInput(this, "AC", "");
    CheckBox player = new CheckBox(this);
    player.setText("Player character");
    player.setTextSize(Ui.body(this));
    form.addView(name, Ui.fill());
    Ui.add(form, Ui.buttons(this, count, bonus), 6);
    Ui.add(form, init, 6);
    Ui.add(form, Ui.buttons(this, hp, ac), 6);
    Ui.add(form, player, 6);
    Dialogs.builder(this).setTitle("Add to the fight").setView(Ui.scroll(this, form))
        .setPositiveButton("Add", (d, w) -> {
          String n = name.getText().toString().trim();
          if (n.isEmpty()) n = player.isChecked() ? "Player" : "Monster";
          String fixed = init.getText().toString().trim();
          enc.addGroup(n, Ui.parseInt(count.getText().toString(), 1), Ui.parseInt(bonus.getText().toString(), 0),
              fixed.isEmpty() ? null : Ui.parseInt(fixed, 10), Ui.parseInt(hp.getText().toString(), 0),
              Ui.parseInt(ac.getText().toString(), 0), player.isChecked(), new SecureRandom());
          changed(false);
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void combatantDialog(Encounter.Combatant c) {
    LinearLayout box = Ui.column(this);
    int pad = Ui.dp(this, 16);
    box.setPadding(pad, Ui.dp(this, 8), pad, 0);
    TextView hpLine = Ui.title(this, "");
    Runnable showHp = () -> hpLine.setText("HP " + c.hp + (c.maxHp > 0 ? " / " + c.maxHp : "")
        + (c.tempHp > 0 ? "   temp " + c.tempHp : "") + (c.ac > 0 ? "    AC " + c.ac : ""));
    showHp.run();
    box.addView(hpLine, Ui.fill());

    EditText amount = Ui.numberInput(this, "Amount", "");
    amount.setInputType(InputType.TYPE_CLASS_NUMBER);
    Ui.add(box, amount, 8);
    TextView dmg = Ui.button(this, "Damage", v -> { Encounter.damage(c, Ui.parseInt(amount.getText().toString(), 0)); amount.setText(""); showHp.run(); });
    TextView heal = Ui.button(this, "Heal", v -> { Encounter.heal(c, Ui.parseInt(amount.getText().toString(), 0)); amount.setText(""); showHp.run(); });
    TextView temp = Ui.button(this, "Temp", v -> { Encounter.giveTemp(c, Ui.parseInt(amount.getText().toString(), 0)); amount.setText(""); showHp.run(); });
    Ui.add(box, Ui.buttons(this, dmg, heal, temp), 6);

    Ui.add(box, Ui.muted(this, "Conditions"), 12);
    int cols = Ui.large(this) ? 3 : 2;
    LinearLayout line = null;
    for (int i = 0; i < Encounter.CONDITIONS.length; i++) {
      if (i % cols == 0) { line = Ui.row(this); Ui.add(box, line, 4); }
      String cond = Encounter.CONDITIONS[i];
      TextView t = Ui.button(this, cond, null);
      t.setTextSize(Ui.body(this) * 0.8f);
      Ui.setActive(t, c.conditions.contains(cond));
      t.setOnClickListener(v -> {
        if (!c.conditions.remove(cond)) c.conditions.add(cond);
        Ui.setActive(t, c.conditions.contains(cond));
      });
      LinearLayout.LayoutParams lp = Ui.weight(1);
      if (i % cols > 0) lp.leftMargin = Ui.dp(this, 4);
      line.addView(t, lp);
    }
    for (int i = Encounter.CONDITIONS.length % cols; i > 0 && i < cols; i++) line.addView(new View(this), Ui.weight(1));

    TextView edit = Ui.button(this, "Edit", null);
    TextView remove = Ui.button(this, "Remove", null);
    Ui.add(box, Ui.buttons(this, edit, remove), 12);

    AlertDialog d = Dialogs.builder(this).setTitle(c.name).setView(Ui.scroll(this, box))
        .setPositiveButton("Done", null).create();
    d.setOnDismissListener(x -> changed(false));
    edit.setOnClickListener(v -> { d.dismiss(); editDialog(c); });
    remove.setOnClickListener(v -> { enc.remove(c.id); d.dismiss(); });
    d.show();
  }

  private void editDialog(Encounter.Combatant c) {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText name = Ui.input(this, "Name", c.name);
    EditText init = Ui.numberInput(this, "Initiative", String.valueOf(c.initiative));
    EditText bonus = Ui.numberInput(this, "Init bonus", String.valueOf(c.initBonus));
    EditText hp = Ui.numberInput(this, "HP", String.valueOf(c.hp));
    EditText max = Ui.numberInput(this, "Max HP", String.valueOf(c.maxHp));
    EditText ac = Ui.numberInput(this, "AC", String.valueOf(c.ac));
    form.addView(name, Ui.fill());
    Ui.add(form, Ui.buttons(this, init, bonus), 6);
    Ui.add(form, Ui.buttons(this, hp, max, ac), 6);
    Ui.add(form, Ui.muted(this, "Initiative, bonus / HP, max, AC"), 4);
    Dialogs.builder(this).setTitle("Edit").setView(form)
        .setPositiveButton("Save", (d, w) -> {
          c.name = name.getText().toString().trim().isEmpty() ? c.name : name.getText().toString().trim();
          c.initiative = Ui.parseInt(init.getText().toString(), c.initiative);
          c.initBonus = Ui.parseInt(bonus.getText().toString(), c.initBonus);
          c.hp = Math.max(0, Ui.parseInt(hp.getText().toString(), c.hp));
          c.maxHp = Math.max(0, Ui.parseInt(max.getText().toString(), c.maxHp));
          c.ac = Ui.parseInt(ac.getText().toString(), c.ac);
          changed(false);
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void newEncounter() {
    Dialogs.choose(this, "New encounter", new String[]{"Keep players, remove the rest", "Remove everyone",
        "Just restart at round 1"}, which -> {
      if (which == 0) enc.removeNonPlayers();
      else if (which == 1) { enc.list.clear(); enc.restart(); }
      else enc.restart();
      changed(true);
    });
  }

  // ---- storage ----

  private void save() {
    try {
      JSONArray a = new JSONArray();
      for (Encounter.Combatant c : enc.list) {
        a.put(new JSONObject().put("id", c.id).put("name", c.name).put("init", c.initiative)
            .put("bonus", c.initBonus).put("hp", c.hp).put("max", c.maxHp).put("temp", c.tempHp).put("ac", c.ac)
            .put("player", c.player).put("conditions", new JSONArray(c.conditions)));
      }
      Store.write(this, FILE, new JSONObject().put("round", enc.round)
          .put("current", enc.currentId == null ? "" : enc.currentId).put("list", a));
    } catch (JSONException ignored) {
      // Only thrown for NaN numbers, which we never store.
    }
  }

  private void load() {
    JSONObject o = Store.readObject(this, FILE);
    JSONArray a = o.optJSONArray("list");
    if (a == null) return;
    for (int i = 0; i < a.length(); i++) {
      JSONObject j = a.optJSONObject(i);
      if (j == null) continue;
      Encounter.Combatant c = new Encounter.Combatant();
      c.id = j.optString("id", c.id);
      c.name = j.optString("name");
      c.initiative = j.optInt("init");
      c.initBonus = j.optInt("bonus");
      c.hp = j.optInt("hp");
      c.maxHp = j.optInt("max");
      c.tempHp = j.optInt("temp");
      c.ac = j.optInt("ac");
      c.player = j.optBoolean("player");
      JSONArray cs = j.optJSONArray("conditions");
      for (int k = 0; cs != null && k < cs.length(); k++) c.conditions.add(cs.optString(k));
      enc.list.add(c);
    }
    enc.round = Math.max(1, o.optInt("round", 1));
    String cur = o.optString("current");
    enc.currentId = cur.isEmpty() ? null : cur;
    enc.sort();
  }
}
