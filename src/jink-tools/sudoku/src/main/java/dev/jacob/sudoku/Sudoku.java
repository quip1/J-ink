package dev.jacob.sudoku;

import java.util.Random;

/**
 * Sudoku solving and generation on an int[81] (0 = empty). Every generated puzzle has exactly one
 * solution. Plain Java so it can be unit tested.
 */
final class Sudoku {
  private Sudoku() {}

  enum Difficulty {
    EASY("Easy", 38), MEDIUM("Medium", 32), HARD("Hard", 27), EXPERT("Expert", 24);
    final String label;
    final int clues;
    Difficulty(String label, int clues) { this.label = label; this.clues = clues; }
  }

  static int row(int cell) { return cell / 9; }

  static int col(int cell) { return cell % 9; }

  static int box(int cell) { return (row(cell) / 3) * 3 + col(cell) / 3; }

  /** Bitmask of digits (bit d for digit d) that can legally go in {@code cell}. */
  static int candidates(int[] g, int cell) {
    int used = 0, r = row(cell), c = col(cell), b = box(cell);
    for (int i = 0; i < 9; i++) {
      used |= 1 << g[r * 9 + i];
      used |= 1 << g[i * 9 + c];
      int br = (b / 3) * 3 + i / 3, bc = (b % 3) * 3 + i % 3;
      used |= 1 << g[br * 9 + bc];
    }
    return ~used & 0x3FE;
  }

  /** True if no filled cell conflicts with another in its row, column or box. */
  static boolean consistent(int[] g) {
    for (int i = 0; i < 81; i++) {
      if (g[i] == 0) continue;
      int v = g[i];
      g[i] = 0;
      boolean ok = (candidates(g, i) & (1 << v)) != 0;
      g[i] = v;
      if (!ok) return false;
    }
    return true;
  }

  /** Counts solutions, stopping once {@code limit} are found. Leaves {@code g} unchanged. */
  static int countSolutions(int[] g, int limit) {
    int[] work = g.clone();
    return count(work, limit, null);
  }

  /** Solves in place, returning true if a solution was found. */
  static boolean solve(int[] g) {
    int[] work = g.clone();
    int[] out = new int[81];
    if (count(work, 1, out) == 0) return false;
    System.arraycopy(out, 0, g, 0, 81);
    return true;
  }

  /** Backtracking that always tries the cell with the fewest options first. */
  private static int count(int[] g, int limit, int[] firstSolution) {
    int best = -1, bestMask = 0, bestCount = 10;
    for (int i = 0; i < 81; i++) {
      if (g[i] != 0) continue;
      int m = candidates(g, i);
      int n = Integer.bitCount(m);
      if (n == 0) return 0;
      if (n < bestCount) { best = i; bestMask = m; bestCount = n; if (n == 1) break; }
    }
    if (best < 0) {
      if (firstSolution != null) System.arraycopy(g, 0, firstSolution, 0, 81);
      return 1;
    }
    int found = 0;
    for (int d = 1; d <= 9 && found < limit; d++) {
      if ((bestMask & (1 << d)) == 0) continue;
      g[best] = d;
      found += count(g, limit - found, found == 0 ? firstSolution : null);
    }
    g[best] = 0;
    return found;
  }

  /** A random complete, valid grid. */
  static int[] fullGrid(Random r) {
    int[] g = new int[81];
    fill(g, 0, r);
    return g;
  }

  private static boolean fill(int[] g, int cell, Random r) {
    if (cell == 81) return true;
    int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int i = 8; i > 0; i--) { int j = r.nextInt(i + 1); int t = digits[i]; digits[i] = digits[j]; digits[j] = t; }
    int m = candidates(g, cell);
    for (int d : digits) {
      if ((m & (1 << d)) == 0) continue;
      g[cell] = d;
      if (fill(g, cell + 1, r)) return true;
    }
    g[cell] = 0;
    return false;
  }

  /**
   * Returns {puzzle, solution}. Clues are removed in symmetric pairs, keeping each removal only if
   * the puzzle still has a single solution, until the difficulty's clue count is reached or no
   * more can go.
   */
  static int[][] generate(Difficulty d, Random r) {
    int[] solution = fullGrid(r);
    int[] puzzle = solution.clone();
    int[] order = new int[41];
    for (int i = 0; i < 41; i++) order[i] = i;
    for (int i = 40; i > 0; i--) { int j = r.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
    int clues = 81;
    for (int cell : order) {
      if (clues <= d.clues) break;
      int twin = 80 - cell;
      int a = puzzle[cell], b = puzzle[twin];
      puzzle[cell] = 0;
      puzzle[twin] = 0;
      if (countSolutions(puzzle, 2) == 1) clues -= cell == twin ? 1 : 2;
      else { puzzle[cell] = a; puzzle[twin] = b; }
    }
    return new int[][]{puzzle, solution};
  }
}
