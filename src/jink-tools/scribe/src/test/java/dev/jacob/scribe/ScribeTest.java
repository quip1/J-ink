package dev.jacob.scribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScribeTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test public void wavRoundTrip() throws IOException {
    File f = tmp.newFile("a.wav");
    short[] pcm = new short[16000];
    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(i / 10.0) * 16000);
    try (Wav.Writer w = new Wav.Writer(f, 16000, 1)) {
      w.write(pcm, 8000);
      w.write(Arrays.copyOfRange(pcm, 8000, 16000), 8000);
    }
    float[] back = Wav.readMono16k(f);
    assertEquals(16000, back.length);
    assertEquals(pcm[123] / 32768f, back[123], 1e-6);
    assertEquals(1000, Wav.durationMs(f));
  }

  @Test public void readsStereo44kAndUnfinishedFiles() throws IOException {
    File f = tmp.newFile("b.wav");
    short[] pcm = new short[44100 * 2]; // one second of 44.1 kHz stereo
    for (int i = 0; i < pcm.length; i += 2) { pcm[i] = 1000; pcm[i + 1] = 3000; }
    try (Wav.Writer w = new Wav.Writer(f, 44100, 2)) { w.write(pcm, pcm.length); }
    float[] back = Wav.readMono16k(f);
    assertEquals(16000, back.length);
    assertEquals(2000 / 32768f, back[500], 1e-5); // channels averaged
    // A recording interrupted before the header was fixed (sizes still 0) is still readable.
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) { r.seek(40); r.writeInt(0); }
    assertEquals(16000, Wav.readMono16k(f).length);
    try { Wav.readMono16k(tmp.newFile("empty.wav")); fail(); } catch (IOException expected) { /* ok */ }
  }

  @Test public void resampling() {
    float[] in = {0, 1, 2, 3, 4, 5, 6, 7};
    float[] half = Wav.resample(in, 32000, 16000);
    assertEquals(4, half.length);
    assertEquals(2, half[1], 1e-6);
    assertEquals(16, Wav.resample(in, 8000, 16000).length);
  }

  @Test public void paragraphsBreakOnPauses() {
    List<TextTools.Segment> s = Arrays.asList(
        new TextTools.Segment(0, 2000, " Hello there."),
        new TextTools.Segment(2100, 4000, " How are you?"),
        new TextTools.Segment(9000, 11000, " After a pause."),
        new TextTools.Segment(11000, 12000, " [BLANK_AUDIO]"));
    assertEquals("Hello there. How are you?\n\nAfter a pause.", TextTools.paragraphs(s, false));
    assertEquals("[0:00] Hello there. How are you?\n\n[0:09] After a pause.", TextTools.paragraphs(s, true));
    assertEquals("1:02:03", TextTools.clock(3723000));
  }

  @Test public void chunksRespectTheLimit() {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 200; i++) b.append("Sentence number ").append(i).append(" is here. ");
    b.append("\n\n").append("x".repeat(50)).append(' ').append("word ".repeat(100));
    List<String> c = TextTools.chunks(b.toString(), 300);
    assertTrue(c.size() > 10);
    for (String part : c) assertTrue(part.length() + " chars", part.length() <= 300);
    assertTrue(c.get(0).startsWith("Sentence number 0 is here."));
    assertTrue(c.get(0).endsWith("."));
    assertEquals(1, TextTools.chunks("short.", 300).size());
  }

  @Test public void readsWindowsOfLongRecordings() throws IOException {
    File f = tmp.newFile("w.wav");
    short[] pcm = new short[48000];
    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) i;
    try (Wav.Writer w = new Wav.Writer(f, 16000, 1)) { w.write(pcm, pcm.length); }
    assertEquals(48000, Wav.samples(f));
    float[] mid = Wav.readWindow(f, 16000, 16000);
    assertEquals(16000, mid.length);
    assertEquals(16000 / 32768f, mid[0], 1e-6);
    assertEquals(16000, Wav.readWindow(f, 32000, 99999).length); // clipped at the end
    assertEquals(0, Wav.readWindow(f, 48000, 10).length);
  }

  @Test public void picksRelevantChunksInOrder() {
    List<String> chunks = Arrays.asList("We talked about the budget for next year.", "Lunch was pizza.",
        "The budget needs approval from Sam by Friday.", "Weather chat.");
    String r = TextTools.relevant(chunks, "Who approves the budget?", 95);
    assertTrue(r, r.startsWith("We talked about the budget"));
    assertTrue(r.contains("approval from Sam"));
    assertTrue(!r.contains("pizza"));
  }

  @Test public void tidy() {
    assertEquals("Points", TextTools.tidy("  Summary: Points "));
    assertTrue(TextTools.questionPrompt("T", "Q?").contains("Question: Q?"));
  }
}
