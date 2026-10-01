package dev.jacob.wordsearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class PuzzleTest {
  @Test public void everyPlacedWordReadsCorrectlyAndGridIsFull() {
    Random r = new Random(4);
    for (int t = 0; t < Puzzle.THEMES.length; t++) {
      for (Puzzle.Difficulty d : Puzzle.Difficulty.values()) {
        Puzzle p = Puzzle.generate(t, d, r);
        assertTrue(Puzzle.THEMES[t] + " " + d + " placed only " + p.words.size(), p.words.size() >= d.words - 2);
        for (Puzzle.Placement w : p.words) assertEquals(w.word, p.read(w));
        for (char[] row : p.grid) for (char ch : row) assertTrue(ch >= 'A' && ch <= 'Z');
      }
    }
  }

  @Test public void easyOnlyGoesRightOrDown() {
    Puzzle p = Puzzle.generate(0, Puzzle.Difficulty.EASY, new Random(8));
    for (Puzzle.Placement w : p.words) assertTrue((w.dx == 1 && w.dy == 0) || (w.dx == 0 && w.dy == 1));
  }

  @Test public void selectingEitherEndFindsTheWord() {
    Puzzle p = Puzzle.generate(2, Puzzle.Difficulty.HARD, new Random(5));
    Puzzle.Placement w = p.words.get(0);
    assertNull(p.select(w.x, w.y, w.x, w.y));
    assertNotNull(p.select(w.endX(), w.endY(), w.x, w.y));
    assertTrue(w.found);
    assertNull(p.select(w.x, w.y, w.endX(), w.endY())); // already found
    for (Puzzle.Placement o : p.words) if (!o.found) p.select(o.x, o.y, o.endX(), o.endY());
    assertTrue(p.done());
  }

  @Test public void wordListsAreClean() {
    for (String[] list : Puzzle.WORDS) {
      assertTrue(list.length >= 16);
      for (String w : list) {
        assertTrue(w, w.matches("[A-Z]{3,15}"));
        assertFalse(w, w.length() > 15);
      }
    }
    assertEquals(Puzzle.THEMES.length, Puzzle.WORDS.length);
  }
}
