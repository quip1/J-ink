package dev.jacob.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * Chess rules: board, legal moves, check/mate/draw detection, FEN and SAN. Plain Java so it can be
 * unit tested (move generation is verified against standard perft counts).
 *
 * <p>Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56. Pieces are +1..+6 for white pawn, knight,
 * bishop, rook, queen, king and negative for black. A move is packed into an int: from | to << 6 |
 * promotion piece type << 12 | flags << 16.
 */
final class Chess {
  static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
  static final int WHITE = 1, BLACK = -1;
  static final int F_CAPTURE = 1, F_EP = 2, F_CASTLE = 4, F_DOUBLE = 8;
  static final int CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;
  static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  private static final int[][] KNIGHT_FR = {{1, 2}, {-1, 2}, {2, 1}, {-2, 1}, {2, -1}, {-2, -1}, {1, -2}, {-1, -2}};
  private static final int[][] KING_FR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
  private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

  final int[] board = new int[64];
  int side = WHITE, castling, ep = -1, halfmove, fullmove = 1;

  /** Everything make() changes that unmake() needs to restore. */
  private final List<int[]> undoStack = new ArrayList<>();

  static int from(int m) { return m & 63; }
  static int to(int m) { return (m >> 6) & 63; }
  static int promo(int m) { return (m >> 12) & 7; }
  static int flags(int m) { return m >> 16; }
  static int move(int from, int to, int promo, int flags) { return from | to << 6 | promo << 12 | flags << 16; }
  static int file(int sq) { return sq & 7; }
  static int rank(int sq) { return sq >> 3; }
  static String sqName(int sq) { return "" + (char) ('a' + file(sq)) + (char) ('1' + rank(sq)); }

  static Chess start() { return fromFen(START); }

  // ---- FEN ----

  static Chess fromFen(String fen) {
    Chess c = new Chess();
    String[] f = fen.trim().split("\\s+");
    int r = 7, fl = 0;
    for (char ch : f[0].toCharArray()) {
      if (ch == '/') { r--; fl = 0; }
      else if (Character.isDigit(ch)) fl += ch - '0';
      else {
        int t = "pnbrqk".indexOf(Character.toLowerCase(ch)) + 1;
        if (t == 0 || r < 0 || fl > 7) throw new IllegalArgumentException("Bad FEN: " + fen);
        c.board[r * 8 + fl++] = Character.isUpperCase(ch) ? t : -t;
      }
    }
    c.side = f.length > 1 && f[1].equals("b") ? BLACK : WHITE;
    if (f.length > 2) {
      for (char ch : f[2].toCharArray()) {
        if (ch == 'K') c.castling |= CASTLE_WK;
        if (ch == 'Q') c.castling |= CASTLE_WQ;
        if (ch == 'k') c.castling |= CASTLE_BK;
        if (ch == 'q') c.castling |= CASTLE_BQ;
      }
    }
    if (f.length > 3 && !f[3].equals("-")) c.ep = (f[3].charAt(0) - 'a') + 8 * (f[3].charAt(1) - '1');
    if (f.length > 4) c.halfmove = Integer.parseInt(f[4]);
    if (f.length > 5) c.fullmove = Integer.parseInt(f[5]);
    return c;
  }

  /** FEN without the move counters: identical positions give identical keys (for repetition). */
  String positionKey() {
    StringBuilder b = new StringBuilder();
    for (int r = 7; r >= 0; r--) {
      int empty = 0;
      for (int f = 0; f < 8; f++) {
        int p = board[r * 8 + f];
        if (p == 0) { empty++; continue; }
        if (empty > 0) { b.append(empty); empty = 0; }
        char ch = "pnbrqk".charAt(Math.abs(p) - 1);
        b.append(p > 0 ? Character.toUpperCase(ch) : ch);
      }
      if (empty > 0) b.append(empty);
      if (r > 0) b.append('/');
    }
    b.append(side == WHITE ? " w " : " b ");
    String cr = (castling & CASTLE_WK) != 0 ? "K" : "";
    cr += (castling & CASTLE_WQ) != 0 ? "Q" : "";
    cr += (castling & CASTLE_BK) != 0 ? "k" : "";
    cr += (castling & CASTLE_BQ) != 0 ? "q" : "";
    b.append(cr.isEmpty() ? "-" : cr).append(' ').append(ep < 0 ? "-" : sqName(ep));
    return b.toString();
  }

