package dev.jacob.feeds;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Reads RSS 2.0, RSS 1.0 (RDF) and Atom feeds. Plain Java (javax.xml) so it can be unit tested. */
final class FeedParser {
  private FeedParser() {}

  static final class Item {
    String id, title, link, html;
    long date;
  }

  static final class Feed {
    String title = "";
    final List<Item> items = new ArrayList<>();
  }

  static Feed parse(byte[] xml) throws IOException {
    Document doc;
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      // Feeds are untrusted input: no external entities or DTDs.
      try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (ParserConfigurationException ignored) { /* not supported */ }
      f.setExpandEntityReferences(false);
      DocumentBuilder b = f.newDocumentBuilder();
      doc = b.parse(new ByteArrayInputStream(xml));
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Not a feed: " + e.getMessage());
    }
    Element root = doc.getDocumentElement();
    Feed feed = new Feed();
    String rootName = root.getLocalName() == null ? root.getNodeName() : root.getLocalName();
    if (rootName.equals("feed")) {
      feed.title = text(child(root, "title"));
      for (Element e : children(root, "entry")) {
        Item it = new Item();
        it.title = text(child(e, "title"));
        it.link = atomLink(e);
        it.id = first(text(child(e, "id")), it.link, it.title);
        String content = text(child(e, "content"));
        it.html = content.isEmpty() ? text(child(e, "summary")) : content;
        it.date = parseDate(first(text(child(e, "published")), text(child(e, "updated"))));
        feed.items.add(it);
      }
    } else if (rootName.equals("rss") || rootName.equals("RDF")) {
      Element channel = child(root, "channel");
      if (channel != null) feed.title = text(child(channel, "title"));
      List<Element> items = channel != null ? children(channel, "item") : new ArrayList<>();
      if (items.isEmpty()) items = children(root, "item"); // RSS 1.0 puts items beside the channel
      for (Element e : items) {
        Item it = new Item();
        it.title = text(child(e, "title"));
        it.link = text(child(e, "link"));
        it.id = first(text(child(e, "guid")), it.link, it.title);
        String encoded = text(child(e, "encoded")); // content:encoded
        it.html = encoded.isEmpty() ? text(child(e, "description")) : encoded;
        it.date = parseDate(first(text(child(e, "pubDate")), text(child(e, "date"))));
        feed.items.add(it);
      }
    } else {
      throw new IOException("Not an RSS or Atom feed");
    }
    return feed;
  }

  /** Finds a feed link in a web page ({@code <link rel="alternate" type="application/rss+xml">}). */
  static String discover(String html, String pageUrl) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<link[^>]+>").matcher(html);
    while (m.find()) {
      String tag = m.group();
      if (!tag.matches("(?is).*rel=[\"']?alternate.*")) continue;
      if (!tag.matches("(?is).*type=[\"']?application/(rss|atom)\\+xml.*")) continue;
      java.util.regex.Matcher href = java.util.regex.Pattern.compile("(?is)href=[\"']([^\"']+)").matcher(tag);
      if (href.find()) return resolve(pageUrl, href.group(1).replace("&amp;", "&"));
    }
    return null;
  }

  static String resolve(String base, String href) {
    try { return new java.net.URI(base).resolve(href.trim()).toString(); }
    catch (java.net.URISyntaxException | IllegalArgumentException e) { return href; }
  }

  private static String atomLink(Element entry) {
    String any = "";
    for (Element l : children(entry, "link")) {
      String rel = l.getAttribute("rel");
      if (rel.isEmpty() || rel.equals("alternate")) return l.getAttribute("href");
      if (any.isEmpty()) any = l.getAttribute("href");
    }
    return any;
  }

  private static final String[] DATE_FORMATS = {
      "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z",
      "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd"};

  /** Milliseconds since 1970, or 0 if the date can't be read. */
  static long parseDate(String s) {
    String t = s.trim();
    if (t.isEmpty()) return 0;
    for (String f : DATE_FORMATS) {
      SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
      if (f.endsWith("'Z'")) df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
      df.setLenient(false);
      try { return df.parse(t).getTime(); } catch (ParseException ignored) { /* try the next format */ }
    }
    return 0;
  }

  // ---- DOM helpers that ignore namespace prefixes ----

  private static String name(Node n) { return n.getLocalName() != null ? n.getLocalName() : n.getNodeName(); }

  private static Element child(Element parent, String local) {
    if (parent == null) return null;
    NodeList kids = parent.getChildNodes();
    for (int i = 0; i < kids.getLength(); i++) {
      Node n = kids.item(i);
      if (n instanceof Element && name(n).equals(local)) return (Element) n;
    }
    return null;
  }

  private static List<Element> children(Element parent, String local) {
    List<Element> out = new ArrayList<>();
    NodeList kids = parent.getChildNodes();
    for (int i = 0; i < kids.getLength(); i++) {
      Node n = kids.item(i);
      if (n instanceof Element && name(n).equals(local)) out.add((Element) n);
    }
    return out;
  }

  private static String text(Element e) { return e == null ? "" : e.getTextContent().trim(); }

  private static String first(String... s) {
    for (String x : s) if (x != null && !x.isEmpty()) return x;
    return "";
  }

  static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
