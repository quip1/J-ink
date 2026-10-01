package dev.jacob.chess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class EngineTest {
  static String best(String fen, Engine.Level level) {
    Chess c = Chess.fromFen(fen);
    return Chess.uci(new Engine(new Random(1)).bestMove(c, level));
  }

  @Test public void findsBackRankMateInOne() {
    // Rook to e8 is mate.
    assertEquals("e1e8", best("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1", Engine.Level.MEDIUM));
  }

  @Test public void takesAHangingQueen() {
    // The black queen on d5 is undefended.
    assertEquals("d1d5", best("4k3/8/8/3q4/8/8/8/3QK3 w - - 0 1", Engine.Level.EASY));
  }

  @Test public void doesNotWalkIntoAnObviousRecapture() {
    // Taking the pawn on d5 with the queen loses her to the c6 pawn.
    String m = best("4k3/8/2p5/3p4/8/8/8/3QK3 w - - 0 1", Engine.Level.MEDIUM);
    assertTrue(m, !m.equals("d1d5"));
  }

  @Test public void finishesQuicklyAndPlaysLegalMoves() {
    Chess c = Chess.start();
    Engine e = new Engine(new Random(3));
    for (int ply = 0; ply < 16 && !c.legalMoves().isEmpty(); ply++) {
      long t0 = System.currentTimeMillis();
      int m = e.bestMove(c, Engine.Level.HARD);
      assertTrue("took too long", System.currentTimeMillis() - t0 < Engine.Level.HARD.millis + 1500);
      assertTrue(c.legalMoves().contains(m));
      c.make(m);
    }
  }

  @Test public void noMovesMeansMinusOne() {
    Chess mated = Chess.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
    assertEquals(-1, new Engine(new Random()).bestMove(mated, Engine.Level.EASY));
  }
}
