package dev.jacob.inkprint;

/** Page sizes for saved PDFs, in PostScript points (1/72 inch). */
enum Paper {
  LETTER("US Letter", 612, 792),
  A4("A4", 595, 842),
  A5("A5", 420, 595),
  // Screen-shaped pages, so a template fills the display edge to edge with no letterboxing.
  NOTE_AIR("Note Air screen (3:4)", 558, 744),
  PALMA("Palma screen (1:2)", 216, 432);

  final String label;
  final int width, height;

  Paper(String label, int width, int height) {
    this.label = label;
    this.width = width;
    this.height = height;
  }

  static final float PT_PER_MM = 72f / 25.4f;

  static float mm(float mm) { return mm * PT_PER_MM; }

  static String[] labels() {
    Paper[] all = values();
    String[] out = new String[all.length];
    for (int i = 0; i < all.length; i++) out[i] = all[i].label;
    return out;
  }
}
