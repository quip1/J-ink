package dev.jacob.sudoku;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class SudokuTest {
  static int[] parse(String s) {
    int[] g = new int[81];
    for (int i = 0; i < 81; i++) g[i] = s.charAt(i) == '.' ? 0 : s.charAt(i) - '0';
    return g;
  }

  // A well-known puzzle with a single solution.
  static final String PUZZLE =
      "53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79";
  static final String SOLUTION =
      "534678912672195348198342567859761423426853791713924856961537284287419635345286179";

  @Test public void solvesAKnownPuzzle() {
    int[] g = parse(PUZZLE);
    assertTrue(Sudoku.solve(g));
    assertArrayEquals(parse(SOLUTION), g);
    assertEquals(1, Sudoku.countSolutions(parse(PUZZLE), 2));
  }

  @Test public void detectsMultipleAndNoSolutions() {
    assertEquals(2, Sudoku.countSolutions(new int[81], 2));
    int[] bad = parse(PUZZLE);
    bad[2] = 5; // second 5 in the first row
    assertFalse(Sudoku.consistent(bad));
    assertEquals(0, Sudoku.countSolutions(bad, 2));
  }

  @Test public void fullGridsAreValid() {
    Random r = new Random(1);
    for (int i = 0; i < 20; i++) {
      int[] g = Sudoku.fullGrid(r);
      assertTrue(Sudoku.consistent(g));
      for (int v : g) assertTrue(v >= 1 && v <= 9);
    }
  }

  @Test public void generatedPuzzlesAreUniqueAndMatchTheirSolution() {
    Random r = new Random(2026);
    for (Sudoku.Difficulty d : Sudoku.Difficulty.values()) {
      for (int k = 0; k < 3; k++) {
        long t0 = System.nanoTime();
        int[][] ps = Sudoku.generate(d, r);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(d + " took " + ms + " ms", ms < 5000);
        int[] puzzle = ps[0], solution = ps[1];
        assertEquals(1, Sudoku.countSolutions(puzzle, 2));
        int clues = 0;
        for (int i = 0; i < 81; i++) {
          if (puzzle[i] != 0) { clues++; assertEquals(solution[i], puzzle[i]); }
        }
        assertTrue(d + " has " + clues + " clues", clues <= d.clues + 6);
        int[] solved = puzzle.clone();
        assertTrue(Sudoku.solve(solved));
        assertArrayEquals(solution, solved);
      }
    }
  }
}
