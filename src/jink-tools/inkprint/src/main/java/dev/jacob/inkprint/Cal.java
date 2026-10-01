package dev.jacob.inkprint;

import java.util.Calendar;
import java.util.GregorianCalendar;

/** Calendar arithmetic for the planner templates. Plain Java so it can be unit tested. */
final class Cal {
  private Cal() {}

  /** Column (0-6) of the 1st of the month, counting from {@code firstDayOfWeek} (Calendar.SUNDAY etc.). */
  static int firstColumn(int year, int month, int firstDayOfWeek) {
    Calendar c = new GregorianCalendar(year, month, 1);
    return (c.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7;
  }

  static int daysInMonth(int year, int month) {
    return new GregorianCalendar(year, month, 1).getActualMaximum(Calendar.DAY_OF_MONTH);
  }

  /** Rows needed to show the month (4 to 6). */
  static int weeks(int year, int month, int firstDayOfWeek) {
    return (firstColumn(year, month, firstDayOfWeek) + daysInMonth(year, month) + 6) / 7;
  }

  /** The date {@code days} after the given one, as a new Calendar. */
  static Calendar plusDays(Calendar start, int days) {
    Calendar c = (Calendar) start.clone();
    c.add(Calendar.DAY_OF_MONTH, days);
    return c;
  }

  /** The start of the week containing {@code day}. */
  static Calendar weekStart(Calendar day, int firstDayOfWeek) {
    Calendar c = (Calendar) day.clone();
    int back = (c.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7;
    c.add(Calendar.DAY_OF_MONTH, -back);
    return c;
  }

  /** Short weekday names in column order, e.g. Mon..Sun when weeks start on Monday. */
  static String[] weekdayNames(int firstDayOfWeek, boolean full) {
    String[] shortNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    String[] fullNames = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    String[] src = full ? fullNames : shortNames;
    String[] out = new String[7];
    for (int i = 0; i < 7; i++) out[i] = src[(firstDayOfWeek - 1 + i) % 7];
    return out;
  }

  static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
      "August", "September", "October", "November", "December"};
}
