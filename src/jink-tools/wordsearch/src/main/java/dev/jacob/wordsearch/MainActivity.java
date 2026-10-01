package dev.jacob.wordsearch;

import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Tap the first letter of a word, then its last letter. */
public class MainActivity extends InkActivity {
  private Puzzle puzzle;
  private int theme;
  private Puzzle.Difficulty difficulty = Puzzle.Difficulty.MEDIUM;
  private GridView grid;
  private TextView status;
  private LinearLayout wordList;
  private SharedPreferences prefs;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("wordsearch", MODE_PRIVATE);
    LinearLayout page = Ui.column(this);
    page.addView(header("Word Search", Ui.button(this, "New", v -> chooseNew()), refreshButton()), Ui.fill());
    status = Ui.muted(this, "");
    status.setGravity(Gravity.CENTER);
    Ui.add(page, status, 6);
    grid = new GridView(this);
    grid.onTap = this::tap;
    LinearLayout holder = Ui.column(this);
    holder.setGravity(Gravity.CENTER);
    holder.addView(grid, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, 0, 1);
    gp.topMargin = Ui.dp(this, 6);
    page.addView(holder, gp);
    wordList = Ui.column(this);
    Ui.add(page, wordList, 8);
    setPage(page);
    puzzle = load();
    if (puzzle == null) newPuzzle(0, Puzzle.Difficulty.MEDIUM);
    else show();
  }

  @Override protected void onPause() {
    super.onPause();
    save();
  }

  private void chooseNew() {
    Dialogs.choose(this, "Theme", Puzzle.THEMES, t -> {
      Puzzle.Difficulty[] all = Puzzle.Difficulty.values();
      String[] labels = new String[all.length];
      for (int i = 0; i < all.length; i++) {
        labels[i] = all[i].label + " — " + all[i].size + "×" + all[i].size
            + (all[i].directions == 2 ? ", across and down" : all[i].directions == 4 ? ", plus diagonals" : ", any direction");
      }
      Dialogs.choose(this, "Difficulty", labels, d -> newPuzzle(t, all[d]));
    });
  }

  private void newPuzzle(int t, Puzzle.Difficulty d) {
    theme = t;
    difficulty = d;
    puzzle = Puzzle.generate(t, d, new SecureRandom());
    grid.pickX = grid.pickY = -1;
    show();
    fullRefresh();
    save();
  }

  private void tap(int x, int y) {
    if (grid.pickX < 0) {
      grid.pickX = x;
      grid.pickY = y;
    } else if (grid.pickX == x && grid.pickY == y) {
      grid.pickX = grid.pickY = -1;
    } else {
      Puzzle.Placement found = puzzle.select(grid.pickX, grid.pickY, x, y);
      if (found != null) {
        grid.pickX = grid.pickY = -1;
        show();
        save();
        if (puzzle.done()) {
          fullRefresh();
          Dialogs.message(this, "All found!", "You found all " + puzzle.words.size() + " words. Tap New for another.");
        }
        return;
      }
      // Not a word: start a new selection from this letter.
      grid.pickX = x;
      grid.pickY = y;
    }
    grid.invalidate();
  }

  private void show() {
    grid.puzzle = puzzle;
    grid.invalidate();
    int found = 0;
    for (Puzzle.Placement p : puzzle.words) if (p.found) found++;
    status.setText(Puzzle.THEMES[theme] + " · " + difficulty.label + " · " + found + " of " + puzzle.words.size()
        + " found · tap first letter, then last");
    wordList.removeAllViews();
    int cols = Ui.large(this) ? 4 : 3;
    LinearLayout row = null;
    for (int i = 0; i < puzzle.words.size(); i++) {
      if (i % cols == 0) { row = Ui.row(this); wordList.addView(row, Ui.fill()); }
      Puzzle.Placement p = puzzle.words.get(i);
      TextView t = Ui.text(this, p.word, 0.85f);
      if (p.found) {
        t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        t.setTextColor(Ui.MUTED);
      }
      row.addView(t, Ui.weight(1));
    }
    for (int i = puzzle.words.size() % cols; i > 0 && i < cols; i++) row.addView(new TextView(this), Ui.weight(1));
  }

  // ---- storage ----

  private void save() {
    if (puzzle == null) return;
    try {
      JSONArray rows = new JSONArray(), words = new JSONArray();
      for (char[] r : puzzle.grid) rows.put(new String(r));
      for (Puzzle.Placement p : puzzle.words) {
        words.put(new JSONObject().put("w", p.word).put("x", p.x).put("y", p.y).put("dx", p.dx).put("dy", p.dy)
            .put("found", p.found));
      }
      prefs.edit().putString("game", new JSONObject().put("theme", theme).put("difficulty", difficulty.name())
          .put("rows", rows).put("words", words).toString()).apply();
    } catch (JSONException ignored) {
      // Only thrown for non-finite numbers.
    }
  }

  private Puzzle load() {
    String s = prefs.getString("game", null);
    if (s == null) return null;
    try {
      JSONObject o = new JSONObject(s);
      theme = Math.max(0, Math.min(Puzzle.THEMES.length - 1, o.getInt("theme")));
      difficulty = Puzzle.Difficulty.valueOf(o.getString("difficulty"));
      JSONArray rows = o.getJSONArray("rows"), words = o.getJSONArray("words");
      Puzzle p = new Puzzle(rows.length());
      for (int y = 0; y < rows.length(); y++) {
        String r = rows.getString(y);
        if (r.length() != p.size) return null;
        p.grid[y] = r.toCharArray();
      }
      for (int i = 0; i < words.length(); i++) {
        JSONObject w = words.getJSONObject(i);
        Puzzle.Placement pl = new Puzzle.Placement(w.getString("w"), w.getInt("x"), w.getInt("y"), w.getInt("dx"), w.getInt("dy"));
        pl.found = w.optBoolean("found");
        p.words.add(pl);
      }
      return p;
    } catch (JSONException | IllegalArgumentException e) {
      return null;
    }
  }
}
