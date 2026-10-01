package dev.jacob.solitaire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class KlondikeTest {
  static int card(int suit, int rank) { return suit * 13 + rank; }

  @Test public void dealLayout() {
    Klondike k = Klondike.deal(new Random(1), 1);
    Set<Integer> all = new HashSet<>(k.stock);
    for (int p = 0; p < 7; p++) {
      assertEquals(p + 1, k.tableau[p].size());
      assertEquals(p, k.hidden[p]);
      all.addAll(k.tableau[p]);
    }
    assertEquals(24, k.stock.size());
    assertEquals(52, all.size());
  }

  @Test public void stackingRules() {
    Klondike k = new Klondike();
    k.tableau[0].add(card(0, 7)); // 8 of spades
    assertTrue(k.fitsTableau(card(1, 6), 0)); // red 7 on black 8
    assertFalse(k.fitsTableau(card(3, 6), 0)); // black 7 on black 8
    assertFalse(k.fitsTableau(card(1, 5), 0)); // wrong rank
    assertTrue(k.fitsTableau(card(2, 12), 1)); // king on empty
    assertFalse(k.fitsTableau(card(2, 11), 1)); // queen on empty
  }

  @Test public void foundationsBuildUpBySuit() {
    Klondike k = new Klondike();
    assertEquals(-1, k.foundationFor(card(0, 1)));
    int f = k.foundationFor(card(1, 0));
    assertTrue(f >= 0);
    k.foundations[f].add(card(1, 0));
    assertEquals(f, k.foundationFor(card(1, 1)));
    assertEquals(-1, k.foundationFor(card(2, 1)));
  }

  @Test public void movingARunFlipsTheCardUnderneath() {
    Klondike k = new Klondike();
    // Pile 0: hidden card, then 9 of hearts, 8 of clubs. Pile 1: 10 of spades.
    k.tableau[0].add(card(2, 3));
    k.tableau[0].add(card(1, 8));
    k.tableau[0].add(card(3, 7));
    k.hidden[0] = 1;
    k.tableau[1].add(card(0, 9));
    assertFalse(k.tableauToTableau(0, 0, 1)); // can't move a face-down card
    assertTrue(k.tableauToTableau(0, 1, 1));
    assertEquals(3, k.tableau[1].size());
    assertEquals(1, k.tableau[0].size());
    assertEquals(0, k.hidden[0]); // now face up
  }

  @Test public void drawingAndRecycling() {
    Klondike k = Klondike.deal(new Random(3), 3);
    int top = Klondike.top(k.stock);
    k.draw();
    assertEquals(3, k.waste.size());
    assertEquals(top, k.waste.get(0).intValue());
    while (!k.stock.isEmpty()) k.draw();
    assertEquals(24, k.waste.size());
    k.draw(); // turn the waste over
    assertEquals(24, k.stock.size());
    assertEquals(top, Klondike.top(k.stock));
  }

  @Test public void sweepFinishesASolvedLayout() {
    Klondike k = new Klondike();
    // Every suit stacked King..Ace, one suit per pile, all face up.
    for (int s = 0; s < 4; s++) for (int r = 12; r >= 0; r--) k.tableau[s].add(card(s, r));
    assertTrue(k.canAutoFinish());
    assertEquals(52, k.sweepToFoundations());
    assertTrue(k.won());
  }

  @Test public void copyIsIndependent() {
    Klondike k = Klondike.deal(new Random(9), 1);
    Klondike c = k.copy();
    c.draw();
    List<Integer> w = k.waste;
    assertEquals(0, w.size());
    assertEquals(1, c.waste.size());
  }
}
