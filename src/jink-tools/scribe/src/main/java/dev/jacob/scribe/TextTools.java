package dev.jacob.scribe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Transcript formatting, chunking for the language model, and the prompts. Plain Java so it can be unit tested. */
final class TextTools {
  private TextTools() {}

  static final class Segment {
    final long start, end;
    final String text;
    Segment(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
  }

  static String clock(long ms) {
    long s = ms / 1000;
    return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
  }

  /**
   * Joins Whisper's segments into readable paragraphs: a new paragraph after a pause of more than
   * two seconds, or once a paragraph gets long. Optionally starts each paragraph with its time.
   */
  static String paragraphs(List<Segment> segs, boolean times) {
    StringBuilder out = new StringBuilder();
    StringBuilder para = new StringBuilder();
    long paraStart = 0, lastEnd = -1;
    for (Segment s : segs) {
      String t = s.text.trim();
      if (t.isEmpty() || t.equals("[BLANK_AUDIO]")) continue;
      boolean breakHere = para.length() > 0 && (s.start - lastEnd > 2000 || para.length() > 600);
      if (breakHere) {
        flush(out, para, paraStart, times);
        para.setLength(0);
      }
      if (para.length() == 0) paraStart = s.start;
      else para.append(' ');
      para.append(t);
      lastEnd = s.end;
    }
    if (para.length() > 0) flush(out, para, paraStart, times);
    return out.toString().trim();
  }

  private static void flush(StringBuilder out, StringBuilder para, long start, boolean times) {
    if (out.length() > 0) out.append("\n\n");
    if (times) out.append('[').append(clock(start)).append("] ");
    out.append(para);
  }

  /**
   * Splits text into pieces of at most {@code maxChars}, breaking between paragraphs, then
   * sentences, then words, so a long transcript can be summarised piece by piece.
   */
  static List<String> chunks(String text, int maxChars) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (String sentence : sentences(text)) {
      if (cur.length() > 0 && cur.length() + sentence.length() + 1 > maxChars) {
        out.add(cur.toString().trim());
        cur.setLength(0);
      }
      String s = sentence;
      while (s.length() > maxChars) { // one enormous "sentence": cut at a space
        int cut = s.lastIndexOf(' ', maxChars);
        if (cut <= 0) cut = maxChars;
        out.add(s.substring(0, cut).trim());
        s = s.substring(cut).trim();
      }
      if (cur.length() > 0) cur.append(' ');
      cur.append(s);
    }
    if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
    return out;
  }

  private static List<String> sentences(String text) {
    List<String> out = new ArrayList<>();
    for (String para : text.split("\n\\s*\n")) {
      String p = para.replaceAll("\\s+", " ").trim();
      if (p.isEmpty()) continue;
      int start = 0;
      for (int i = 0; i < p.length(); i++) {
        char c = p.charAt(i);
        if ((c == '.' || c == '!' || c == '?') && (i + 1 == p.length() || p.charAt(i + 1) == ' ')) {
          out.add(p.substring(start, i + 1).trim());
          start = i + 1;
        }
      }
      if (start < p.length()) out.add(p.substring(start).trim());
    }
    return out;
  }

  /**
   * For questions about long transcripts: the chunks that share the most words with the question,
   * kept in their original order, up to {@code maxChars} in total.
   */
  static String relevant(List<String> chunks, String question, int maxChars) {
    java.util.Set<String> q = words(question);
    Integer[] order = new Integer[chunks.size()];
    double[] score = new double[chunks.size()];
    for (int i = 0; i < chunks.size(); i++) {
      order[i] = i;
      java.util.Set<String> w = words(chunks.get(i));
      int hit = 0;
      for (String x : q) if (w.contains(x)) hit++;
      score[i] = hit;
    }
    java.util.Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]) != 0 ? Double.compare(score[b], score[a]) : a - b);
    java.util.List<Integer> keep = new ArrayList<>();
    int used = 0;
    for (int i : order) {
      if (used + chunks.get(i).length() > maxChars) continue;
      keep.add(i);
      used += chunks.get(i).length();
    }
    java.util.Collections.sort(keep);
    StringBuilder b = new StringBuilder();
    for (int i : keep) b.append(b.length() == 0 ? "" : "\n\n[\u2026]\n\n").append(chunks.get(i));
    return b.toString();
  }

  private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(("the a an and or of to in on "
      + "for is are was were be it this that what who when where why how did do does with about as at by from i you we they "
      + "he she not no yes can could would should will just so").split(" ")));

  private static java.util.Set<String> words(String s) {
    java.util.Set<String> out = new java.util.HashSet<>();
    for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2 && !STOP.contains(w)) out.add(w);
    return out;
  }

  // ---- prompts ----

  static final String SYSTEM = "You are a careful assistant that works with transcripts of recordings. "
      + "Only use information from the transcript. If something isn't in it, say so. Be concise.";

  static String summaryPrompt(String transcript) {
    return "Summarise this transcript. Start with a one-sentence overview, then list the key points as short bullets "
        + "(use \"- \"). Finish with any decisions or action items, if there are some.\n\nTranscript:\n" + transcript;
  }

  /** For long recordings: notes on one part, merged later. */
  static String partPrompt(String part, int index, int total) {
    return "This is part " + index + " of " + total + " of a transcript. Write brief bullet notes (\"- \") of the main "
        + "points in this part only.\n\nTranscript part:\n" + part;
  }

  static String mergePrompt(String notes) {
    return "These are notes from consecutive parts of one recording. Combine them into a single summary: a one-sentence "
        + "overview, then the key points as short bullets (\"- \"), then any decisions or action items.\n\nNotes:\n" + notes;
  }

  static String questionPrompt(String transcript, String question) {
    return "Transcript:\n" + transcript + "\n\nQuestion: " + question
        + "\nAnswer using only the transcript. Quote short phrases from it where helpful.";
  }

  /** Cleans up model output: trims, and drops a repeated "Summary:" heading. */
  static String tidy(String s) {
    String t = s.trim();
    if (t.toLowerCase(Locale.ROOT).startsWith("summary:")) t = t.substring(8).trim();
    return t;
  }
}
