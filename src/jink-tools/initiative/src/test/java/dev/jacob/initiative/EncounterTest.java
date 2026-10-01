package dev.jacob.initiative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class EncounterTest {
  static Encounter.Combatant c(String name, int init, int bonus, boolean player) {
    Encounter.Combatant c = new Encounter.Combatant();
    c.name = name;
    c.initiative = init;
    c.initBonus = bonus;
    c.player = player;
    return c;
  }

  @Test public void sortsByInitiativeThenBonusThenPlayer() {
    Encounter e = new Encounter();
    e.add(c("Goblin", 15, 2, false));
    e.add(c("Aria", 15, 2, true));
    e.add(c("Bree", 18, 0, true));
    e.add(c("Ogre", 15, 4, false));
    assertEquals("Bree", e.list.get(0).name);
    assertEquals("Ogre", e.list.get(1).name);
    assertEquals("Aria", e.list.get(2).name);
    assertEquals("Goblin", e.list.get(3).name);
  }

  @Test public void turnsWrapAndCountRounds() {
    Encounter e = new Encounter();
    e.add(c("A", 20, 0, true));
    e.add(c("B", 10, 0, true));
    e.restart();
    assertEquals("A", e.current().name);
    e.next();
    assertEquals("B", e.current().name);
    e.next();
    assertEquals("A", e.current().name);
    assertEquals(2, e.round);
    e.prev();
    assertEquals("B", e.current().name);
    assertEquals(1, e.round);
    e.prev();
    e.prev(); // can't go before round 1
    assertEquals("A", e.current().name);
    assertEquals(1, e.round);
  }

  @Test public void addingSomeoneKeepsTheCurrentTurn() {
    Encounter e = new Encounter();
    e.add(c("A", 20, 0, true));
    e.add(c("B", 10, 0, true));
    e.restart();
    e.next();
    e.add(c("Fast newcomer", 25, 0, false));
    assertEquals("B", e.current().name);
  }

  @Test public void removingCurrentPassesTurnOn() {
    Encounter e = new Encounter();
    e.add(c("A", 20, 0, true));
    e.add(c("B", 15, 0, true));
    e.add(c("C", 10, 0, true));
    e.restart();
    e.next();
    e.remove(e.current().id);
    assertEquals("C", e.current().name);
    e.remove(e.current().id); // last in order: turn wraps to the top and a new round starts
    assertEquals("A", e.current().name);
    assertEquals(2, e.round);
  }

  @Test public void damageUsesTempHpFirstAndHealCaps() {
    Encounter.Combatant x = c("X", 0, 0, true);
    x.hp = x.maxHp = 20;
    Encounter.giveTemp(x, 5);
    Encounter.giveTemp(x, 3); // doesn't stack
    assertEquals(5, x.tempHp);
    Encounter.damage(x, 8);
    assertEquals(0, x.tempHp);
    assertEquals(17, x.hp);
    Encounter.damage(x, 50);
    assertEquals(0, x.hp);
    assertTrue(x.down());
    Encounter.heal(x, 100);
    assertEquals(20, x.hp);
  }

  @Test public void groupsGetNumberedNamesAndRolledInitiative() {
    Encounter e = new Encounter();
    e.addGroup("Goblin", 3, 2, null, 7, 15, false, new Random(1));
    assertEquals(3, e.list.size());
    for (Encounter.Combatant g : e.list) {
      assertTrue(g.name.matches("Goblin [123]"));
      assertTrue(g.initiative >= 3 && g.initiative <= 22);
      assertEquals(7, g.hp);
    }
    e.add(c("Hero", 30, 0, true));
    e.removeNonPlayers();
    assertEquals(1, e.list.size());
  }
}
