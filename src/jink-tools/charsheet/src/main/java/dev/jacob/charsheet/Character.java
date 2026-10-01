package dev.jacob.charsheet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One character and the 5e arithmetic around it. Plain Java so it can be unit tested. */
final class Character {
  static final String[] ABILITIES = {"Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma"};
  static final String[] ABBR = {"Str", "Dex", "Con", "Int", "Wis", "Cha"};
  static final String[] SKILLS = {"Acrobatics", "Animal Handling", "Arcana", "Athletics", "Deception", "History",
      "Insight", "Intimidation", "Investigation", "Medicine", "Nature", "Perception", "Performance", "Persuasion",
      "Religion", "Sleight of Hand", "Stealth", "Survival"};
  static final int[] SKILL_ABILITY = {1, 4, 3, 0, 5, 3, 4, 5, 3, 4, 3, 4, 5, 5, 3, 1, 1, 4};
  static final int PERCEPTION = 11;
  static final String[] COINS = {"cp", "sp", "ep", "gp", "pp"};

  static final class Attack {
    String name = "", bonus = "", damage = "";
  }

  static final class Item {
    String name = "";
    int qty = 1;
  }

  String id = UUID.randomUUID().toString();
  String name = "New character", klass = "", species = "", background = "", alignment = "";
  int level = 1, xp;
  final int[] scores = {10, 10, 10, 10, 10, 10};
  final boolean[] saveProf = new boolean[6];
  /** 0 = not proficient, 1 = proficient, 2 = expertise (double proficiency). */
  final int[] skillProf = new int[SKILLS.length];
  int ac = 10, speed = 30, initExtra;
  int hp = 10, maxHp = 10, tempHp;
  String hitDice = "1d8";
  int hitDiceUsed, deathSuccesses, deathFailures;
  final List<Attack> attacks = new ArrayList<>();
  /** Spellcasting ability index, or -1 for none. */
  int spellAbility = -1;
  final int[] slotMax = new int[9], slotUsed = new int[9];
  String spells = "";
  final List<Item> items = new ArrayList<>();
  final int[] coins = new int[COINS.length];
  String features = "", notes = "";

  static int modifier(int score) { return Math.floorDiv(score - 10, 2); }

  static String signed(int n) { return n >= 0 ? "+" + n : String.valueOf(n); }

  int mod(int ability) { return modifier(scores[ability]); }

  int proficiency() { return 2 + (Math.max(1, Math.min(20, level)) - 1) / 4; }

  int saveBonus(int ability) { return mod(ability) + (saveProf[ability] ? proficiency() : 0); }

  int skillBonus(int skill) { return mod(SKILL_ABILITY[skill]) + skillProf[skill] * proficiency(); }

  int passivePerception() { return 10 + skillBonus(PERCEPTION); }

  int initiative() { return mod(1) + initExtra; }

  int spellSaveDc() { return spellAbility < 0 ? 0 : 8 + proficiency() + mod(spellAbility); }

  int spellAttack() { return spellAbility < 0 ? 0 : proficiency() + mod(spellAbility); }

  /** Total hit dice, read from the number before the "d" in e.g. "5d10". Defaults to the level. */
  int hitDiceTotal() {
    String s = hitDice.trim().toLowerCase();
    int d = s.indexOf('d');
    if (d > 0) {
      try { return Integer.parseInt(s.substring(0, d).trim()); } catch (NumberFormatException ignored) { /* below */ }
    }
    return level;
  }

  /** Long rest: full HP, no temp HP, spell slots back, and up to half your hit dice (minimum 1) regained. */
  void longRest() {
    hp = maxHp;
    tempHp = 0;
    java.util.Arrays.fill(slotUsed, 0);
    hitDiceUsed = Math.max(0, hitDiceUsed - Math.max(1, hitDiceTotal() / 2));
    deathSuccesses = deathFailures = 0;
  }

  static int xpForNextLevel(int level) {
    int[] table = {0, 300, 900, 2700, 6500, 14000, 23000, 34000, 48000, 64000, 85000, 100000, 120000, 140000,
        165000, 195000, 225000, 265000, 305000, 355000};
    return level >= 20 ? -1 : table[Math.max(1, level)];
  }
}
