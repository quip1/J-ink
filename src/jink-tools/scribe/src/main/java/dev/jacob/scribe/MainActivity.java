package dev.jacob.scribe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jacob.aillama.Llama;
import dev.jacob.aiwhisper.Whisper;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Record, transcribe with Whisper, then summarise or ask questions with a local language model.
 * Everything runs on the device; models are downloaded once.
 */
public class MainActivity extends InkActivity {
  private enum Screen { HOME, RECORDING, DETAIL, MODELS }
  private enum Tab { TRANSCRIPT, SUMMARY, QA }

  private static final String[][] LANGUAGES = {{"auto", "Detect automatically"}, {"en", "English"}, {"es", "Spanish"},
      {"fr", "French"}, {"de", "German"}, {"it", "Italian"}, {"pt", "Portuguese"}, {"nl", "Dutch"}, {"pl", "Polish"},
      {"ru", "Russian"}, {"uk", "Ukrainian"}, {"tr", "Turkish"}, {"ar", "Arabic"}, {"hi", "Hindi"}, {"zh", "Chinese"},
      {"ja", "Japanese"}, {"ko", "Korean"}, {"sv", "Swedish"}, {"no", "Norwegian"}, {"da", "Danish"}, {"fi", "Finnish"}};

  private final Handler handler = new Handler(Looper.getMainLooper());
  private Screen screen = Screen.HOME;
  private Tab tab = Tab.TRANSCRIPT;
  private Recording open;
  private Pager<Recording> pager;
  private ScrollView contentScroll;
  private TextView jobLine, liveTime, liveLevel, contentText;
  private MediaPlayer player;
  private TextToSpeech tts;
  private boolean speaking, showTimes;

  // Model downloads (one at a time).
  private static volatile String downloading, downloadStatus = "";
  private static final AtomicBoolean cancelDownload = new AtomicBoolean();

  private final Runnable recordingTicker = new Runnable() {
    @Override public void run() {
      if (screen != Screen.RECORDING) return;
      if (!RecorderService.recording) { finishedRecording(); return; }
      String t = TextTools.clock(RecorderService.recordedMs) + (RecorderService.paused ? "  paused" : "");
      if (!t.contentEquals(liveTime.getText())) liveTime.setText(t);
      liveLevel.setText(meter(RecorderService.level));
      handler.postDelayed(this, 1000);
    }
  };

  private final Runnable downloadTicker = new Runnable() {
    @Override public void run() {
      if (screen != Screen.MODELS) return;
      if (jobLine != null && !downloadStatus.contentEquals(jobLine.getText())) jobLine.setText(downloadStatus);
      if (downloading != null) handler.postDelayed(this, 1500); else showModels();
    }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    showTimes = Jobs.prefs(this).getBoolean("times", false);
    if (RecorderService.recording) showRecordingScreen(); else showHome();
    Intent in = getIntent();
    if (Intent.ACTION_SEND.equals(in.getAction()) || Intent.ACTION_VIEW.equals(in.getAction())) importIntent(in);
  }

  @Override protected void onResume() {
    super.onResume();
    Jobs.listener = this::jobChanged;
    jobChanged();
  }

  @Override protected void onPause() {
    super.onPause();
    Jobs.listener = null;
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    stopPlayback();
    if (tts != null) tts.shutdown();
  }

