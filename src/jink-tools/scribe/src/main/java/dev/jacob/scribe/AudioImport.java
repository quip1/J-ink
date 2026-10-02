package dev.jacob.scribe;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts any audio file Android can decode (m4a, mp3, ogg, wav, a voice memo from another app)
 * into the 16 kHz mono WAV that Scribe transcribes. Streams, so long files don't fill memory.
 */
final class AudioImport {
  private AudioImport() {}

  interface Progress { void update(int percent); }

  static void toWav(Context c, Uri uri, File out, Progress progress) throws IOException {
    MediaExtractor ex = new MediaExtractor();
    MediaCodec codec = null;
    try {
      ex.setDataSource(c, uri, null);
      int track = -1;
      MediaFormat fmt = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
      }
      if (track < 0) throw new IOException("No audio in that file");
      ex.selectTrack(track);
      long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
      codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
      codec.configure(fmt, null, null, 0);
      codec.start();
      int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false, outputDone = false;
      int lastPct = -1;
      try (Wav.Writer w = new Wav.Writer(out, Wav.RATE, 1)) {
        while (!outputDone) {
          if (!inputDone) {
            int in = codec.dequeueInputBuffer(10_000);
            if (in >= 0) {
              ByteBuffer buf = codec.getInputBuffer(in);
              int n = buf == null ? -1 : ex.readSampleData(buf, 0);
              if (n < 0) {
                codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
              } else {
                codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                ex.advance();
              }
            }
          }
          int o = codec.dequeueOutputBuffer(info, 10_000);
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat f = codec.getOutputFormat();
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          } else if (o >= 0) {
            ByteBuffer buf = codec.getOutputBuffer(o);
            if (buf != null && info.size > 0) {
              buf.position(info.offset).limit(info.offset + info.size);
              writeBlock(w, buf.slice().order(ByteOrder.nativeOrder()), info.size, rate, channels);
            }
            codec.releaseOutputBuffer(o, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            if (durationUs > 0) {
              int pct = (int) Math.min(100, info.presentationTimeUs * 100 / durationUs);
              if (pct != lastPct) { lastPct = pct; progress.update(pct); }
            }
          }
        }
      }
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw new IOException("Couldn't decode that audio: " + e.getMessage());
    } finally {
      if (codec != null) {
        try { codec.stop(); } catch (IllegalStateException ignored) { /* not started */ }
        codec.release();
      }
      ex.release();
    }
  }

  /** Mixes a block of 16-bit PCM down to mono and resamples it to 16 kHz. */
  private static void writeBlock(Wav.Writer w, ByteBuffer pcm, int bytes, int rate, int channels) throws IOException {
    int frames = bytes / 2 / channels;
    float[] mono = new float[frames];
    for (int i = 0; i < frames; i++) {
      int sum = 0;
      for (int ch = 0; ch < channels; ch++) sum += pcm.getShort((i * channels + ch) * 2);
      mono[i] = sum / (32768f * channels);
    }
    float[] r = Wav.resample(mono, rate, Wav.RATE);
    short[] s = new short[r.length];
    for (int i = 0; i < r.length; i++) s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(r[i] * 32767)));
    w.write(s, s.length);
  }
}
