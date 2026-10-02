package dev.jacob.speak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SentencesTest {
  static List<String> texts(String s, int max) {
    List<String> out = new ArrayList<>();
    for (Sentences.Chunk c : Sentences.split(s, max)) out.add(s.substring(c.start, c.end));
    return out;
  }

  @Test public void splitsSentences() {
    assertEquals(List.of("Hello there.", "How are you?", "Fine!"), texts("Hello there. How are you?  Fine!", 500));
  }

  @Test public void keepsAbbreviationsDecimalsAndInitials() {
    assertEquals(List.of("Dr. Smith paid 3.50 dollars.", "Then J. R. R. Tolkien wrote, e.g. this."),
        texts("Dr. Smith paid 3.50 dollars. Then J. R. R. Tolkien wrote, e.g. this.", 500));
  }

  @Test public void quotesStayWithTheirSentence() {
    assertEquals(List.of("He said “Stop.”", "She did."), texts("He said “Stop.” She did.", 500));
  }

  @Test public void paragraphsAndLongSentences() {
    List<Sentences.Chunk> c = Sentences.split("First para\n\nSecond para here.", 500);
    assertEquals(2, c.size());
    assertTrue(c.get(0).paragraphStart);
    assertTrue(c.get(1).paragraphStart);
    String longOne = "one two three four five, six seven eight nine ten eleven twelve thirteen.";
    for (String part : texts(longOne, 30)) assertTrue(part, part.length() <= 30);
    assertEquals(longOne.replace(" ", ""), String.join("", texts(longOne, 30)).replace(" ", ""));
  }

  @Test public void pagesPackWholeChunks() {
    List<Sentences.Chunk> c = Sentences.split("Aaaa aaaa. Bbbb bbbb. Cccc cccc. Dddd dddd.", 500);
    List<Integer> p = Sentences.pages(c, 25);
    assertEquals(0, (int) p.get(0));
    assertTrue(p.size() >= 2);
    assertEquals(p.size() - 1, Sentences.pageOf(p, c.size() - 1));
  }

  @Test public void htmlToText() {
    String t = Sentences.stripHtml("<h1>Title</h1><p>One &amp; two.</p><script>bad()</script><p>Three<br>four</p>");
    assertEquals("Title\n\nOne & two.\n\nThree\nfour", t);
    assertFalse(t.contains("bad"));
  }
}
