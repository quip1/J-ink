package dev.jacob.chess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Perft counts from the Chess Programming Wiki's standard test positions. */
public class ChessTest {
  static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
  static final String POS3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
  static final String POS4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
  static final String POS5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";

  @Test public void perftStart() {
    Chess c = Chess.start();
    assertEquals(20, c.perft(1));
    assertEquals(400, c.perft(2));
    assertEquals(8902, c.perft(3));
    assertEquals(197281, c.perft(4));
  }

  @Test public void perftKiwipete() {
    Chess c = Chess.fromFen(KIWIPETE);
    assertEquals(48, c.perft(1));
    assertEquals(2039, c.perft(2));
    assertEquals(97862, c.perft(3));
  }

  @Test public void perftEndgameWithEnPassant() {
    Chess c = Chess.fromFen(POS3);
    assertEquals(14, c.perft(1));
    assertEquals(191, c.perft(2));
    assertEquals(2812, c.perft(3));
    assertEquals(43238, c.perft(4));
  }

  @Test public void perftPromotionsAndChecks() {
    assertEquals(6, Chess.fromFen(POS4).perft(1));
    assertEquals(264, Chess.fromFen(POS4).perft(2));
    assertEquals(9467, Chess.fromFen(POS4).perft(3));
    assertEquals(1486, Chess.fromFen(POS5).perft(2));
    assertEquals(62379, Chess.fromFen(POS5).perft(3));
  }

  @Test public void makeUnmakeRestoresTheFen() {
    Chess c = Chess.fromFen(KIWIPETE);
    String before = c.fen();
    for (int m : c.legalMoves()) { c.make(m); c.unmake(); }
    assertEquals(before, c.fen());
  }

  @Test public void fenRoundTrip() {
    assertEquals(Chess.START, Chess.start().fen());
    assertEquals(KIWIPETE, Chess.fromFen(KIWIPETE).fen());
  }

  @Test public void sanNotation() {
    Chess c = Chess.start();
    assertEquals("e4", c.san(c.parseUci("e2e4")));
    assertEquals("Nf3", c.san(c.parseUci("g1f3")));
    Chess k = Chess.fromFen(KIWIPETE);
    assertEquals("O-O", k.san(k.parseUci("e1g1")));
    assertEquals("O-O-O", k.san(k.parseUci("e1c1")));
    assertEquals("Bxa6", k.san(k.parseUci("e2a6")));
    // Both rooks can reach d1, so the a-file rook needs its file named.
    Chess r = Chess.fromFen("4k3/8/8/8/8/8/8/R4RK1 w - - 0 1");
    assertEquals("Rad1", r.san(r.parseUci("a1d1")));
    assertEquals("Rfd1", r.san(r.parseUci("f1d1")));
    // Two rooks on the same file: name the rank instead.
    Chess q = Chess.fromFen("4k3/R7/8/8/8/8/8/R5K1 w - - 0 1");
    assertEquals("R1a4", q.san(q.parseUci("a1a4")));
    // Promotion with mate.
    Chess p = Chess.fromFen("7k/4P3/6K1/8/8/8/8/8 w - - 0 1");
    assertEquals("e8=Q#", p.san(p.parseUci("e7e8q")));
  }

  @Test public void checkmateAndStalemate() {
    Chess fools = Chess.start();
    for (String m : new String[]{"f2f3", "e7e5", "g2g4", "d8h4"}) fools.make(fools.parseUci(m));
    assertTrue(fools.inCheck(Chess.WHITE));
    assertTrue(fools.legalMoves().isEmpty());
    Chess stale = Chess.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
    assertFalse(stale.inCheck(Chess.BLACK));
    assertTrue(stale.legalMoves().isEmpty());
  }

  @Test public void insufficientMaterial() {
    assertTrue(Chess.fromFen("8/8/4k3/8/8/2N5/4K3/8 w - - 0 1").insufficientMaterial());
    assertFalse(Chess.fromFen("8/8/4k3/8/8/2R5/4K3/8 w - - 0 1").insufficientMaterial());
  }
}
