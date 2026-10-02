package dev.jacob.feeds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;

public class FeedsTest {
  static final String RSS = "<?xml version=\"1.0\"?><rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">"
      + "<channel><title>Example Blog</title>"
      + "<item><title>First &amp; best</title><link>https://ex.com/1</link><guid>id-1</guid>"
      + "<pubDate>Thu, 01 Oct 2026 09:30:00 +0000</pubDate><description>Short</description>"
      + "<content:encoded><![CDATA[<p>Full <b>text</b> here.</p><p>Second para.</p>]]></content:encoded></item>"
      + "<item><title>Second</title><link>https://ex.com/2</link><description>&lt;p&gt;Escaped html&lt;/p&gt;</description></item>"
      + "</channel></rss>";

  static final String ATOM = "<?xml version=\"1.0\" encoding=\"utf-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
      + "<title>Atom Site</title><entry><title>Entry one</title>"
      + "<link rel=\"self\" href=\"https://a.com/self\"/><link rel=\"alternate\" href=\"https://a.com/e1\"/>"
      + "<id>urn:1</id><updated>2026-09-30T12:00:00Z</updated><summary>Sum</summary>"
      + "<content type=\"html\">&lt;p&gt;Body&lt;/p&gt;</content></entry></feed>";

  @Test public void parsesRss() throws IOException {
    FeedParser.Feed f = FeedParser.parse(FeedParser.bytes(RSS));
    assertEquals("Example Blog", f.title);
    assertEquals(2, f.items.size());
    FeedParser.Item a = f.items.get(0);
    assertEquals("First & best", a.title);
    assertEquals("id-1", a.id);
    assertEquals("Full text here.\n\nSecond para.", Readable.fromFragment(a.html));
    assertEquals(1790847000000L, a.date);
    assertEquals("https://ex.com/2", f.items.get(1).id); // falls back to link
    assertEquals("Escaped html", Readable.fromFragment(f.items.get(1).html));
  }

  @Test public void parsesAtom() throws IOException {
    FeedParser.Feed f = FeedParser.parse(FeedParser.bytes(ATOM));
    assertEquals("Atom Site", f.title);
    FeedParser.Item e = f.items.get(0);
    assertEquals("https://a.com/e1", e.link);
    assertEquals("Body", Readable.fromFragment(e.html));
    assertTrue(e.date > 0);
  }

  @Test public void rejectsNonFeedsAndEntityTricks() {
    for (String bad : new String[]{"<html><body>hi</body></html>", "not xml at all",
        "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><rss><channel><title>&e;</title></channel></rss>"}) {
      try { FeedParser.parse(FeedParser.bytes(bad)); fail("accepted: " + bad); } catch (IOException expected) { /* ok */ }
    }
  }

  @Test public void dates() {
    assertTrue(FeedParser.parseDate("Wed, 30 Sep 2026 18:05:00 GMT") > 0);
    assertTrue(FeedParser.parseDate("2026-09-30T18:05:00+02:00") > 0);
    assertTrue(FeedParser.parseDate("2026-09-30T18:05:00.123Z") > 0);
    assertEquals(0, FeedParser.parseDate("someday"));
  }

  @Test public void discoversFeedLinks() {
    String page = "<head><link rel=\"stylesheet\" href=\"a.css\"><link rel=\"alternate\" type=\"application/rss+xml\" "
        + "title=\"RSS\" href=\"/feed.xml\"></head>";
    assertEquals("https://site.org/feed.xml", FeedParser.discover(page, "https://site.org/blog/post"));
    assertNull(FeedParser.discover("<html></html>", "https://x.org"));
  }

  @Test public void extractsTheArticleFromAPage() {
    String page = "<html><head><style>p{}</style><script>var p='<p>no</p>';</script></head><body>"
        + "<nav><p>Home</p><p>About</p></nav><div class=\"side\"><p>Ad</p></div>"
        + "<article><h1>The Title</h1><p>This is the first paragraph of the story, long enough to count as real "
        + "content for the extractor.</p><p>Caf&eacute; &#8212; second&nbsp;para &#x2014; ok.</p><ul><li>one</li></ul></article>"
        + "<footer><p>Copyright</p></footer></body></html>";
    String t = Readable.fromPage(page);
    assertTrue(t, t.startsWith("## The Title"));
    assertTrue(t.contains("first paragraph"));
    assertTrue(t.contains("— second para — ok."));
    assertTrue(t.contains("• one"));
    assertFalse(t.contains("Home"));
    assertFalse(t.contains("Copyright"));
    assertFalse(t.contains("var p"));
    assertTrue(t.contains("Caf\u00E9"));
  }
}
