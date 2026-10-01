package dev.jacob.focus;

/**
 * Focus / break cycle with wall-clock end times, so the timer survives the app being closed.
 * Plain Java so it can be unit tested.
 */
final class Pomodoro {
  enum Phase {
    FOCUS("Focus"), SHORT_BREAK("Short break"), LONG_BREAK("Long break");
    final String label;
    Phase(String label) { this.label = label; }
  }

  int focusMin = 25, shortMin = 5, longMin = 15, longEvery = 4;
  Phase phase = Phase.FOCUS;
  /** Focus sessions finished in the current cycle (resets after a long break). */
  int doneInCycle;
  /** When running: the wall-clock time the phase ends. */
  long endsAt;
  /** When paused: what was left. */
  long remainingMs = -1;
  boolean running;

  long duration(Phase p) {
    int min = p == Phase.FOCUS ? focusMin : p == Phase.SHORT_BREAK ? shortMin : longMin;
    return Math.max(1, min) * 60_000L;
  }

  long remaining(long now) {
    if (running) return Math.max(0, endsAt - now);
    return remainingMs >= 0 ? remainingMs : duration(phase);
  }

  void start(long now) {
    if (running) return;
    endsAt = now + remaining(now);
    running = true;
  }

  void pause(long now) {
    if (!running) return;
    remainingMs = remaining(now);
    running = false;
  }

  /** Back to the start of the current phase, stopped. */
  void reset() {
    running = false;
    remainingMs = -1;
  }

  /**
   * Checks whether the running phase has ended. If so, moves to the next phase (stopped, full
   * length) and returns the phase that just finished; otherwise returns null.
   */
  Phase tick(long now) {
    if (!running || now < endsAt) return null;
    Phase finished = phase;
    advance();
    return finished;
  }

  /** Ends the current phase early and moves on, without counting a skipped focus as done. */
  void skip() {
    if (phase == Phase.FOCUS) {
      phase = Phase.SHORT_BREAK;
    } else {
      if (phase == Phase.LONG_BREAK) doneInCycle = 0;
      phase = Phase.FOCUS;
    }
    reset();
  }

  private void advance() {
    if (phase == Phase.FOCUS) {
      doneInCycle++;
      phase = doneInCycle >= Math.max(1, longEvery) ? Phase.LONG_BREAK : Phase.SHORT_BREAK;
    } else {
      if (phase == Phase.LONG_BREAK) doneInCycle = 0;
      phase = Phase.FOCUS;
    }
    reset();
  }

  /** "25" style minutes left (rounded up), or "24:59" when seconds are shown. */
  static String format(long ms, boolean seconds) {
    long totalSec = (ms + 999) / 1000;
    if (seconds) return (totalSec / 60) + ":" + String.format(java.util.Locale.US, "%02d", totalSec % 60);
    return String.valueOf((totalSec + 59) / 60);
  }
}
