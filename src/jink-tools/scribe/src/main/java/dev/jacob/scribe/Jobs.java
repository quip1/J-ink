package dev.jacob.scribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import dev.jacob.aillama.Llama;
import dev.jacob.aiwhisper.Whisper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the AI work one job at a time on a background thread: transcribing, summarising and
 * answering questions. The UI watches {@link #status} and {@link #live}.
 */
final class Jobs {
  private Jobs() {}

  /** Ten-minute windows: whole lectures don't have to fit in memory at once. */
  private static final int WINDOW = Wav.RATE * 600;
  private static final int CONTEXT = 4096, THREADS = 4;
  /** Roughly how many characters fit in one model prompt alongside instructions and the reply. */
  private static final int PROMPT_CHARS = 8000, PART_CHARS = 5000;

  interface Listener { void changed(); }

  static volatile boolean running;
  static volatile String status = "", error;
  static volatile Recording subject;
  /** Text being written right now (a summary or answer), for showing it as it appears. */
  static final StringBuilder live = new StringBuilder();
  static volatile Listener listener;

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static Thread thread;

  private interface Work { void run() throws IOException; }

  private static boolean start(Recording r, String first, Work work) {
    if (running) return false;
    running = true;
    error = null;
    subject = r;
    synchronized (live) { live.setLength(0); }
    status = first;
    notifyUi();
    thread = new Thread(() -> {
      try { work.run(); }
      catch (IOException | RuntimeException | OutOfMemoryError e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
      finally {
        running = false;
        status = "";
        notifyUi();
      }
    }, "scribe-ai");
    thread.start();
    return true;
  }

  static void cancel() {
    Whisper.cancel();
    Llama.cancel();
    status = "Stopping…";
    notifyUi();
  }

  private static long lastNotify;

  private static void notifyUi() {
    lastNotify = System.currentTimeMillis();
    MAIN.post(() -> { Listener l = listener; if (l != null) l.changed(); });
  }

  /** At most about once a second: each update is a screen refresh on e-ink. */
  private static void notifySometimes() {
    if (System.currentTimeMillis() - lastNotify > 1000) notifyUi();
  }

  static SharedPreferences prefs(Context c) { return c.getSharedPreferences("scribe", Context.MODE_PRIVATE); }

  // ---- transcription ----

  static boolean transcribe(Context c, Recording r) {
    Context app = c.getApplicationContext();
    return start(r, "Loading the speech model…", () -> {
      Models.Model m = Models.chosen(app, Models.Kind.SPEECH);
      if (m == null) throw new IOException("Download a speech model first (Models button).");
      String lang = prefs(app).getString("language", "auto");
      boolean translate = prefs(app).getBoolean("translate", false);
      File audio = r.audio();
      long total = Wav.samples(audio);
      if (total < Wav.RATE / 2) throw new IOException("The recording is too short.");
      synchronized (r) { r.segments.clear(); }
      r.transcribed = false;
      try (Whisper w = Whisper.load(Models.fileOf(app, m).getPath())) {
        for (long off = 0; off < total; off += WINDOW) {
          float[] window = Wav.readWindow(audio, off, WINDOW);
          long offMs = off * 1000 / Wav.RATE;
          long base = off;
          // Carry the end of the previous window over as context, so names and style stay consistent.
          String carry = r.segments.isEmpty() ? null : tail(r.transcript(false), 200);
          boolean finished = w.transcribe(window, lang, translate, THREADS, carry, new Whisper.Listener() {
            @Override public void onProgress(int percent) {
              long doneSamples = base + (long) window.length * Math.min(100, Math.max(0, percent)) / 100;
              status = "Transcribing… " + Math.min(99, doneSamples * 100 / total) + "%";
              notifySometimes();
            }

            @Override public void onSegment(long startMs, long endMs, String text) {
              synchronized (r) { r.segments.add(new TextTools.Segment(offMs + startMs, offMs + endMs, text)); }
              notifySometimes();
            }
          });
          if (r.language.isEmpty() || "auto".equals(lang)) r.language = w.language();
          r.save();
          if (!finished) { status = "Stopped"; return; }
        }
      }
      r.transcribed = true;
      r.save();
    });
  }

  private static String tail(String s, int n) { return s.length() <= n ? s : s.substring(s.length() - n); }

  // ---- language model jobs ----

  private static Llama loadLlama(Context app) throws IOException {
    Models.Model m = Models.chosen(app, Models.Kind.LANGUAGE);
    if (m == null) throw new IOException("Download a language model first (Models button).");
    status = "Loading " + m.label + "…";
    notifyUi();
    return Llama.load(Models.fileOf(app, m).getPath(), CONTEXT, THREADS);
  }

  private static String ask(Llama llm, String userPrompt, int maxTokens, boolean showLive) throws IOException {
    String prompt = llm.format(Arrays.asList(new Llama.Message("system", TextTools.SYSTEM), new Llama.Message("user", userPrompt)));
    StringBuilder out = new StringBuilder();
    llm.generate(prompt, maxTokens, 0.3f, piece -> {
      out.append(piece);
      if (showLive) {
        synchronized (live) { live.append(piece); }
        notifySometimes();
      }
      return true;
    });
    return TextTools.tidy(out.toString());
  }

  static boolean summarize(Context c, Recording r) {
    Context app = c.getApplicationContext();
    return start(r, "Preparing…", () -> {
      String text = r.transcript(false);
      if (text.isEmpty()) throw new IOException("Transcribe the recording first.");
      try (Llama llm = loadLlama(app)) {
        String summary;
        if (text.length() <= PROMPT_CHARS) {
          status = "Writing the summary…";
          notifyUi();
          summary = ask(llm, TextTools.summaryPrompt(text), 500, true);
        } else {
          // Long recordings: notes on each part, then one summary of the notes.
          List<String> parts = TextTools.chunks(text, PART_CHARS);
          List<String> notes = new ArrayList<>();
          for (int i = 0; i < parts.size(); i++) {
            status = "Reading part " + (i + 1) + " of " + parts.size() + "…";
            notifyUi();
            notes.add(ask(llm, TextTools.partPrompt(parts.get(i), i + 1, parts.size()), 250, false));
          }
          String joined = String.join("\n", notes);
          if (joined.length() > PROMPT_CHARS) joined = joined.substring(0, PROMPT_CHARS);
          status = "Writing the summary…";
          notifyUi();
          summary = ask(llm, TextTools.mergePrompt(joined), 500, true);
        }
        r.summary = summary;
        r.save();
      }
    });
  }

  static boolean question(Context c, Recording r, String q) {
    Context app = c.getApplicationContext();
    return start(r, "Preparing…", () -> {
      String text = r.transcript(false);
      if (text.isEmpty()) throw new IOException("Transcribe the recording first.");
      String context = text.length() <= PROMPT_CHARS ? text : TextTools.relevant(TextTools.chunks(text, 1200), q, PROMPT_CHARS);
      try (Llama llm = loadLlama(app)) {
        status = "Thinking…";
        notifyUi();
        String a = ask(llm, TextTools.questionPrompt(context, q), 400, true);
        r.answers.add(0, new String[]{q, a});
        r.save();
      }
    });
  }
}
