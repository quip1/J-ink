package dev.jacob.sudoku;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Tap a cell, then a number. Notes mode writes small pencil marks instead. */
public class MainActivity extends InkActivity {
  private BoardView board;
  private TextView status, notesBtn;
  private final TextView[] digitBtns = new TextView[9];
  private int[] solution = new int[81];
  private Sudoku.Difficulty difficulty = Sudoku.Difficulty.MEDIUM;
  private boolean notesMode, solved;
  private int hints;
  private final Deque<int[]> undo = new ArrayDeque<>();
  private SharedPreferences prefs;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("sudoku", MODE_PRIVATE);
    LinearLayout page = Ui.column(this);
    page.addView(header("Sudoku", Ui.button(this, "New", v -> chooseNew()), refreshButton()), Ui.fill());
    status = Ui.muted(this, "");
    status.setGravity(Gravity.CENTER);
    Ui.add(page, status, 6);

    board = new BoardView(this);
    board.onTap = cell -> { board.selected = cell; board.invalidate(); };
    LinearLayout holder = Ui.column(this);
    holder.setGravity(Gravity.CENTER);
    holder.addView(board, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
    bp.topMargin = Ui.dp(this, 6);
    page.addView(holder, bp);

    TextView[] r1 = new TextView[5], r2 = new TextView[5];
    for (int d = 1; d <= 9; d++) {
      int digit = d;
      digitBtns[d - 1] = Ui.button(this, String.valueOf(d), v -> enter(digit));
      digitBtns[d - 1].setTextSize(Ui.body(this) * 1.3f);
      if (d <= 5) r1[d - 1] = digitBtns[d - 1]; else r2[d - 6] = digitBtns[d - 1];
    }
    r2[4] = Ui.button(this, "⌫", v -> enter(0));
    Ui.add(page, Ui.buttons(this, r1), 8);
    Ui.add(page, Ui.buttons(this, r2), 4);
    notesBtn = Ui.button(this, "Notes", v -> { notesMode = !notesMode; Ui.setActive(notesBtn, notesMode); });
    TextView undoBtn = Ui.button(this, "Undo", v -> undo());
    TextView hint = Ui.button(this, "Hint", v -> hint());
    TextView check = Ui.button(this, "Check", v -> check());
    Ui.add(page, Ui.buttons(this, notesBtn, undoBtn, hint, check), 4);
    setPage(page);
    if (!load()) newGame(Sudoku.Difficulty.MEDIUM);
  }

  @Override protected void onPause() {
    super.onPause();
    save();
  }

  private void chooseNew() {
    Sudoku.Difficulty[] all = Sudoku.Difficulty.values();
    String[] labels = new String[all.length];
    for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
    Dialogs.choose(this, "New puzzle", labels, i -> newGame(all[i]));
  }

  private void newGame(Sudoku.Difficulty d) {
    status.setText("Making a " + d.label.toLowerCase() + " puzzle…");
    new Thread(() -> {
      int[][] ps = Sudoku.generate(d, new SecureRandom());
      runOnUiThread(() -> {
        difficulty = d;
        board.givens = ps[0].clone();
        board.values = ps[0].clone();
        board.notes = new int[81];
        board.wrong = new boolean[81];
        board.selected = -1;
        solution = ps[1];
        undo.clear();
        hints = 0;
        solved = false;
        save();
        refresh();
        fullRefresh();
      });
    }).start();
  }

  private void enter(int digit) {
    int cell = board.selected;
    if (cell < 0 || solved) { if (!solved) toast("Tap a cell first"); return; }
    if (board.givens[cell] != 0) return;
    undo.push(new int[]{cell, board.values[cell], board.notes[cell]});
    if (digit == 0) {
      board.values[cell] = 0;
      board.notes[cell] = 0;
    } else if (notesMode) {
      if (board.values[cell] != 0) { undo.pop(); return; }
      board.notes[cell] ^= 1 << digit;
    } else {
      board.values[cell] = board.values[cell] == digit ? 0 : digit;
      if (board.values[cell] != 0) clearNotes(cell, digit);
    }
    board.wrong[cell] = false;
    afterMove();
  }

