package dev.jacob.dmgen;

import java.util.Random;

/**
 * Random tables for a DM improvising at the table. All tables are original. Plain Java so it can
 * be unit tested.
 */
final class Generators {
  enum Kind { NPC("NPC"), TAVERN("Tavern"), LOOT("Loot"), HOOK("Plot hook"), TOWN("Town"), WEATHER("Weather"),
    NAMES("Names");
    final String label;
    Kind(String label) { this.label = label; }
  }

  enum Style { ANY("Any"), HUMAN("Human"), ELF("Elf"), DWARF("Dwarf"), HALFLING("Halfling"), ORC("Orc");
    final String label;
    Style(String label) { this.label = label; }
  }

  static final class Result {
    final String title, body;
    Result(String title, String body) { this.title = title; this.body = body; }
    @Override public String toString() { return title + "\n" + body; }
  }

  private final Random r;

  Generators(Random r) { this.r = r; }

  Result make(Kind k, Style s) {
    switch (k) {
      case NPC: return npc(s);
      case TAVERN: return tavern();
      case LOOT: return loot();
      case HOOK: return hook();
      case TOWN: return town();
      case WEATHER: return weather();
      default: return names(s);
    }
  }

  // ---- names ----

  private static final String[][] HUMAN = {
      {"Al", "Bran", "Cor", "Da", "El", "Fen", "Gar", "Hal", "Is", "Jor", "Ka", "Le", "Mar", "Ned", "Os", "Per", "Ro",
          "Sa", "Tam", "Wil", "Ver", "Bea", "Mae", "Ty"},
      {"a", "an", "en", "ric", "in", "ett", "wyn", "ia", "on", "as", "el", "ard", "ine", "ley", "ham", "dra", "rus"}};
  private static final String[][] HUMAN_LAST = {
      {"Ash", "Black", "Bright", "Copper", "Fair", "Hawk", "Iron", "Mill", "Oak", "Raven", "Stone", "Thorn", "White",
          "Wood", "Marsh", "Salt", "Kettle"},
      {"ford", "wood", "well", "more", "field", "brook", "ridge", "smith", "wright", "ton", "by", "gate", "worth"}};
  private static final String[][] ELF = {
      {"Ae", "Cael", "Ela", "Fae", "Gal", "Ith", "Lia", "Mira", "Nae", "Syl", "Tha", "Vae", "Ylla", "Ser", "Ori"},
      {"", "", "la", "ri", "the", "na", "li", "ve"},
      {"ndil", "riel", "thas", "wyn", "lian", "sil", "nor", "viel", "ra", "mir", "las", "wen"}};
  private static final String[][] ELF_LAST = {
      {"Moon", "Star", "Silver", "Dawn", "Leaf", "Mist", "Wind", "Song"},
      {"whisper", "bough", "petal", "brook", "glade", "veil", "shade", "thread"}};
  private static final String[][] DWARF = {
      {"Bar", "Dur", "Grim", "Thor", "Bal", "Dag", "Kil", "Mor", "Brom", "Har", "Rur", "Tor", "Gun", "Hel", "Vist"},
      {"in", "ek", "grim", "dal", "rin", "um", "ar", "ok", "dra", "hild", "li", "na", "ra"}};
  private static final String[][] DWARF_LAST = {
      {"Anvil", "Coal", "Deep", "Flint", "Granite", "Hammer", "Iron", "Rune", "Stout", "Bronze"},
      {"beard", "delver", "fist", "forge", "helm", "mantle", "shield", "vein", "hewer"}};
  private static final String[][] HALFLING = {
      {"Bil", "Cora", "Mer", "Pip", "Ros", "Tob", "Lil", "Wel", "Fen", "Pol", "Sam", "Dai", "Hob", "Mil"},
      {"bo", "ry", "rin", "pin", "ie", "wise", "ly", "dy", "o", "sy", "ble"}};
  private static final String[][] HALFLING_LAST = {
      {"Under", "Tea", "Green", "Thistle", "Brandy", "Honey", "Good", "Tall", "Apple", "Burrow"},
      {"hill", "bottle", "leaf", "down", "foot", "barrel", "fellow", "wick", "crumb", "kettle"}};
  private static final String[][] ORC = {
      {"Gru", "Mok", "Ur", "Thra", "Kar", "Dur", "Zug", "Bra", "Og", "Sha", "Vor", "Yag"},
      {"sh", "gak", "nak", "rg", "k", "ka", "gul", "zar", "mash", "th", "ra"}};
  private static final String[] ORC_EPITHET = {"the Unbroken", "Bone-Singer", "of the Red Ash", "Three-Scars",
      "the Patient", "Ironjaw", "Who Laughs", "of the Long Walk"};

