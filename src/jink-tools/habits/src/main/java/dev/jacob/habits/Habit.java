package dev.jacob.habits;

import java.time.LocalDate;
import java.util.TreeSet;
import java.util.UUID;

/** A habit and the days it was done. Plain Java so it can be unit tested. */
final class Habit {
  String id = UUID.randomUUID().toString();
  String name;
  /** Days done, as epoch days. */
  final TreeSet<Long> days = new TreeSet<>();

  Habit(String name) { this.name = name; }

  boolean done(LocalDate d) { return days.contains(d.toEpochDay()); }

  void toggle(LocalDate d) {
    if (!days.remove(d.toEpochDay())) days.add(d.toEpochDay());
  }

  /** Days in a row ending today, or ending yesterday if today isn't ticked yet. */
  int streak(LocalDate today) {
    long d = done(today) ? today.toEpochDay() : today.toEpochDay() - 1;
    int n = 0;
    while (days.contains(d)) { n++; d--; }
    return n;
  }

  int bestStreak() {
    int best = 0, run = 0;
    long prev = Long.MIN_VALUE;
    for (long d : days) {
      run = d == prev + 1 ? run + 1 : 1;
      best = Math.max(best, run);
      prev = d;
    }
    return best;
  }

  /** Percentage of the last {@code n} days (today included) that were done. */
  int percent(LocalDate today, int n) {
    int hit = 0;
    for (int i = 0; i < n; i++) if (done(today.minusDays(i))) hit++;
    return Math.round(100f * hit / n);
  }

  /** The last {@code n} days, oldest first, as ■ (done) and □ (not). */
  String recent(LocalDate today, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = n - 1; i >= 0; i--) b.append(done(today.minusDays(i)) ? '■' : '□');
    return b.toString();
  }
}
