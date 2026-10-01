package dev.jacob.dmgen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class GeneratorsTest {
  @Test public void everyGeneratorProducesText() {
    Generators g = new Generators(new Random(3));
    for (int i = 0; i < 200; i++) {
      for (Generators.Kind k : Generators.Kind.values()) {
        for (Generators.Style s : Generators.Style.values()) {
          Generators.Result r = g.make(k, s);
          assertFalse(k + " title empty", r.title.trim().isEmpty());
          assertFalse(k + " body empty", r.body.trim().isEmpty());
          assertFalse(k + " has 'null'", r.toString().contains("null"));
        }
      }
    }
  }

  @Test public void namesAreCapitalised() {
    Generators g = new Generators(new Random(11));
    for (int i = 0; i < 500; i++) {
      for (Generators.Style s : Generators.Style.values()) {
        String n = g.name(s);
        assertTrue(n, java.lang.Character.isUpperCase(n.charAt(0)));
        assertFalse(n, n.contains("  "));
      }
    }
  }

  @Test public void namesVary() {
    Generators g = new Generators(new Random(5));
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 100; i++) seen.add(g.name(Generators.Style.DWARF));
    assertTrue("only " + seen.size() + " distinct names", seen.size() > 80);
  }

  @Test public void nameListHasTenLines() {
    Generators.Result r = new Generators(new Random(1)).make(Generators.Kind.NAMES, Generators.Style.ELF);
    assertEquals(10, r.body.split("\n").length);
  }

  @Test public void sameSeedSameResult() {
    String a = new Generators(new Random(42)).make(Generators.Kind.NPC, Generators.Style.ANY).toString();
    String b = new Generators(new Random(42)).make(Generators.Kind.NPC, Generators.Style.ANY).toString();
    assertEquals(a, b);
  }
}
