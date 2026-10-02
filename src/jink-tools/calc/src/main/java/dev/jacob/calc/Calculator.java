package dev.jacob.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Evaluates calculator input like "12.5 × (3 + 4)^2 − 10%". Plain Java so it can be unit tested.
 *
 * <p>Precedence, lowest first: + −, then × ÷, then unary minus, then ^ (right-associative), then
 * postfix %, then numbers, parentheses, √ and the constants π and e.
 */
final class Calculator {
  private final String s;
  private int pos;

  private Calculator(String s) { this.s = s; }

  static double eval(String input) {
    String norm = input.replace('×', '*').replace('÷', '/').replace('−', '-').replace(" ", "")
        .replace("sqrt", "√").replace("pi", "π");
    if (norm.isEmpty()) throw new IllegalArgumentException("Nothing to calculate");
    Calculator c = new Calculator(norm);
    double v = c.sum();
    if (c.pos < c.s.length()) throw new IllegalArgumentException("Unexpected “" + c.s.charAt(c.pos) + "”");
    if (Double.isNaN(v)) throw new IllegalArgumentException("Not a number");
    if (Double.isInfinite(v)) throw new IllegalArgumentException("Too big");
    return v;
  }

  private double sum() {
    double v = product();
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c == '+') { pos++; v += product(); }
      else if (c == '-') { pos++; v -= product(); }
      else break;
    }
    return v;
  }

  private double product() {
    double v = unary();
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c == '*') { pos++; v *= unary(); }
      else if (c == '/') {
        pos++;
        double d = unary();
        if (d == 0) throw new IllegalArgumentException("Can't divide by zero");
        v /= d;
      } else break;
    }
    return v;
  }

  private double unary() {
    if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -unary(); }
    if (pos < s.length() && s.charAt(pos) == '+') { pos++; return unary(); }
    return power();
  }

  private double power() {
    double base = postfix();
    if (pos < s.length() && s.charAt(pos) == '^') {
      pos++;
      return Math.pow(base, unary()); // right-associative: 2^3^2 = 2^9
    }
    return base;
  }

  private double postfix() {
    double v = atom();
    while (pos < s.length() && s.charAt(pos) == '%') { pos++; v /= 100; }
    return v;
  }

  private double atom() {
    if (pos >= s.length()) throw new IllegalArgumentException("Expression ends too early");
    char c = s.charAt(pos);
    if (c == '(') {
      pos++;
      double v = sum();
      if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("Missing )");
      pos++;
      return v;
    }
    if (c == '√') {
      pos++;
      double v = postfix();
      if (v < 0) throw new IllegalArgumentException("No square root of a negative number");
      return Math.sqrt(v);
    }
    if (c == 'π') { pos++; return Math.PI; }
    if (c == 'e') { pos++; return Math.E; }
    int start = pos;
    while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
    if (start == pos) throw new IllegalArgumentException("Unexpected “" + c + "”");
    try { return Double.parseDouble(s.substring(start, pos)); }
    catch (NumberFormatException e) { throw new IllegalArgumentException("Bad number " + s.substring(start, pos)); }
  }

  /** Up to 12 significant digits, no trailing zeros, no "1.0E10" style unless the number is huge or tiny. */
  static String format(double v) {
    if (v == 0) return "0";
    double a = Math.abs(v);
    if (a >= 1e15 || a < 1e-9) {
      String e = new BigDecimal(v).round(new MathContext(10)).stripTrailingZeros().toString();
      return e.replace("E+", "e").replace("E", "e");
    }
    String plain = new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros().toPlainString();
    return plain.equals("-0") ? "0" : plain;
  }
}
