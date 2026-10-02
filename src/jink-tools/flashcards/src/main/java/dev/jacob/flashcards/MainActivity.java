package dev.jacob.flashcards;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Flashcard decks with spaced repetition. Import cards from CSV/TSV files. */
public class MainActivity extends InkActivity {
  private static final String FILE = "decks.json";
  private static final String[] GRADES = {"Again", "Hard", "Good", "Easy"};

  private final List<Deck> decks = new ArrayList<>();
  private SharedPreferences prefs;
  private Deck open;
  private Pager<?> pager;
  private boolean studying, screenIsCards;

  // Study session.
  private List<Deck.Card> queue = new ArrayList<>();
  private int studied;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("flashcards", MODE_PRIVATE);
    load();
    showDecks();
  }

  @Override protected void onPause() {
    super.onPause();
    save();
  }

  @Override protected boolean onPageKey(int dir) { return !studying && pager != null && pager.turn(dir); }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (studying || screenIsCards) { save(); showDeck(open); return; }
    if (open != null) { showDecks(); return; }
    super.onBackPressed();
  }

  private static long today() { return LocalDate.now().toEpochDay(); }

  private String newKey(Deck d) { return "new:" + d.id + ":" + today(); }

  // ---- decks ----

  private void showDecks() {
    open = null;
    studying = false;
    screenIsCards = false;
    LinearLayout page = Ui.column(this);
    page.addView(header("Flashcards", Ui.button(this, "+ Deck", v -> Dialogs.prompt(this, "New deck", "", n -> {
      if (n.trim().isEmpty()) return;
      Deck d = new Deck(n.trim());
      decks.add(d);
      save();
      showDeck(d);
    })), refreshButton()), Ui.fill());
    Pager<Deck> p = new Pager<>(this, Pager.fit(this, 76, 180), "No decks yet. Tap + Deck, then add or import cards.", d -> {
      LinearLayout row = Ui.column(this);
      int pad = Ui.dp(this, 12);
      row.setPadding(pad, pad, pad, pad);
      row.addView(Ui.title(this, d.name));
      int newLeft = Math.min(d.newCount(), Math.max(0, d.newPerDay - prefs.getInt(newKey(d), 0)));
      row.addView(Ui.muted(this, d.dueCount(today()) + " to review · " + newLeft + " new today · " + d.cards.size() + " cards"));
      row.setOnClickListener(v -> showDeck(d));
      return row;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(p.view(), lp);
    p.setItems(decks);
    setPage(page);
  }

  private void showDeck(Deck d) {
    open = d;
    studying = false;
    screenIsCards = false;
    pager = null;
    LinearLayout page = Ui.column(this);
    page.addView(header(d.name, Ui.button(this, "←", v -> showDecks()), Ui.button(this, "⋯", v -> deckMenu(d))), Ui.fill());
    int due = d.dueCount(today());
    int newLeft = Math.min(d.newCount(), Math.max(0, d.newPerDay - prefs.getInt(newKey(d), 0)));
    TextView summary = Ui.text(this, due + " to review\n" + newLeft + " new for today\n" + d.cards.size() + " cards in total", 1.2f);
    Ui.add(page, summary, 20);
    TextView study = Ui.button(this, due + newLeft == 0 ? "Nothing due — study ahead" : "Study now", v -> startStudy(d));
    study.setTextSize(Ui.body(this) * 1.3f);
    Ui.setActive(study, due + newLeft > 0);
    Ui.add(page, study, 24);
    Ui.add(page, Ui.buttons(this, Ui.button(this, "+ Add card", v -> editCard(d, null)),
        Ui.button(this, "Browse cards", v -> showCards(d))), 10);
    Ui.add(page, Ui.buttons(this, Ui.button(this, "Import CSV…", v -> importCards(d)),
        Ui.button(this, "Export…", v -> exportCards(d))), 6);
    Ui.add(page, Ui.muted(this, "Import a .csv or .txt file with one card per line: front, back (or front<tab>back)."), 12);
    setPage(page);
  }

  private void deckMenu(Deck d) {
    Dialogs.choose(this, d.name, new String[]{"Rename…", "New cards per day (" + d.newPerDay + ")…", "Delete deck"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Rename", d.name, n -> { if (!n.trim().isEmpty()) d.name = n.trim(); save(); showDeck(d); });
      else if (i == 1) Dialogs.number(this, "New cards per day", d.newPerDay, n -> { d.newPerDay = Math.max(0, Math.min(500, n)); save(); showDeck(d); });
      else Dialogs.confirm(this, "Delete “" + d.name + "” and all " + d.cards.size() + " cards?", "Delete", () -> {
        decks.remove(d);
        save();
        showDecks();
      });
    });
  }

  // ---- studying ----

  private void startStudy(Deck d) {
    queue = d.todaysQueue(today(), prefs.getInt(newKey(d), 0));
    if (queue.isEmpty()) {
      // Study ahead: the cards due soonest.
      List<Deck.Card> all = new ArrayList<>(d.cards);
      all.removeIf(Deck.Card::isNew);
      all.sort((a, b) -> Long.compare(a.due, b.due));
      queue = new ArrayList<>(all.subList(0, Math.min(20, all.size())));
    }
    if (queue.isEmpty()) { toast("Add some cards first"); return; }
    studied = 0;
    studying = true;
    showCard(false);
  }

  private void showCard(boolean answer) {
    if (queue.isEmpty()) {
      studying = false;
      save();
      Dialogs.message(this, "Done for now", "You studied " + studied + " card" + (studied == 1 ? "" : "s") + ". See you tomorrow!");
      showDeck(open);
      return;
    }
    Deck.Card c = queue.get(0);
    LinearLayout page = Ui.column(this);
    page.addView(header(open.name, Ui.button(this, "←", v -> { save(); showDeck(open); })), Ui.fill());
    Ui.add(page, Ui.muted(this, queue.size() + " left" + (c.isNew() ? " · new card" : "")), 6);
    LinearLayout card = Ui.column(this);
    card.setGravity(Gravity.CENTER);
    TextView front = Ui.text(this, c.front, 1.8f);
    front.setGravity(Gravity.CENTER);
    front.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    card.addView(front, Ui.fill());
    if (answer) {
      Ui.add(card, Ui.rule(this), 24);
      TextView back = Ui.text(this, c.back, 1.5f);
      back.setGravity(Gravity.CENTER);
      back.setTypeface(Typeface.SERIF);
      Ui.add(card, back, 24);
    }
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1);
    page.addView(Ui.scroll(this, card), cp);
    if (!answer) {
      TextView show = Ui.button(this, "Show answer", v -> showCard(true));
      show.setMinHeight(Ui.dp(this, 72));
      Ui.add(page, show, 8);
    } else {
      int[] next = Deck.preview(c);
      TextView[] grades = new TextView[4];
      for (int g = 0; g < 4; g++) {
        int grade = g;
        grades[g] = Ui.button(this, GRADES[g] + "\n" + Deck.span(next[g]), v -> answer(c, grade));
        grades[g].setMinHeight(Ui.dp(this, 72));
      }
      Ui.add(page, Ui.buttons(this, grades), 8);
    }
    setPage(page);
  }

  private void answer(Deck.Card c, int grade) {
    boolean wasNew = c.isNew();
    Deck.grade(c, grade, today());
    if (wasNew) prefs.edit().putInt(newKey(open), prefs.getInt(newKey(open), 0) + 1).apply();
    queue.remove(0);
    if (grade == Deck.AGAIN) queue.add(Math.min(queue.size(), 3), c); // back in a few cards
    else studied++;
    showCard(false);
  }

  // ---- cards ----

  private void showCards(Deck d) {
    screenIsCards = true;
    LinearLayout page = Ui.column(this);
    page.addView(header(d.name + " · cards", Ui.button(this, "←", v -> showDeck(d)),
        Ui.button(this, "+ Add", v -> editCard(d, null))), Ui.fill());
    Pager<Deck.Card> p = new Pager<>(this, Pager.fit(this, 70, 180), "No cards yet.", c -> {
      LinearLayout row = Ui.column(this);
      int pad = Ui.dp(this, 10);
      row.setPadding(pad, pad, pad, pad);
      TextView f = Ui.text(this, c.front);
      f.setTypeface(Typeface.DEFAULT_BOLD);
      f.setSingleLine(true);
      row.addView(f);
      TextView bk = Ui.muted(this, c.back.replace('\n', ' ') + (c.isNew() ? "  · new" : "  · every " + Deck.span(c.interval)));
      bk.setSingleLine(true);
      row.addView(bk);
      row.setOnClickListener(v -> editCard(d, c));
      row.setOnLongClickListener(v -> {
        Dialogs.confirm(this, "Delete this card?", "Delete", () -> { d.cards.remove(c); save(); showCards(d); });
        return true;
      });
      return row;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(p.view(), lp);
    p.setItems(d.cards);
    Ui.add(page, Ui.muted(this, "Tap to edit, hold to delete."), 6);
    setPage(page);
  }

  private void editCard(Deck d, Deck.Card existing) {
    LinearLayout form = Ui.column(this);
    int pad = Ui.dp(this, 16);
    form.setPadding(pad, Ui.dp(this, 8), pad, 0);
    EditText front = Ui.input(this, "Front (question)", existing == null ? "" : existing.front);
    EditText back = Ui.input(this, "Back (answer)", existing == null ? "" : existing.back);
    back.setSingleLine(false);
    back.setMinLines(2);
    form.addView(front, Ui.fill());
    Ui.add(form, back, 8);
    android.app.AlertDialog.Builder bld = Dialogs.builder(this).setTitle(existing == null ? "New card" : "Edit card").setView(form)
        .setPositiveButton("Save", (x, w) -> {
          String f = front.getText().toString().trim(), bk = back.getText().toString().trim();
          if (f.isEmpty()) return;
          if (existing == null) { d.cards.add(new Deck.Card(f, bk)); toast("Added"); }
          else { existing.front = f; existing.back = bk; }
          save();
          if (screenIsCards) showCards(d); else showDeck(d);
          if (existing == null) editCard(d, null); // keep adding
        })
        .setNegativeButton(existing == null ? "Done" : "Cancel", null);
    bld.show();
  }

  private void importCards(Deck d) {
    openDocument(new String[]{"text/*", "application/csv", "application/octet-stream"}, (Uri uri) -> {
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("Can't open");
        List<Deck.Card> cards = Deck.parse(Store.readAll(in));
        if (cards.isEmpty()) { toast("No cards found. Use one card per line: front, back"); return; }
        d.cards.addAll(cards);
        save();
        toast("Imported " + cards.size() + " cards");
        showDeck(d);
      } catch (IOException e) {
        toast("Import failed: " + e.getMessage());
      }
    });
  }

  private void exportCards(Deck d) {
    createDocument("text/tab-separated-values", d.name.replaceAll("[\\\\/:*?\"<>|]", "-") + ".tsv", uri -> {
      try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
        if (out == null) throw new IOException("Can't write there");
        out.write(d.toTsv().getBytes(StandardCharsets.UTF_8));
        toast("Exported " + d.cards.size() + " cards");
      } catch (IOException e) {
        toast("Export failed: " + e.getMessage());
      }
    });
  }

  // ---- storage ----

  private void load() {
    JSONArray a = Store.readArray(this, FILE);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Deck d = new Deck(o.optString("name", "Deck"));
      d.id = o.optString("id", d.id);
      d.newPerDay = o.optInt("newPerDay", 20);
      JSONArray cs = o.optJSONArray("cards");
      for (int k = 0; cs != null && k < cs.length(); k++) {
        JSONObject c = cs.optJSONObject(k);
        if (c == null) continue;
        Deck.Card card = new Deck.Card(c.optString("f"), c.optString("b"));
        card.id = c.optString("id", card.id);
        card.ease = c.optDouble("ease", 2.5);
        card.interval = c.optInt("int");
        card.reps = c.optInt("reps");
        card.lapses = c.optInt("lapses");
        card.due = c.optLong("due");
        d.cards.add(card);
      }
      decks.add(d);
    }
  }

  private void save() {
    JSONArray a = new JSONArray();
    try {
      for (Deck d : decks) {
        JSONArray cs = new JSONArray();
        for (Deck.Card c : d.cards) {
          cs.put(new JSONObject().put("id", c.id).put("f", c.front).put("b", c.back).put("ease", c.ease)
              .put("int", c.interval).put("reps", c.reps).put("lapses", c.lapses).put("due", c.due));
        }
        a.put(new JSONObject().put("id", d.id).put("name", d.name).put("newPerDay", d.newPerDay).put("cards", cs));
      }
    } catch (JSONException e) {
      return;
    }
    if (!Store.write(this, FILE, a)) toast("Couldn't save");
  }
}
