package dev.jacob.aillama;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * A local language model (any GGUF file llama.cpp supports), run entirely on the device.
 * Not thread-safe: use one instance from one thread at a time.
 */
public final class Llama implements Closeable {
  static {
    System.loadLibrary("llamajni");
  }

  /** Receives generated text a piece at a time. Return false to stop early. */
  public interface Listener {
    boolean onText(String piece);
  }

  /** One chat turn; role is "system", "user" or "assistant". */
  public static final class Message {
    public final String role, content;
    public Message(String role, String content) { this.role = role; this.content = content; }
  }

  public enum Result { FINISHED, STOPPED, LENGTH_LIMIT }

  private long handle;

  private Llama(long handle) { this.handle = handle; }

  /** Loads a model with room for {@code contextTokens} of prompt plus reply. */
  public static Llama load(String path, int contextTokens, int threads) throws IOException {
    long h = nativeLoad(path, contextTokens, threads, false);
    if (h == 0) throw new IOException("Couldn't load the language model (" + path + "). It may be damaged or too new.");
    return new Llama(h);
  }

  /** Loads only the tokenizer, for counting tokens without the memory cost of the weights. */
  public static Llama loadVocabOnly(String path) throws IOException {
    long h = nativeLoad(path, 0, 1, true);
    if (h == 0) throw new IOException("Couldn't read " + path);
    return new Llama(h);
  }

  public int contextSize() { return nativeContextSize(check()); }

  public int countTokens(String text) { return nativeCountTokens(check(), text); }

  /** The conversation in the model's own chat format, ending where the assistant's reply should begin. */
  public String format(List<Message> chat) {
    String[] roles = new String[chat.size()], contents = new String[chat.size()];
    for (int i = 0; i < chat.size(); i++) {
      roles[i] = chat.get(i).role;
      contents[i] = chat.get(i).content;
    }
    String s = nativeFormat(check(), roles, contents);
    return s != null ? s : chatMl(chat);
  }

  /** The widely used ChatML format, for models without a built-in template. */
  static String chatMl(List<Message> chat) {
    StringBuilder b = new StringBuilder();
    for (Message m : chat) b.append("<|im_start|>").append(m.role).append('\n').append(m.content).append("<|im_end|>\n");
    return b.append("<|im_start|>assistant\n").toString();
  }

  /**
   * Generates a reply to {@code prompt} (already formatted, see {@link #format}). Temperature 0
   * picks the most likely word every time; around 0.7 is more natural.
   */
  public Result generate(String prompt, int maxTokens, float temperature, Listener listener) throws IOException {
    int rc = nativeGenerate(check(), prompt, maxTokens, temperature, -1, listener);
    switch (rc) {
      case 0: return Result.FINISHED;
      case 1: return Result.STOPPED;
      case 2: return Result.LENGTH_LIMIT;
      case -2: throw new IOException("The text is too long for the model's memory. Try a shorter piece.");
      default: throw new IOException("The model failed while writing (code " + rc + ")");
    }
  }

  /** Stops a generation in progress, from any thread. */
  public static void cancel() { nativeCancel(); }

  public static String systemInfo() { return nativeSystemInfo(); }

  private long check() {
    if (handle == 0) throw new IllegalStateException("Model closed");
    return handle;
  }

  @Override public void close() {
    if (handle != 0) {
      nativeFree(handle);
      handle = 0;
    }
  }

  private static native long nativeLoad(String path, int nCtx, int threads, boolean vocabOnly);

  private static native void nativeFree(long handle);

  private static native void nativeCancel();

  private static native int nativeContextSize(long handle);

  private static native int nativeCountTokens(long handle, String text);

  private static native String nativeFormat(long handle, String[] roles, String[] contents);

  private static native int nativeGenerate(long handle, String prompt, int maxTokens, float temperature, int seed,
      Listener listener);

  private static native String nativeSystemInfo();
}