  String fen() { return positionKey() + " " + halfmove + " " + fullmove; }

  // ---- attacks ----

  private static boolean on(int f, int r) { return f >= 0 && f < 8 && r >= 0 && r < 8; }

  /** Is {@code sq} attacked by any piece of {@code by}? */
  boolean attacked(int sq, int by) {
    int f = file(sq), r = rank(sq);
    // Pawns attack diagonally forward, so look one rank "behind" sq from the attacker's view.
    int pr = r - by;
    if (on(f - 1, pr) && board[pr * 8 + f - 1] == by * PAWN) return true;
    if (on(f + 1, pr) && board[pr * 8 + f + 1] == by * PAWN) return true;
    for (int[] d : KNIGHT_FR) if (on(f + d[0], r + d[1]) && board[(r + d[1]) * 8 + f + d[0]] == by * KNIGHT) return true;
    for (int[] d : KING_FR) if (on(f + d[0], r + d[1]) && board[(r + d[1]) * 8 + f + d[0]] == by * KING) return true;
    if (slides(f, r, ROOK_DIRS, by * ROOK, by * QUEEN)) return true;
    return slides(f, r, BISHOP_DIRS, by * BISHOP, by * QUEEN);
  }

  private boolean slides(int f, int r, int[][] dirs, int a, int b) {
    for (int[] d : dirs) {
      int x = f + d[0], y = r + d[1];
      while (on(x, y)) {
        int p = board[y * 8 + x];
        if (p != 0) { if (p == a || p == b) return true; break; }
        x += d[0];
        y += d[1];
      }
    }
    return false;
  }

  int kingSquare(int color) {
    for (int i = 0; i < 64; i++) if (board[i] == color * KING) return i;
    return -1;
  }

  boolean inCheck(int color) {
    int k = kingSquare(color);
    return k >= 0 && attacked(k, -color);
  }

  // ---- move generation ----

  /** All legal moves for the side to move. */
  List<Integer> legalMoves() {
    List<Integer> out = new ArrayList<>();
    for (int m : pseudoMoves(false)) {
      make(m);
      if (!inCheck(-side)) out.add(m);
      unmake();
    }
    return out;
  }

  /** Legal captures and promotions only, for the AI's quiescence search. */
  List<Integer> legalCaptures() {
    List<Integer> out = new ArrayList<>();
    for (int m : pseudoMoves(true)) {
      make(m);
      if (!inCheck(-side)) out.add(m);
      unmake();
    }
    return out;
  }

  private List<Integer> pseudoMoves(boolean capturesOnly) {
    List<Integer> out = new ArrayList<>(48);
    int us = side;
    for (int sq = 0; sq < 64; sq++) {
      int p = board[sq];
      if (p == 0 || Integer.signum(p) != us) continue;
      int t = Math.abs(p), f = file(sq), r = rank(sq);
      switch (t) {
        case PAWN: pawnMoves(sq, f, r, us, out, capturesOnly); break;
        case KNIGHT: steps(sq, f, r, KNIGHT_FR, us, out, capturesOnly); break;
        case KING:
          steps(sq, f, r, KING_FR, us, out, capturesOnly);
          if (!capturesOnly) castles(sq, us, out);
          break;
        default:
          if (t == BISHOP || t == QUEEN) rays(sq, f, r, BISHOP_DIRS, us, out, capturesOnly);
          if (t == ROOK || t == QUEEN) rays(sq, f, r, ROOK_DIRS, us, out, capturesOnly);
      }
    }
    return out;
  }

