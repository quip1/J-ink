package dev.jacob.scribe;

import android.content.Context;
import dev.jacob.jink.Store;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One recording: a folder holding audio.wav and info.json (title, transcript, summary, questions). */
final class Recording {
  final File dir;
  String title = "";
  long created;
  String language = "";
  final List<TextTools.Segment> segments = new ArrayList<>();
  boolean transcribed;
  String summary = "";
  final List<String[]> answers = new ArrayList<>(); // {question, answer}

  private Recording(File dir) { this.dir = dir; }

  File audio() { return new File(dir, "audio.wav"); }

  long durationMs() { return Wav.durationMs(audio()); }

  /** Synchronized: Whisper adds segments from its own thread while the screen shows them. */
  synchronized String transcript(boolean times) { return TextTools.paragraphs(segments, times); }

  static File root(Context c) {
    File base = c.getExternalFilesDir(null);
    File d = new File(base != null ? base : c.getFilesDir(), "recordings");
    d.mkdirs();
    return d;
  }

  static Recording create(Context c, String title) {
    File d = new File(root(c), System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
    d.mkdirs();
    Recording r = new Recording(d);
    r.title = title;
    r.created = System.currentTimeMillis();
    r.save();
    return r;
  }

  static List<Recording> all(Context c) {
    File[] dirs = root(c).listFiles(File::isDirectory);
    List<Recording> out = new ArrayList<>();
    if (dirs == null) return out;
    Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
    for (File d : dirs) {
      Recording r = load(d);
      if (r != null) out.add(r);
    }
    return out;
  }

  static Recording load(File d) {
    Recording r = new Recording(d);
    try {
      JSONObject o = new JSONObject(Store.readText(new File(d, "info.json")));
      r.title = o.optString("title");
      r.created = o.optLong("created");
      r.language = o.optString("language");
      r.transcribed = o.optBoolean("transcribed");
      r.summary = o.optString("summary");
      JSONArray s = o.optJSONArray("segments");
      for (int i = 0; s != null && i < s.length(); i++) {
        JSONArray x = s.getJSONArray(i);
        r.segments.add(new TextTools.Segment(x.getLong(0), x.getLong(1), x.getString(2)));
      }
      JSONArray q = o.optJSONArray("qa");
      for (int i = 0; q != null && i < q.length(); i++) r.answers.add(new String[]{q.getJSONArray(i).getString(0), q.getJSONArray(i).getString(1)});
      return r;
    } catch (IOException | JSONException e) {
      // A folder without readable info (e.g. a crash mid-create) still shows, so the audio isn't lost.
      if (!r.audio().isFile()) return null;
      r.title = "Recording";
      r.created = d.lastModified();
      return r;
    }
  }

  synchronized void save() {
    try {
      JSONArray s = new JSONArray();
      for (TextTools.Segment x : segments) s.put(new JSONArray().put(x.start).put(x.end).put(x.text));
      JSONArray q = new JSONArray();
      for (String[] a : answers) q.put(new JSONArray().put(a[0]).put(a[1]));
      JSONObject o = new JSONObject().put("title", title).put("created", created).put("language", language)
          .put("transcribed", transcribed).put("summary", summary).put("segments", s).put("qa", q);
      Store.writeText(new File(dir, "info.json"), o.toString());
    } catch (IOException | JSONException ignored) {
      // Next save will try again; the audio file is what matters most.
    }
  }

  void delete() {
    File[] files = dir.listFiles();
    if (files != null) for (File f : files) f.delete();
    dir.delete();
  }
}