  /** A placed digit removes that pencil mark from its row, column and box. */
  private void clearNotes(int cell, int digit) {
    for (int i = 0; i < 81; i++) {
      if (Sudoku.row(i) == Sudoku.row(cell) || Sudoku.col(i) == Sudoku.col(cell) || Sudoku.box(i) == Sudoku.box(cell))
        board.notes[i] &= ~(1 << digit);
    }
  }

  private void undo() {
    int[] u = undo.poll();
    if (u == null) return;
    board.values[u[0]] = u[1];
    board.notes[u[0]] = u[2];
    board.wrong[u[0]] = false;
    board.selected = u[0];
    afterMove();
  }

  private void hint() {
    if (solved) return;
    int cell = board.selected;
    if (cell < 0 || board.values[cell] == solution[cell]) {
      // No useful selection: fill the empty cell with the fewest candidates.
      int best = -1, bestCount = 10;
      for (int i = 0; i < 81; i++) {
        if (board.values[i] != 0) continue;
        int n = Integer.bitCount(Sudoku.candidates(board.values, i));
        if (n < bestCount) { best = i; bestCount = n; }
      }
      cell = best;
    }
    if (cell < 0) return;
    undo.push(new int[]{cell, board.values[cell], board.notes[cell]});
    board.values[cell] = solution[cell];
    board.wrong[cell] = false;
    clearNotes(cell, solution[cell]);
    board.selected = cell;
    hints++;
    afterMove();
  }

  private void check() {
    int wrongCount = 0, empty = 0;
    for (int i = 0; i < 81; i++) {
      board.wrong[i] = board.values[i] != 0 && board.values[i] != solution[i];
      if (board.wrong[i]) wrongCount++;
      if (board.values[i] == 0) empty++;
    }
    board.invalidate();
    toast(wrongCount == 0 ? "All good so far. " + empty + " to go." : wrongCount + " mistake" + (wrongCount == 1 ? "" : "s")
        + " crossed out.");
  }

  private void afterMove() {
    boolean full = true;
    for (int i = 0; i < 81; i++) if (board.values[i] != solution[i]) { full = false; break; }
    if (full && !solved) {
      solved = true;
      fullRefresh();
      Dialogs.message(this, "Solved!", "Nicely done." + (hints > 0 ? " (" + hints + " hint" + (hints == 1 ? "" : "s") + ")" : "")
          + "\nTap New for another puzzle.");
    }
    save();
    refresh();
  }

  private void refresh() {
    int[] counts = new int[10];
    for (int v : board.values) counts[v]++;
    int filled = 81 - counts[0];
    status.setText(difficulty.label + " · " + filled + "/81" + (solved ? " · solved" : ""));
    // A digit's button goes dark once all nine are placed.
    for (int d = 1; d <= 9; d++) Ui.setActive(digitBtns[d - 1], counts[d] >= 9);
    Ui.setActive(notesBtn, notesMode);
    board.invalidate();
  }

  // ---- storage ----

  private void save() {
    try {
      JSONObject o = new JSONObject().put("difficulty", difficulty.name()).put("hints", hints).put("solved", solved)
          .put("givens", arr(board.givens)).put("values", arr(board.values)).put("notes", arr(board.notes))
          .put("solution", arr(solution));
      prefs.edit().putString("game", o.toString()).apply();
    } catch (JSONException ignored) {
      // Only thrown for non-finite numbers.
    }
  }

  private boolean load() {
    String s = prefs.getString("game", null);
    if (s == null) return false;
    try {
      JSONObject o = new JSONObject(s);
      difficulty = Sudoku.Difficulty.valueOf(o.getString("difficulty"));
      hints = o.optInt("hints");
      solved = o.optBoolean("solved");
      board.givens = ints(o.getJSONArray("givens"));
      board.values = ints(o.getJSONArray("values"));
      board.notes = ints(o.getJSONArray("notes"));
      solution = ints(o.getJSONArray("solution"));
      board.wrong = new boolean[81];
      refresh();
      return true;
    } catch (JSONException | IllegalArgumentException e) {
      return false;
    }
  }

  private static JSONArray arr(int[] a) {
    JSONArray j = new JSONArray();
    for (int v : a) j.put(v);
    return j;
  }

  private static int[] ints(JSONArray j) throws JSONException {
    if (j.length() != 81) throw new JSONException("bad grid");
    int[] a = new int[81];
    for (int i = 0; i < 81; i++) a[i] = j.getInt(i);
    return a;
  }
}
