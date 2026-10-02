package dev.jacob.speak;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads text aloud with the device's text-to-speech voice. The sentence being read is shown in
 * bold, pages turn by themselves, and tapping any sentence starts reading from there.
 */
public class MainActivity extends InkActivity {
  private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

  private SharedPreferences prefs;
  private TextToSpeech tts;
  private boolean ready, playing;
  private String text = "";
  private List<Sentences.Chunk> chunks = new ArrayList<>();
  private List<Integer> pages = new ArrayList<>();
  private int current, shownPage = -1;
  private TextView body, status, playBtn;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("speak", MODE_PRIVATE);
    build();
    try { text = Store.readText(docFile()); } catch (IOException e) { text = ""; }
    current = prefs.getInt("pos", 0);
    if (!handleIntent(getIntent())) setText(text, current);
    tts = new TextToSpeech(this, st -> runOnUiThread(() -> {
      ready = st == TextToSpeech.SUCCESS;
      if (!ready) { status.setText("No text-to-speech voice found. Install one in Android settings."); return; }
      applyVoiceSettings();
      tts.setOnUtteranceProgressListener(new Progress());
      showStatus();
    }));
  }

  @Override protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    handleIntent(i);
  }

  @Override protected void onPause() {
    super.onPause();
    prefs.edit().putInt("pos", current).apply();
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    if (tts != null) { tts.stop(); tts.shutdown(); }
  }

  @Override protected boolean onPageKey(int dir) {
    int p = Math.max(0, Math.min(pages.size() - 1, shownPage + dir));
    if (pages.isEmpty() || p == shownPage) return false;
    showPage(p);
    return true;
  }

  private File docFile() { return Store.file(this, "document.txt"); }

  // ---- layout ----

  @SuppressLint("ClickableViewAccessibility")
  private void build() {
    LinearLayout page = Ui.column(this);
    page.addView(header("Speak", Ui.button(this, "Open", v -> openMenu()), Ui.button(this, "Voice", v -> voiceMenu()),
        refreshButton()), Ui.fill());
    body = Ui.text(this, "");
    body.setTypeface(Typeface.SERIF);
    body.setLineSpacing(0, 1.3f);
    body.setTextSize(Ui.body(this) * 1.05f);
    // Tap a sentence to read from there.
    body.setOnTouchListener((v, e) -> {
      if (e.getAction() != MotionEvent.ACTION_UP || chunks.isEmpty()) return true;
      int off = body.getOffsetForPosition(e.getX(), e.getY()) + pageStartOffset();
      for (int i = 0; i < chunks.size(); i++) {
        if (off < chunks.get(i).end || i == chunks.size() - 1) { jumpTo(i); break; }
      }
      return true;
    });
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
    bp.topMargin = Ui.dp(this, 10);
    page.addView(body, bp);
    status = Ui.muted(this, "");
    Ui.add(page, status, 6);
    TextView back = Ui.button(this, "⏮", v -> jumpTo(Math.max(0, current - 1)));
    playBtn = Ui.button(this, "Play", v -> { if (playing) pause(); else play(); });
    TextView fwd = Ui.button(this, "⏭", v -> jumpTo(Math.min(chunks.size() - 1, current + 1)));
    TextView prevPage = Ui.button(this, "← Page", v -> onPageKey(-1));
    TextView nextPage = Ui.button(this, "Page →", v -> onPageKey(1));
    Ui.add(page, Ui.buttons(this, back, playBtn, fwd), 6);
    Ui.add(page, Ui.buttons(this, prevPage, nextPage), 6);
    setPage(page);
  }

  private void setText(String t, int pos) {
    text = t;
    chunks = Sentences.split(text, 400);
    pages = Sentences.pages(chunks, Ui.large(this) ? 1400 : 650);
    current = chunks.isEmpty() ? 0 : Math.max(0, Math.min(chunks.size() - 1, pos));
    shownPage = -1;
    if (chunks.isEmpty()) body.setText("Nothing to read yet.\n\nTap Open to paste text or open a file, or share text to Speak from any app.");
    else showPage(Sentences.pageOf(pages, current));
    showStatus();
  }

  private int pageStartOffset() {
    return shownPage < 0 || pages.isEmpty() ? 0 : chunks.get(pages.get(shownPage)).start;
  }

  /** Draws one page with the current sentence in bold and underlined. */
  private void showPage(int p) {
    if (pages.isEmpty()) return;
    shownPage = p;
    int first = pages.get(p), last = p + 1 < pages.size() ? pages.get(p + 1) - 1 : chunks.size() - 1;
    int from = chunks.get(first).start, to = chunks.get(last).end;
    SpannableString s = new SpannableString(text.substring(from, to));
    if (current >= first && current <= last) {
      int a = chunks.get(current).start - from, z = chunks.get(current).end - from;
      s.setSpan(new StyleSpan(Typeface.BOLD), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      s.setSpan(new UnderlineSpan(), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    body.setText(s);
    showStatus();
  }

  private void showStatus() {
    if (status == null) return;
    String pos = chunks.isEmpty() ? "" : "Page " + (Math.max(0, shownPage) + 1) + " of " + pages.size() + " · sentence "
        + (current + 1) + " of " + chunks.size();
    status.setText(pos + (ready ? " · " + SPEEDS[speedIndex()] + "×" : ""));
    playBtn.setText(playing ? "Pause" : "Play");
    Ui.setActive(playBtn, playing);
  }

  // ---- playback ----

  private void play() {
    if (!ready || chunks.isEmpty()) return;
    playing = true;
    speak(current, TextToSpeech.QUEUE_FLUSH);
    if (current + 1 < chunks.size()) speak(current + 1, TextToSpeech.QUEUE_ADD);
    showStatus();
  }

  private void pause() {
    playing = false;
    tts.stop();
    prefs.edit().putInt("pos", current).apply();
    showStatus();
  }

  private void jumpTo(int i) {
    if (chunks.isEmpty()) return;
    current = i;
    showPage(Sentences.pageOf(pages, current));
    if (playing) play();
  }

  private void speak(int i, int mode) {
    Sentences.Chunk c = chunks.get(i);
    tts.speak(text.substring(c.start, c.end), mode, null, String.valueOf(i));
  }

  /** Keeps one sentence queued ahead so there's no gap, and moves the highlight as each one starts. */
  private final class Progress extends UtteranceProgressListener {
    @Override public void onStart(String id) {
      int i = Integer.parseInt(id);
      runOnUiThread(() -> {
        if (!playing) return;
        current = i;
        int p = Sentences.pageOf(pages, i);
        showPage(p);
      });
    }

    @Override public void onDone(String id) {
      int i = Integer.parseInt(id);
      runOnUiThread(() -> {
        if (!playing) return;
        if (i + 2 < chunks.size()) speak(i + 2, TextToSpeech.QUEUE_ADD);
        if (i == chunks.size() - 1) { playing = false; current = 0; showStatus(); }
      });
    }

    @SuppressWarnings("deprecation")
    @Override public void onError(String id) {
      runOnUiThread(() -> { playing = false; status.setText("The voice stopped with an error."); showStatus(); });
    }
  }

  // ---- voice settings ----

  private int speedIndex() { return Math.max(0, Math.min(SPEEDS.length - 1, prefs.getInt("speed", 1))); }

  private void applyVoiceSettings() {
    tts.setSpeechRate(SPEEDS[speedIndex()]);
    String voice = prefs.getString("voice", null);
    if (voice != null && tts.getVoices() != null) {
      for (Voice v : tts.getVoices()) if (v.getName().equals(voice)) { tts.setVoice(v); return; }
    }
    tts.setLanguage(Locale.getDefault());
  }

  private void voiceMenu() {
    if (!ready) { toast("Text-to-speech isn't ready"); return; }
    Dialogs.choose(this, "Voice", new String[]{"Speed (" + SPEEDS[speedIndex()] + "×)…", "Choose voice…",
        "Text-to-speech settings…"}, i -> {
      if (i == 0) {
        String[] labels = new String[SPEEDS.length];
        for (int k = 0; k < SPEEDS.length; k++) labels[k] = SPEEDS[k] + "×";
        Dialogs.choose(this, "Speed", labels, k -> { prefs.edit().putInt("speed", k).apply(); restartWith(); });
      } else if (i == 1) {
        List<Voice> voices = new ArrayList<>(tts.getVoices() == null ? Collections.<Voice>emptySet() : tts.getVoices());
        voices.removeIf(v -> v.isNetworkConnectionRequired());
        voices.sort((a, c) -> (a.getLocale().getDisplayName() + a.getName()).compareTo(c.getLocale().getDisplayName() + c.getName()));
        if (voices.isEmpty()) { toast("No offline voices installed"); return; }
        String[] labels = new String[voices.size()];
        for (int k = 0; k < labels.length; k++) labels[k] = voices.get(k).getLocale().getDisplayName() + " — " + voices.get(k).getName();
        Dialogs.choose(this, "Voice", labels, k -> { prefs.edit().putString("voice", voices.get(k).getName()).apply(); restartWith(); });
      } else {
        try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
        catch (RuntimeException e) { toast("Open Settings → Accessibility → Text-to-speech"); }
      }
    });
  }

  private void restartWith() {
    boolean was = playing;
    if (was) tts.stop();
    applyVoiceSettings();
    showStatus();
    if (was) play();
  }

  // ---- opening text ----

  private void openMenu() {
    Dialogs.choose(this, "Read aloud", new String[]{"Paste text…", "Open a file (.txt, .md, .html)…", "Clear"}, i -> {
      if (i == 0) Dialogs.prompt(this, "Paste or type text", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
          t -> load(t, false));
      else if (i == 1) openDocument(new String[]{"text/*"}, this::readUri);
      else load("", false);
    });
  }

  private void readUri(Uri uri) {
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("Can't open");
      String raw = Store.readAll(in);
      String type = getContentResolver().getType(uri);
      boolean html = (type != null && type.contains("html")) || raw.trim().startsWith("<");
      load(html ? Sentences.stripHtml(raw) : raw, false);
    } catch (IOException e) {
      toast("Couldn't open that file");
    }
  }

  private void load(String t, boolean autoplay) {
    if (playing) pause();
    try { Store.writeText(docFile(), t); } catch (IOException e) { toast("Couldn't save the text"); }
    setText(t, 0);
    if (autoplay && ready) play();
  }

  @SuppressWarnings("deprecation")
  private boolean handleIntent(Intent i) {
    if (i == null) return false;
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      String t = i.getStringExtra(Intent.EXTRA_TEXT);
      Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
      setIntent(new Intent());
      if (t != null) { load(t, false); return true; }
      if (stream != null) { readUri(stream); return true; }
    } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
      Uri u = i.getData();
      setIntent(new Intent());
      readUri(u);
      return true;
    }
    return false;
  }
}
