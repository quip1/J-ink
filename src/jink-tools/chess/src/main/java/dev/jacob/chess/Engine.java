package dev.jacob.chess;

import java.util.List;
import java.util.Random;

/**
 * A small alpha-beta chess AI: material plus simple positional bonuses, captures searched to the
 * end so it doesn't blunder mid-exchange, and a time limit so it never hangs the device.
 */
final class Engine {
  enum Level {
    BEGINNER("Beginner", 1, 120, 400), EASY("Easy", 2, 40, 800), MEDIUM("Medium", 3, 0, 2000),
    HARD("Hard", 5, 0, 4000);
    final String label;
    final int depth, noise, millis;
    Level(String label, int depth, int noise, int millis) {
      this.label = label; this.depth = depth; this.noise = noise; this.millis = millis;
    }
  }

  static final int[] VALUE = {0, 100, 320, 330, 500, 900, 0};
  static final int MATE = 100_000;

  private final Random rng;
  private long deadline;
  private boolean outOfTime;
  int nodes;

  Engine(Random rng) { this.rng = rng; }

  /** Picks a move for the side to move, or -1 if there are none. Doesn't change {@code game}. */
  int bestMove(Chess game, Level level) {
    Chess pos = Chess.fromFen(game.fen());
    List<Integer> root = pos.legalMoves();
    if (root.isEmpty()) return -1;
    deadline = System.currentTimeMillis() + level.millis;
    outOfTime = false;
    nodes = 0;
    int best = root.get(0);
    // Iterative deepening: each finished depth's best move is searched first at the next depth.
    for (int depth = 1; depth <= level.depth; depth++) {
      order(pos, root, best);
      int alpha = -MATE * 2, chosen = best;
      for (int m : root) {
        pos.make(m);
        // With noise we need every move's exact score, not just "no better than the best so far",
        // so the window stays fully open; otherwise narrow it for speed.
        int score = -search(pos, depth - 1, -MATE * 2, level.noise > 0 ? MATE * 2 : -alpha, 1);
        pos.unmake();
        if (outOfTime) break;
        if (level.noise > 0) score += rng.nextInt(2 * level.noise + 1) - level.noise;
        if (score > alpha) { alpha = score; chosen = m; }
      }
      if (outOfTime && depth > 1) break;
      best = chosen;
      if (alpha >= MATE - 100) break; // found a forced mate
    }
    return best;
  }

  private int search(Chess pos, int depth, int alpha, int beta, int ply) {
    if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) outOfTime = true;
    if (outOfTime) return 0;
    if (pos.halfmove >= 100) return 0;
    if (depth <= 0) return quiesce(pos, alpha, beta, ply, 0);
    List<Integer> moves = pos.legalMoves();
    if (moves.isEmpty()) return pos.inCheck(pos.side) ? -MATE + ply : 0;
    order(pos, moves, -1);
    for (int m : moves) {
      pos.make(m);
      int score = -search(pos, depth - 1, -beta, -alpha, ply + 1);
      pos.unmake();
      if (score >= beta) return beta;
      if (score > alpha) alpha = score;
    }
    return alpha;
  }

  /** Keeps playing captures until the position is quiet, so the evaluation isn't mid-trade. */
  private int quiesce(Chess pos, int alpha, int beta, int ply, int qdepth) {
    int stand = evaluate(pos);
    if (stand >= beta) return beta;
    if (stand > alpha) alpha = stand;
    if (qdepth > 6) return alpha;
    List<Integer> caps = pos.legalCaptures();
    order(pos, caps, -1);
    for (int m : caps) {
      pos.make(m);
      int score = -quiesce(pos, -beta, -alpha, ply + 1, qdepth + 1);
      pos.unmake();
      if (score >= beta) return beta;
      if (score > alpha) alpha = score;
    }
    return alpha;
  }

  /** Best-guess first: the given move, then captures of big pieces by small ones, then promotions. */
  private static void order(Chess pos, List<Integer> moves, int first) {
    moves.sort((a, b) -> Integer.compare(guess(pos, b, first), guess(pos, a, first)));
  }

  private static int guess(Chess pos, int m, int first) {
    if (m == first) return 1_000_000;
    int g = 0;
    if ((Chess.flags(m) & Chess.F_CAPTURE) != 0) {
      int victim = (Chess.flags(m) & Chess.F_EP) != 0 ? Chess.PAWN : Math.abs(pos.board[Chess.to(m)]);
      g += 10 * VALUE[victim] - VALUE[Math.abs(pos.board[Chess.from(m)])] / 10;
    }
    if (Chess.promo(m) != 0) g += VALUE[Chess.promo(m)];
    return g;
  }

  /** Score from the point of view of the side to move. */
  static int evaluate(Chess pos) {
    int score = 0, material = 0;
    for (int p : pos.board) if (Math.abs(p) != Chess.KING && Math.abs(p) != Chess.PAWN) material += VALUE[Math.abs(p)];
    boolean endgame = material <= 2600;
    for (int sq = 0; sq < 64; sq++) {
      int p = pos.board[sq];
      if (p == 0) continue;
      int t = Math.abs(p), color = Integer.signum(p);
      // Flip the board for black so "forward" and "home" mean the same thing for both sides.
      int rank = color == Chess.WHITE ? Chess.rank(sq) : 7 - Chess.rank(sq), file = Chess.file(sq);
      score += color * (VALUE[t] + positional(t, rank, file, endgame));
    }
    return score * pos.side;
  }

  /** Simple hand-written placement bonuses (in centipawns). */
  static int positional(int type, int rank, int file, boolean endgame) {
    int centre = 6 - (Math.abs(2 * file - 7) + Math.abs(2 * rank - 7)) / 2; // 0 at corners .. 6 in the middle
    switch (type) {
      case Chess.PAWN: {
        int b = rank * (endgame ? 12 : 5);
        if ((file == 3 || file == 4) && (rank == 3 || rank == 4)) b += 15;
        if ((file == 3 || file == 4) && rank == 1) b -= 10; // get the centre pawns moving
        return b;
      }
      case Chess.KNIGHT: return centre * 8 - 20 - (rank == 0 ? 10 : 0);
      case Chess.BISHOP: return centre * 4 - (rank == 0 ? 10 : 0);
      case Chess.ROOK: return (rank == 6 ? 20 : 0) + ((file == 3 || file == 4) && rank == 0 ? 5 : 0);
      case Chess.QUEEN: return centre * 2;
      case Chess.KING:
        if (endgame) return centre * 8;
        return (rank == 0 ? 10 : -25 * rank) + (file == 6 || file == 1 || file == 2 ? 15 : 0);
      default: return 0;
    }
  }
}
