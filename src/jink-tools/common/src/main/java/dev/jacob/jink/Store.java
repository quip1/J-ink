package dev.jacob.jink;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small JSON and text files in the app's private storage, written atomically. */
public final class Store {
  private Store() {}

  public static File file(Context c, String name) { return new File(c.getFilesDir(), name); }

  /** Reads a JSON object, or returns an empty one if the file is missing or unreadable. */
  public static JSONObject readObject(Context c, String name) {
    try { return new JSONObject(readText(file(c, name))); }
    catch (IOException | JSONException e) { return new JSONObject(); }
  }

  public static JSONArray readArray(Context c, String name) {
    try { return new JSONArray(readText(file(c, name))); }
    catch (IOException | JSONException e) { return new JSONArray(); }
  }

  /** Writes a JSONObject or JSONArray. Returns false (and keeps the old file) if the write fails. */
  public static boolean write(Context c, String name, Object json) {
    try { writeText(file(c, name), json.toString()); return true; }
    catch (IOException e) { return false; }
  }

  public static String readText(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) { return readAll(in); }
  }

  /** Writes to a temp file first and renames it, so a crash mid-write never leaves half a file. */
  public static void writeText(File f, String text) throws IOException {
    File dir = f.getParentFile();
    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
    File tmp = new File(f.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (!tmp.renameTo(f)) throw new IOException("Can't replace " + f);
  }

  public static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
  }

  public static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
  }
}
