package dev.jacob.charsheet;

import android.graphics.Typeface;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A list of characters, and a tabbed sheet for the one you open. Tabs replace one long scroll. */
public class MainActivity extends InkActivity {
  private static final String FILE = "characters.json";
  private static final String[] TABS = {"Stats", "Skills", "Combat", "Spells", "Gear", "Notes"};

  private final List<Character> characters = new ArrayList<>();
  private Character open;
  private int tab;
  private LinearLayout body;
  private final TextView[] tabButtons = new TextView[TABS.length];
  private Pager<Character> listPager;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    load();
    showList();
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (open != null) { saveAll(); showList(); }
    else super.onBackPressed();
  }

  @Override protected void onPause() {
    super.onPause();
    saveAll();
  }

  @Override protected boolean onPageKey(int dir) {
    if (open == null) return listPager != null && listPager.turn(dir);
    int t = tab + dir;
    if (t < 0 || t >= TABS.length) return false;
    selectTab(t);
    return true;
  }

  // ---- character list ----

  private void showList() {
    open = null;
    LinearLayout page = Ui.column(this);
    page.addView(header("Characters", Ui.button(this, "+ New", v -> {
      Character c = new Character();
      characters.add(c);
      saveAll();
      openSheet(c);
    }), refreshButton()), Ui.fill());
    listPager = new Pager<>(this, Pager.fit(this, 72, 200), "No characters yet. Tap + New.", c -> {
      LinearLayout row = Ui.column(this);
      int p = Ui.dp(this, 10);
      row.setPadding(p, p, p, p);
      TextView n = Ui.title(this, c.name);
      row.addView(n);
      String sub = (c.species + " " + c.klass).trim();
      row.addView(Ui.muted(this, (sub.isEmpty() ? "" : sub + " · ") + "Level " + c.level
          + " · HP " + c.hp + "/" + c.maxHp));
      row.setOnClickListener(v -> openSheet(c));
      row.setOnLongClickListener(v -> {
        Dialogs.confirm(this, "Delete " + c.name + "? This can't be undone.", "Delete", () -> {
          characters.remove(c);
          saveAll();
          showList();
        });
        return true;
      });
      return row;
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(listPager.view(), lp);
    listPager.setItems(characters);
    Ui.add(page, Ui.muted(this, "Hold a character to delete it."), 6);
    setPage(page);
  }

  // ---- sheet ----

  private void openSheet(Character c) {
    open = c;
    LinearLayout page = Ui.column(this);
    page.addView(header(c.name, backButton(), Ui.button(this, "Long rest", v -> Dialogs.confirm(this,
        "Long rest: restore HP, spell slots and half your hit dice?", "Rest", () -> {
          c.longRest();
          changed();
        })), refreshButton()), Ui.fill());
    int perRow = Ui.large(this) ? 6 : 3;
    LinearLayout tabs = Ui.column(this);
    for (int r = 0; r < TABS.length / perRow; r++) {
      TextView[] row = new TextView[perRow];
      for (int i = 0; i < perRow; i++) {
        int t = r * perRow + i;
        row[i] = tabButtons[t] = Ui.button(this, TABS[t], v -> selectTab(t));
      }
      Ui.add(tabs, Ui.buttons(this, row), r == 0 ? 0 : 4);
    }
    Ui.add(page, tabs, 8);
    body = Ui.column(this);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(Ui.scroll(this, body), lp);
    setPage(page);
    selectTab(tab);
  }

  @Override public void finish() {
    // The back arrow in the sheet header returns to the list instead of closing the app.
    if (open != null) { saveAll(); showList(); return; }
    super.finish();
  }

  private void selectTab(int t) {
    tab = t;
    for (int i = 0; i < TABS.length; i++) Ui.setActive(tabButtons[i], i == t);
    render();
  }

  /** Saves and redraws the current tab after any edit. */
  private void changed() {
    saveAll();
    render();
  }

  private void render() {
    body.removeAllViews();
    switch (tab) {
      case 0: stats(); break;
      case 1: skills(); break;
      case 2: combat(); break;
      case 3: spellsTab(); break;
      case 4: gear(); break;
      default: notesTab(); break;
    }
  }

  private void stats() {
    Character c = open;
    textField("Name", c.name, v -> { c.name = v.isEmpty() ? c.name : v; openSheet(c); });
    textField("Class", c.klass, v -> c.klass = v);
    numberField("Level", c.level, n -> c.level = Math.max(1, Math.min(20, n)));
    textField("Species", c.species, v -> c.species = v);
    textField("Background", c.background, v -> c.background = v);
    textField("Alignment", c.alignment, v -> c.alignment = v);
    int next = Character.xpForNextLevel(c.level);
    field("Experience", c.xp + (next > 0 ? "  (next level at " + next + ")" : ""),
        v -> Dialogs.number(this, "Experience points", c.xp, n -> { c.xp = Math.max(0, n); changed(); }));
    field("Proficiency bonus", Character.signed(c.proficiency()), null);

    Ui.add(body, Ui.muted(this, "Ability scores — tap to change"), 14);
    int cols = 3;
    LinearLayout row = null;
    for (int i = 0; i < 6; i++) {
      if (i % cols == 0) { row = Ui.row(this); Ui.add(body, row, 6); }
      int a = i;
      LinearLayout cell = Ui.column(this);
      cell.setGravity(Gravity.CENTER);
      cell.setBackground(Ui.box(this, Ui.PAPER));
      int p = Ui.dp(this, 8);
      cell.setPadding(p, p, p, p);
      TextView name = Ui.muted(this, Character.ABBR[a].toUpperCase());
      TextView mod = Ui.text(this, Character.signed(c.mod(a)), 1.8f);
      mod.setTypeface(Typeface.DEFAULT_BOLD);
      TextView score = Ui.text(this, String.valueOf(c.scores[a]));
      for (TextView t : new TextView[]{name, mod, score}) { t.setGravity(Gravity.CENTER); cell.addView(t, Ui.fill()); }
      cell.setOnClickListener(v -> Dialogs.number(this, Character.ABILITIES[a] + " score", c.scores[a], n -> {
        c.scores[a] = Math.max(1, Math.min(30, n));
        changed();
      }));
      LinearLayout.LayoutParams lp = Ui.weight(1);
      if (i % cols > 0) lp.leftMargin = Ui.dp(this, 6);
      row.addView(cell, lp);
    }
  }

  private void skills() {
    Character c = open;
    Ui.add(body, Ui.muted(this, "Saving throws — tap to toggle proficiency"), 4);
    for (int i = 0; i < 6; i++) {
      int a = i;
      checkRow(c.saveProf[a] ? 1 : 0, Character.signed(c.saveBonus(a)), Character.ABILITIES[a], () -> {
        c.saveProf[a] = !c.saveProf[a];
        changed();
      });
    }
    Ui.add(body, Ui.muted(this, "Skills — tap: proficient → expertise → none"), 14);
    for (int i = 0; i < Character.SKILLS.length; i++) {
      int s = i;
      checkRow(c.skillProf[s], Character.signed(c.skillBonus(s)),
          Character.SKILLS[s] + " (" + Character.ABBR[Character.SKILL_ABILITY[s]] + ")", () -> {
            c.skillProf[s] = (c.skillProf[s] + 1) % 3;
            changed();
          });
    }
    Ui.add(body, Ui.rule(this), 8);
    field("Passive Perception", String.valueOf(c.passivePerception()), null);
  }

  private void combat() {
    Character c = open;
    LinearLayout hpRow = Ui.row(this);
    TextView hp = Ui.text(this, "HP " + c.hp + " / " + c.maxHp + (c.tempHp > 0 ? "  +" + c.tempHp + " temp" : ""), 1.5f);
    hp.setTypeface(Typeface.DEFAULT_BOLD);
    hpRow.addView(hp, Ui.weight(1));
    Ui.add(body, hpRow, 4);
    TextView dmg = Ui.button(this, "Damage", v -> Dialogs.number(this, "Damage taken", 0, n -> {
      int fromTemp = Math.min(c.tempHp, Math.max(0, n));
      c.tempHp -= fromTemp;
      c.hp = Math.max(0, c.hp - (Math.max(0, n) - fromTemp));
      changed();
    }));
    TextView heal = Ui.button(this, "Heal", v -> Dialogs.number(this, "HP healed", 0, n -> {
      c.hp = Math.min(c.maxHp, c.hp + Math.max(0, n));
      if (c.hp > 0) c.deathSuccesses = c.deathFailures = 0;
      changed();
    }));
    TextView temp = Ui.button(this, "Temp HP", v -> Dialogs.number(this, "Temporary HP", c.tempHp, n -> {
      c.tempHp = Math.max(0, n);
      changed();
    }));
    Ui.add(body, Ui.buttons(this, dmg, heal, temp), 6);
    numberField("Max HP", c.maxHp, n -> { c.maxHp = Math.max(1, n); c.hp = Math.min(c.hp, c.maxHp); });
    numberField("Armor class", c.ac, n -> c.ac = n);
    field("Initiative", Character.signed(c.initiative()), v -> Dialogs.number(this,
        "Extra initiative bonus (on top of Dex)", c.initExtra, n -> { c.initExtra = n; changed(); }));
    numberField("Speed (ft)", c.speed, n -> c.speed = Math.max(0, n));
    textField("Hit dice", c.hitDice, v -> c.hitDice = v.isEmpty() ? c.hitDice : v);
    int total = c.hitDiceTotal();
    field("Hit dice left", (total - c.hitDiceUsed) + " of " + total + "  (tap to spend one)", v -> {
      if (c.hitDiceUsed < total) { c.hitDiceUsed++; changed(); }
    });

    if (c.hp == 0) {
      Ui.add(body, Ui.muted(this, "Death saves"), 12);
      Ui.add(body, pips("Successes", c.deathSuccesses, 3, n -> { c.deathSuccesses = n; changed(); }), 4);
      Ui.add(body, pips("Failures", c.deathFailures, 3, n -> { c.deathFailures = n; changed(); }), 4);
    }

    Ui.add(body, Ui.muted(this, "Attacks — tap to edit, hold to delete"), 14);
    for (Character.Attack a : c.attacks) {
      TextView row = Ui.text(this, a.name + "   " + a.bonus + "   " + a.damage);
      int p = Ui.dp(this, 8);
      row.setPadding(p, p, p, p);
      row.setOnClickListener(v -> attackDialog(a, false));
      row.setOnLongClickListener(v -> {
        Dialogs.confirm(this, "Delete " + a.name + "?", "Delete", () -> { c.attacks.remove(a); changed(); });
        return true;
      });
      Ui.add(body, row, 0);
      body.addView(Ui.rule(this));
    }
    Ui.add(body, Ui.button(this, "+ Add attack", v -> attackDialog(new Character.Attack(), true)), 6);
  }

  private void attackDialog(Character.Attack a, boolean isNew) {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText name = Ui.input(this, "Name (e.g. Longsword)", a.name);
    EditText bonus = Ui.input(this, "To hit (e.g. +5)", a.bonus);
    EditText damage = Ui.input(this, "Damage (e.g. 1d8+3 slashing)", a.damage);
    form.addView(name, Ui.fill());
    Ui.add(form, bonus, 6);
    Ui.add(form, damage, 6);
    Dialogs.builder(this).setTitle(isNew ? "New attack" : "Edit attack").setView(form)
        .setPositiveButton("Save", (d, w) -> {
          a.name = name.getText().toString().trim();
          a.bonus = bonus.getText().toString().trim();
          a.damage = damage.getText().toString().trim();
          if (isNew && !a.name.isEmpty()) open.attacks.add(a);
          changed();
        }).setNegativeButton("Cancel", null).show();
  }

  private void spellsTab() {
    Character c = open;
    String ab = c.spellAbility < 0 ? "None" : Character.ABILITIES[c.spellAbility];
    field("Spellcasting ability", ab, v -> {
      String[] opts = new String[7];
      opts[0] = "None";
      System.arraycopy(Character.ABILITIES, 0, opts, 1, 6);
      Dialogs.choose(this, "Spellcasting ability", opts, i -> { c.spellAbility = i - 1; changed(); });
    });
    if (c.spellAbility >= 0) {
      field("Spell save DC", String.valueOf(c.spellSaveDc()), null);
      field("Spell attack", Character.signed(c.spellAttack()), null);
    }
    Ui.add(body, Ui.muted(this, "Spell slots — tap a box to use or restore; ± sets how many"), 14);
    for (int i = 0; i < 9; i++) {
      int lvl = i;
      if (c.slotMax[lvl] == 0 && lvl > 0 && c.slotMax[lvl - 1] == 0) continue;
      LinearLayout row = Ui.row(this);
      TextView label = Ui.text(this, "Level " + (lvl + 1));
      label.setMinWidth(Ui.dp(this, 80));
      row.addView(label);
      LinearLayout boxes = Ui.row(this);
      for (int k = 0; k < c.slotMax[lvl]; k++) {
        boolean used = k < c.slotUsed[lvl];
        TextView box = Ui.button(this, used ? "✕" : "", v -> {
          c.slotUsed[lvl] = used ? c.slotUsed[lvl] - 1 : c.slotUsed[lvl] + 1;
          changed();
        });
        box.setMinWidth(Ui.dp(this, 40));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 44));
        bp.rightMargin = Ui.dp(this, 4);
        boxes.addView(box, bp);
      }
      row.addView(boxes, Ui.weight(1));
      row.addView(Ui.button(this, "−", v -> {
        c.slotMax[lvl] = Math.max(0, c.slotMax[lvl] - 1);
        c.slotUsed[lvl] = Math.min(c.slotUsed[lvl], c.slotMax[lvl]);
        changed();
      }));
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
      pp.leftMargin = Ui.dp(this, 4);
      row.addView(Ui.button(this, "+", v -> { c.slotMax[lvl] = Math.min(9, c.slotMax[lvl] + 1); changed(); }), pp);
      Ui.add(body, row, 6);
    }
    Ui.add(body, Ui.muted(this, "Spells known / prepared"), 14);
    Ui.add(body, bigText(c.spells, v -> c.spells = v), 4);
  }

  private void gear() {
    Character c = open;
    Ui.add(body, Ui.muted(this, "Coins — tap to change"), 4);
    TextView[] coinViews = new TextView[Character.COINS.length];
    for (int i = 0; i < Character.COINS.length; i++) {
      int k = i;
      coinViews[i] = Ui.button(this, c.coins[k] + "\n" + Character.COINS[k], v ->
          Dialogs.number(this, Character.COINS[k] + " amount", c.coins[k], n -> { c.coins[k] = Math.max(0, n); changed(); }));
      coinViews[i].setTypeface(Typeface.DEFAULT);
    }
    Ui.add(body, Ui.buttons(this, coinViews), 4);
    Ui.add(body, Ui.muted(this, "Items — −/+ for quantity, hold to delete"), 14);
    for (Character.Item it : c.items) {
      LinearLayout row = Ui.row(this);
      TextView name = Ui.text(this, it.name);
      row.addView(name, Ui.weight(1));
      row.addView(Ui.button(this, "−", v -> { if (it.qty > 0) { it.qty--; changed(); } }));
      TextView q = Ui.title(this, String.valueOf(it.qty));
      q.setGravity(Gravity.CENTER);
      q.setMinWidth(Ui.dp(this, 48));
      row.addView(q);
      row.addView(Ui.button(this, "+", v -> { it.qty++; changed(); }));
      row.setOnLongClickListener(v -> {
        Dialogs.confirm(this, "Delete " + it.name + "?", "Delete", () -> { c.items.remove(it); changed(); });
        return true;
      });
      name.setOnLongClickListener(v -> row.performLongClick());
      Ui.add(body, row, 4);
    }
    Ui.add(body, Ui.button(this, "+ Add item", v -> Dialogs.prompt(this, "Item name", "", s -> {
      if (s.trim().isEmpty()) return;
      Character.Item it = new Character.Item();
      it.name = s.trim();
      c.items.add(it);
      changed();
    })), 8);
  }

  private void notesTab() {
    Character c = open;
    Ui.add(body, Ui.muted(this, "Features & traits"), 4);
    Ui.add(body, bigText(c.features, v -> c.features = v), 4);
    Ui.add(body, Ui.muted(this, "Notes"), 14);
    Ui.add(body, bigText(c.notes, v -> c.notes = v), 4);
  }

  // ---- row builders ----

  /** "Label ........ value" row. Tapping runs {@code onTap} (or nothing for computed values). */
  private void field(String label, String value, View.OnClickListener onTap) {
    LinearLayout row = Ui.row(this);
    int p = Ui.dp(this, 8);
    row.setPadding(0, p, 0, p);
    row.addView(Ui.text(this, label), Ui.weight(1));
    TextView v = Ui.text(this, value.isEmpty() ? "—" : value);
    v.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(v);
    if (onTap != null) row.setOnClickListener(onTap);
    body.addView(row, Ui.fill());
    body.addView(Ui.rule(this));
  }

  private void textField(String label, String value, Consumer<String> set) {
    field(label, value, v -> Dialogs.prompt(this, label, value, s -> { set.accept(s.trim()); changed(); }));
  }

  private void numberField(String label, int value, IntConsumer set) {
    field(label, String.valueOf(value), v -> Dialogs.number(this, label, value, n -> { set.accept(n); changed(); }));
  }

  /** A proficiency row: ○ none, ● proficient, ◉ expertise, then the bonus and name. */
  private void checkRow(int level, String bonus, String name, Runnable toggle) {
    LinearLayout row = Ui.row(this);
    int p = Ui.dp(this, 6);
    row.setPadding(0, p, 0, p);
    TextView mark = Ui.text(this, level == 0 ? "○" : level == 1 ? "●" : "◉", 1.2f);
    mark.setMinWidth(Ui.dp(this, 36));
    row.addView(mark);
    TextView b = Ui.text(this, bonus);
    b.setTypeface(Typeface.DEFAULT_BOLD);
    b.setMinWidth(Ui.dp(this, 48));
    row.addView(b);
    row.addView(Ui.text(this, name), Ui.weight(1));
    row.setOnClickListener(v -> toggle.run());
    body.addView(row, Ui.fill());
  }

  private View pips(String label, int value, int max, IntConsumer set) {
    LinearLayout row = Ui.row(this);
    row.addView(Ui.text(this, label), Ui.weight(1));
    for (int i = 1; i <= max; i++) {
      int n = i;
      TextView t = Ui.button(this, "", v -> set.accept(value == n ? n - 1 : n));
      Ui.setActive(t, i <= value);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
      lp.leftMargin = Ui.dp(this, 6);
      row.addView(t, lp);
    }
    return row;
  }

  /** A multi-line box that saves as you type (no redraw, so the keyboard stays put). */
  private EditText bigText(String value, Consumer<String> set) {
    EditText e = Ui.input(this, "", value);
    e.setSingleLine(false);
    e.setMinLines(6);
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    e.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) { set.accept(s.toString()); }
    });
    return e;
  }

  // ---- storage ----

  private void load() {
    JSONArray a = Store.readArray(this, FILE);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null) characters.add(CharacterJson.read(o));
    }
  }

  private void saveAll() {
    JSONArray a = new JSONArray();
    try {
      for (Character c : characters) a.put(CharacterJson.write(c));
    } catch (JSONException e) {
      toast("Couldn't save: " + e.getMessage());
      return;
    }
    if (!Store.write(this, FILE, a)) toast("Couldn't save characters");
  }
}
