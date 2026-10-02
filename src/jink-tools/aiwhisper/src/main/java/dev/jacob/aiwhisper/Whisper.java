package dev.jacob.aiwhisper;

import java.io.Closeable;
import java.io.IOException;

/**
 * Speech-to-text with whisper.cpp, entirely on the device. Load a ggml model once, then
 * {@link #transcribe} 16 kHz mono audio. Not thread-safe: use one instance per thread.
 */
public final class Whisper implements Closeable {
  static {
    System.loadLibrary("whisperjni");
  }

  /** Receives progress (0-100) and each new piece of text as it's recognised. Called on the transcribing thread. */
  public interface Listener {
    void onProgress(int percent);

    /** Times are in milliseconds from the start of the audio. */
    void onSegment(long startMs, long endMs, String text);
  }

  private long handle;

  private Whisper(long handle) { this.handle = handle; }

  public static Whisper load(String modelPath) throws IOException {
    long h = nativeInit(modelPath);
    if (h == 0) throw new IOException("Couldn't load the speech model (" + modelPath + ")");
    return new Whisper(h);
  }

  /**
   * Transcribes the samples. {@code language} is a code like "en" or "auto" to detect it.
   * Returns false if it was cancelled.
   */
  public boolean transcribe(float[] samples16k, String language, boolean translateToEnglish, int threads,
      String prompt, Listener listener) throws IOException {
    if (handle == 0) throw new IOException("Model already closed");
    int rc = nativeTranscribe(handle, samples16k, language, translateToEnglish, threads, prompt, listener);
    if (rc == 1) return false;
    if (rc != 0) throw new IOException("Transcription failed (code " + rc + ")");
    return true;
  }

  /** Language of the last transcription, e.g. "en". */
  public String language() { return handle == 0 ? "" : nativeLanguage(handle); }

  /** Stops a transcription in progress, from any thread. */
  public static void cancel() { nativeCancel(); }

  /** Which CPU features the native code is using (NEON, dot product...). */
  public static String systemInfo() { return nativeSystemInfo(); }

  @Override public void close() {
    if (handle != 0) {
      nativeFree(handle);
      handle = 0;
    }
  }

  private static native long nativeInit(String path);

  private static native void nativeFree(long handle);

  private static native void nativeCancel();

  private static native int nativeTranscribe(long handle, float[] samples, String language, boolean translate,
      int threads, String prompt, Listener listener);

  private static native String nativeLanguage(long handle);

  private static native String nativeSystemInfo();
}
