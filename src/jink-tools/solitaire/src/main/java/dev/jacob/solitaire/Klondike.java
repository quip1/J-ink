package dev.jacob.solitaire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Klondike rules. Cards are ints: suit * 13 + rank, with rank 0 = Ace ... 12 = King and suits
 * 0 spades, 1 hearts, 2 diamonds, 3 clubs. Plain Java so it can be unit tested.
 */
final class Klondike {
  static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
  /** Black suits are solid, red suits are outlines, so they're told apart without colour. */
  static final String[] SUITS = {"♠", "♡", "♢", "♣"};

  static int suit(int card) { return card / 13; }

  static int rank(int card) { return card % 13; }

  static boolean red(int card) { return suit(card) == 1 || suit(card) == 2; }

  static String name(int card) { return RANKS[rank(card)] + SUITS[suit(card)]; }

  final List<Integer> stock = new ArrayList<>(), waste = new ArrayList<>();
  @SuppressWarnings("unchecked")
  final List<Integer>[] foundations = new List[4], tableau = new List[7];
  /** How many cards at the bottom of each tableau pile are still face down. */
  final int[] hidden = new int[7];
  int drawCount = 1;
  int moves;

  Klondike() {
    for (int i = 0; i < 4; i++) foundations[i] = new ArrayList<>();
    for (int i = 0; i < 7; i++) tableau[i] = new ArrayList<>();
  }

  static Klondike deal(Random r, int drawCount) {
    Klondike k = new Klondike();
    k.drawCount = drawCount;
    List<Integer> deck = new ArrayList<>();
    for (int i = 0; i < 52; i++) deck.add(i);
    Collections.shuffle(deck, r);
    int n = 0;
    for (int p = 0; p < 7; p++) {
      for (int i = 0; i <= p; i++) k.tableau[p].add(deck.get(n++));
      k.hidden[p] = p;
    }
    while (n < 52) k.stock.add(deck.get(n++));
    return k;
  }

  Klondike copy() {
    Klondike k = new Klondike();
    k.stock.addAll(stock);
    k.waste.addAll(waste);
    for (int i = 0; i < 4; i++) k.foundations[i].addAll(foundations[i]);
    for (int i = 0; i < 7; i++) k.tableau[i].addAll(tableau[i]);
    System.arraycopy(hidden, 0, k.hidden, 0, 7);
    k.drawCount = drawCount;
    k.moves = moves;
    return k;
  }

  static int top(List<Integer> pile) { return pile.isEmpty() ? -1 : pile.get(pile.size() - 1); }

  // ---- rules ----

  /** Draws from the stock, or turns the waste back over when the stock is empty. */
  boolean draw() {
    if (stock.isEmpty()) {
      if (waste.isEmpty()) return false;
      for (int i = waste.size() - 1; i >= 0; i--) stock.add(waste.get(i));
      waste.clear();
    } else {
      for (int i = 0; i < drawCount && !stock.isEmpty(); i++) waste.add(stock.remove(stock.size() - 1));
    }
    moves++;
    return true;
  }

  /** Can {@code card} go on top of tableau pile {@code t}? */
  boolean fitsTableau(int card, int t) {
    int top = top(tableau[t]);
    if (top < 0) return rank(card) == 12;
    return red(top) != red(card) && rank(top) == rank(card) + 1;
  }

  /** Which foundation {@code card} can go on, or -1. */
  int foundationFor(int card) {
    for (int f = 0; f < 4; f++) {
      int top = top(foundations[f]);
      if (top < 0 ? rank(card) == 0 : suit(top) == suit(card) && rank(top) + 1 == rank(card)) return f;
    }
    return -1;
  }

  /** Index of the first face-up card in tableau pile {@code t}. */
  int firstUp(int t) { return hidden[t]; }

  // ---- moves; each returns false (and changes nothing) if illegal ----

  boolean wasteToTableau(int t) {
    int c = top(waste);
    if (c < 0 || !fitsTableau(c, t)) return false;
    tableau[t].add(waste.remove(waste.size() - 1));
    moves++;
    return true;
  }

  boolean wasteToFoundation() {
    int c = top(waste);
    int f = c < 0 ? -1 : foundationFor(c);
    if (f < 0) return false;
    foundations[f].add(waste.remove(waste.size() - 1));
    moves++;
    return true;
  }

  /** Moves the run starting at {@code index} of pile {@code from} onto pile {@code to}. */
  boolean tableauToTableau(int from, int index, int to) {
    if (from == to || index < firstUp(from) || index >= tableau[from].size()) return false;
    if (!fitsTableau(tableau[from].get(index), to)) return false;
    List<Integer> run = tableau[from].subList(index, tableau[from].size());
    tableau[to].addAll(run);
    run.clear();
    flip(from);
    moves++;
    return true;
  }

  boolean tableauToFoundation(int from) {
    int c = top(tableau[from]);
    if (c < 0 || tableau[from].size() <= firstUp(from)) return false;
    int f = foundationFor(c);
    if (f < 0) return false;
    foundations[f].add(tableau[from].remove(tableau[from].size() - 1));
    flip(from);
    moves++;
    return true;
  }

  boolean foundationToTableau(int f, int t) {
    int c = top(foundations[f]);
    if (c < 0 || !fitsTableau(c, t)) return false;
    tableau[t].add(foundations[f].remove(foundations[f].size() - 1));
    moves++;
    return true;
  }

  /** Turns the new top card face up after cards leave a pile. */
  private void flip(int t) {
    if (hidden[t] > 0 && hidden[t] >= tableau[t].size()) hidden[t] = tableau[t].size() - 1;
    if (hidden[t] < 0) hidden[t] = 0;
  }

  boolean won() {
    for (List<Integer> f : foundations) if (f.size() != 13) return false;
    return true;
  }

  /** Everything is face up and dealt, so the rest is just moving cards home. */
  boolean canAutoFinish() {
    if (!stock.isEmpty() || !waste.isEmpty()) return false;
    for (int h : hidden) if (h > 0) return false;
    return !won();
  }

  /** Plays every card it can onto the foundations. Returns how many moved. */
  int sweepToFoundations() {
    int n = 0;
    boolean moved = true;
    while (moved) {
      moved = false;
      if (wasteToFoundation()) { n++; moved = true; }
      for (int t = 0; t < 7; t++) if (tableauToFoundation(t)) { n++; moved = true; }
    }
    return n;
  }
}
