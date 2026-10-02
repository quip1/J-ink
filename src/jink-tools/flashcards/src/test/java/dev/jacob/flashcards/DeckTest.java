package dev.jacob.flashcards;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class DeckTest {
  @Test public void goodAnswersSpreadOut() {
    Deck.Card c = new Deck.Card("hola", "hello");
    long day = 100;
    int[] expect = {1, 3, 8, 20, 50};
    for (int e : expect) {
      Deck.grade(c, Deck.GOOD, day);
      assertEquals(e, c.interval);
      day = c.due;
    }
  }

  @Test public void forgettingResetsAndLowersEase() {
    Deck.Card c = new Deck.Card("a", "b");
    Deck.grade(c, Deck.GOOD, 10);
    Deck.grade(c, Deck.GOOD, 11);
    Deck.grade(c, Deck.AGAIN, 14);
    assertEquals(0, c.interval);
    assertEquals(14, c.due);
    assertEquals(1, c.lapses);
    assertEquals(2.3, c.ease, 1e-9);
    for (int i = 0; i < 20; i++) Deck.grade(c, Deck.AGAIN, 14);
    assertEquals(1.3, c.ease, 1e-9); // floor
  }

  @Test public void previewMatchesGrading() {
    Deck.Card c = new Deck.Card("a", "b");
    int[] p = Deck.preview(c);
    assertEquals(0, p[Deck.AGAIN]);
    assertEquals(1, p[Deck.GOOD]);
    assertEquals(4, p[Deck.EASY]);
    assertTrue(c.isNew()); // preview doesn't change the card
  }

  @Test public void queueHasDueThenLimitedNew() {
    Deck d = new Deck("Spanish");
    d.newPerDay = 2;
    for (int i = 0; i < 5; i++) d.cards.add(new Deck.Card("q" + i, "a" + i));
    Deck.grade(d.cards.get(0), Deck.GOOD, 9); // due day 10
    Deck.grade(d.cards.get(1), Deck.EASY, 9); // due day 13
    List<Deck.Card> q = d.todaysQueue(10, 0);
    assertEquals(3, q.size());
    assertEquals("q0", q.get(0).front);
    assertTrue(q.get(1).isNew());
    assertEquals(1, d.todaysQueue(10, 1).size() - 1);
    assertEquals(1, d.dueCount(10));
    assertEquals(3, d.newCount());
  }

  @Test public void importCsvAndTsv() {
    List<Deck.Card> csv = Deck.parse("# comment\nbonjour,hello\n\"un, deux\",\"one, \"\"two\"\"\"\nbad line\n");
    assertEquals(2, csv.size());
    assertEquals("un, deux", csv.get(1).front);
    assertEquals("one, \"two\"", csv.get(1).back);
    List<Deck.Card> tsv = Deck.parse("H2O\twater, as you know\r\nNaCl\tsalt\\nline two\n");
    assertEquals("water, as you know", tsv.get(0).back);
    assertEquals("salt\nline two", tsv.get(1).back);
    Deck d = new Deck("x");
    d.cards.addAll(tsv);
    assertEquals(2, Deck.parse(d.toTsv()).size());
    assertEquals("salt\nline two", Deck.parse(d.toTsv()).get(1).back);
  }

  @Test public void spans() {
    assertEquals("<1d", Deck.span(0));
    assertEquals("12d", Deck.span(12));
    assertEquals("3mo", Deck.span(90));
    assertEquals("1.4y", Deck.span(500));
    assertFalse(new Deck.Card("a", "b").front.isEmpty());
  }
}
