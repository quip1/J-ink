package dev.slate.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Spanned;
import android.util.Base64;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Turns EPUB / FB2 / HTML / Markdown / TXT into styled chapters for the reflow engine. */
final class Formats {
  static final class Chapter {
    final String title; final CharSequence text;
    Chapter(String title, CharSequence text) { this.title = title; this.text = text; }
  }

  /** A book's chapters. EPUB parses each chapter only when asked; the rest are in memory. */
  interface Book {
    int size();
    /** A title from the book's own table of contents, or null. */
    String tocTitle(int i);
    CharSequence text(int i);
    void close();
  }

  static Book open(File f) throws IOException {
    if (f.getName().toLowerCase(Locale.US).endsWith(".epub")) return new Epub(f);
    List<Chapter> list = load(f);
    return new Book() {
      public int size() { return list.size(); }
      public String tocTitle(int i) { return list.get(i).title; }
      public CharSequence text(int i) { return list.get(i).text; }
      public void close() {}
    };
  }

  interface ImageLoader { byte[] load(String src) throws IOException; }

  /** Image size limits for inline pictures, in px. */
  static int maxImgW = 1200, maxImgH = 1600;

  static List<Chapter> load(File f) throws IOException {
    String n = f.getName().toLowerCase(Locale.US);
    if (n.endsWith(".fb2")) return fb2(readText(f));
    if (n.endsWith(".md") || n.endsWith(".markdown")) return markdown(readText(f));
    if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml")) {
      File dir = f.getParentFile();
      Spanned s = html(readText(f), src -> readAll(new FileInputStream(new File(dir, decode(src)))));
      return Collections.singletonList(new Chapter(Storage.stripExt(f.getName()), s));
    }
    return txt(readText(f));
  }

  // ---------------------------------------------------------------- EPUB

  static final class Epub implements Book {
    private final ZipFile z;
    private final String key;
    private final List<String> paths = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final LinkedHashMap<Integer, CharSequence> cache = new LinkedHashMap<Integer, CharSequence>(8, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Integer, CharSequence> e) { return size() > 4; }
    };

    Epub(File f) throws IOException {
      z = new ZipFile(f);
      key = f.getPath();
      String opfPath = "OEBPS/content.opf";
      String container = entry(z, "META-INF/container.xml");
      if (container != null) {
        Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(container);
        if (m.find()) opfPath = m.group(1);
      }
      String opf = entry(z, opfPath);
      if (opf == null) { z.close(); throw new IOException("Not a valid EPUB (no package file)"); }
      String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
      Map<String, String> hrefs = new HashMap<>();
      List<String> spine = new ArrayList<>();
      String ncxId = null, navHref = null;
      try {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(opf));
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
          if (ev != XmlPullParser.START_TAG) continue;
          String tag = local(p.getName());
          if (tag.equals("item")) {
            String id = p.getAttributeValue(null, "id"), href = p.getAttributeValue(null, "href");
            String props = p.getAttributeValue(null, "properties");
            if (props != null && props.contains("nav")) navHref = href;
            hrefs.put(id, href);
          } else if (tag.equals("spine")) {
            ncxId = p.getAttributeValue(null, "toc");
          } else if (tag.equals("itemref")) {
            if (!"no".equals(p.getAttributeValue(null, "linear"))) spine.add(p.getAttributeValue(null, "idref"));
          }
        }
      } catch (Exception e) {
        z.close();
        throw new IOException("Couldn't read EPUB package: " + e.getMessage());
      }
      Map<String, String> toc = new HashMap<>();
      if (navHref != null) readNav(normalize(base + decode(navHref)), toc);
      if (toc.isEmpty() && ncxId != null && hrefs.get(ncxId) != null) readNcx(normalize(base + decode(hrefs.get(ncxId))), toc);
      for (String id : spine) {
        String href = hrefs.get(id);
        if (href == null) continue;
        String path = normalize(base + decode(href));
        if (z.getEntry(path) == null) continue;
        paths.add(path);
        titles.add(toc.get(path));
      }
      if (paths.isEmpty()) { z.close(); throw new IOException("EPUB has no readable chapters"); }
    }

    private void readNcx(String path, Map<String, String> toc) {
      try {
        String x = entry(z, path);
        if (x == null) return;
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        Matcher m = Pattern.compile("(?is)<navPoint.*?<text>(.*?)</text>.*?<content[^>]*src\\s*=\\s*\"([^\"]+)\"").matcher(x);
        while (m.find()) {
          String target = normalize(dir + decode(m.group(2)));
          if (!toc.containsKey(target)) toc.put(target, clean(m.group(1)));
        }
      } catch (Exception ignored) {}
    }

    private void readNav(String path, Map<String, String> toc) {
      try {
        String x = entry(z, path);
        if (x == null) return;
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        Matcher nav = Pattern.compile("(?is)<nav[^>]*toc[^>]*>(.*?)</nav>").matcher(x);
        String body = nav.find() ? nav.group(1) : x;
        Matcher m = Pattern.compile("(?is)<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>").matcher(body);
        while (m.find()) {
          String target = normalize(dir + decode(m.group(1)));
          if (!toc.containsKey(target)) toc.put(target, clean(m.group(2)));
        }
      } catch (Exception ignored) {}
    }

    private static String clean(String h) {
      String t = Html.fromHtml(h, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
      return t.isEmpty() ? null : (t.length() > 70 ? t.substring(0, 67) + "…" : t);
    }

    public int size() { return paths.size(); }
    public String tocTitle(int i) { return titles.get(i); }

    public synchronized CharSequence text(int i) {
      CharSequence c = cache.get(i);
      if (c != null) return c;
      String path = paths.get(i);
      String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
      try {
        String doc = entry(z, path);
        c = doc == null ? "" : trimEnd(html(doc, key, src -> {
          String p = normalize(dir + decode(src));
          ZipEntry e = z.getEntry(p);
          if (e == null) throw new FileNotFoundException(p);
          try (InputStream in = z.getInputStream(e)) { return readAll(in); }
        }));
      } catch (IOException e) {
        c = "";
      }
      cache.put(i, c);
      return c;
    }

    public void close() { try { z.close(); } catch (IOException ignored) {} }
  }

  private static String local(String tag) { return tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag; }

  private static boolean hasImage(Spanned s) {
    return s.getSpans(0, s.length(), android.text.style.ImageSpan.class).length > 0;
  }

  private static String entry(ZipFile z, String name) throws IOException {
    ZipEntry e = z.getEntry(name);
    if (e == null) return null;
    try (InputStream in = z.getInputStream(e)) { return decodeBytes(readAll(in)); }
  }

  static String normalize(String p) {
    Deque<String> parts = new ArrayDeque<>();
    for (String s : p.split("/")) {
      if (s.isEmpty() || s.equals(".")) continue;
      if (s.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
      else parts.addLast(s);
    }
    int hash = -1;
    String joined = String.join("/", parts);
    hash = joined.indexOf('#');
    return hash >= 0 ? joined.substring(0, hash) : joined;
  }

  static String decode(String s) {
    try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
  }

  // ---------------------------------------------------------------- HTML

  static Spanned html(String doc, ImageLoader images) { return html(doc, String.valueOf(doc.hashCode()), images); }

  static Spanned html(String doc, String cacheKey, ImageLoader images) {
    Matcher m = Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(doc);
    String body = m.find() ? m.group(1) : doc;
    body = body.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", "")
        .replaceAll("(?is)<image[^>]*?(?:xlink:)?href\\s*=\\s*\"([^\"]+)\"[^>]*/?>", "<img src=\"$1\">")
        .replaceAll("(?is)</?svg[^>]*>", "");
    Html.ImageGetter getter = src -> image(cacheKey + "|" + src, src, images);
    return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY, getter, null);
  }

  /** Reads only the image header now; pixels decode later, when the page is drawn. */
  private static Drawable image(String key, String src, ImageLoader images) {
    try {
      byte[] data = images.load(src);
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, o);
      if (o.outWidth <= 0 || o.outHeight <= 0) return empty();
      float s = Math.min(1f, Math.min(maxImgW / (float) o.outWidth, maxImgH / (float) o.outHeight));
      int w = Math.max(1, Math.round(o.outWidth * s)), h = Math.max(1, Math.round(o.outHeight * s));
      return new LazyImage(key, () -> images.load(src), o.outWidth, o.outHeight, w, h);
    } catch (Exception e) {
      return empty();
    }
  }

  private static Drawable empty() {
    Drawable d = new ColorDrawable(0);
    d.setBounds(0, 0, 1, 1);
    return d;
  }

  // ---------------------------------------------------------------- FB2

  static List<Chapter> fb2(String xml) {
    Map<String, byte[]> bins = new HashMap<>();
    Matcher bm = Pattern.compile("(?is)<binary[^>]*id\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</binary>").matcher(xml);
    while (bm.find()) {
      try { bins.put(bm.group(1), Base64.decode(bm.group(2).replaceAll("\\s", ""), Base64.DEFAULT)); }
      catch (IllegalArgumentException ignored) {}
    }
    Matcher body = Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(xml);
    String b = body.find() ? body.group(1) : xml;
    List<String> sections = topLevelSections(b);
    if (sections.isEmpty()) sections = Collections.singletonList(b);
    List<Chapter> out = new ArrayList<>();
    int n = 0;
    for (String sec : sections) {
      String h = sec
          .replaceAll("(?is)<title[^>]*>(.*?)</title>", "<h2>$1</h2>")
          .replaceAll("(?is)<subtitle[^>]*>(.*?)</subtitle>", "<h3>$1</h3>")
          .replaceAll("(?is)<emphasis[^>]*>", "<i>").replaceAll("(?is)</emphasis>", "</i>")
          .replaceAll("(?is)<strong[^>]*>", "<b>").replaceAll("(?is)</strong>", "</b>")
          .replaceAll("(?is)<empty-line\\s*/>", "<br>")
          .replaceAll("(?is)<(epigraph|cite)[^>]*>", "<blockquote>").replaceAll("(?is)</(epigraph|cite)>", "</blockquote>")
          .replaceAll("(?is)<v>(.*?)</v>", "$1<br>")
          .replaceAll("(?is)<image[^>]*href\\s*=\\s*\"#?([^\"]+)\"[^>]*/?>", "<img src=\"$1\">")
          .replaceAll("(?is)</?(section|poem|stanza|text-author|annotation)[^>]*>", "");
      Spanned s = html(h, src -> {
        byte[] d = bins.get(src.startsWith("#") ? src.substring(1) : src);
        if (d == null) throw new FileNotFoundException(src);
        return d;
      });
      CharSequence t = trimEnd(s);
      if (t.toString().trim().isEmpty()) continue;
      n++;
      out.add(new Chapter(titleOf(t, "Section " + n), t));
    }
    return out;
  }

  /** Splits FB2 body into its top-level <section> blocks. */
  private static List<String> topLevelSections(String b) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("(?i)<(/?)section\\b[^>]*>").matcher(b);
    int depth = 0, start = -1;
    while (m.find()) {
      if (m.group(1).isEmpty()) { if (depth == 0) start = m.start(); depth++; }
      else { depth--; if (depth == 0 && start >= 0) { out.add(b.substring(start, m.end())); start = -1; } }
    }
    return out;
  }

  // ---------------------------------------------------------------- Markdown

  static List<Chapter> markdown(String md) {
    List<Chapter> out = new ArrayList<>();
    StringBuilder html = new StringBuilder();
    String title = null;
    boolean inCode = false;
    StringBuilder para = new StringBuilder();
    for (String line : md.split("\\r?\\n")) {
      if (line.trim().startsWith("```")) {
        flushPara(html, para);
        html.append(inCode ? "</tt></p>" : "<p><tt>");
        inCode = !inCode;
        continue;
      }
      if (inCode) { html.append(escape(line)).append("<br>"); continue; }
      Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
      if (h.find()) {
        flushPara(html, para);
        int level = h.group(1).length();
        if (level <= 2 && html.length() > 0) {
          out.add(new Chapter(title != null ? title : "Start", trimEnd(Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_LEGACY))));
          html.setLength(0);
        }
        if (level <= 2) title = h.group(2).trim();
        html.append("<h").append(level).append('>').append(inline(h.group(2))).append("</h").append(level).append('>');
        continue;
      }
      if (line.trim().isEmpty()) { flushPara(html, para); continue; }
      Matcher li = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").matcher(line);
      if (li.find()) {
        flushPara(html, para);
        String bullet = li.group(1).matches("\\d+[.)]") ? li.group(1) : "•";
        html.append(bullet).append("&nbsp;").append(inline(li.group(2))).append("<br>");
        continue;
      }
      if (line.startsWith(">")) { flushPara(html, para); html.append("<blockquote>").append(inline(line.substring(1).trim())).append("</blockquote>"); continue; }
      if (para.length() > 0) para.append(' ');
      para.append(inline(line.trim()));
    }
    flushPara(html, para);
    if (html.length() > 0 || out.isEmpty())
      out.add(new Chapter(title != null ? title : "Start", trimEnd(Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_LEGACY))));
    return out;
  }

  private static void flushPara(StringBuilder html, StringBuilder para) {
    if (para.length() == 0) return;
    html.append("<p>").append(para).append("</p>");
    para.setLength(0);
  }

  private static String inline(String s) {
    s = escape(s);
    s = s.replaceAll("`([^`]+)`", "<tt>$1</tt>");
    s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>").replaceAll("__([^_]+)__", "<b>$1</b>");
    s = s.replaceAll("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])", "<i>$1</i>").replaceAll("(?<![_\\w])_([^_]+)_(?![_\\w])", "<i>$1</i>");
    s = s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
    return s;
  }

  private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

  // ---------------------------------------------------------------- TXT

  static List<Chapter> txt(String text) {
    text = text.replace("\r\n", "\n").replace('\r', '\n');
    List<Chapter> out = new ArrayList<>();
    Pattern heading = Pattern.compile("(?im)^\\s*(chapter|part|book|prologue|epilogue)\\b.{0,60}$");
    Matcher m = heading.matcher(text);
    List<Integer> cuts = new ArrayList<>();
    while (m.find()) if (m.start() > 0) cuts.add(m.start());
    if (cuts.size() < 2) { // no usable headings: cut into ~40k-char parts at paragraph breaks
      cuts.clear();
      int pos = 40000;
      while (pos < text.length()) {
        int br = text.indexOf("\n\n", pos);
        if (br < 0) break;
        cuts.add(br + 2);
        pos = br + 40000;
      }
    }
    int start = 0, n = 0;
    cuts.add(text.length());
    for (int c : cuts) {
      if (c <= start) continue;
      String part = text.substring(start, c).replaceAll("\\s+$", "");
      start = c;
      if (part.trim().isEmpty()) continue;
      n++;
      out.add(new Chapter(titleOf(part, "Part " + n), part));
    }
    if (out.isEmpty()) out.add(new Chapter("Text", text));
    return out;
  }

  // ---------------------------------------------------------------- helpers

  static String titleOf(CharSequence t, String fallback) {
    String s = t.toString().replace('\uFFFC', ' ').trim();
    int nl = s.indexOf('\n');
    String first = (nl >= 0 ? s.substring(0, nl) : s).trim();
    if (first.isEmpty()) return fallback;
    return first.length() > 60 ? first.substring(0, 57) + "…" : first;
  }

  static CharSequence trimEnd(CharSequence s) {
    int e = s.length();
    while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
    return s.subSequence(0, e);
  }

  static String stripExt(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

  static byte[] readAll(InputStream in) throws IOException {
    try (InputStream i = in) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      Storage.pipe(i, b);
      return b.toByteArray();
    }
  }

  static String readText(File f) throws IOException { return decodeBytes(readAll(new FileInputStream(f))); }

  /** UTF-8 (with BOM handling), falling back to Windows-1252 for old text files. */
  static String decodeBytes(byte[] b) {
    int off = (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) ? 3 : 0;
    String s = new String(b, off, b.length - off, StandardCharsets.UTF_8);
    int bad = 0;
    for (int i = 0; i < Math.min(s.length(), 20000); i++) if (s.charAt(i) == '\uFFFD') bad++;
    if (bad > 5) {
      try { return new String(b, Charset.forName("windows-1252")); } catch (Exception ignored) {}
    }
    return s;
  }
}
