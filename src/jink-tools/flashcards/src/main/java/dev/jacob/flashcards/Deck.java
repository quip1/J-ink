package dev.jacob.flashcards;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A deck of cards with spaced-repetition scheduling (a simplified SM-2, like Anki's): cards you
 * know come back after longer and longer gaps; cards you miss come back soon. Plain Java so it
 * can be unit tested.
 */
final class Deck {
  static final int AGAIN = 0, HARD = 1, GOOD = 2, EASY = 3;

  static final class Card {
    String id = UUID.randomUUID().toString();
    String front, back;
    double ease = 2.5;
    int interval; // days
    int reps, lapses;
    long due; // epoch day; 0 = new, never studied

    Card(String front, String back) { this.front = front; this.back = back; }

    boolean isNew() { return reps == 0 && due == 0; }
  }

  String id = UUID.randomUUID().toString();
  String name;
  int newPerDay = 20;
  final List<Card> cards = new ArrayList<>();

  Deck(String name) { this.name = name; }

  /** Days until the card would next be due for each grade, for the button labels. */
  static int[] preview(Card c) {
    int[] out = new int[4];
    for (int g = 0; g < 4; g++) {
      Card copy = new Card(c.front, c.back);
      copy.ease = c.ease;
      copy.interval = c.interval;
      copy.reps = c.reps;
      grade(copy, g, 0);
      out[g] = copy.interval;
    }
    return out;
  }

  static void grade(Card c, int g, long today) {
    switch (g) {
      case AGAIN:
        if (c.reps > 0) c.lapses++;
        c.reps = 0;
        c.interval = 0; // see it again this session
        c.ease = Math.max(1.3, c.ease - 0.2);
        break;
      case HARD:
        c.interval = c.reps == 0 ? 1 : Math.max(c.interval + 1, (int) Math.round(c.interval * 1.2));
        c.ease = Math.max(1.3, c.ease - 0.15);
        c.reps++;
        break;
      case GOOD:
        c.interval = c.reps == 0 ? 1 : c.reps == 1 ? 3 : Math.max(c.interval + 1, (int) Math.round(c.interval * c.ease));
        c.reps++;
        break;
      default:
        c.interval = c.reps == 0 ? 4 : Math.max(c.interval + 2, (int) Math.round(c.interval * c.ease * 1.3));
        c.ease += 0.15;
        c.reps++;
    }
    c.due = today + c.interval;
    if (c.due == 0) c.due = -1; // keep "studied" distinct from "new" even on day 0 in tests
  }

  /** Cards to study today: everything due, then up to the daily number of new cards. */
  List<Card> todaysQueue(long today, int newSeenToday) {
    List<Card> due = new ArrayList<>(), fresh = new ArrayList<>();
    for (Card c : cards) {
      if (c.isNew()) { if (fresh.size() < Math.max(0, newPerDay - newSeenToday)) fresh.add(c); }
      else if (c.due <= today) due.add(c);
    }
    due.sort((a, b) -> Long.compare(a.due, b.due));
    due.addAll(fresh);
    return due;
  }

  int dueCount(long today) {
    int n = 0;
    for (Card c : cards) if (!c.isNew() && c.due <= today) n++;
    return n;
  }

  int newCount() {
    int n = 0;
    for (Card c : cards) if (c.isNew()) n++;
    return n;
  }

  /** "<1d", "3d", "2mo", "1.5y" */
  static String span(int days) {
    if (days < 1) return "<1d";
    if (days < 31) return days + "d";
    if (days < 365) return Math.round(days / 30.0) + "mo";
    return (Math.round(days / 36.5) / 10.0) + "y";
  }

  /**
   * Cards from CSV or TSV text: one card per line, front and back separated by a tab (or a comma
   * when there are no tabs). Quoted CSV fields may contain commas and doubled quotes.
   */
  static List<Card> parse(String text) {
    List<Card> out = new ArrayList<>();
    boolean tabs = text.contains("\t");
    for (String line : text.split("\r?\n")) {
      if (line.trim().isEmpty() || line.startsWith("#")) continue;
      List<String> f = tabs ? split(line, '\t', false) : split(line, ',', true);
      if (f.size() < 2 || f.get(0).trim().isEmpty()) continue;
      out.add(new Card(f.get(0).trim(), f.get(1).trim().replace("\\n", "\n")));
    }
    return out;
  }

  private static List<String> split(String line, char sep, boolean quotes) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean in = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quotes && c == '"') {
        if (in && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
        else in = !in;
      } else if (c == sep && !in) {
        out.add(cur.toString());
        cur.setLength(0);
      } else cur.append(c);
    }
    out.add(cur.toString());
    return out;
  }

  /** Tab-separated export that {@link #parse} reads back. */
  String toTsv() {
    StringBuilder b = new StringBuilder();
    for (Card c : cards) b.append(c.front.replace("\t", " ").replace("\n", " ")).append('\t')
        .append(c.back.replace("\t", " ").replace("\n", "\\n")).append('\n');
    return b.toString();
  }
}
