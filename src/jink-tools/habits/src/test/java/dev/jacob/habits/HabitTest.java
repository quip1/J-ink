package dev.jacob.habits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

public class HabitTest {
  static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

  static Habit with(int... daysAgo) {
    Habit h = new Habit("Read");
    for (int d : daysAgo) h.toggle(TODAY.minusDays(d));
    return h;
  }

  @Test public void streakCountsFromTodayOrYesterday() {
    assertEquals(3, with(0, 1, 2, 4).streak(TODAY));
    assertEquals(2, with(1, 2, 4).streak(TODAY)); // not done yet today: streak still alive
    assertEquals(0, with(2, 3).streak(TODAY));
  }

  @Test public void bestStreakAndPercent() {
    Habit h = with(0, 1, 5, 6, 7, 8, 20);
    assertEquals(4, h.bestStreak());
    assertEquals(57, h.percent(TODAY, 7)); // days 0, 1, 5, 6 = 4 of 7
    assertEquals(0, new Habit("x").bestStreak());
  }

  @Test public void toggleAndRecent() {
    Habit h = with(0, 2);
    assertEquals("□□□□■□■", h.recent(TODAY, 7));
    h.toggle(TODAY);
    assertFalse(h.done(TODAY));
    assertTrue(h.done(TODAY.minusDays(2)));
  }
}
