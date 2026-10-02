package dev.jacob.timer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClocksTest {
  @Test public void stopwatchWithLaps() {
    Clocks.Stopwatch w = new Clocks.Stopwatch();
    w.start(1000);
    w.lap(31_000);
    w.lap(81_000);
    w.stop(91_000);
    assertEquals(90_000, w.elapsed(500_000)); // stopped time doesn't count
    assertEquals(30_000, w.lapTime(0));
    assertEquals(50_000, w.lapTime(1));
    w.start(600_000);
    assertEquals(100_000, w.elapsed(610_000));
    w.reset();
    assertEquals(0, w.elapsed(700_000));
    assertTrue(w.laps.isEmpty());
  }

  @Test public void countdownPauseAndResume() {
    Clocks.Countdown c = new Clocks.Countdown(1, "Tea", 180_000);
    c.start(0);
    c.pause(60_000);
    assertEquals(120_000, c.left(999_999));
    c.start(1_000_000);
    assertFalse(c.finished(1_119_999));
    assertTrue(c.finished(1_120_000));
    c.reset();
    assertEquals(180_000, c.left(0));
  }

  @Test public void formatting() {
    assertEquals("0:00", Clocks.format(0, false));
    assertEquals("2:05", Clocks.format(125_000, false));
    assertEquals("1:02:03", Clocks.format(3_723_000, false));
    assertEquals("0:01.5", Clocks.format(1_500, true));
    assertEquals("0:01", Clocks.formatLeft(1)); // rounds up
    assertEquals("3:00", Clocks.formatLeft(180_000));
  }
}
