package dev.jacob.dice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Parses and rolls dice notation. Plain Java so it can be unit tested.
 *
 * <pre>
 *   d20, 1d20+5, 2d6 + 1d4 - 1     sums of dice and numbers
 *   d%                             same as d100
 *   4d6dl1   4d6kh3                drop lowest / keep highest (also dh, kl, and k = kh)
 *   2d6r2                          reroll dice showing 2 or less, once each
 *   3d6!                           exploding: a max roll adds another die
 *   adv, dis, adv+7                2d20 keep highest / lowest
 *   6x 4d6dl1                      repeat a whole expression, e.g. a set of ability scores
 * </pre>
 */
final class DiceRoller {
  static final int MAX_DICE = 1000, MAX_SIDES = 10000, MAX_EXPLODE = 100, MAX_REPEAT = 20;

  static final class Result {
    final String expression;
    final int[] totals;
    final String detail;
    /** "Natural 20!" / "Natural 1" for a single d20 roll, else null. */
    final String flag;

    Result(String expression, int[] totals, String detail, String flag) {
      this.expression = expression;
      this.totals = totals;
      this.detail = detail;
      this.flag = flag;
    }

    int total() { return totals[0]; }

    String totalText() {
      if (totals.length == 1) return String.valueOf(totals[0]);
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < totals.length; i++) b.append(i == 0 ? "" : ", ").append(totals[i]);
      return b.toString();
    }
  }

  private final Random rng;

  DiceRoller(Random rng) { this.rng = rng; }

  Result roll(String input) {
    String expr = input == null ? "" : input.trim().toLowerCase(Locale.US);
    if (expr.isEmpty()) throw new IllegalArgumentException("Type something to roll, like 1d20+5");
    int repeat = 1;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\s*x\\s+(.+)$").matcher(expr);
    if (m.matches()) {
      repeat = Integer.parseInt(m.group(1));
      expr = m.group(2);
      if (repeat < 1 || repeat > MAX_REPEAT) throw new IllegalArgumentException("Repeat 1 to " + MAX_REPEAT + " times");
    }
    String body = expr.replaceAll("\\s+", "").replace("adv", "2d20kh1").replace("dis", "2d20kl1");
    int[] totals = new int[repeat];
    StringBuilder detail = new StringBuilder();
    String flag = null;
    for (int r = 0; r < repeat; r++) {
      Parser p = new Parser(body);
      totals[r] = p.parse();
      if (r > 0) detail.append('\n');
      detail.append(p.detail);
      if (repeat == 1) flag = p.natural();
    }
    return new Result(input.trim(), totals, detail.toString(), flag);
  }

  /** Recursive-descent parser over one expression. Rolls as it parses. */
  private final class Parser {
    final String s;
    int pos;
    final StringBuilder detail = new StringBuilder();
    int d20Terms, onlyD20Kept = -1;

    Parser(String s) { this.s = s; }

    int parse() {
      int total = term(true);
      while (pos < s.length()) {
        char op = s.charAt(pos);
        if (op != '+' && op != '-') throw error("Unexpected '" + op + "'");
        pos++;
        detail.append(op == '+' ? " + " : " - ");
        int v = term(false);
        total += op == '+' ? v : -v;
      }
      return total;
    }

    int term(boolean first) {
      if (pos >= s.length()) throw error("Expression ends too early");
      boolean negative = false;
      if (first && s.charAt(pos) == '-') { negative = true; pos++; detail.append('-'); }
      int count = number(-1);
      if (pos < s.length() && s.charAt(pos) == 'd') {
        pos++;
        return (negative ? -1 : 1) * dice(count == -1 ? 1 : count);
      }
      if (count == -1) throw error("Expected a number or dice like 2d6");
      detail.append(count);
      return negative ? -count : count;
    }

    int dice(int count) {
      int sides;
      if (pos < s.length() && s.charAt(pos) == '%') { pos++; sides = 100; }
      else sides = number(-1);
      if (sides < 1) throw error("Dice need a number of sides, like d6");
      if (count < 1 || count > MAX_DICE) throw error("Roll between 1 and " + MAX_DICE + " dice");
      if (sides > MAX_SIDES) throw error("Dice can have at most " + MAX_SIDES + " sides");

      int keepHigh = -1, keepLow = -1, dropHigh = 0, dropLow = 0, rerollAt = 0;
      boolean explode = false;
      String label = count + "d" + sides;
      while (pos < s.length()) {
        char ch = s.charAt(pos);
        if (s.startsWith("kh", pos)) { pos += 2; keepHigh = number(1); label += "kh" + keepHigh; }
        else if (s.startsWith("kl", pos)) { pos += 2; keepLow = number(1); label += "kl" + keepLow; }
        else if (s.startsWith("dh", pos)) { pos += 2; dropHigh = number(1); label += "dh" + dropHigh; }
        else if (s.startsWith("dl", pos)) { pos += 2; dropLow = number(1); label += "dl" + dropLow; }
        else if (ch == 'k') { pos++; keepHigh = number(1); label += "k" + keepHigh; }
        else if (ch == 'r') {
          pos++;
          rerollAt = number(1);
          if (rerollAt >= sides) throw error("Can't reroll every result on a d" + sides);
          label += "r" + rerollAt;
        } else if (ch == '!') {
          pos++;
          if (sides < 2) throw error("A d1 can't explode");
          explode = true;
          label += "!";
        } else break;
      }

      List<Integer> rolls = new ArrayList<>();
      StringBuilder shown = new StringBuilder();
      for (int i = 0; i < count; i++) {
        int v = die(sides);
        if (rerollAt > 0 && v <= rerollAt) {
          shown.append(shown.length() > 0 ? ", " : "").append('~').append(v).append('~');
          v = die(sides);
        }
        rolls.add(v);
        shown.append(shown.length() > 0 ? ", " : "").append(v);
        int chain = 0;
        while (explode && v == sides && chain++ < MAX_EXPLODE) {
          v = die(sides);
          rolls.add(v);
          shown.append("!, ").append(v);
        }
      }

      int n = rolls.size();
      boolean[] kept = new boolean[n];
      Arrays.fill(kept, true);
      Integer[] order = new Integer[n];
      for (int i = 0; i < n; i++) order[i] = i;
      // Sort indices by value so drop/keep can mark which dice are left out.
      Arrays.sort(order, (a, b) -> Integer.compare(rolls.get(a), rolls.get(b)));
      int drop = 0, dropTop = 0;
      if (keepHigh >= 0) drop = Math.max(0, n - keepHigh);
      if (keepLow >= 0) dropTop = Math.max(0, n - keepLow);
      drop = Math.max(drop, dropLow);
      dropTop = Math.max(dropTop, dropHigh);
      for (int i = 0; i < Math.min(drop, n); i++) kept[order[i]] = false;
      for (int i = 0; i < Math.min(dropTop, n); i++) kept[order[n - 1 - i]] = false;

      int sum = 0;
      for (int i = 0; i < n; i++) if (kept[i]) sum += rolls.get(i);
      if (sides == 20) {
        int keptCount = 0, last = -1;
        for (int i = 0; i < n; i++) if (kept[i]) { keptCount++; last = rolls.get(i); }
        onlyD20Kept = keptCount == 1 && d20Terms == 0 ? last : -2;
        d20Terms++;
      }

      detail.append(label).append(" [");
      if (drop + dropTop == 0) detail.append(shown);
      else {
        for (int i = 0; i < n; i++) {
          if (i > 0) detail.append(", ");
          detail.append(kept[i] ? String.valueOf(rolls.get(i)) : "(" + rolls.get(i) + ")");
        }
      }
      detail.append("]");
      return sum;
    }

    /** "Natural 20!" or "Natural 1" when the roll was a single kept d20. */
    String natural() {
      if (onlyD20Kept == 20) return "Natural 20!";
      if (onlyD20Kept == 1) return "Natural 1";
      return null;
    }

    int number(int fallback) {
      int start = pos;
      while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
      if (start == pos) return fallback;
      if (pos - start > 6) throw error("Number too large");
      return Integer.parseInt(s.substring(start, pos));
    }

    IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg); }
  }

  private int die(int sides) { return 1 + rng.nextInt(sides); }
}
