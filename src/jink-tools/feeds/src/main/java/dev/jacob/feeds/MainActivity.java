package dev.jacob.feeds;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An RSS/Atom reader that saves each article as clean text when it updates, so everything can be
 * read offline later, a page at a time.
 */
public class MainActivity extends InkActivity {
  private static final int KEEP = 200, FULL_FETCH_LIMIT = 30;

  /** One saved article. */
  static final class Story {
    String id, feedUrl, feedTitle, title, link, text;
    long date;
    boolean read, full;
  }

  static final class Source {
    String url, title;
  }

  private enum Screen { FEEDS, STORIES, READER }

  private SharedPreferences prefs;
  private final List<Source> sources = new ArrayList<>();
  private Screen screen = Screen.FEEDS;
  private Source openSource; // null = all unread
  private Story reading;
  private List<Story> shownStories = new ArrayList<>();
  private Pager<?> pager;
  private ScrollView readerScroll;
  private volatile boolean updating;
  private TextView status;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("feeds", MODE_PRIVATE);
    loadSources();
    Intent in = getIntent();
    String shared = Intent.ACTION_SEND.equals(in.getAction()) ? in.getStringExtra(Intent.EXTRA_TEXT) : null;
    showFeeds();
    if (shared != null && shared.contains("http")) addFeed(shared.substring(shared.indexOf("http")).split("\\s")[0]);
  }

  @Override protected boolean onPageKey(int dir) {
    if (screen == Screen.READER && readerScroll != null) {
      readerScroll.scrollBy(0, (int) (dir * readerScroll.getHeight() * 0.92f));
      return true;
    }
    return pager != null && pager.turn(dir);
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (screen == Screen.READER) showStories(openSource);
    else if (screen == Screen.STORIES) showFeeds();
    else super.onBackPressed();
  }

  // ---- feeds ----

  private void showFeeds() {
    screen = Screen.FEEDS;
    LinearLayout page = Ui.column(this);
    page.addView(header("Feeds", Ui.button(this, "+ Feed", v -> Dialogs.prompt(this, "Feed or website address", "https://",
        android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT, this::addFeed)),
        Ui.button(this, "Update", v -> updateAll()), refreshButton()), Ui.fill());
    status = Ui.muted(this, statusText());
    Ui.add(page, status, 6);
    List<Source> rows = new ArrayList<>();
    rows.add(null); // "All unread"
    rows.addAll(sources);
    Map<String, Integer> unread = unreadCounts();
    Pager<Source> p = new Pager<>(this, Pager.fit(this, 64, 200), "", s -> {
      LinearLayout row = Ui.row(this);
      int pad = Ui.dp(this, 12);
      row.setPadding(pad, pad, pad, pad);
      String title = s == null ? "All unread" : s.title.isEmpty() ? s.url : s.title;
      TextView t = Ui.title(this, title);
      t.setSingleLine(true);
      row.addView(t, Ui.weight(1));
      int n = s == null ? total(unread) : unread.getOrDefault(s.url, 0);
      row.addView(Ui.muted(this, n + " unread"));
      row.setOnClickListener(v -> showStories(s));
      if (s != null) row.setOnLongClickListener(v -> { feedMenu(s); return true; });
      return row;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 6);
    page.addView(p.view(), lp);
    p.setItems(rows);
    Ui.add(page, Ui.muted(this, sources.isEmpty() ? "Add a feed with + Feed. A website address works too; Feeds finds its feed."
        : "Hold a feed to rename or remove it."), 6);
    setPage(page);
  }

  private String statusText() {
    long at = prefs.getLong("updated", 0);
    if (updating) return "Updating…";
    return at == 0 ? "Not updated yet" : "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at));
  }

  private static int total(Map<String, Integer> m) {
    int t = 0;
    for (int v : m.values()) t += v;
    return t;
  }

  private void feedMenu(Source s) {
    Dialogs.choose(this, s.title, new String[]{"Rename…", "Mark all read", "Remove"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Rename", s.title, n -> { s.title = n.trim(); saveSources(); showFeeds(); });
      else if (i == 1) {
        List<Story> st = loadStories(s.url);
        for (Story x : st) x.read = true;
        saveStories(s.url, st);
        showFeeds();
      } else Dialogs.confirm(this, "Remove " + s.title + " and its saved articles?", "Remove", () -> {
        sources.remove(s);
        Store.file(this, storiesFile(s.url)).delete();
        saveSources();
        showFeeds();
      });
    });
  }

  private void addFeed(String url) {
    String u = url.trim();
    if (!u.startsWith("http")) u = "https://" + u;
    for (Source s : sources) if (s.url.equals(u)) { toast("Already added"); return; }
    String target = u;
    toast("Checking " + u + "…");
    new Thread(() -> {
      String err = null;
      Source added = null;
      try {
        Fetched f = fetchFeed(target);
        added = new Source();
        added.url = f.url;
        added.title = f.feed.title;
        merge(added, f.feed);
      } catch (IOException e) {
        err = e.getMessage();
      }
      Source a = added;
      String m = err;
      runOnUiThread(() -> {
        if (m != null) { toast("Couldn't add: " + m); return; }
        sources.add(a);
        saveSources();
        showFeeds();
        toast("Added " + a.title);
      });
    }).start();
  }

  // ---- updating ----

  private static final class Fetched {
    String url;
    FeedParser.Feed feed;
  }

  /** Downloads a feed; if the address is a web page, finds the page's feed link and uses that. */
  private Fetched fetchFeed(String url) throws IOException {
    byte[] body = get(url);
    Fetched f = new Fetched();
    f.url = url;
    try {
      f.feed = FeedParser.parse(body);
    } catch (IOException notAFeed) {
      String link = FeedParser.discover(new String(body, StandardCharsets.UTF_8), url);
      if (link == null) throw new IOException("no feed found at that address");
      f.url = link;
      f.feed = FeedParser.parse(get(link));
    }
    return f;
  }

  private void updateAll() {
    if (updating || sources.isEmpty()) return;
    updating = true;
    if (status != null) status.setText("Updating…");
    List<Source> list = new ArrayList<>(sources);
    new Thread(() -> {
      int fresh = 0;
      List<String> failed = new ArrayList<>();
      for (Source s : list) {
        try { fresh += merge(s, FeedParser.parse(get(s.url))); }
        catch (IOException e) { failed.add(s.title); }
      }
      int n = fresh;
      runOnUiThread(() -> {
        updating = false;
        prefs.edit().putLong("updated", System.currentTimeMillis()).apply();
        toast(n + " new article" + (n == 1 ? "" : "s") + (failed.isEmpty() ? "" : ". Couldn't reach: " + String.join(", ", failed)));
        if (screen == Screen.FEEDS) showFeeds(); else if (screen == Screen.STORIES) showStories(openSource);
      });
    }).start();
  }

  /** Adds new items to a feed's saved stories (fetching full text for short ones). Returns how many were new. */
  private int merge(Source s, FeedParser.Feed feed) {
    List<Story> stories = loadStories(s.url);
    Map<String, Story> byId = new HashMap<>();
    for (Story x : stories) byId.put(x.id, x);
    int fresh = 0, fullFetches = 0;
    boolean saveFull = prefs.getBoolean("saveFull", true);
    for (FeedParser.Item it : feed.items) {
      if (it.id.isEmpty() || byId.containsKey(it.id)) continue;
      Story x = new Story();
      x.id = it.id;
      x.feedUrl = s.url;
      x.feedTitle = s.title.isEmpty() ? feed.title : s.title;
      x.title = it.title.isEmpty() ? "(untitled)" : Readable.flatten(it.title);
      x.link = it.link;
      x.date = it.date == 0 ? System.currentTimeMillis() : it.date;
      x.text = Readable.fromFragment(it.html == null ? "" : it.html);
      // Feeds that only send a teaser: save the real article now, so it's there offline.
      if (saveFull && x.text.length() < 800 && x.link != null && x.link.startsWith("http") && fullFetches < FULL_FETCH_LIMIT) {
        fullFetches++;
        try {
          String page = Readable.fromPage(new String(get(x.link), StandardCharsets.UTF_8));
          if (page.length() > x.text.length()) { x.text = page; x.full = true; }
        } catch (IOException ignored) {
          // Keep the summary; the article can be fetched later from the reader.
        }
      }
      stories.add(x);
      byId.put(x.id, x);
      fresh++;
    }
    stories.sort((a, b) -> Long.compare(b.date, a.date));
    while (stories.size() > KEEP) stories.remove(stories.size() - 1);
    saveStories(s.url, stories);
    return fresh;
  }

  static byte[] get(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(25000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) J-ink Feeds");
    try {
      int code = c.getResponseCode();
      if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
        // Java won't follow http <-> https redirects by itself.
        return get(FeedParser.resolve(url, c.getHeaderField("Location")));
      }
      if (code != 200) throw new IOException("server said " + code);
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
          out.write(b, 0, n);
          if (out.size() > 8_000_000) throw new IOException("too large");
        }
        return out.toByteArray();
      }
    } finally {
      c.disconnect();
    }
  }

  // ---- story list ----

  private void showStories(Source s) {
    screen = Screen.STORIES;
    openSource = s;
    List<Story> list = new ArrayList<>();
    if (s != null) list.addAll(loadStories(s.url));
    else {
      for (Source src : sources) for (Story x : loadStories(src.url)) if (!x.read) list.add(x);
      list.sort((a, b) -> Long.compare(b.date, a.date));
    }
    shownStories = list;
    LinearLayout page = Ui.column(this);
    page.addView(header(s == null ? "All unread" : s.title, Ui.button(this, "←", v -> showFeeds()),
        Ui.button(this, "Update", v -> updateAll())), Ui.fill());
    DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
    Pager<Story> p = new Pager<>(this, Pager.fit(this, 84, 180), s == null ? "All caught up." : "Nothing saved yet. Tap Update.", x -> {
      LinearLayout row = Ui.column(this);
      int pad = Ui.dp(this, 10);
      row.setPadding(pad, pad, pad, pad);
      TextView t = Ui.text(this, x.title);
      t.setTypeface(x.read ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
      t.setMaxLines(2);
      row.addView(t);
      row.addView(Ui.muted(this, (s == null ? x.feedTitle + " · " : "") + df.format(new Date(x.date))
          + (x.read ? " · read" : "")));
      row.setOnClickListener(v -> read(x));
      return row;
    });
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 6);
    page.addView(p.view(), lp);
    p.setItems(list);
    setPage(page);
  }

  // ---- reader ----

  private void read(Story x) {
    screen = Screen.READER;
    reading = x;
    if (!x.read) { x.read = true; saveStory(x); }
    LinearLayout page = Ui.column(this);
    page.addView(header(x.feedTitle == null ? "" : x.feedTitle, Ui.button(this, "←", v -> showStories(openSource)),
        Ui.button(this, "⋯", v -> readerMenu(x))), Ui.fill());
    LinearLayout article = Ui.column(this);
    TextView title = Ui.title(this, x.title);
    title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
    article.addView(title, Ui.fill());
    Ui.add(article, Ui.muted(this, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(x.date))
        + (x.full ? " · full article saved" : "")), 4);
    for (String para : x.text.split("\n\n")) {
      String p = para.trim();
      if (p.isEmpty()) continue;
      TextView t;
      if (p.startsWith("## ")) {
        t = Ui.text(this, p.substring(3), 1.15f);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
      } else {
        t = Ui.text(this, p, 1.05f);
        t.setTypeface(Typeface.SERIF);
        t.setLineSpacing(0, 1.35f);
      }
      t.setTextIsSelectable(true);
      Ui.add(article, t, 12);
    }
    if (x.text.trim().isEmpty()) Ui.add(article, Ui.muted(this, "This item has no text. Try ⋯ → Get the full article."), 12);
    int next = shownStories.indexOf(x) + 1;
    if (next > 0 && next < shownStories.size()) {
      Story n = shownStories.get(next);
      Ui.add(article, Ui.button(this, "Next: " + n.title, v -> read(n)), 24);
    }
    article.setPadding(0, 0, 0, Ui.dp(this, 40));
    readerScroll = Ui.scroll(this, article);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(readerScroll, lp);
    Ui.add(page, Ui.muted(this, "Volume keys turn pages."), 4);
    setPage(page);
  }

  private void readerMenu(Story x) {
    List<String> items = new ArrayList<>();
    if (x.link != null && x.link.startsWith("http")) {
      items.add(x.full ? "Download the article again" : "Get the full article");
      items.add("Open in browser");
    }
    items.add("Read aloud");
    items.add("Share link");
    items.add("Mark unread");
    Dialogs.choose(this, x.title, items.toArray(new String[0]), i -> {
      String c = items.get(i);
      if (c.startsWith("Get") || c.startsWith("Download")) fetchFull(x);
      else if (c.startsWith("Open")) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(x.link))); }
        catch (ActivityNotFoundException e) { toast("No browser installed"); }
      } else if (c.startsWith("Read")) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, x.title + ".\n\n" + x.text.replace("## ", ""));
        send.setPackage("dev.jacob.speak"); // J-ink Speak, if installed
        try { startActivity(send); }
        catch (ActivityNotFoundException e) { send.setPackage(null); startActivity(Intent.createChooser(send, "Read aloud with")); }
      } else if (c.startsWith("Share")) {
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, x.title + "\n" + (x.link == null ? "" : x.link)), "Share"));
      } else {
        x.read = false;
        saveStory(x);
        toast("Marked unread");
      }
    });
  }

  private void fetchFull(Story x) {
    toast("Downloading…");
    new Thread(() -> {
      String err = null;
      try {
        String page = Readable.fromPage(new String(get(x.link), StandardCharsets.UTF_8));
        if (page.length() < 100) throw new IOException("couldn't find the article text on that page");
        x.text = page;
        x.full = true;
        saveStory(x);
      } catch (IOException e) {
        err = e.getMessage();
      }
      String m = err;
      runOnUiThread(() -> {
        if (m != null) toast("Failed: " + m);
        else if (screen == Screen.READER && reading == x) read(x);
      });
    }).start();
  }

  // ---- storage: one JSON file of stories per feed ----

  private static String storiesFile(String feedUrl) { return "stories_" + Integer.toHexString(feedUrl.hashCode()) + ".json"; }

  private Map<String, Integer> unreadCounts() {
    Map<String, Integer> m = new HashMap<>();
    for (Source s : sources) {
      int n = 0;
      for (Story x : loadStories(s.url)) if (!x.read) n++;
      m.put(s.url, n);
    }
    return m;
  }

  private synchronized List<Story> loadStories(String feedUrl) {
    List<Story> out = new ArrayList<>();
    JSONArray a = Store.readArray(this, storiesFile(feedUrl));
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Story x = new Story();
      x.id = o.optString("id");
      x.feedUrl = feedUrl;
      x.feedTitle = o.optString("feed");
      x.title = o.optString("title");
      x.link = o.optString("link");
      x.text = o.optString("text");
      x.date = o.optLong("date");
      x.read = o.optBoolean("read");
      x.full = o.optBoolean("full");
      out.add(x);
    }
    return out;
  }

  private synchronized void saveStories(String feedUrl, List<Story> stories) {
    JSONArray a = new JSONArray();
    try {
      for (Story x : stories) {
        a.put(new JSONObject().put("id", x.id).put("feed", x.feedTitle).put("title", x.title).put("link", x.link)
            .put("text", x.text).put("date", x.date).put("read", x.read).put("full", x.full));
      }
    } catch (JSONException e) {
      return;
    }
    Store.write(this, storiesFile(feedUrl), a);
  }

  /** Saves one changed story back into its feed's file. */
  private synchronized void saveStory(Story x) {
    List<Story> all = loadStories(x.feedUrl);
    for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(x.id)) all.set(i, x);
    saveStories(x.feedUrl, all);
  }

  private void loadSources() {
    JSONArray a = Store.readArray(this, "feeds.json");
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Source s = new Source();
      s.url = o.optString("url");
      s.title = o.optString("title");
      sources.add(s);
    }
  }

  private void saveSources() {
    JSONArray a = new JSONArray();
    try { for (Source s : sources) a.put(new JSONObject().put("url", s.url).put("title", s.title)); }
    catch (JSONException e) { return; }
    Store.write(this, "feeds.json", a);
  }
}