  String name(Style s) {
    if (s == Style.ANY) s = Style.values()[1 + r.nextInt(Style.values().length - 1)];
    switch (s) {
      case ELF: return cap(pick(ELF[0]) + pick(ELF[1]) + pick(ELF[2])) + " " + pick(ELF_LAST[0]) + pick(ELF_LAST[1]);
      case DWARF: return cap(pick(DWARF[0]) + pick(DWARF[1])) + " " + pick(DWARF_LAST[0]) + pick(DWARF_LAST[1]);
      case HALFLING: return cap(pick(HALFLING[0]) + pick(HALFLING[1])) + " " + pick(HALFLING_LAST[0])
          + pick(HALFLING_LAST[1]);
      case ORC: return cap(pick(ORC[0]) + pick(ORC[1])) + (r.nextBoolean() ? " " + pick(ORC_EPITHET) : "");
      default: return cap(pick(HUMAN[0]) + pick(HUMAN[1])) + " " + pick(HUMAN_LAST[0]) + pick(HUMAN_LAST[1]);
    }
  }

  private Result names(Style s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 10; i++) b.append(i == 0 ? "" : "\n").append(name(s));
    return new Result(s.label + " names", b.toString());
  }

  // ---- NPC ----

  private static final String[] JOBS = {"blacksmith", "ferry operator", "retired soldier", "herbalist", "tax collector",
      "street preacher", "cartographer", "rat catcher", "minstrel", "candle maker", "bounty hunter", "temple acolyte",
      "smuggler", "beekeeper", "scribe", "gravedigger", "horse trader", "bridge toll keeper", "lamplighter",
      "fortune teller", "shipwright", "falconer", "apothecary", "pawnbroker", "town crier"};
  private static final String[] LOOKS = {"a nose broken more than once", "ink-stained fingers", "a braid tied with copper wire",
      "one glove, always", "a burn scar across one cheek", "spotless, expensive boots", "a hat far too big",
      "freckles and a gap-toothed grin", "eyes of two different colours", "a missing finger they never mention",
      "a cloak patched with a dozen fabrics", "a voice much deeper than expected", "a tattoo of a fish on the neck",
      "silver rings on every finger", "a limp that comes and goes"};
  private static final String[] TRAITS = {"warm but nosy", "nervous and over-polite", "blunt to the point of rude",
      "cheerful no matter what", "suspicious of every stranger", "proud and easily flattered", "tired of everything",
      "endlessly curious", "a terrible liar", "very religious", "calculating and patient", "loud and generous",
      "deeply superstitious", "quietly brave", "competitive about everything"};
  private static final String[] QUIRKS = {"hums while thinking", "counts coins twice", "never sits with their back to a door",
      "quotes a proverb nobody has heard of", "names their tools", "always eating something", "speaks in the third person",
      "taps the table three times before deciding", "collects buttons", "laughs at their own jokes before the punchline",
      "won't say the name of the river", "keeps a pet beetle in a box"};
  private static final String[] WANTS = {"to pay off a debt to the wrong people", "to find a missing sibling",
      "a quiet life, finally", "revenge on a former business partner", "to see the sea once", "respect from their guild",
      "to get their stolen heirloom back", "a cure for a sick parent", "to leave town without anyone noticing",
      "to prove a rival is a fraud", "enough coin to open their own shop", "to be forgiven"};
  private static final String[] SECRETS = {"is informing for the local thieves", "can't actually read",
      "is a noble in hiding", "buried something under the mill", "saw the murder and said nothing",
      "is slowly being replaced by a doppelganger", "owes money to everyone in town", "worships a forgotten god",
      "is far older than they look", "forged the letter that started the feud", "has no secret at all, and that's suspicious"};

  private Result npc(Style s) {
    String name = name(s);
    String body = "A " + pick(JOBS) + " with " + pick(LOOKS) + ".\n"
        + "Personality: " + pick(TRAITS) + ".\n"
        + "Quirk: " + pick(QUIRKS) + ".\n"
        + "Wants: " + pick(WANTS) + ".\n"
        + "Secret: " + pick(SECRETS) + ".";
    return new Result(name, body);
  }

  // ---- tavern ----

  private static final String[] T_ADJ = {"Laughing", "Drowned", "Crooked", "Golden", "Sleepy", "Rusty", "Wandering",
      "Prancing", "Hollow", "Merry", "Salted", "Lucky", "Broken", "Velvet", "Muddy", "Third"};
  private static final String[] T_NOUN = {"Goose", "Lantern", "Barrel", "Anchor", "Dragon", "Fiddle", "Badger",
      "Kettle", "Owl", "Crown", "Boot", "Mermaid", "Pig", "Hound", "Wheel", "Stag"};
  private static final String[] SPECIALS = {"onion soup that could wake the dead", "black bread with honey butter",
      "spiced pear cider", "a stew nobody admits the recipe for", "smoked eel pies", "a stout brewed with chicory",
      "roast chestnuts by the bucket", "plum brandy that glows faintly", "mushroom pasties", "the cheapest ale in the city"};
  private static final String[] VIBES = {"loud, crowded and friendly", "quiet; everyone speaks in whispers",
      "a sailors' haunt full of songs", "fancy, with prices to match", "half-empty and a little sad",
      "full of off-duty guards", "smoky, with a dice game in the back", "run like a family kitchen",
      "decorated with trophies from a famous adventurer", "recently burned and half rebuilt"};
  private static final String[] RUMORS = {"lights have been seen in the old watchtower again",
      "the miller's daughter came back from the woods speaking a strange language",
      "someone is paying gold for live frogs, no questions asked", "the bridge toll doubled overnight and nobody knows why",
      "a merchant caravan is three days overdue", "the well water tastes of iron since the earthquake",
      "the baron hasn't been seen in a month", "a stranger has been buying every map of the hills",
      "the temple bell rang by itself at midnight", "wolves were seen walking on their hind legs"};

  private Result tavern() {
    String n = r.nextInt(3) == 0 ? "The " + pick(T_NOUN) + " & " + pick(T_NOUN) : "The " + pick(T_ADJ) + " " + pick(T_NOUN);
    String body = "Atmosphere: " + pick(VIBES) + ".\n"
        + "Run by " + name(Style.ANY) + ", " + pick(TRAITS) + ".\n"
        + "Known for " + pick(SPECIALS) + ".\n"
        + "Rumor: " + pick(RUMORS) + ".";
    return new Result(n, body);
  }

  // ---- loot ----

  private static final String[] TRINKETS = {"a tin whistle that only dogs can't hear", "a pressed flower in a glass locket",
      "a die with two sixes", "a map of a city that doesn't exist", "a tiny brass key on a red ribbon",
      "a letter sealed with black wax, never opened", "a carved wooden fox", "a spoon engraved with a stranger's name",
      "a jar of buttons from many uniforms", "a compass that points at the nearest bakery", "a child's drawing of a dragon",
      "a ring too small for any finger", "a deck of cards missing all the queens", "a smooth stone that is always warm",
      "half of a torn treasure map"};
  private static final String[] VALUABLES = {"a silver goblet (25 gp)", "a garnet the size of a thumbnail (50 gp)",
      "an ivory comb (15 gp)", "a jade frog figurine (40 gp)", "a gold-thread scarf (30 gp)", "a pearl earring (60 gp)",
      "an amber pendant with a trapped insect (75 gp)", "a painted miniature portrait (20 gp)",
      "a silver holy symbol (25 gp)", "a set of crystal dice (35 gp)"};
  private static final String[] MAGIC = {"potion of healing", "a candle that burns without heat",
      "a cloak that keeps its wearer dry", "a rope that knots itself on command", "a quill that never runs out of ink",
      "boots that leave no footprints in snow", "a coin that always lands on the side you call (once a day)",
      "a lantern that shows invisible ink", "a ring that tells you if your drink is poisoned",
      "a whetstone that makes the next hit sing", "a bag that is slightly bigger inside", "a scroll of a 1st-level spell"};

  private Result loot() {
    int tier = r.nextInt(3);
    String[] tierName = {"Pocket loot", "Small hoard", "Big hoard"};
    StringBuilder b = new StringBuilder();
    if (tier == 0) b.append(dice(3, 6)).append(" sp, ").append(dice(2, 6)).append(" cp");
    else if (tier == 1) b.append(dice(4, 6) * 10).append(" gp, ").append(dice(6, 6) * 10).append(" sp");
    else b.append(dice(2, 6) * 100).append(" gp, ").append(dice(3, 6) * 10).append(" pp");
    b.append("\n").append(cap(pick(TRINKETS)));
    for (int i = 0; i < tier; i++) b.append("\n").append(cap(pick(VALUABLES)));
    if (r.nextInt(4) < tier + 1) b.append("\nMagic: ").append(pick(MAGIC));
    return new Result(tierName[tier], b.toString());
  }

  // ---- plot hooks ----

  private static final String[] PATRONS = {"A desperate innkeeper", "The town's oldest priest", "A nervous noble's steward",
      "A child with a handful of copper", "A retired adventurer", "The harbourmaster", "A travelling scholar",
      "A ghost only one party member can see", "The local thieves' guild", "A talking crow"};
  private static final String[] TASKS = {"recover a stolen relic", "escort a sealed wagon to the next town",
      "find out who is poisoning the wells", "clear the old mine of whatever moved in", "deliver a letter to a hermit",
      "rescue a captured mapmaker", "win a rigged contest at the fair", "track a beast that leaves no footprints",
      "spy on a secret meeting at the lighthouse", "bring back a flower that only blooms in a haunted grove"};
  private static final String[] REASONS = {"the festival is in three days", "someone powerful is blackmailing them",
      "a prophecy said strangers would help", "the guard has been bribed", "it belonged to their late partner",
      "the river will flood soon", "nobody else will go near it", "they owe a debt to a fey creature"};
  private static final String[] TWISTS = {"the patron is lying about why", "a rival party wants the same thing",
      "the 'monster' is protecting something", "the reward is counterfeit", "the target wants to be found",
      "it's a test set by a secret order", "the map is upside down", "the job is a distraction for a bigger crime"};

  private Result hook() {
    String body = pick(PATRONS) + " needs the party to " + pick(TASKS) + ", because " + pick(REASONS)
        + ".\n\nThe catch: " + pick(TWISTS) + ".";
    return new Result("Plot hook", body);
  }

  // ---- town ----

  private static final String[] TOWN_A = {"Ash", "Bram", "Cold", "Elder", "Fox", "Gull", "Hollow", "Kings", "Mire", "Oak",
      "Raven", "Salt", "Thorn", "Wey", "Wolf", "Stag"};
  private static final String[] TOWN_B = {"ford", "haven", "bury", "wick", "mere", "stead", "cross", "dale", "port",
      "gate", "hollow", "barrow"};
  private static final String[] SIZES = {"hamlet of about 60 people", "village of about 300 people",
      "market town of about 2,000", "walled town of about 6,000", "river city of about 20,000"};
  private static final String[] FEATURES = {"a giant statue with its face chiselled off", "a market that only opens at night",
      "houses built on stilts over a marsh", "a cathedral far too big for the town", "a famous cheese",
      "a wall made of old shields", "a lake that freezes even in summer", "a ruined wizard's tower on the hill",
      "a bridge older than anyone can explain", "a yearly festival of masks"};
  private static final String[] PROBLEMS = {"two families are close to open feud", "the harvest is rotting in the fields",
      "people keep sleepwalking into the forest", "a new tax has everyone angry", "bandits control the only road",
      "the mayor was replaced overnight and no one questions it", "a plague of very clever rats",
      "the dead won't stay buried", "a dragon has asked politely for rent"};
  private static final String[] LEADERS = {"an elected mayor who means well", "a stern abbess", "a council of guild masters",
      "a young lord who inherited too early", "a retired general", "a merchant prince", "nobody, since last month"};

  private Result town() {
    String body = "A " + pick(SIZES) + ".\nKnown for " + pick(FEATURES) + ".\nLed by " + pick(LEADERS)
        + ".\nTrouble: " + pick(PROBLEMS) + ".";
    return new Result(pick(TOWN_A) + pick(TOWN_B), body);
  }

  // ---- weather ----

  private static final String[] SKIES = {"Clear and bright", "Thin high cloud", "Heavy grey overcast", "Drizzle",
      "Steady rain", "Thunderstorm in the afternoon", "Thick fog until midday", "Light snow", "Hail showers",
      "Hazy and still"};
  private static final String[] TEMPS = {"freezing", "cold", "cool", "mild", "warm", "hot", "sweltering"};
  private static final String[] WINDS = {"no wind", "a light breeze", "gusty wind", "a strong wind", "a howling gale"};
  private static final String[] EFFECTS = {"No effect on travel.", "Tracks are easy to follow.",
      "Visibility is short: disadvantage on sight-based Perception at range.", "Roads are muddy: travel is slower.",
      "Ranged attacks at long range are harder in the wind.", "Fires are hard to start.",
      "A beautiful sunset; spirits are high.", "Sound carries strangely tonight."};

  private Result weather() {
    return new Result("Today's weather", pick(SKIES) + ", " + pick(TEMPS) + ", with " + pick(WINDS) + ".\n" + pick(EFFECTS));
  }

  // ---- helpers ----

  private String pick(String[] a) { return a[r.nextInt(a.length)]; }

  private int dice(int n, int sides) {
    int t = 0;
    for (int i = 0; i < n; i++) t += 1 + r.nextInt(sides);
    return t;
  }

  static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