  @Override protected boolean onPageKey(int dir) {
    if (screen == Screen.DETAIL && contentScroll != null) {
      contentScroll.scrollBy(0, (int) (dir * contentScroll.getHeight() * 0.9f));
      return true;
    }
    return screen == Screen.HOME && pager != null && pager.turn(dir);
  }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    if (screen == Screen.DETAIL || screen == Screen.MODELS) showHome();
    else super.onBackPressed();
  }

  // ---- home ----

  private void showHome() {
    screen = Screen.HOME;
    open = null;
    stopPlayback();
    LinearLayout page = Ui.column(this);
    page.addView(header("Scribe", Ui.button(this, "Models", v -> showModels()), refreshButton()), Ui.fill());
    TextView rec = Ui.button(this, "●  Record", v -> startRecording());
    rec.setTextSize(Ui.body(this) * 1.4f);
    rec.setMinHeight(Ui.dp(this, 80));
    Ui.setActive(rec, true);
    Ui.add(page, rec, 12);
    Ui.add(page, Ui.button(this, "Import an audio file…", v -> openDocument(new String[]{"audio/*", "video/*"}, this::importAudio)), 6);
    jobLine = Ui.muted(this, "");
    Ui.add(page, jobLine, 8);
    jobLine.setOnClickListener(v -> { if (Jobs.subject != null) showDetail(Jobs.subject); });
    DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    pager = new Pager<>(this, Pager.fit(this, 76, 330), "No recordings yet.", r -> {
      LinearLayout row = Ui.column(this);
      int p = Ui.dp(this, 10);
      row.setPadding(p, p, p, p);
      TextView t = Ui.title(this, r.title.isEmpty() ? "Recording" : r.title);
      t.setSingleLine(true);
      row.addView(t);
      String state = !r.summary.isEmpty() ? "summarised" : r.transcribed ? "transcribed" : "not transcribed";
      row.addView(Ui.muted(this, df.format(new Date(r.created)) + " · " + TextTools.clock(r.durationMs()) + " · " + state));
      row.setOnClickListener(v -> showDetail(r));
      return row;
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 6);
    page.addView(pager.view(), lp);
    pager.setItems(Recording.all(this));
    if (Models.chosen(this, Models.Kind.SPEECH) == null) {
      Ui.add(page, Ui.muted(this, "To transcribe, first download a speech model: tap Models."), 6);
    }
    setPage(page);
    jobChanged();
  }

  // ---- recording ----

  private void startRecording() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      String[] perms = Build.VERSION.SDK_INT >= 33
          ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
          : new String[]{Manifest.permission.RECORD_AUDIO};
      requestPermissions(perms, 1);
      return;
    }
    Recording r = Recording.create(this, "Recording " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
    RecorderService.send(this, RecorderService.START, r.dir.getAbsolutePath());
    showRecordingScreen();
  }

  @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
    super.onRequestPermissionsResult(req, perms, results);
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording();
    else toast("Scribe needs the microphone to record.");
  }

  private void showRecordingScreen() {
    screen = Screen.RECORDING;
    LinearLayout page = Ui.column(this);
    page.addView(header("Recording"), Ui.fill());
    liveTime = Ui.text(this, "0:00", Ui.large(this) ? 6f : 4.5f);
    liveTime.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    liveTime.setGravity(Gravity.CENTER);
    Ui.add(page, liveTime, 40);
    liveLevel = Ui.text(this, "", 1.2f);
    liveLevel.setTypeface(Typeface.MONOSPACE);
    liveLevel.setGravity(Gravity.CENTER);
    Ui.add(page, liveLevel, 8);
    TextView note = Ui.muted(this, "Recording carries on with the screen off or in other apps.");
    note.setGravity(Gravity.CENTER);
    Ui.add(page, note, 12);
    page.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
    TextView pause = Ui.button(this, RecorderService.paused ? "Resume" : "Pause", null);
    pause.setOnClickListener(v -> {
      boolean nowPaused = !RecorderService.paused;
      RecorderService.send(this, nowPaused ? RecorderService.PAUSE : RecorderService.RESUME, null);
      pause.setText(nowPaused ? "Resume" : "Pause");
    });
    TextView stop = Ui.button(this, "■  Stop and save", v -> RecorderService.send(this, RecorderService.STOP, null));
    Ui.setActive(stop, true);
    stop.setMinHeight(Ui.dp(this, 72));
    Ui.add(page, Ui.buttons(this, pause), 8);
    Ui.add(page, Ui.buttons(this, stop), 8);
    setPage(page);
    handler.removeCallbacks(recordingTicker);
    handler.post(recordingTicker);
  }

  private static String meter(int level) {
    int n = level / 10;
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 10; i++) b.append(i < n ? '■' : '□');
    return b.toString();
  }

  private void finishedRecording() {
    if (RecorderService.error != null) toast("Recording problem: " + RecorderService.error);
    String dir = RecorderService.currentDir;
    Recording r = dir == null ? null : Recording.load(new File(dir));
    if (r == null || r.durationMs() < 500) { toast("Nothing was recorded."); showHome(); return; }
    showDetail(r);
    if (Models.chosen(this, Models.Kind.SPEECH) != null) {
      Dialogs.confirm(this, "Transcribe this recording now?", "Transcribe", () -> { if (!Jobs.transcribe(this, r)) toast("Busy with another job"); });
    }
  }

  // ---- importing audio ----

  @SuppressWarnings("deprecation")
  private void importIntent(Intent in) {
    Uri u = Intent.ACTION_SEND.equals(in.getAction()) ? in.getParcelableExtra(Intent.EXTRA_STREAM) : in.getData();
    setIntent(new Intent());
    if (u != null) importAudio(u);
  }

  private void importAudio(Uri uri) {
    Recording r = Recording.create(this, "Imported " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
    TextView msg = Ui.text(this, "Converting…");
    int pad = Ui.dp(this, 20);
    msg.setPadding(pad, pad, pad, pad);
    android.app.AlertDialog d = Dialogs.builder(this).setView(msg).setCancelable(false).create();
    d.show();
    new Thread(() -> {
      String err = null;
      try {
        AudioImport.toWav(this, uri, r.audio(), pct -> runOnUiThread(() -> msg.setText("Converting… " + pct + "%")));
      } catch (IOException | RuntimeException e) {
        err = e.getMessage();
      }
      String m = err;
      runOnUiThread(() -> {
        d.dismiss();
        if (m != null) { r.delete(); toast("Couldn't import: " + m); return; }
        showDetail(r);
      });
    }).start();
  }

  // ---- one recording ----

  private void showDetail(Recording r) {
    screen = Screen.DETAIL;
    open = r;
    LinearLayout page = Ui.column(this);
    page.addView(header(r.title.isEmpty() ? "Recording" : r.title, Ui.button(this, "←", v -> showHome()),
        Ui.button(this, "⋯", v -> detailMenu(r))), Ui.fill());
    Ui.add(page, Ui.muted(this, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(r.created))
        + " · " + TextTools.clock(r.durationMs()) + (r.language.isEmpty() ? "" : " · " + r.language)), 4);
    TextView play = Ui.button(this, player != null ? "■ Stop" : "▶ Play", v -> togglePlayback(r));
    TextView transcribe = Ui.button(this, r.transcribed ? "Transcribe again" : "Transcribe", v -> {
      if (!Jobs.transcribe(this, r)) toast("Busy with another job");
    });
    TextView summarize = Ui.button(this, "Summarise", v -> {
      tab = Tab.SUMMARY;
      if (!Jobs.summarize(this, r)) toast("Busy with another job");
    });
    TextView askBtn = Ui.button(this, "Ask…", v -> Dialogs.prompt(this, "Ask about this recording", "", q -> {
      if (q.trim().isEmpty()) return;
      tab = Tab.QA;
      if (!Jobs.question(this, r, q.trim())) toast("Busy with another job");
    }));
    TextView read = Ui.button(this, speaking ? "Stop reading" : "Read aloud", v -> toggleSpeech(r));
    Ui.add(page, Ui.buttons(this, play, transcribe), 8);
    Ui.add(page, Ui.buttons(this, summarize, askBtn, read), 4);

    LinearLayout jobRow = Ui.row(this);
    jobLine = Ui.text(this, "");
    jobLine.setTypeface(Typeface.DEFAULT_BOLD);
    jobRow.addView(jobLine, Ui.weight(1));
    TextView cancel = Ui.button(this, "Stop", v -> Jobs.cancel());
    cancel.setTag("cancel");
    jobRow.addView(cancel);
    Ui.add(page, jobRow, 8);

    TextView[] tabs = new TextView[3];
    Tab[] all = Tab.values();
    String[] names = {"Transcript", "Summary", "Questions"};
    for (int i = 0; i < 3; i++) {
      Tab t = all[i];
      tabs[i] = Ui.button(this, names[i], v -> { tab = t; showDetail(r); });
      Ui.setActive(tabs[i], t == tab);
    }
    Ui.add(page, Ui.buttons(this, tabs), 8);
    contentText = Ui.text(this, "");
    contentText.setTypeface(Typeface.SERIF);
    contentText.setLineSpacing(0, 1.3f);
    contentText.setTextIsSelectable(true);
    contentScroll = Ui.scroll(this, contentText);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(contentScroll, lp);
    setPage(page);
    jobChanged();
  }

  /** Fills the tab's text: the saved result, or what the model is writing right now. */
  private void fillContent() {
    if (contentText == null || open == null) return;
    boolean thisJob = Jobs.running && Jobs.subject == open;
    String text;
    switch (tab) {
      case SUMMARY: {
        String liveText;
        synchronized (Jobs.live) { liveText = Jobs.live.toString(); }
        text = thisJob && !liveText.isEmpty() ? liveText : open.summary.isEmpty()
            ? "No summary yet. Tap Summarise (needs a language model and a transcript)." : open.summary;
        break;
      }
      case QA: {
        StringBuilder b = new StringBuilder();
        if (thisJob) {
          synchronized (Jobs.live) { if (Jobs.live.length() > 0) b.append(Jobs.live).append("\n\n—\n\n"); }
        }
        for (String[] qa : open.answers) b.append("Q: ").append(qa[0]).append("\n\n").append(qa[1]).append("\n\n—\n\n");
        text = b.length() == 0 ? "Tap Ask… to ask a question about this recording." : b.toString();
        break;
      }
      default: {
        String t = open.transcript(showTimes);
        text = t.isEmpty() ? (open.transcribed ? "No speech was found." : "Not transcribed yet.") : t;
      }
    }
    if (!text.contentEquals(contentText.getText())) contentText.setText(text);
  }

  private void detailMenu(Recording r) {
    String[] items = {"Rename…", showTimes ? "Hide times in transcript" : "Show times in transcript", "Share text…",
        "Save transcript as a file…", "Share the audio…", "Delete"};
    Dialogs.choose(this, r.title, items, i -> {
      switch (i) {
        case 0: Dialogs.prompt(this, "Rename", r.title, n -> { if (!n.trim().isEmpty()) { r.title = n.trim(); r.save(); showDetail(r); } }); break;
        case 1: showTimes = !showTimes; Jobs.prefs(this).edit().putBoolean("times", showTimes).apply(); fillContent(); break;
        case 2: startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, exportText(r)), "Share")); break;
        case 3: createDocument("text/markdown", r.title.replaceAll("[\\\\/:*?\"<>|]", "-") + ".md", uri -> writeUri(uri, exportText(r))); break;
        case 4: shareAudio(r); break;
        default: Dialogs.confirm(this, "Delete “" + r.title + "”, its audio and transcript?", "Delete", () -> {
          if (Jobs.running && Jobs.subject == r) Jobs.cancel();
          stopPlayback();
          r.delete();
          showHome();
        });
      }
    });
  }

  private String exportText(Recording r) {
    StringBuilder b = new StringBuilder("# ").append(r.title).append("\n\n");
    if (!r.summary.isEmpty()) b.append("## Summary\n\n").append(r.summary).append("\n\n");
    b.append("## Transcript\n\n").append(r.transcript(true)).append('\n');
    for (String[] qa : r.answers) b.append("\n**Q:** ").append(qa[0]).append("\n\n").append(qa[1]).append('\n');
    return b.toString();
  }

  private void writeUri(Uri uri, String text) {
    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
      if (out == null) throw new IOException("Can't write there");
      out.write(text.getBytes(StandardCharsets.UTF_8));
      toast("Saved");
    } catch (IOException e) {
      toast("Couldn't save: " + e.getMessage());
    }
  }

  private void shareAudio(Recording r) {
    createDocument("audio/wav", r.title.replaceAll("[\\\\/:*?\"<>|]", "-") + ".wav", uri -> {
      try (OutputStream out = getContentResolver().openOutputStream(uri, "wt"); InputStream in = new java.io.FileInputStream(r.audio())) {
        if (out == null) throw new IOException("Can't write there");
        Store.copy(in, out);
        toast("Saved the audio");
      } catch (IOException e) {
        toast("Couldn't save: " + e.getMessage());
      }
    });
  }

  private void togglePlayback(Recording r) {
    if (player != null) { stopPlayback(); showDetail(r); return; }
    try {
      player = new MediaPlayer();
      player.setDataSource(r.audio().getPath());
      player.setOnCompletionListener(mp -> { stopPlayback(); if (screen == Screen.DETAIL) showDetail(r); });
      player.prepare();
      player.start();
    } catch (IOException | RuntimeException e) {
      stopPlayback();
      toast("Can't play this recording");
    }
    showDetail(r);
  }

  private void stopPlayback() {
    if (player != null) {
      try { player.stop(); } catch (IllegalStateException ignored) { /* not started */ }
      player.release();
      player = null;
    }
  }

  /** Reads the summary if there is one, otherwise the transcript, with the device's voice. */
  private void toggleSpeech(Recording r) {
    if (speaking) {
      if (tts != null) tts.stop();
      speaking = false;
      showDetail(r);
      return;
    }
    String text = !r.summary.isEmpty() ? r.summary : r.transcript(false);
    if (text.isEmpty()) { toast("Nothing to read yet"); return; }
    Runnable go = () -> {
      speaking = true;
      List<String> parts = TextTools.chunks(text.replace("- ", ""), 3500);
      for (int i = 0; i < parts.size(); i++) tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "p" + i);
      showDetail(r);
    };
    if (tts == null) {
      tts = new TextToSpeech(this, st -> runOnUiThread(() -> {
        if (st != TextToSpeech.SUCCESS) { toast("No text-to-speech voice installed"); return; }
        tts.setLanguage(Locale.getDefault());
        go.run();
      }));
    } else go.run();
  }

  // ---- job status ----

  private void jobChanged() {
    boolean running = Jobs.running;
    if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (Jobs.error != null) {
      String e = Jobs.error;
      Jobs.error = null;
      Dialogs.message(this, "Couldn't finish", e);
    }
    if (jobLine == null) return;
    String line = "";
    if (running) line = (screen == Screen.HOME && Jobs.subject != null ? Jobs.subject.title + ": " : "") + Jobs.status;
    if (screen == Screen.MODELS) return;
    if (!line.contentEquals(jobLine.getText())) jobLine.setText(line);
    View row = (View) jobLine.getParent();
    if (screen == Screen.DETAIL) {
      View cancel = row.findViewWithTag("cancel");
      boolean mine = running && Jobs.subject == open;
      if (cancel != null) cancel.setVisibility(mine ? View.VISIBLE : View.GONE);
      if (!mine && Jobs.subject == open && open != null && !running) {
        // Just finished: reload so the new text shows.
        Recording fresh = Recording.load(open.dir);
        if (fresh != null) open = fresh;
      }
      fillContent();
    } else if (screen == Screen.HOME && !running && pager != null) {
      pager.setItems(Recording.all(this));
    }
  }

  // ---- models ----

  private void showModels() {
    screen = Screen.MODELS;
    LinearLayout page = Ui.column(this);
    page.addView(header("Models", Ui.button(this, "←", v -> showHome()), refreshButton()), Ui.fill());
    LinearLayout body = Ui.column(this);
    jobLine = Ui.text(this, downloadStatus);
    jobLine.setTypeface(Typeface.DEFAULT_BOLD);
    body.addView(jobLine, Ui.fill());
    if (downloading != null) Ui.add(body, Ui.button(this, "Cancel download", v -> cancelDownload.set(true)), 4);

    Ui.add(body, Ui.title(this, "Speech to text"), 12);
    for (Models.Model m : Models.all(this, Models.Kind.SPEECH)) Ui.add(body, modelRow(m), 6);
    String lang = Jobs.prefs(this).getString("language", "auto");
    String langName = lang;
    for (String[] l : LANGUAGES) if (l[0].equals(lang)) langName = l[1];
    Ui.add(body, Ui.button(this, "Language: " + langName, v -> {
      String[] labels = new String[LANGUAGES.length];
      for (int i = 0; i < labels.length; i++) labels[i] = LANGUAGES[i][1];
      Dialogs.choose(this, "Spoken language", labels, i -> { Jobs.prefs(this).edit().putString("language", LANGUAGES[i][0]).apply(); showModels(); });
    }), 8);
    boolean translate = Jobs.prefs(this).getBoolean("translate", false);
    TextView tr = Ui.button(this, "Translate to English", v -> { Jobs.prefs(this).edit().putBoolean("translate", !translate).apply(); showModels(); });
    Ui.setActive(tr, translate);
    Ui.add(body, tr, 4);

    Ui.add(body, Ui.title(this, "Summaries and questions"), 20);
    for (Models.Model m : Models.all(this, Models.Kind.LANGUAGE)) Ui.add(body, modelRow(m), 6);

    Ui.add(body, Ui.title(this, "Other models"), 20);
    Ui.add(body, Ui.button(this, "Import a model file (.bin or .gguf)…", v -> openDocument(new String[]{"*/*"}, this::importModel)), 6);
    Ui.add(body, Ui.button(this, "Download from a link…", v -> Dialogs.prompt(this, "Link to a .gguf or ggml .bin file", "https://",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, url -> {
          String name = Uri.parse(url.trim()).getLastPathSegment();
          if (name == null || !(name.endsWith(".gguf") || name.endsWith(".bin"))) { toast("The link should end in .gguf or .bin"); return; }
          startDownload(url.trim(), new File(Models.dir(this), name), name);
        })), 6);

    Ui.add(body, Ui.title(this, "This device"), 20);
    String info;
    try { info = "Whisper: " + Whisper.systemInfo() + "\n\nLanguage model: " + Llama.systemInfo(); }
    catch (UnsatisfiedLinkError e) { info = "The AI engine couldn't start on this device: " + e.getMessage(); }
    Ui.add(body, Ui.muted(this, info + "\n\nModels run on the CPU using its ARM dot-product instructions. "
        + "The Snapdragon NPU (Hexagon) isn't used yet. Models are stored in " + Models.dir(this).getPath() + "."), 4);

    contentScroll = Ui.scroll(this, body);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 8);
    page.addView(contentScroll, lp);
    setPage(page);
    if (downloading != null) { handler.removeCallbacks(downloadTicker); handler.post(downloadTicker); }
  }

  private View modelRow(Models.Model m) {
    boolean ready = Models.ready(this, m);
    Models.Model chosen = Models.chosen(this, m.kind);
    boolean inUse = chosen != null && chosen.id.equals(m.id);
    LinearLayout card = Ui.column(this);
    card.setBackground(Ui.box(this, Ui.PAPER));
    int p = Ui.dp(this, 10);
    card.setPadding(p, p, p, p);
    TextView name = Ui.text(this, m.label + "  ·  " + (m.sizeMb >= 1000 ? (m.sizeMb / 100) / 10.0 + " GB" : m.sizeMb + " MB")
        + (inUse ? "  ·  in use" : ""));
    name.setTypeface(Typeface.DEFAULT_BOLD);
    card.addView(name);
    card.addView(Ui.muted(this, m.note));
    LinearLayout buttons = Ui.row(this);
    if (!ready) {
      if (m.url != null) {
        TextView dl = Ui.button(this, m.file.equals(downloading) ? "Downloading…" : "Download", v -> {
          if (downloading != null) { toast("One download at a time"); return; }
          startDownload(m.url, Models.fileOf(this, m), m.file);
        });
        buttons.addView(dl, Ui.weight(1));
      }
    } else {
      TextView use = Ui.button(this, inUse ? "In use" : "Use", v -> { Models.choose(this, m); showModels(); });
      Ui.setActive(use, inUse);
      buttons.addView(use, Ui.weight(1));
      LinearLayout.LayoutParams dp = Ui.weight(1);
      dp.leftMargin = Ui.dp(this, 6);
      buttons.addView(Ui.button(this, "Delete", v -> Dialogs.confirm(this, "Delete " + m.label + "? You can download it again later.",
          "Delete", () -> { Models.fileOf(this, m).delete(); showModels(); })), dp);
    }
    Ui.add(card, buttons, 6);
    return card;
  }

  private void startDownload(String url, File target, String label) {
    if (downloading != null) { toast("One download at a time"); return; }
    downloading = label;
    cancelDownload.set(false);
    downloadStatus = "Starting download of " + label + "…";
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    new Thread(() -> {
      String err = null;
      try {
        Models.download(url, target, (done, total) -> downloadStatus = "Downloading " + label + ": " + (done >> 20) + " MB"
            + (total > 0 ? " of " + (total >> 20) + " MB (" + done * 100 / total + "%)" : ""), cancelDownload);
      } catch (IOException e) {
        err = e.getMessage();
      }
      String m = err;
      downloadStatus = m == null ? "Downloaded " + label + "." : "Download stopped: " + m + ". Tap Download again to resume.";
      downloading = null;
      runOnUiThread(() -> {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (screen == Screen.MODELS) showModels();
      });
    }).start();
    showModels();
  }

  private void importModel(Uri uri) {
    String name = null;
    try (android.database.Cursor cur = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cur != null && cur.moveToFirst()) name = cur.getString(0);
    } catch (RuntimeException ignored) {
      // Fall back to the path below.
    }
    if (name == null) name = uri.getLastPathSegment();
    if (name == null || !(name.endsWith(".gguf") || name.endsWith(".bin"))) { toast("Choose a .gguf (language) or .bin (Whisper) file"); return; }
    File target = new File(Models.dir(this), name);
    String n = name;
    downloadStatus = "Copying " + n + "…";
    downloading = n;
    showModels();
    new Thread(() -> {
      String err = null;
      File part = new File(target.getPath() + ".part");
      try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new java.io.FileOutputStream(part)) {
        if (in == null) throw new IOException("Can't open the file");
        Store.copy(in, out);
      } catch (IOException e) {
        err = e.getMessage();
      }
      if (err == null && !part.renameTo(target)) err = "couldn't save it";
      if (err != null) part.delete();
      downloadStatus = err == null ? "Imported " + n + "." : "Import failed: " + err;
      downloading = null;
      runOnUiThread(() -> { if (screen == Screen.MODELS) showModels(); });
    }).start();
  }
}
