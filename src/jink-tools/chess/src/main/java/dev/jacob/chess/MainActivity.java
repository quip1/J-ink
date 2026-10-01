package dev.jacob.chess;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Play the computer as either colour, or pass the device between two players. */
public class MainActivity extends InkActivity {
  private static final String[] PROMOS = {"Queen", "Rook", "Bishop", "Knight"};
  private static final int[] PROMO_TYPES = {Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT};

  private Chess game;
  private final List<Integer> history = new ArrayList<>();
  private final List<String> sans = new ArrayList<>(), keys = new ArrayList<>();
  /** Chess.WHITE or Chess.BLACK for the human's colour against the computer; 0 for two players. */
  private int human = Chess.WHITE;
  private Engine.Level level = Engine.Level.EASY;
  private final Engine engine = new Engine(new SecureRandom());
  private BoardView board;
  private TextView status, moves;
  private boolean thinking, over;
  private int generation;
  private SharedPreferences prefs;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("chess", MODE_PRIVATE);
    LinearLayout page = Ui.column(this);
    page.addView(header("Chess", Ui.button(this, "New", v -> newGameDialog()), refreshButton()), Ui.fill());
    status = Ui.title(this, "");
    status.setGravity(Gravity.CENTER);
    Ui.add(page, status, 6);
    board = new BoardView(this);
    board.onTap = this::tap;
    LinearLayout holder = Ui.column(this);
    holder.setGravity(Gravity.CENTER);
    holder.addView(board, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
    bp.topMargin = Ui.dp(this, 6);
    page.addView(holder, bp);
    moves = Ui.muted(this, "");
    moves.setGravity(Gravity.CENTER);
    moves.setMaxLines(2);
    Ui.add(page, moves, 6);
    TextView undo = Ui.button(this, "Undo", v -> undo());
    TextView flip = Ui.button(this, "Flip", v -> { board.flipped = !board.flipped; board.invalidate(); });
    Ui.add(page, Ui.buttons(this, undo, flip), 6);
    setPage(page);
    load();
  }

  @Override protected void onPause() {
    super.onPause();
    save();
  }

  // ---- setup ----

  private void newGameDialog() {
    String[] who = {"Play white vs computer", "Play black vs computer", "Two players"};
    Dialogs.choose(this, "New game", who, w -> {
      if (w == 2) { start(0, level); return; }
      Engine.Level[] all = Engine.Level.values();
      String[] labels = new String[all.length];
      for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
      Dialogs.choose(this, "Computer strength", labels, l -> start(w == 0 ? Chess.WHITE : Chess.BLACK, all[l]));
    });
  }

  private void start(int humanColor, Engine.Level lvl) {
    generation++; // ignore any reply still being computed for the old game
    human = humanColor;
    level = lvl;
    game = Chess.start();
    history.clear();
    sans.clear();
    keys.clear();
    keys.add(game.positionKey());
    over = false;
    thinking = false;
    board.game = game;
    board.flipped = human == Chess.BLACK;
    board.selected = -1;
    board.lastFrom = board.lastTo = -1;
    board.targets.clear();
    fullRefresh();
    afterMove();
  }

  // ---- playing ----

  private boolean humansTurn() { return human == 0 || game.side == human; }

  private void tap(int sq) {
    if (over || thinking || !humansTurn()) return;
    if (board.selected >= 0) {
      List<Integer> choices = new ArrayList<>();
      for (int m : game.legalMoves())
        if (Chess.from(m) == board.selected && Chess.to(m) == sq) choices.add(m);
      if (!choices.isEmpty()) {
        board.selected = -1;
        board.targets.clear();
        if (choices.size() == 1) play(choices.get(0));
        else Dialogs.choose(this, "Promote to", PROMOS, i -> {
          for (int m : choices) if (Chess.promo(m) == PROMO_TYPES[i]) { play(m); return; }
        });
        return;
      }
    }
    int p = game.board[sq];
    if (p != 0 && Integer.signum(p) == game.side && sq != board.selected) {
      board.selected = sq;
      board.targets.clear();
      for (int m : game.legalMoves()) if (Chess.from(m) == sq) board.targets.add(Chess.to(m));
    } else {
      board.selected = -1;
      board.targets.clear();
    }
    board.invalidate();
  }

