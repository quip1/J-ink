package dev.jacob.calc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class CalcTest {
  static String calc(String s) { return Calculator.format(Calculator.eval(s)); }

  @Test public void arithmeticAndPrecedence() {
    assertEquals("14", calc("2+3×4"));
    assertEquals("20", calc("(2+3)×4"));
    assertEquals("2.5", calc("10÷4"));
    assertEquals("-7", calc("−3−4"));
    assertEquals("512", calc("2^3^2"));
    assertEquals("-4", calc("-2^2")); // as on paper: −(2²)
    assertEquals("0.3", calc("0.1+0.2")); // no 0.30000000000000004
    assertEquals("50", calc("200×25%"));
    assertEquals("3", calc("√9"));
    assertEquals("3.14159265359", calc("π"));
    assertEquals("12", calc("sqrt(144)"));
  }

  @Test public void formatting() {
    assertEquals("1000000", Calculator.format(1e6));
    assertEquals("0.333333333333", Calculator.format(1.0 / 3));
    assertEquals("1e20", Calculator.format(1e20));
    assertEquals("0", Calculator.format(-0.0));
  }

  @Test public void errorsAreFriendly() {
    for (String bad : new String[]{"", "1÷0", "(1+2", "2+", "√(-4)", "1..2", "abc", "3)"}) {
      try { Calculator.eval(bad); fail("no error for " + bad); }
      catch (IllegalArgumentException expected) { /* ok */ }
    }
  }

  @Test public void unitConversions() {
    Units.Category length = Units.ALL[0], temp = Units.ALL[2], data = Units.ALL[6];
    assertEquals(2.54, Units.convert(length, 4, 1, 1), 1e-9); // inch -> cm
    assertEquals(1, Units.convert(length, 3, 7, 1.609344), 1e-9); // km -> mile
    assertEquals(212, Units.convert(temp, 0, 1, 100), 1e-9);
    assertEquals(-40, Units.convert(temp, 1, 0, -40), 1e-9);
    assertEquals(0, Units.convert(temp, 2, 0, 273.15), 1e-9);
    assertEquals(1024, Units.convert(data, 3, 2, 1), 1e-9); // GB -> MB
    for (Units.Category c : Units.ALL)
      for (int i = 0; i < c.units.length; i++)
        assertEquals(c.name + " " + i, 42, Units.convert(c, i, i, 42), 1e-9);
  }
}
