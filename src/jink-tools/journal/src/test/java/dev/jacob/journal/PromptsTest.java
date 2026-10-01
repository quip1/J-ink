package dev.jacob.journal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class PromptsTest {
  @Test public void samePromptAllDayDifferentTomorrow() {
    LocalDate d = LocalDate.of(2026, 10, 1);
    assertEquals(Prompts.forDate(d), Prompts.forDate(LocalDate.of(2026, 10, 1)));
    assertNotEquals(Prompts.forDate(d), Prompts.forDate(d.plusDays(1)));
  }

  @Test public void aMonthOfPromptsIsVaried() {
    Set<Integer> seen = new HashSet<>();
    LocalDate d = LocalDate.of(2026, 1, 1);
    for (int i = 0; i < 31; i++) seen.add(Prompts.indexFor(d.plusDays(i)));
    assertEquals(31, seen.size());
  }

  @Test public void worksForDatesBeforeTheEpoch() {
    int i = Prompts.indexFor(LocalDate.of(1960, 5, 5));
    assertTrue(i >= 0 && i < Prompts.ALL.length);
  }

  @Test public void snippets() {
    String text = "Went to the market early.\nBought plums and talked to the beekeeper about winter.";
    assertEquals("…plums and talked to the beekeeper about winter.", Prompts.snippet(text, "BEEKEEPER"));
    assertNull(Prompts.snippet(text, "dragon"));
    assertNull(Prompts.snippet(text, "   "));
    assertTrue(Prompts.snippet(text, "went").startsWith("Went to the market"));
  }
}
