package dev.jacob.typewriter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WordsTest {
  @Test public void counting() {
    assertEquals(0, Words.count(""));
    assertEquals(0, Words.count("  \n\t "));
    assertEquals(2, Words.count("Hello world"));
    assertEquals(4, Words.count("It's a well-known fact."));
    assertEquals(3, Words.count("one\ntwo\n\nthree"));
    assertEquals(2, Words.count("— dash — words"));
    assertEquals(3, Words.count("Chapter 12: begin"));
    assertEquals(1, Words.count("don’t"));
    assertEquals(2, Words.count("trailing- hyphen"));
  }

  @Test public void titles() {
    assertEquals("My Story", Words.title("\n\n# My Story\nOnce upon a time"));
    assertEquals("Untitled", Words.title("   \n  "));
    assertEquals(58, Words.title("x".repeat(100)).length());
  }

  @Test public void readingTime() {
    assertEquals(0, Words.readingMinutes(0));
    assertEquals(1, Words.readingMinutes(1));
    assertEquals(2, Words.readingMinutes(231));
  }
}
