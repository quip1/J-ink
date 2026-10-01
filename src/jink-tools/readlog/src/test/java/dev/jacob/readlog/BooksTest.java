package dev.jacob.readlog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BooksTest {
  static final ZoneId UTC = ZoneOffset.UTC;

  static long at(LocalDate d, int hour) { return d.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(); }

  @Test public void loggingMovesTheBookmarkAndDetectsTheEnd() {
    Books.Book b = new Books.Book();
    b.totalPages = 300;
    assertFalse(b.log(0, 30, 40));
    assertEquals(40, b.currentPage);
    assertTrue(b.log(0, 30, 999));
    assertEquals(300, b.currentPage);
    assertEquals(300, b.pagesRead());
    assertEquals(100, b.percent());
  }

  @Test public void paceAndTimeLeft() {
    Books.Book b = new Books.Book();
    b.totalPages = 200;
    b.log(0, 60, 40); // 40 pages an hour
    assertEquals(40.0, b.pagesPerHour(), 0.001);
    assertEquals(240, b.minutesLeft()); // 160 pages left
    Books.Book fresh = new Books.Book();
    fresh.totalPages = 100;
    assertEquals(-1, fresh.minutesLeft());
  }

  @Test public void streakCountsBackFromTodayOrYesterday() {
    LocalDate today = LocalDate.of(2026, 10, 1);
    Books.Book b = new Books.Book();
    b.totalPages = 1000;
    for (int ago : new int[]{1, 2, 3, 5}) b.log(at(today.minusDays(ago), 20), 20, b.currentPage + 10);
    List<Books.Book> all = Arrays.asList(b);
    assertEquals(3, Books.streak(all, today, UTC)); // nothing yet today, so counts yesterday back
    b.log(at(today, 8), 10, b.currentPage + 5);
    assertEquals(4, Books.streak(all, today, UTC));
    assertEquals(0, Books.streak(all, today.plusDays(3), UTC));
  }

  @Test public void lastSevenDaysTotals() {
    LocalDate today = LocalDate.of(2026, 10, 1);
    Books.Book b = new Books.Book();
    b.totalPages = 1000;
    b.log(at(today.minusDays(7), 9), 60, 50); // just outside the window
    b.log(at(today.minusDays(6), 9), 30, 70);
    b.log(at(today, 9), 15, 80);
    assertArrayEquals(new int[]{30, 45}, Books.lastSevenDays(Arrays.asList(b), today, UTC));
  }

  @Test public void durations() {
    assertEquals("45 min", Books.duration(45));
    assertEquals("2 h", Books.duration(120));
    assertEquals("1 h 5 min", Books.duration(65));
  }
}
