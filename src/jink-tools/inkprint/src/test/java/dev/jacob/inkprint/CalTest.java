package dev.jacob.inkprint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.GregorianCalendar;
import org.junit.Test;

public class CalTest {
  @Test public void firstColumnRespectsWeekStart() {
    // 1 October 2026 is a Thursday.
    assertEquals(4, Cal.firstColumn(2026, Calendar.OCTOBER, Calendar.SUNDAY));
    assertEquals(3, Cal.firstColumn(2026, Calendar.OCTOBER, Calendar.MONDAY));
  }

  @Test public void monthLengthsIncludingLeapYears() {
    assertEquals(29, Cal.daysInMonth(2028, Calendar.FEBRUARY));
    assertEquals(28, Cal.daysInMonth(2026, Calendar.FEBRUARY));
    assertEquals(31, Cal.daysInMonth(2026, Calendar.DECEMBER));
  }

  @Test public void weeksNeeded() {
    // February 2026 starts on a Sunday and has 28 days: exactly 4 rows with Sunday-first weeks.
    assertEquals(4, Cal.weeks(2026, Calendar.FEBRUARY, Calendar.SUNDAY));
    assertEquals(5, Cal.weeks(2026, Calendar.FEBRUARY, Calendar.MONDAY));
    // August 2026 starts on Saturday with 31 days: 6 rows Sunday-first.
    assertEquals(6, Cal.weeks(2026, Calendar.AUGUST, Calendar.SUNDAY));
  }

  @Test public void weekStartGoesBackToFirstDay() {
    Calendar thu = new GregorianCalendar(2026, Calendar.OCTOBER, 1);
    Calendar mon = Cal.weekStart(thu, Calendar.MONDAY);
    assertEquals(28, mon.get(Calendar.DAY_OF_MONTH));
    assertEquals(Calendar.SEPTEMBER, mon.get(Calendar.MONTH));
    assertEquals(Calendar.MONDAY, mon.get(Calendar.DAY_OF_WEEK));
  }

  @Test public void weekdayNamesRotate() {
    assertArrayEquals(new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"},
        Cal.weekdayNames(Calendar.MONDAY, false));
  }
}
