package dev.jacob.speak;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into speakable chunks (sentences, with long ones broken at commas or spaces) and
 * groups chunks into screen-sized pages. Plain Java so it can be unit tested.
 */
final class Sentences {
  private Sentences() {}

  static final class Chunk {
    final int start, end; // character range in the original text
    final boolean paragraphStart;
    Chunk(int start, int end, boolean paragraphStart) { this.start = start; this.end = end; this.paragraphStart = paragraphStart; }
  }

  private static final String ABBREV = " mr mrs ms dr st jr sr vs etc e.g i.e no fig prof mt ";

  /** Chunks no longer than {@code max} characters. Whitespace between chunks isn't included. */
  static List<Chunk> split(String text, int max) {
    List<Chunk> out = new ArrayList<>();
    int n = text.length(), i = 0;
    boolean newPara = true;
    while (i < n) {
      while (i < n && Character.isWhitespace(text.charAt(i))) {
        if (text.charAt(i) == '\n' && i + 1 < n && text.charAt(i + 1) == '\n') newPara = true;
        if (text.charAt(i) == '\n' && (out.isEmpty() || i > 0 && text.charAt(i - 1) == '\n')) newPara = true;
        i++;
      }
      if (i >= n) break;
      int end = sentenceEnd(text, i);
      while (end - i > max) {
        int cut = breakPoint(text, i, i + max);
        out.add(new Chunk(i, cut, newPara));
        newPara = false;
        i = cut;
        while (i < end && Character.isWhitespace(text.charAt(i))) i++;
      }
      if (end > i) out.add(new Chunk(i, trimEnd(text, i, end), newPara));
      newPara = false;
      i = end;
    }
    return out;
  }

  /** End (exclusive) of the sentence starting at {@code from}: after . ! ? (plus closing quotes), or a blank line. */
  private static int sentenceEnd(String t, int from) {
    int n = t.length();
    for (int i = from; i < n; i++) {
      char c = t.charAt(i);
      if (c == '\n' && i + 1 < n && t.charAt(i + 1) == '\n') return i;
      if (c == '\n' && i + 1 < n && (t.charAt(i + 1) == '#' || t.charAt(i + 1) == '-' || t.charAt(i + 1) == '*')) return i;
      if (c == '.' || c == '!' || c == '?' || c == '…') {
        int j = i + 1;
        while (j < n && ".!?\"'”’)]".indexOf(t.charAt(j)) >= 0) j++;
        if (j >= n) return n;
        if (!Character.isWhitespace(t.charAt(j))) continue; // "3.14", "e.g.x"
        if (c == '.' && isAbbreviation(t, i)) continue;
        return j;
      }
    }
    return n;
  }

  private static boolean isAbbreviation(String t, int dot) {
    int s = dot;
    // Look back over letters and inner dots, so "e.g." is seen as "e.g", not "g".
    while (s > 0 && (Character.isLetter(t.charAt(s - 1)) || t.charAt(s - 1) == '.')) s--;
    while (s < dot && t.charAt(s) == '.') s++;
    String word = t.substring(s, dot).toLowerCase();
    if (word.length() == 1 && Character.isUpperCase(t.charAt(s))) return true; // initials: "J. R. R."
    return !word.isEmpty() && ABBREV.contains(" " + word + " ");
  }

  /** A good place to break an over-long sentence: the last comma/semicolon, else the last space, before {@code limit}. */
  private static int breakPoint(String t, int from, int limit) {
    for (int i = limit - 1; i > from + (limit - from) / 2; i--) if (",;:—".indexOf(t.charAt(i)) >= 0) return i + 1;
    for (int i = limit - 1; i > from; i--) if (Character.isWhitespace(t.charAt(i))) return i;
    return limit;
  }

  private static int trimEnd(String t, int from, int end) {
    while (end > from && Character.isWhitespace(t.charAt(end - 1))) end--;
    return end;
  }

  /** Index of the first chunk of each page, packing whole chunks up to {@code pageChars} characters. */
  static List<Integer> pages(List<Chunk> chunks, int pageChars) {
    List<Integer> starts = new ArrayList<>();
    int used = pageChars;
    for (int i = 0; i < chunks.size(); i++) {
      int len = chunks.get(i).end - chunks.get(i).start + (chunks.get(i).paragraphStart ? 40 : 1);
      if (used + len > pageChars && (used > 0 || starts.isEmpty())) { starts.add(i); used = 0; }
      used += len;
    }
    return starts;
  }

  static int pageOf(List<Integer> pageStarts, int chunk) {
    int p = 0;
    for (int i = 0; i < pageStarts.size(); i++) if (pageStarts.get(i) <= chunk) p = i;
    return p;
  }

  /** Very small HTML-to-text: drops tags, scripts and styles; turns block ends into line breaks. */
  static String stripHtml(String html) {
    String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
        .replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote)>", "\n\n")
        .replaceAll("(?s)<[^>]+>", "");
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘")
        .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&mdash;", "—").replace("&hellip;", "…");
    return s.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
  }
}
