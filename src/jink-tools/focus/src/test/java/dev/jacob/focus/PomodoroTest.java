package dev.jacob.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PomodoroTest {
  static final long MIN = 60_000L;

  @Test public void pauseAndResumeKeepTheRemainder() {
    Pomodoro p = new Pomodoro();
    p.start(0);
    p.pause(10 * MIN);
    assertEquals(15 * MIN, p.remaining(99 * MIN)); // paused time doesn't count
    p.start(100 * MIN);
    assertEquals(5 * MIN, p.remaining(110 * MIN));
  }

  @Test public void cycleGoesFocusShortFocusLong() {
    Pomodoro p = new Pomodoro();
    p.longEvery = 2;
    long t = 0;
    p.start(t);
    t += 25 * MIN;
    assertEquals(Pomodoro.Phase.FOCUS, p.tick(t));
    assertEquals(Pomodoro.Phase.SHORT_BREAK, p.phase);
    assertFalse(p.running);
    p.start(t);
    t += 5 * MIN;
    p.tick(t);
    assertEquals(Pomodoro.Phase.FOCUS, p.phase);
    p.start(t);
    t += 25 * MIN;
    p.tick(t);
    assertEquals(Pomodoro.Phase.LONG_BREAK, p.phase);
    p.start(t);
    t += 15 * MIN;
    p.tick(t);
    assertEquals(Pomodoro.Phase.FOCUS, p.phase);
    assertEquals(0, p.doneInCycle);
  }

  @Test public void tickBeforeTheEndDoesNothing() {
    Pomodoro p = new Pomodoro();
    p.start(0);
    assertNull(p.tick(24 * MIN));
    assertEquals(Pomodoro.Phase.FOCUS, p.phase);
  }

  @Test public void skippingFocusDoesNotCountIt() {
    Pomodoro p = new Pomodoro();
    p.skip();
    assertEquals(Pomodoro.Phase.SHORT_BREAK, p.phase);
    assertEquals(0, p.doneInCycle);
  }

  @Test public void formatting() {
    assertEquals("25", Pomodoro.format(25 * MIN, false));
    assertEquals("25", Pomodoro.format(24 * MIN + 1, false)); // rounds up
    assertEquals("1", Pomodoro.format(1000, false));
    assertEquals("24:59", Pomodoro.format(24 * MIN + 59_000, true));
    assertEquals("0:05", Pomodoro.format(4_001, true));
  }
}
