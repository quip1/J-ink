package dev.jacob.scribe;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The AI models Scribe can use, where they're stored, and downloading them (resumable, so a
 * dropped Wi-Fi connection doesn't mean starting a 1 GB download again).
 */
final class Models {
  private Models() {}

  enum Kind { SPEECH, LANGUAGE }

  static final class Model {
    final Kind kind;
    final String id, label, file, url, note;
    final int sizeMb;

    Model(Kind kind, String id, String label, String file, String url, int sizeMb, String note) {
      this.kind = kind; this.id = id; this.label = label; this.file = file; this.url = url; this.sizeMb = sizeMb; this.note = note;
    }
  }

  private static final String WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

  static final Model[] CATALOG = {
      new Model(Kind.SPEECH, "whisper-base", "Whisper Base", "ggml-base-q5_1.bin", WHISPER + "ggml-base-q5_1.bin", 60,
          "Fast. Good for clear speech."),
      new Model(Kind.SPEECH, "whisper-small", "Whisper Small", "ggml-small-q5_1.bin", WHISPER + "ggml-small-q5_1.bin", 190,
          "Recommended. Noticeably more accurate, about 3× slower than Base."),
      new Model(Kind.SPEECH, "whisper-medium", "Whisper Medium", "ggml-medium-q5_0.bin", WHISPER + "ggml-medium-q5_0.bin", 540,
          "Most accurate here, but slow on a tablet. For accents or noisy rooms."),
      new Model(Kind.LANGUAGE, "qwen-0.5b", "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
          "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf", 400,
          "Quick but simple. Fine for short summaries."),
      new Model(Kind.LANGUAGE, "qwen-1.5b", "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
          "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf", 1120,
          "Recommended. Good summaries and answers at a usable speed."),
      new Model(Kind.LANGUAGE, "qwen-3b", "Qwen2.5 3B", "qwen2.5-3b-instruct-q4_k_m.gguf",
          "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf", 2100,
          "Smartest, but slow and uses about 3 GB of memory. Free for personal use (Qwen Research licence)."),
  };

  static File dir(Context c) {
    File base = c.getExternalFilesDir(null);
    File d = new File(base != null ? base : c.getFilesDir(), "models");
    d.mkdirs();
    return d;
  }

  static File fileOf(Context c, Model m) { return new File(dir(c), m.file); }

  static boolean ready(Context c, Model m) { return fileOf(c, m).isFile(); }

  /** Catalog models plus any other model files the user imported or downloaded from a link. */
  static List<Model> all(Context c, Kind kind) {
    List<Model> out = new ArrayList<>();
    List<String> known = new ArrayList<>();
    for (Model m : CATALOG) { known.add(m.file); if (m.kind == kind) out.add(m); }
    File[] files = dir(c).listFiles();
    if (files != null) {
      for (File f : files) {
        String n = f.getName();
        if (known.contains(n) || n.endsWith(".part")) continue;
        boolean isSpeech = n.endsWith(".bin"), isLanguage = n.endsWith(".gguf");
        if ((kind == Kind.SPEECH && isSpeech) || (kind == Kind.LANGUAGE && isLanguage)) {
          out.add(new Model(kind, "file:" + n, n, n, null, (int) (f.length() >> 20), "Your own model file."));
        }
      }
    }
    return out;
  }

  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("models", Context.MODE_PRIVATE); }

  /** The model chosen for a job, or the first downloaded one, or null if none is downloaded. */
  static Model chosen(Context c, Kind kind) {
    String id = prefs(c).getString(kind.name(), null);
    Model first = null;
    for (Model m : all(c, kind)) {
      if (!ready(c, m)) continue;
      if (m.id.equals(id)) return m;
      if (first == null) first = m;
    }
    return first;
  }

  static void choose(Context c, Model m) { prefs(c).edit().putString(m.kind.name(), m.id).apply(); }

  interface Progress { void update(long done, long total); }

  /** Downloads into {@code target}, resuming a previous partial download if there is one. */
  static void download(String url, File target, Progress progress, AtomicBoolean cancel) throws IOException {
    File part = new File(target.getPath() + ".part");
    String current = url;
    for (int hop = 0; hop < 6; hop++) {
      HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(20000);
      c.setReadTimeout(30000);
      c.setRequestProperty("User-Agent", "J-ink Scribe");
      long have = part.length();
      if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
      int code = c.getResponseCode();
      if (code >= 300 && code < 400) {
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) throw new IOException("Bad redirect");
        current = new URL(new URL(current), loc).toString();
        continue;
      }
      if (code == 416) { c.disconnect(); break; } // already complete
      if (code != 200 && code != 206) { c.disconnect(); throw new IOException("Server said " + code); }
      boolean append = code == 206;
      long total = c.getContentLengthLong();
      long done = append ? have : 0;
      if (total > 0 && append) total += have;
      try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
        byte[] buf = new byte[1 << 16];
        int n;
        long lastReport = 0;
        while ((n = in.read(buf)) > 0) {
          if (cancel.get()) throw new IOException("Cancelled");
          out.write(buf, 0, n);
          done += n;
          long now = System.currentTimeMillis();
          if (now - lastReport > 1000) { progress.update(done, total); lastReport = now; }
        }
      } finally {
        c.disconnect();
      }
      if (total > 0 && done < total) throw new IOException("Download was cut short. Try again to resume.");
      break;
    }
    if (!part.renameTo(target)) throw new IOException("Couldn't save the model file");
  }
}
