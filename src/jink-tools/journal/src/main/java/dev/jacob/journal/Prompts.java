package dev.jacob.journal;

import java.time.LocalDate;
import java.util.Locale;

/** Daily writing prompts and small text helpers. Plain Java so it can be unit tested. */
final class Prompts {
  private Prompts() {}

  static final String[] ALL = {
      "What took up most of your attention today?",
      "Describe a small moment from today you'd like to remember.",
      "What's something you're looking forward to?",
      "What drained your energy today, and what gave it back?",
      "Write about a conversation that stuck with you.",
      "What would you tell yourself from a year ago?",
      "What are three things you noticed on a walk, or out of a window?",
      "What's a problem you're chewing on? Write it out as if explaining it to a friend.",
      "What did you learn today, however small?",
      "Who made your day better, and how?",
      "What are you avoiding? What's the smallest next step?",
      "Describe your perfect ordinary day.",
      "What's a book, song or game that's been on your mind? Why?",
      "What did you do today just for you?",
      "Write about a place that feels like home.",
      "What surprised you this week?",
      "What would you do with a free afternoon tomorrow?",
      "List what you're grateful for right now, even the tiny things.",
      "What's a habit you'd like to build? What gets in the way?",
      "What did you change your mind about recently?",
      "Describe the weather, the light and the sounds around you right now.",
      "What's something you made or fixed lately?",
      "Write a letter to someone you haven't spoken to in a while (you don't have to send it).",
      "What does a good week look like for you?",
      "What are you proud of that nobody else knows about?",
      "What's worrying you? What part of it is in your control?",
      "Write about a meal you remember well.",
      "What would you like more of in your life? Less of?",
      "What's the kindest thing someone has done for you lately?",
      "If today were a chapter title, what would it be?",
      "What's a skill you'd learn if time and money didn't matter?",
      "Describe someone you admire in three sentences.",
      "What did you say yes to that you wish you'd said no to, or the other way round?",
      "What's a memory that makes you laugh?",
      "What does rest look like for you?",
      "Where do you want to be in five years? In five days?",
      "What's on your desk or table right now, and what story does it tell?",
      "What's one thing you'd like to finish this month?",
      "Write about a time you were brave.",
      "What made today different from yesterday?",
      "What question would you like someone to ask you?",
      "What's something you've been putting off saying?",
      "Describe how your body feels right now, head to toe.",
      "What's a rule you live by? Where did it come from?",
      "What did you enjoy as a child that you could still enjoy now?",
      "Write about something you saw today that was beautiful or strange.",
      "What did you spend money on this week that was worth it?",
      "What's the best advice you've ever been given?",
      "If you could redo one moment from today, which and how?",
      "Write freely for five minutes. Start with: \"Right now I...\"",
  };

  /** The same prompt all day, a different one tomorrow. */
  static int indexFor(LocalDate day) { return (int) Math.floorMod(day.toEpochDay() * 7 + 3, (long) ALL.length); }

  static String forDate(LocalDate day) { return ALL[indexFor(day)]; }

  /**
   * A one-line snippet of {@code text} around the first match of {@code query} (case-insensitive),
   * or null if it doesn't appear.
   */
  static String snippet(String text, String query) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return null;
    int i = text.toLowerCase(Locale.ROOT).indexOf(q);
    if (i < 0) return null;
    int from = Math.max(0, i - 30), to = Math.min(text.length(), i + q.length() + 50);
    if (from > 0) {
      // Start on a word boundary rather than mid-word.
      int j = from;
      while (j < i && !Character.isWhitespace(text.charAt(j))) j++;
      if (j < i) from = j + 1;
    }
    String s = text.substring(from, to).replace('\n', ' ').trim();
    return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
  }

  static final String[] MOODS = {"Rough", "Meh", "Okay", "Good", "Great"};
}