  private void play(int m) {
    sans.add(game.san(m));
    game.make(m);
    history.add(m);
    keys.add(game.positionKey());
    board.lastFrom = Chess.from(m);
    board.lastTo = Chess.to(m);
    afterMove();
  }

  /** Checks for the end of the game, updates the labels, and lets the computer reply if it's its turn. */
  private void afterMove() {
    String result = result();
    over = result != null;
    if (over) {
      status.setText(result);
      fullRefresh();
    } else if (humansTurn()) {
      String who = human == 0 ? (game.side == Chess.WHITE ? "White" : "Black") + " to move" : "Your move";
      status.setText(game.inCheck(game.side) ? "Check! " + who : who);
    } else {
      status.setText("Thinking…");
      think();
    }
    showMoves();
    board.invalidate();
    save();
  }

  private void think() {
    thinking = true;
    int gen = generation;
    Chess snapshot = Chess.fromFen(game.fen());
    Engine.Level lvl = level;
    new Thread(() -> {
      int m = engine.bestMove(snapshot, lvl);
      runOnUiThread(() -> {
        if (gen != generation) return;
        thinking = false;
        if (m >= 0) play(m);
      });
    }).start();
  }

  /** A description of how the game ended, or null if it's still going. */
  private String result() {
    if (game.legalMoves().isEmpty()) {
      if (!game.inCheck(game.side)) return "Stalemate — draw";
      boolean whiteWon = game.side == Chess.BLACK;
      if (human == 0) return "Checkmate — " + (whiteWon ? "white" : "black") + " wins";
      return (whiteWon == (human == Chess.WHITE)) ? "Checkmate — you win!" : "Checkmate — the computer wins";
    }
    if (game.halfmove >= 100) return "Draw by the 50-move rule";
    if (game.insufficientMaterial()) return "Draw — not enough pieces to mate";
    if (Collections.frequency(keys, game.positionKey()) >= 3) return "Draw by repetition";
    return null;
  }

  private void showMoves() {
    StringBuilder b = new StringBuilder();
    int from = Math.max(0, sans.size() - 8);
    if (from % 2 == 1) from--;
    for (int i = from; i < sans.size(); i++) {
      if (i % 2 == 0) b.append(i / 2 + 1).append(". ");
      b.append(sans.get(i)).append(' ');
    }
    moves.setText(b.toString().trim());
  }

  /** Takes back your last move (and the computer's reply). */
  private void undo() {
    if (history.isEmpty()) return;
    generation++;
    thinking = false;
    int n = 1;
    if (human != 0) {
      // Undo back to a position where it's the human's turn, at least one of their moves back.
      n = game.side == human ? 2 : 1;
      if (n > history.size()) n = history.size();
    }
    List<Integer> keep = new ArrayList<>(history.subList(0, history.size() - n));
    replay(keep);
    afterMove();
  }

  private void replay(List<Integer> moveList) {
    game = Chess.start();
    history.clear();
    sans.clear();
    keys.clear();
    keys.add(game.positionKey());
    board.game = game;
    board.lastFrom = board.lastTo = -1;
    board.selected = -1;
    board.targets.clear();
    for (int m : moveList) {
      sans.add(game.san(m));
      game.make(m);
      history.add(m);
      keys.add(game.positionKey());
      board.lastFrom = Chess.from(m);
      board.lastTo = Chess.to(m);
    }
  }

  // ---- storage: the move list, replayed from the start ----

  private void save() {
    StringBuilder b = new StringBuilder();
    for (int m : history) b.append(Chess.uci(m)).append(' ');
    prefs.edit().putString("moves", b.toString().trim()).putInt("human", human).putString("level", level.name()).apply();
  }

  private void load() {
    human = prefs.getInt("human", Chess.WHITE);
    try { level = Engine.Level.valueOf(prefs.getString("level", "EASY")); }
    catch (IllegalArgumentException e) { level = Engine.Level.EASY; }
    board.flipped = human == Chess.BLACK;
    game = Chess.start();
    board.game = game;
    List<Integer> list = new ArrayList<>();
    String saved = prefs.getString("moves", "").trim();
    if (!saved.isEmpty()) {
      Chess probe = Chess.start();
      for (String u : saved.split(" ")) {
        int m = probe.parseUci(u);
        if (m < 0) break;
        probe.make(m);
        list.add(m);
      }
    }
    replay(list);
    afterMove();
  }
}
