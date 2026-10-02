package dev.jacob.lists;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChecklistTest {
  static String order(Checklist c) {
    StringBuilder b = new StringBuilder();
    for (Checklist.Item it : c.items) b.append(it.done ? it.text.toUpperCase() : it.text);
    return b.toString();
  }

  @Test public void doneItemsSinkAndNewOnesStayOnTop() {
    Checklist c = new Checklist("Groceries");
    c.add("a");
    c.add("b");
    c.add("c");
    c.toggle(c.items.get(0)); // a done
    assertEquals("bcA", order(c));
    c.add("d");
    assertEquals("bcdA", order(c));
    c.toggle(c.items.get(3)); // un-tick a
    assertEquals("bcda", order(c));
    assertEquals(4, c.remaining());
  }

  @Test public void movingStaysInsideItsSection() {
    Checklist c = new Checklist("x");
    c.add("a");
    c.add("b");
    c.toggle(c.items.get(1)); // b done -> "aB"
    assertFalse(c.move(c.items.get(0), 1)); // can't move a into the done part
    c.add("c"); // "acB"
    assertTrue(c.move(c.items.get(1), -1));
    assertEquals("caB", order(c));
  }

  @Test public void clearAndReset() {
    Checklist c = new Checklist("x");
    c.add("a");
    c.add("b");
    c.toggle(c.items.get(0));
    c.uncheckAll();
    assertEquals(2, c.remaining());
    c.toggle(c.items.get(0));
    assertEquals(1, c.clearDone());
    assertEquals(1, c.items.size());
    c.add("   ");
    assertEquals(1, c.items.size());
  }

  @Test public void importAndExportText() {
    Checklist c = new Checklist("Trip");
    assertEquals(3, c.addLines("- tent\n[x] map\n\n* stove\n"));
    assertEquals("Trip\n[ ] tent\n[ ] stove\n[x] map\n", c.toText());
  }
}
