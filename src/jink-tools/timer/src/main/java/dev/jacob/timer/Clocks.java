package dev.jacob.timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stopwatch and countdown state, driven by wall-clock times so they survive the app closing. */
final class Clocks {
  private Clocks() {}

  static final class Stopwatch {
    long startedAt = -1;
    long banked;
    final List<Long> laps = new ArrayList<>();

    boolean running() { return startedAt >= 0; }

    long elapsed(long now) { return banked + (running() ? now - startedAt : 0); }

    void start(long now) { if (!running()) startedAt = now; }

    void stop(long now) {
      if (!running()) return;
      banked += now - startedAt;
      startedAt = -1;
    }

    void reset() { startedAt = -1; banked = 0; laps.clear(); }

    /** Records the total elapsed time at this moment; lap times are the differences. */
    void lap(long now) { if (running()) laps.add(elapsed(now)); }

    long lapTime(int i) { return laps.get(i) - (i == 0 ? 0 : laps.get(i - 1)); }
  }

  static final class Countdown {
    final int id;
    String name;
    long duration;
    long endsAt = -1;
    long pausedLeft = -1;

    Countdown(int id, String name, long duration) { this.id = id; this.name = name; this.duration = duration; }

    boolean running() { return endsAt >= 0; }

    long left(long now) {
      if (running()) return Math.max(0, endsAt - now);
      return pausedLeft >= 0 ? pausedLeft : duration;
    }

    boolean finished(long now) { return running() && now >= endsAt; }

    void start(long now) { if (!running()) { endsAt = now + left(now); pausedLeft = -1; } }

    void pause(long now) { if (running()) { pausedLeft = left(now); endsAt = -1; } }

    void reset() { endsAt = -1; pausedLeft = -1; }
  }

  /** 1:02:03 or 2:03, with tenths for the stopwatch if asked. */
  static String format(long ms, boolean tenths) {
    long t = Math.max(0, ms);
    long h = t / 3_600_000, m = t / 60_000 % 60, s = t / 1000 % 60, d = t / 100 % 10;
    String base = h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    return tenths ? base + "." + d : base;
  }

  /** Countdowns show whole seconds rounded up, so a timer never reads 0:00 before it rings. */
  static String formatLeft(long ms) { return format((Math.max(0, ms) + 999) / 1000 * 1000, false); }
}