  private void pawnMoves(int sq, int f, int r, int us, List<Integer> out, boolean capturesOnly) {
    int dir = us, startRank = us == WHITE ? 1 : 6, lastRank = us == WHITE ? 7 : 0;
    int r1 = r + dir;
    if (!on(f, r1)) return;
    int one = r1 * 8 + f;
    if (board[one] == 0) {
      if (r1 == lastRank) addPromos(sq, one, 0, out);
      else if (!capturesOnly) {
        out.add(move(sq, one, 0, 0));
        int two = (r + 2 * dir) * 8 + f;
        if (r == startRank && board[two] == 0) out.add(move(sq, two, 0, F_DOUBLE));
      }
    }
    for (int df = -1; df <= 1; df += 2) {
      if (!on(f + df, r1)) continue;
      int target = r1 * 8 + f + df;
      int victim = board[target];
      if (victim != 0 && Integer.signum(victim) == -us) {
        if (r1 == lastRank) addPromos(sq, target, F_CAPTURE, out);
        else out.add(move(sq, target, 0, F_CAPTURE));
      } else if (target == ep) {
        out.add(move(sq, target, 0, F_CAPTURE | F_EP));
      }
    }
  }

  private static void addPromos(int from, int to, int flags, List<Integer> out) {
    for (int t = QUEEN; t >= KNIGHT; t--) out.add(move(from, to, t, flags));
  }

  private void steps(int sq, int f, int r, int[][] deltas, int us, List<Integer> out, boolean capturesOnly) {
    for (int[] d : deltas) {
      int x = f + d[0], y = r + d[1];
      if (!on(x, y)) continue;
      int to = y * 8 + x, p = board[to];
      if (p == 0) { if (!capturesOnly) out.add(move(sq, to, 0, 0)); }
      else if (Integer.signum(p) != us) out.add(move(sq, to, 0, F_CAPTURE));
    }
  }

  private void rays(int sq, int f, int r, int[][] dirs, int us, List<Integer> out, boolean capturesOnly) {
    for (int[] d : dirs) {
      int x = f + d[0], y = r + d[1];
      while (on(x, y)) {
        int to = y * 8 + x, p = board[to];
        if (p == 0) { if (!capturesOnly) out.add(move(sq, to, 0, 0)); }
        else {
          if (Integer.signum(p) != us) out.add(move(sq, to, 0, F_CAPTURE));
          break;
        }
        x += d[0];
        y += d[1];
      }
    }
  }

  private void castles(int sq, int us, List<Integer> out) {
    int home = us == WHITE ? 4 : 60;
    if (sq != home || attacked(home, -us)) return;
    int kingSide = us == WHITE ? CASTLE_WK : CASTLE_BK, queenSide = us == WHITE ? CASTLE_WQ : CASTLE_BQ;
    if ((castling & kingSide) != 0 && board[home + 1] == 0 && board[home + 2] == 0 && board[home + 3] == us * ROOK
        && !attacked(home + 1, -us) && !attacked(home + 2, -us))
      out.add(move(home, home + 2, 0, F_CASTLE));
    if ((castling & queenSide) != 0 && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0
        && board[home - 4] == us * ROOK && !attacked(home - 1, -us) && !attacked(home - 2, -us))
      out.add(move(home, home - 2, 0, F_CASTLE));
  }

  // ---- make / unmake ----

  void make(int m) {
    int from = from(m), to = to(m), fl = flags(m), piece = board[from];
    int captured = board[to];
    int capSq = to;
    if ((fl & F_EP) != 0) { capSq = to - 8 * side; captured = board[capSq]; board[capSq] = 0; }
    undoStack.add(new int[]{m, captured, castling, ep, halfmove, capSq});

    board[to] = promo(m) != 0 ? side * promo(m) : piece;
    board[from] = 0;
    if ((fl & F_CASTLE) != 0) {
      boolean kingSide = to > from;
      int rookFrom = kingSide ? from + 3 : from - 4, rookTo = kingSide ? from + 1 : from - 1;
      board[rookTo] = board[rookFrom];
      board[rookFrom] = 0;
    }
    ep = (fl & F_DOUBLE) != 0 ? (from + to) / 2 : -1;
    castling &= ~(rightsLost(from) | rightsLost(to));
    halfmove = Math.abs(piece) == PAWN || captured != 0 ? 0 : halfmove + 1;
    if (side == BLACK) fullmove++;
    side = -side;
  }

