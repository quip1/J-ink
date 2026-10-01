package dev.jacob.typewriter;

/** Word counting and titles for drafts. Plain Java so it can be unit tested. */
final class Words {
  private Words() {}

  /** Counts runs of letters/digits, treating apostrophes and hyphens inside a word as part of it. */
  static int count(CharSequence s) {
    int n = 0;
    boolean in = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean wordChar = Character.isLetterOrDigit(c)
          || ((c == '\'' || c == '’' || c == '-') && in && i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i + 1)));
      if (wordChar && !in) n++;
      in = wordChar;
    }
    return n;
  }

  /** The first non-blank line, without Markdown heading marks, cut to a sensible length. */
  static String title(String text) {
    for (String line : text.split("\n")) {
      String t = line.replaceFirst("^#+\\s*", "").trim();
      if (!t.isEmpty()) return t.length() > 60 ? t.substring(0, 57) + "…" : t;
    }
    return "Untitled";
  }

  /** Reading time at 230 words a minute, rounded up. */
  static int readingMinutes(int words) { return words == 0 ? 0 : (words + 229) / 230; }

  static String thousands(int n) { return String.format(java.util.Locale.US, "%,d", n); }
}
