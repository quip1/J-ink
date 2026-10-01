package dev.jacob.dice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;
import org.junit.Test;

public class DiceRollerTest {
  /** A Random that returns the given die faces in order. */
  static Random faces(int... values) {
    Deque<Integer> q = new ArrayDeque<>();
    for (int v : values) q.add(v);
    return new Random() {
      @Override public int nextInt(int bound) {
        int v = q.removeFirst();
        if (v < 1 || v > bound) throw new AssertionError("face " + v + " out of range for d" + bound);
        return v - 1;
      }
    };
  }

  static DiceRoller.Result roll(String expr, int... faces) { return new DiceRoller(faces(faces)).roll(expr); }

  @Test public void plainDiceAndModifiers() {
    assertEquals(17, roll("1d20+5", 12).total());
    assertEquals(9, roll("2d6 - 1", 4, 6).total());
    assertEquals(7, roll("d8 + 1d4 - 2", 6, 3).total());
    assertEquals(-3, roll("-1d4", 3).total());
    assertEquals(42, roll("42").total());
    assertEquals(88, roll("d%", 88).total());
  }

  @Test public void dropAndKeep() {
    DiceRoller.Result r = roll("4d6dl1", 6, 1, 4, 3);
    assertEquals(13, r.total());
    assertEquals("4d6dl1 [6, (1), 4, 3]", r.detail);
    assertEquals(10, roll("4d6kh2", 2, 6, 4, 1).total());
    assertEquals(3, roll("4d6kl2", 2, 6, 4, 1).total());
    assertEquals(7, roll("3d6dh1", 6, 5, 2).total());
    assertEquals(11, roll("3d6k2", 6, 5, 2).total());
  }

  @Test public void advantageAndNaturals() {
    DiceRoller.Result adv = roll("adv+3", 7, 20);
    assertEquals(23, adv.total());
    assertEquals("Natural 20!", adv.flag);
    DiceRoller.Result dis = roll("dis", 1, 15);
    assertEquals(1, dis.total());
    assertEquals("Natural 1", dis.flag);
    assertEquals("Natural 20!", roll("d20", 20).flag);
    assertNull(roll("2d20", 20, 3).flag);
    assertNull(roll("d20 + d20", 20, 20).flag);
  }

  @Test public void rerollAndExplode() {
    // Great Weapon Fighting style: reroll 1s and 2s once.
    DiceRoller.Result r = roll("2d6r2", 1, 1, 5);
    assertEquals(6, r.total());
    assertEquals("2d6r2 [~1~, 1, 5]", r.detail);
    DiceRoller.Result e = roll("2d6!", 6, 6, 2, 3);
    assertEquals(17, e.total());
  }

  @Test public void repeatedRolls() {
    DiceRoller.Result r = roll("2x 1d6+1", 3, 5);
    assertEquals("4, 6", r.totalText());
    assertTrue(Arrays.equals(new int[]{4, 6}, r.totals));
  }

  @Test public void realRandomStaysInRange() {
    DiceRoller d = new DiceRoller(new Random(7));
    for (int i = 0; i < 2000; i++) {
      int v = d.roll("4d6dl1").total();
      assertTrue(v >= 3 && v <= 18);
    }
  }

  @Test public void badInputGivesReadableErrors() {
    for (String bad : new String[]{"", "d", "2d", "1d20+", "hello", "0d6", "1001d6", "1d6r6", "2*3", "d1!"}) {
      try {
        new DiceRoller(new Random()).roll(bad);
        fail("Expected an error for '" + bad + "'");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().length() > 3);
      }
    }
  }
}
