package dev.jacob.initiative;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Turn order, rounds and hit points for one fight. Plain Java so it can be unit tested. */
final class Encounter {
  static final String[] CONDITIONS = {"Blinded", "Charmed", "Concentrating", "Deafened", "Exhaustion",
      "Frightened", "Grappled", "Incapacitated", "Invisible", "Paralyzed", "Petrified", "Poisoned", "Prone",
      "Restrained", "Stunned", "Unconscious"};

  static final class Combatant {
    String id = UUID.randomUUID().toString();
    String name = "";
    int initiative, initBonus;
    int hp, maxHp, tempHp, ac;
    boolean player;
    final Set<String> conditions = new LinkedHashSet<>();

    boolean down() { return maxHp > 0 && hp <= 0; }
  }

  final List<Combatant> list = new ArrayList<>();
  int round = 1;
  /** Id of the combatant whose turn it is, so re-sorting never loses our place. */
  String currentId;

  /** Highest initiative first; ties go to the higher bonus, then players, then name. */
  static final Comparator<Combatant> ORDER = (a, b) -> {
    if (a.initiative != b.initiative) return Integer.compare(b.initiative, a.initiative);
    if (a.initBonus != b.initBonus) return Integer.compare(b.initBonus, a.initBonus);
    if (a.player != b.player) return a.player ? -1 : 1;
    return a.name.compareToIgnoreCase(b.name);
  };

  void sort() {
    list.sort(ORDER);
    if (currentId == null && !list.isEmpty()) currentId = list.get(0).id;
  }

  int turnIndex() {
    for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(currentId)) return i;
    return list.isEmpty() ? -1 : 0;
  }

  Combatant current() {
    int i = turnIndex();
    return i < 0 ? null : list.get(i);
  }

  void add(Combatant c) {
    list.add(c);
    sort();
  }

  /**
   * Adds {@code count} copies named "Goblin 1", "Goblin 2"... Each rolls its own initiative
   * (d20 + bonus) unless {@code fixedInit} is given.
   */
  List<Combatant> addGroup(String name, int count, int bonus, Integer fixedInit, int hp, int ac, boolean player,
      Random rng) {
    List<Combatant> made = new ArrayList<>();
    for (int i = 1; i <= Math.max(1, count); i++) {
      Combatant c = new Combatant();
      c.name = count > 1 ? name + " " + i : name;
      c.initBonus = bonus;
      c.initiative = fixedInit != null ? fixedInit : 1 + rng.nextInt(20) + bonus;
      c.hp = c.maxHp = Math.max(0, hp);
      c.ac = ac;
      c.player = player;
      list.add(c);
      made.add(c);
    }
    sort();
    return made;
  }

  void remove(String id) {
    int i = indexOf(id);
    if (i < 0) return;
    if (id.equals(currentId)) {
      // The turn passes to whoever was next.
      currentId = list.size() > 1 ? list.get((i + 1) % list.size()).id : null;
      if (i == list.size() - 1 && list.size() > 1) round++;
    }
    list.remove(i);
  }

  void next() {
    if (list.isEmpty()) return;
    int i = turnIndex() + 1;
    if (i >= list.size()) { i = 0; round++; }
    currentId = list.get(i).id;
  }

  void prev() {
    if (list.isEmpty()) return;
    int i = turnIndex() - 1;
    if (i < 0) {
      if (round == 1) return;
      round--;
      i = list.size() - 1;
    }
    currentId = list.get(i).id;
  }

  /** Damage comes off temporary HP first. HP never goes below 0. */
  static void damage(Combatant c, int amount) {
    if (amount <= 0) return;
    int fromTemp = Math.min(c.tempHp, amount);
    c.tempHp -= fromTemp;
    c.hp = Math.max(0, c.hp - (amount - fromTemp));
  }

  /** Healing never goes above max HP (when a max is set). */
  static void heal(Combatant c, int amount) {
    if (amount <= 0) return;
    c.hp = c.maxHp > 0 ? Math.min(c.maxHp, c.hp + amount) : c.hp + amount;
  }

  /** Temporary HP doesn't stack: you keep the higher of the old and new amounts. */
  static void giveTemp(Combatant c, int amount) { c.tempHp = Math.max(c.tempHp, amount); }

  void restart() {
    round = 1;
    currentId = list.isEmpty() ? null : list.get(0).id;
  }

  void removeNonPlayers() {
    list.removeIf(c -> !c.player);
    restart();
  }

  int indexOf(String id) {
    for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
    return -1;
  }

  Combatant find(String id) {
    int i = indexOf(id);
    return i < 0 ? null : list.get(i);
  }
}
