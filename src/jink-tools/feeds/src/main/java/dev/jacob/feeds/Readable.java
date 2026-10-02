package dev.jacob.feeds;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns article HTML into clean plain text for e-ink: headings, paragraphs and list items only.
 * For whole web pages it first picks the part that looks like the article. Plain Java so it can
 * be unit tested.
 */
final class Readable {
  private Readable() {}

  private static final Pattern BLOCKS = Pattern.compile("(?is)<(h[1-6]|p|li|blockquote|pre)\\b[^>]*>(.*?)</\\1>");

  /** Text from a feed item's HTML (which is already just the article). */
  static String fromFragment(String html) {
    String body = blocks(html);
    if (body.trim().isEmpty()) body = flatten(html); // no <p> tags: plain text with line breaks
    return body.trim();
  }

  /** Text from a full web page: the {@code <article>} if there is one, else the densest block of paragraphs. */
  static String fromPage(String html) {
    String h = strip(html, "script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "figure");
    String best = null;
    Matcher article = Pattern.compile("(?is)<article\\b[^>]*>(.*?)</article>").matcher(h);
    while (article.find()) {
      String b = blocks(article.group(1));
      if (best == null || b.length() > best.length()) best = b;
    }
    if (best == null || best.length() < 200) {
      // Score every container by how much paragraph text sits directly in it.
      Matcher main = Pattern.compile("(?is)<(main|div|section)\\b[^>]*>").matcher(h);
      while (main.find()) {
        int start = main.end();
        int end = Math.min(h.length(), start + 200_000);
        String chunk = h.substring(start, end);
        String b = blocks(chunk);
        if (best == null || b.length() > best.length() * 1.2) best = b;
        if (best.length() > 20_000) break;
      }
    }
    return best == null ? "" : best.trim();
  }

  private static String blocks(String html) {
    StringBuilder out = new StringBuilder();
    Matcher m = BLOCKS.matcher(html);
    while (m.find()) {
      String tag = m.group(1).toLowerCase();
      String text = flatten(m.group(2)).replace('\n', ' ').trim();
      if (text.isEmpty()) continue;
      if (tag.startsWith("h")) out.append("\n## ").append(text).append("\n\n");
      else if (tag.equals("li")) out.append("\u2022 ").append(text).append('\n');
      else out.append(text).append("\n\n");
    }
    return out.toString().replaceAll("\n{3,}", "\n\n");
  }

  private static String strip(String html, String... tags) {
    String h = html.replaceAll("(?s)<!--.*?-->", "");
    for (String t : tags) h = h.replaceAll("(?is)<" + t + "\\b[^>]*>.*?</" + t + ">", " ");
    return h;
  }

  /** Removes tags and decodes the common entities. */
  static String flatten(String html) {
    String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?s)<[^>]+>", "");
    s = decode(s);
    return s.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
  }

  static String decode(String s) {
    Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      String e = m.group(1), r;
      if (e.startsWith("#x") || e.startsWith("#X")) r = codePoint(e.substring(2), 16);
      else if (e.startsWith("#")) r = codePoint(e.substring(1), 10);
      else r = named(e);
      m.appendReplacement(b, Matcher.quoteReplacement(r == null ? m.group() : r));
    }
    m.appendTail(b);
    return b.toString();
  }

  private static String codePoint(String digits, int radix) {
    try { return new String(Character.toChars(Integer.parseInt(digits, radix))); }
    catch (IllegalArgumentException e) { return null; }
  }

  /** &eacute; &ntilde; &uuml; and friends: the letter plus a combining accent, normalised to one character. */
  private static String accented(String e) {
    if (e.length() < 4) return null;
    String[][] marks = {{"acute", "\u0301"}, {"grave", "\u0300"}, {"circ", "\u0302"}, {"tilde", "\u0303"},
        {"uml", "\u0308"}, {"cedil", "\u0327"}, {"ring", "\u030A"}};
    for (String[] m : marks) {
      if (e.length() == m[0].length() + 1 && e.endsWith(m[0])) {
        return java.text.Normalizer.normalize(e.charAt(0) + m[1], java.text.Normalizer.Form.NFC);
      }
    }
    return null;
  }

  private static String named(String e) {
    switch (e) {
      case "amp": return "&";
      case "lt": return "<";
      case "gt": return ">";
      case "quot": return "\"";
      case "apos": return "'";
      case "nbsp": return " ";
      case "mdash": return "\u2014";
      case "ndash": return "\u2013";
      case "hellip": return "\u2026";
      case "lsquo": return "\u2018";
      case "rsquo": return "\u2019";
      case "ldquo": return "\u201C";
      case "rdquo": return "\u201D";
      case "copy": return "\u00A9";
      default: return accented(e);
    }
  }
}
