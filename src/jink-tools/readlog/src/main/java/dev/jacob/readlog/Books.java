package dev.jacob.readlog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Books, reading sessions and the stats worked out from them. Plain Java so it can be unit tested. */
final class Books {
  private Books() {}

  enum Status {
    READING("Reading"), WANT("Want to read"), FINISHED("Finished");
    final String label;
    Status(String label) { this.label = label; }
  }

  static final class Session {
    long start;
    int minutes, fromPage, toPage;

    int pages() { return Math.max(0, toPage - fromPage); }
  }

  static final class Book {
    String id = UUID.randomUUID().toString();
    String title = "", author = "";
    int totalPages, currentPage;
    Status status = Status.READING;
    long added = System.currentTimeMillis(), finished;
    final List<Session> sessions = new ArrayList<>();

    int percent() { return totalPages <= 0 ? 0 : Math.min(100, Math.round(100f * currentPage / totalPages)); }

    int pagesRead() {
      int p = 0;
      for (Session s : sessions) p += s.pages();
      return p;
    }

    int minutesRead() {
      int m = 0;
      for (Session s : sessions) m += s.minutes;
      return m;
    }

    /** Pages per hour across all timed sessions, or 0 if there's not enough to go on. */
    double pagesPerHour() {
      int m = minutesRead();
      return m < 5 ? 0 : pagesRead() * 60.0 / m;
    }

    /** Estimated minutes to finish at the current pace, or -1 if unknown. */
    int minutesLeft() {
      double pph = pagesPerHour();
      if (pph <= 0 || totalPages <= 0) return -1;
      return (int) Math.ceil(Math.max(0, totalPages - currentPage) / pph * 60);
    }

    /** Records a session and moves the bookmark. Returns true if that reached the last page. */
    boolean log(long start, int minutes, int toPage) {
      Session s = new Session();
      s.start = start;
      s.minutes = Math.max(0, minutes);
      s.fromPage = currentPage;
      s.toPage = Math.max(0, totalPages > 0 ? Math.min(totalPages, toPage) : toPage);
      sessions.add(s);
      currentPage = s.toPage;
      return totalPages > 0 && currentPage >= totalPages;
    }
  }

  static LocalDate day(long millis, ZoneId zone) { return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(); }

  /**
   * Days in a row with at least one session, ending today, or ending yesterday if you haven't read
   * yet today (so the streak doesn't look broken first thing in the morning).
   */
  static int streak(List<Book> books, LocalDate today, ZoneId zone) {
    Set<LocalDate> days = new TreeSet<>();
    for (Book b : books) for (Session s : b.sessions) days.add(day(s.start, zone));
    LocalDate d = days.contains(today) ? today : today.minusDays(1);
    int n = 0;
    while (days.contains(d)) { n++; d = d.minusDays(1); }
    return n;
  }

  /** Pages and minutes over the last 7 days, today included. Returns {pages, minutes}. */
  static int[] lastSevenDays(List<Book> books, LocalDate today, ZoneId zone) {
    LocalDate from = today.minusDays(6);
    int pages = 0, minutes = 0;
    for (Book b : books) {
      for (Session s : b.sessions) {
        LocalDate d = day(s.start, zone);
        if (!d.isBefore(from) && !d.isAfter(today)) { pages += s.pages(); minutes += s.minutes; }
      }
    }
    return new int[]{pages, minutes};
  }

  static String duration(int minutes) {
    if (minutes < 60) return minutes + " min";
    return (minutes / 60) + " h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
  }
}
