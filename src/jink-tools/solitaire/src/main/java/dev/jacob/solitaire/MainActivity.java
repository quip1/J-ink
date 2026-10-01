package dev.jacob.solitaire;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tap a card to pick it up, then tap where it should go. Tap the same card twice to send it to
 * the foundations (or anywhere it fits).
 */
public class MainActivity extends InkActivity {
  private static final int UNDO_MAX = 200;

  private Klondike game;
  private TableView table;
  private TextView status, finishBtn;
  private final Deque<Klondike> history = new ArrayDeque<>();
  private SharedPreferences prefs;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("solitaire", MODE_PRIVATE);
    LinearLayout page = Ui.column(this);
    page.addView(header("Solitaire", Ui.button(this, "New", v -> chooseNew()), refreshButton()), Ui.fill());
    table = new TableView(this);
    table.onTap = this::tap;
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
    tp.topMargin = Ui.dp(this, 8);
    page.addView(table, tp);
    status = Ui.muted(this, "");
    finishBtn = Ui.button(this, "Finish", v -> { snapshot(); game.sweepToFoundations(); changed(); });
    TextView undo = Ui.button(this, "Undo", v -> undo());
    TextView home = Ui.button(this, "To foundations", v -> {
      snapshot();
      if (game.sweepToFoundations() == 0) history.pop();
      changed();
    });
    Ui.add(page, Ui.buttons(this, undo, home, finishBtn), 6);
    Ui.add(page, status, 4);
    setPage(page);
    game = load();
    if (game == null) game = Klondike.deal(new SecureRandom(), prefs.getInt("draw", 1));
    table.game = game;
    changed();
  }

  @Override protected void onPause() {
    super.onPause();
    save();
  }

  private void chooseNew() {
    Dialogs.choose(this, "New game", new String[]{"Draw 1 (easier)", "Draw 3 (classic)"}, i -> {
      int draw = i == 0 ? 1 : 3;
      prefs.edit().putInt("draw", draw).apply();
      game = Klondike.deal(new SecureRandom(), draw);
      table.game = game;
      table.selected = null;
      history.clear();
      changed();
      fullRefresh();
    });
  }

  private void tap(TableView.Target t) {
    TableView.Target sel = table.selected;
    if (t.area == TableView.Area.STOCK) {
      table.selected = null;
      snapshot();
      if (!game.draw()) history.pop();
      changed();
      return;
    }
    if (sel == null) {
      table.selected = selectable(t) ? t : null;
      table.invalidate();
      return;
    }
    if (sel.same(t)) {
      // Second tap on the same card: send it wherever it can go.
      table.selected = null;
      autoMove(sel);
      return;
    }
    table.selected = null;
    snapshot();
    if (tryMove(sel, t)) { changed(); return; }
    history.pop();
    // Not a legal move: treat the tap as picking up something new instead.
    table.selected = selectable(t) ? t : null;
    table.invalidate();
  }

  private boolean selectable(TableView.Target t) {
    switch (t.area) {
      case WASTE: return !game.waste.isEmpty();
      case FOUNDATION: return !game.foundations[t.pile].isEmpty();
      case TABLEAU: return t.index >= 0 && t.index >= game.firstUp(t.pile);
      default: return false;
    }
  }

  private boolean tryMove(TableView.Target from, TableView.Target to) {
    if (to.area == TableView.Area.TABLEAU) {
      switch (from.area) {
        case WASTE: return game.wasteToTableau(to.pile);
        case FOUNDATION: return game.foundationToTableau(from.pile, to.pile);
        case TABLEAU: return game.tableauToTableau(from.pile, from.index, to.pile);
        default: return false;
      }
    }
    if (to.area == TableView.Area.FOUNDATION) {
      if (from.area == TableView.Area.WASTE) return game.wasteToFoundation();
      if (from.area == TableView.Area.TABLEAU && from.index == game.tableau[from.pile].size() - 1)
        return game.tableauToFoundation(from.pile);
    }
    return false;
  }

  private void autoMove(TableView.Target from) {
    snapshot();
    boolean topCard = from.area == TableView.Area.WASTE
        || (from.area == TableView.Area.TABLEAU && from.index == game.tableau[from.pile].size() - 1);
    boolean moved = false;
    if (topCard) moved = tryMove(from, new TableView.Target(TableView.Area.FOUNDATION, 0, -1));
    for (int p = 0; p < 7 && !moved; p++) {
      if (from.area == TableView.Area.TABLEAU && from.pile == p) continue;
      // Don't shuffle a king from one empty-bottomed pile to another.
      if (from.area == TableView.Area.TABLEAU && from.index == 0 && game.tableau[p].isEmpty()) continue;
      moved = tryMove(from, new TableView.Target(TableView.Area.TABLEAU, p, -1));
    }
    if (!moved) history.pop();
    changed();
  }

  private void snapshot() {
    history.push(game.copy());
    while (history.size() > UNDO_MAX) history.removeLast();
  }

  private void undo() {
    Klondike prev = history.poll();
    if (prev == null) return;
    game = prev;
    table.game = game;
    table.selected = null;
    changed();
  }

  private void changed() {
    int home = 0;
    for (List<Integer> f : game.foundations) home += f.size();
    status.setText("Draw " + game.drawCount + " · " + game.moves + " moves · " + home + "/52 home"
        + " · tap a card, then where it goes");
    Ui.setActive(finishBtn, game.canAutoFinish());
    finishBtn.setEnabled(game.canAutoFinish());
    table.invalidate();
    save();
    if (game.won()) {
      fullRefresh();
      Dialogs.message(this, "You won!", "All 52 cards home in " + game.moves + " moves. Tap New to play again.");
    }
  }

  // ---- storage ----

  private void save() {
    if (game == null) return;
    try {
      JSONArray found = new JSONArray(), tab = new JSONArray(), hid = new JSONArray();
      for (List<Integer> f : game.foundations) found.put(new JSONArray(f));
      for (List<Integer> t : game.tableau) tab.put(new JSONArray(t));
      for (int h : game.hidden) hid.put(h);
      JSONObject o = new JSONObject().put("stock", new JSONArray(game.stock)).put("waste", new JSONArray(game.waste))
          .put("found", found).put("tab", tab).put("hidden", hid).put("draw", game.drawCount).put("moves", game.moves);
      prefs.edit().putString("game", o.toString()).apply();
    } catch (JSONException ignored) {
      // Only thrown for non-finite numbers.
    }
  }

  private Klondike load() {
    String s = prefs.getString("game", null);
    if (s == null) return null;
    try {
      JSONObject o = new JSONObject(s);
      Klondike k = new Klondike();
      fill(o.getJSONArray("stock"), k.stock);
      fill(o.getJSONArray("waste"), k.waste);
      JSONArray found = o.getJSONArray("found"), tab = o.getJSONArray("tab"), hid = o.getJSONArray("hidden");
      for (int i = 0; i < 4; i++) fill(found.getJSONArray(i), k.foundations[i]);
      for (int i = 0; i < 7; i++) { fill(tab.getJSONArray(i), k.tableau[i]); k.hidden[i] = hid.getInt(i); }
      k.drawCount = o.optInt("draw", 1);
      k.moves = o.optInt("moves");
      return k;
    } catch (JSONException e) {
      return null;
    }
  }

  private static void fill(JSONArray a, List<Integer> into) throws JSONException {
    for (int i = 0; i < a.length(); i++) into.add(a.getInt(i));
  }
}
