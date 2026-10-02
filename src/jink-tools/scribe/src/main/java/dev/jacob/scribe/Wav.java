package dev.jacob.scribe;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 16-bit PCM WAV files: written while recording, read back as the 16 kHz mono floats Whisper
 * wants. Plain Java so it can be unit tested.
 */
final class Wav {
  private Wav() {}

  static final int RATE = 16000;

  /** Writes PCM as it arrives; {@link #close} fixes up the sizes in the header. */
  static final class Writer implements AutoCloseable {
    private final RandomAccessFile f;
    private long dataBytes;

    Writer(File file, int rate, int channels) throws IOException {
      f = new RandomAccessFile(file, "rw");
      f.setLength(0);
      f.write(header(rate, channels, 0));
    }

    void write(short[] pcm, int n) throws IOException {
      ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < n; i++) b.putShort(pcm[i]);
      f.write(b.array());
      dataBytes += n * 2L;
    }

    long bytes() { return dataBytes; }

    @Override public void close() throws IOException {
      f.seek(4);
      f.write(le32(36 + dataBytes));
      f.seek(40);
      f.write(le32(dataBytes));
      f.close();
    }
  }

  static byte[] header(int rate, int channels, long dataBytes) {
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataBytes)).put(new byte[]{'W', 'A', 'V', 'E'});
    b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) channels)
        .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
    b.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataBytes);
    return b.array();
  }

  private static byte[] le32(long v) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array();
  }

  /** Reads a 16-bit PCM WAV and returns 16 kHz mono samples in [-1, 1]. */
  static float[] readMono16k(File file) throws IOException {
    byte[] all;
    try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
      if (f.length() > Integer.MAX_VALUE) throw new IOException("Recording too long to transcribe in one go");
      all = new byte[(int) f.length()];
      f.readFully(all);
    }
    ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
    if (all.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IOException("Not a WAV file");
    int rate = 0, channels = 0, bits = 0, dataOff = -1, dataLen = 0;
    int pos = 12;
    while (pos + 8 <= all.length) {
      int id = b.getInt(pos), len = b.getInt(pos + 4);
      if (id == 0x20746d66) { // "fmt "
        if (b.getShort(pos + 8) != 1) throw new IOException("Only uncompressed WAV is supported");
        channels = b.getShort(pos + 10);
        rate = b.getInt(pos + 12);
        bits = b.getShort(pos + 22);
      } else if (id == 0x61746164) { // "data"
        dataOff = pos + 8;
        // Recordings cut off before the header was fixed up have a size of 0: use the rest of the file.
        dataLen = len <= 0 || dataOff + (long) len > all.length ? all.length - dataOff : len;
        break;
      }
      pos += 8 + Math.max(0, len) + (len & 1);
    }
    if (dataOff < 0 || bits != 16 || channels < 1 || rate <= 0) throw new IOException("Unsupported WAV format");
    int frames = dataLen / (2 * channels);
    float[] mono = new float[frames];
    for (int i = 0; i < frames; i++) {
      int sum = 0;
      for (int c = 0; c < channels; c++) sum += b.getShort(dataOff + (i * channels + c) * 2);
      mono[i] = sum / (32768f * channels);
    }
    return resample(mono, rate, RATE);
  }

  /** Linear-interpolation resampling. Good enough for speech recognition, which only needs up to 8 kHz. */
  static float[] resample(float[] in, int from, int to) {
    if (from == to || in.length == 0) return in;
    int n = (int) ((long) in.length * to / from);
    float[] out = new float[n];
    double step = (double) from / to;
    for (int i = 0; i < n; i++) {
      double x = i * step;
      int a = (int) x;
      double t = x - a;
      float s0 = in[Math.min(a, in.length - 1)], s1 = in[Math.min(a + 1, in.length - 1)];
      out[i] = (float) (s0 + (s1 - s0) * t);
    }
    return out;
  }

  /**
   * Reads part of a 16 kHz mono 16-bit WAV (the format Scribe records and imports to), so long
   * recordings can be transcribed a window at a time without loading hours of audio at once.
   */
  static float[] readWindow(File wav16kMono, long firstSample, int count) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(wav16kMono, "r")) {
      long total = Math.max(0, (f.length() - 44) / 2);
      if (firstSample >= total) return new float[0];
      int n = (int) Math.min(count, total - firstSample);
      byte[] raw = new byte[n * 2];
      f.seek(44 + firstSample * 2);
      f.readFully(raw);
      ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      float[] out = new float[n];
      for (int i = 0; i < n; i++) out[i] = b.getShort(i * 2) / 32768f;
      return out;
    }
  }

  static long samples(File wav16kMono) { return Math.max(0, (wav16kMono.length() - 44) / 2); }

  static long durationMs(File wav16kMono) {
    return Math.max(0, (wav16kMono.length() - 44) / 2 * 1000 / RATE);
  }
}
