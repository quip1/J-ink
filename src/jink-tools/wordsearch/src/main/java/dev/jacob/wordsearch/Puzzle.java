package dev.jacob.wordsearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Builds a word search grid and checks selections. Plain Java so it can be unit tested. */
final class Puzzle {
  enum Difficulty {
    EASY("Easy", 9, 8, 2), MEDIUM("Medium", 12, 12, 4), HARD("Hard", 15, 16, 8);
    final String label;
    final int size, words, directions;
    Difficulty(String label, int size, int words, int directions) {
      this.label = label; this.size = size; this.words = words; this.directions = directions;
    }
  }

  /** Right, down, then the diagonals, then the backwards directions. Easy uses the first two. */
  static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}, {-1, 0}, {0, -1}, {-1, -1}, {-1, 1}};

  static final String[] THEMES = {"Animals", "Space", "Fantasy", "Food", "Nature", "Ocean", "Music", "Weather"};
  static final String[][] WORDS = {
      {"BADGER", "OTTER", "FALCON", "HEDGEHOG", "BEAVER", "MOOSE", "LYNX", "WALRUS", "PENGUIN", "GIRAFFE", "ZEBRA",
          "CAMEL", "RABBIT", "SQUIRREL", "TORTOISE", "PANTHER", "GORILLA", "KOALA", "LEMUR", "BISON", "FERRET", "RAVEN"},
      {"COMET", "GALAXY", "NEBULA", "ORBIT", "PLANET", "ROCKET", "SATURN", "JUPITER", "METEOR", "ECLIPSE", "QUASAR",
          "PULSAR", "ASTEROID", "COSMOS", "GRAVITY", "LUNAR", "SOLAR", "STARDUST", "TELESCOPE", "MARS", "VENUS"},
      {"DRAGON", "WIZARD", "GOBLIN", "CASTLE", "POTION", "SCROLL", "KNIGHT", "QUEST", "TAVERN", "DUNGEON", "AMULET",
          "GRIFFIN", "PALADIN", "RANGER", "SPELLBOOK", "TREASURE", "TROLL", "SWORD", "SHIELD", "ROGUE", "BARD", "ELF"},
      {"BREAD", "CHEESE", "PASTA", "NOODLE", "PANCAKE", "WAFFLE", "MUFFIN", "CURRY", "TACO", "BURRITO", "PRETZEL",
          "SOUP", "SALAD", "PICKLE", "HONEY", "CARROT", "LEMON", "MANGO", "GRAPE", "PEACH", "OLIVE", "BAGEL"},
      {"FOREST", "MEADOW", "RIVER", "CANYON", "GLACIER", "VOLCANO", "ISLAND", "VALLEY", "DESERT", "TUNDRA", "MARSH",
          "PRAIRIE", "WATERFALL", "BOULDER", "PEBBLE", "MOSS", "FERN", "ACORN", "BIRCH", "WILLOW", "CEDAR", "MAPLE"},
      {"CORAL", "WHALE", "SHARK", "DOLPHIN", "SQUID", "OCTOPUS", "LOBSTER", "STARFISH", "SEAHORSE", "TIDE", "REEF",
          "KELP", "ANCHOR", "HARBOR", "LAGOON", "OYSTER", "PLANKTON", "JELLYFISH", "CURRENT", "WAVE", "SHELL", "CRAB"},
      {"GUITAR", "PIANO", "VIOLIN", "TRUMPET", "FLUTE", "DRUMS", "CELLO", "HARP", "BANJO", "TUBA", "RHYTHM", "MELODY",
          "CHORD", "TEMPO", "OPERA", "CHOIR", "BALLAD", "LYRICS", "CONCERT", "HARMONY", "SCALE", "ENCORE"},
      {"THUNDER", "LIGHTNING", "RAINBOW", "DRIZZLE", "BLIZZARD", "TORNADO", "HAIL", "SLEET", "FOG", "BREEZE", "GALE",
          "CLOUD", "STORM", "SUNNY", "FROST", "MONSOON", "HUMID", "DEW", "MIST", "SNOWFLAKE", "CYCLONE", "THAW"}};

  static final class Placement {
    final String word;
    final int x, y, dx, dy;
    boolean found;
    Placement(String word, int x, int y, int dx, int dy) { this.word = word; this.x = x; this.y = y; this.dx = dx; this.dy = dy; }
    int endX() { return x + dx * (word.length() - 1); }
    int endY() { return y + dy * (word.length() - 1); }
  }

  final int size;
  final char[][] grid;
  final List<Placement> words = new ArrayList<>();

  Puzzle(int size) {
    this.size = size;
    grid = new char[size][size];
  }

  static Puzzle generate(int theme, Difficulty d, Random r) {
    Puzzle p = new Puzzle(d.size);
    List<String> pool = new ArrayList<>();
    for (String w : WORDS[theme]) if (w.length() <= d.size) pool.add(w);
    java.util.Collections.shuffle(pool, r);
    List<String> chosen = new ArrayList<>(pool.subList(0, Math.min(d.words, pool.size())));
    chosen.sort(Comparator.comparingInt(String::length).reversed()); // long words first: they're hardest to fit
    for (String w : chosen) p.place(w, d.directions, r);
    String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    for (char[] row : p.grid) for (int i = 0; i < row.length; i++) if (row[i] == 0) row[i] = abc.charAt(r.nextInt(26));
    p.words.sort(Comparator.comparing(pl -> pl.word));
    return p;
  }

  private boolean place(String w, int directions, Random r) {
    for (int attempt = 0; attempt < 300; attempt++) {
      int[] d = DIRS[r.nextInt(directions)];
      int x = r.nextInt(size), y = r.nextInt(size);
      int ex = x + d[0] * (w.length() - 1), ey = y + d[1] * (w.length() - 1);
      if (ex < 0 || ex >= size || ey < 0 || ey >= size) continue;
      boolean ok = true;
      for (int i = 0; i < w.length() && ok; i++) {
        char g = grid[y + d[1] * i][x + d[0] * i];
        ok = g == 0 || g == w.charAt(i); // crossing words share letters
      }
      if (!ok) continue;
      for (int i = 0; i < w.length(); i++) grid[y + d[1] * i][x + d[0] * i] = w.charAt(i);
      words.add(new Placement(w, x, y, d[0], d[1]));
      return true;
    }
    return false;
  }

  /**
   * Marks and returns the word running between two tapped cells (in either order), or null if the
   * cells don't bound an unfound word.
   */
  Placement select(int x1, int y1, int x2, int y2) {
    for (Placement p : words) {
      if (p.found) continue;
      boolean forward = p.x == x1 && p.y == y1 && p.endX() == x2 && p.endY() == y2;
      boolean backward = p.x == x2 && p.y == y2 && p.endX() == x1 && p.endY() == y1;
      if (forward || backward) { p.found = true; return p; }
    }
    return null;
  }

  boolean done() {
    for (Placement p : words) if (!p.found) return false;
    return true;
  }

  String read(Placement p) {
    char[] out = new char[p.word.length()];
    for (int i = 0; i < out.length; i++) out[i] = grid[p.y + p.dy * i][p.x + p.dx * i];
    return new String(out);
  }

  @Override public String toString() {
    StringBuilder b = new StringBuilder();
    for (char[] row : grid) b.append(new String(row)).append('\n');
    return b.toString() + Arrays.toString(words.stream().map(w -> w.word).toArray());
  }
}