  /** Castling rights that disappear when a king or rook leaves (or a rook is taken on) {@code sq}. */
  private static int rightsLost(int sq) {
    switch (sq) {
      case 4: return CASTLE_WK | CASTLE_WQ;
      case 60: return CASTLE_BK | CASTLE_BQ;
      case 0: return CASTLE_WQ;
      case 7: return CASTLE_WK;
      case 56: return CASTLE_BQ;
      case 63: return CASTLE_BK;
      default: return 0;
    }
  }

  void unmake() {
    int[] u = undoStack.remove(undoStack.size() - 1);
    int m = u[0], from = from(m), to = to(m), fl = flags(m);
    side = -side;
    if (side == BLACK) fullmove--;
    board[from] = promo(m) != 0 ? side * PAWN : board[to];
    board[to] = 0;
    board[u[5]] = u[1];
    if ((fl & F_CASTLE) != 0) {
      boolean kingSide = to > from;
      int rookFrom = kingSide ? from + 3 : from - 4, rookTo = kingSide ? from + 1 : from - 1;
      board[rookFrom] = board[rookTo];
      board[rookTo] = 0;
    }
    castling = u[2];
    ep = u[3];
    halfmove = u[4];
  }

  /** Number of leaf positions at {@code depth}: the standard way to verify a move generator. */
  long perft(int depth) {
    if (depth == 0) return 1;
    List<Integer> moves = legalMoves();
    if (depth == 1) return moves.size();
    long n = 0;
    for (int m : moves) { make(m); n += perft(depth - 1); unmake(); }
    return n;
  }

  // ---- game state ----

  /** Neither side can possibly mate: bare kings, or a king and one minor piece against a king. */
  boolean insufficientMaterial() {
    int minors = 0;
    for (int p : board) {
      int t = Math.abs(p);
      if (t == PAWN || t == ROOK || t == QUEEN) return false;
      if (t == KNIGHT || t == BISHOP) minors++;
    }
    return minors <= 1;
  }

  // ---- notation ----

  /** Standard algebraic notation (e.g. Nf3, exd5, O-O, e8=Q+) for a legal move in this position. */
  String san(int m) {
    int from = from(m), to = to(m), piece = Math.abs(board[from]);
    StringBuilder s = new StringBuilder();
    if ((flags(m) & F_CASTLE) != 0) s.append(to > from ? "O-O" : "O-O-O");
    else {
      if (piece == PAWN) {
        if ((flags(m) & F_CAPTURE) != 0) s.append((char) ('a' + file(from)));
      } else {
        s.append("PNBRQK".charAt(piece - 1));
        boolean sameFile = false, sameRank = false, ambiguous = false;
        for (int o : legalMoves()) {
          if (o == m || to(o) != to || from(o) == from || Math.abs(board[from(o)]) != piece) continue;
          ambiguous = true;
          if (file(from(o)) == file(from)) sameFile = true;
          if (rank(from(o)) == rank(from)) sameRank = true;
        }
        if (ambiguous) {
          if (!sameFile) s.append((char) ('a' + file(from)));
          else if (!sameRank) s.append((char) ('1' + rank(from)));
          else s.append(sqName(from));
        }
      }
      if ((flags(m) & F_CAPTURE) != 0) s.append('x');
      s.append(sqName(to));
      if (promo(m) != 0) s.append('=').append("PNBRQK".charAt(promo(m) - 1));
    }
    make(m);
    if (inCheck(side)) s.append(legalMoves().isEmpty() ? '#' : '+');
    unmake();
    return s.toString();
  }

  /** Long algebraic like e2e4 or e7e8q, used to save games. */
  static String uci(int m) {
    return sqName(from(m)) + sqName(to(m)) + (promo(m) == 0 ? "" : String.valueOf("pnbrqk".charAt(promo(m) - 1)));
  }

  /** Finds the legal move matching a UCI string, or -1. */
  int parseUci(String s) {
    for (int m : legalMoves()) if (uci(m).equals(s)) return m;
    return -1;
  }
}
