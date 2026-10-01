package dev.jacob.charsheet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CharacterTest {
  @Test public void modifiersRoundDown() {
    assertEquals(-5, Character.modifier(1));
    assertEquals(-1, Character.modifier(9));
    assertEquals(0, Character.modifier(10));
    assertEquals(0, Character.modifier(11));
    assertEquals(3, Character.modifier(16));
    assertEquals(5, Character.modifier(20));
  }

  @Test public void proficiencyByLevel() {
    Character c = new Character();
    int[][] expect = {{1, 2}, {4, 2}, {5, 3}, {8, 3}, {9, 4}, {13, 5}, {17, 6}, {20, 6}};
    for (int[] e : expect) {
      c.level = e[0];
      assertEquals("level " + e[0], e[1], c.proficiency());
    }
  }

  @Test public void skillsSavesAndPassivePerception() {
    Character c = new Character();
    c.level = 5; // +3
    c.scores[4] = 14; // Wis +2
    c.scores[1] = 17; // Dex +3
    c.skillProf[Character.PERCEPTION] = 1;
    c.skillProf[16] = 2; // Stealth expertise
    c.saveProf[1] = true;
    assertEquals(5, c.skillBonus(Character.PERCEPTION));
    assertEquals(15, c.passivePerception());
    assertEquals(9, c.skillBonus(16));
    assertEquals(6, c.saveBonus(1));
    assertEquals(3, c.initiative());
  }

  @Test public void spellcasting() {
    Character c = new Character();
    c.level = 3;
    c.scores[5] = 16;
    assertEquals(0, c.spellSaveDc());
    c.spellAbility = 5;
    assertEquals(13, c.spellSaveDc());
    assertEquals(5, c.spellAttack());
  }

  @Test public void longRestRestoresResources() {
    Character c = new Character();
    c.level = 6;
    c.hitDice = "6d8";
    c.maxHp = 40;
    c.hp = 3;
    c.tempHp = 5;
    c.hitDiceUsed = 5;
    c.slotUsed[0] = 4;
    c.deathFailures = 2;
    c.longRest();
    assertEquals(40, c.hp);
    assertEquals(0, c.tempHp);
    assertEquals(2, c.hitDiceUsed); // regain half of 6
    assertEquals(0, c.slotUsed[0]);
    assertEquals(0, c.deathFailures);
  }

  @Test public void hitDiceTotalParsing() {
    Character c = new Character();
    c.level = 4;
    c.hitDice = "4d10";
    assertEquals(4, c.hitDiceTotal());
    c.hitDice = "whatever";
    assertEquals(4, c.hitDiceTotal());
  }

  @Test public void signedNumbers() {
    assertEquals("+2", Character.signed(2));
    assertEquals("+0", Character.signed(0));
    assertEquals("-1", Character.signed(-1));
  }
}
