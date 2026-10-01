package dev.slate.notes;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** Built-in font families plus imported .ttf/.otf files. Keys: sans, serif, mono, file:Name.ttf */
final class Fonts {
  private static final Map<String, Typeface> cache = new HashMap<>();

  static File dir() { return Storage.fontsDir(App.ctx); }

  static List<String> keys() {
    List<String> out = new ArrayList<>(Arrays.asList("sans", "serif", "mono"));
    File[] fs = dir().listFiles((d, n) -> { String l = n.toLowerCase(); return l.endsWith(".ttf") || l.endsWith(".otf"); });
    if (fs != null) {
      Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      for (File f : fs) out.add("file:" + f.getName());
    }
    return out;
  }

  static String name(String key) {
    switch (key) {
      case "sans": return "Sans";
      case "serif": return "Serif";
      case "mono": return "Mono";
    }
    if (key.startsWith("file:")) return key.substring(5).replaceAll("\\.(ttf|otf|TTF|OTF)$", "");
    return key;
  }

  static synchronized Typeface typeface(String key) {
    Typeface t = cache.get(key);
    if (t != null) return t;
    switch (key) {
      case "serif": t = Typeface.SERIF; break;
      case "mono": t = Typeface.MONOSPACE; break;
      case "sans": t = Typeface.SANS_SERIF; break;
      default:
        if (key.startsWith("file:")) {
          try { t = Typeface.createFromFile(new File(dir(), key.substring(5))); } catch (RuntimeException ignored) {}
        }
    }
    if (t == null) t = Typeface.SANS_SERIF; // missing font file falls back quietly
    cache.put(key, t);
    return t;
  }

  static String importUri(Context ctx, Uri uri) throws IOException {
    String name = Templates.displayName(ctx, uri).replaceAll("[^A-Za-z0-9 _.-]", "_");
    String lower = name.toLowerCase();
    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) throw new IOException("Pick a .ttf or .otf font");
    File f = new File(dir(), name);
    try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
      if (in == null) throw new IOException("Can't read file");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    try { Typeface.createFromFile(f); }
    catch (RuntimeException e) { f.delete(); throw new IOException("Not a valid font"); }
    String key = "file:" + f.getName();
    synchronized (Fonts.class) { cache.remove(key); }
    return key;
  }
}
