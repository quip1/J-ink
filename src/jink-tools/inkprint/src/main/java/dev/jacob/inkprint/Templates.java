package dev.jacob.inkprint;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Calendar;
import java.util.Locale;

/**
 * Draws template pages onto any Canvas measured in points: the on-screen preview, a saved PDF, or
 * a printer page. Ruled templates use real millimetre spacing; form templates scale with the page.
 */
final class Templates {
  private Templates() {}

  enum Kind {
    LINED("Lined"), DOTS("Dot grid"), GRAPH("Graph paper"), CORNELL("Cornell notes"),
    DAILY("Daily to-do"), WEEKLY("Weekly planner"), MONTHLY("Monthly calendar"),
    CHARACTER("D&D character sheet"), SESSION("D&D session log");

    final String label;
    Kind(String label) { this.label = label; }

    /** Templates whose pages advance through dates rather than repeating. */
    boolean dated() { return this == DAILY || this == WEEKLY || this == MONTHLY; }

    /** Templates that use the line-spacing option. */
    boolean ruled() { return this == LINED || this == DOTS || this == GRAPH || this == CORNELL; }

    static String[] labels() {
      Kind[] all = values();
      String[] out = new String[all.length];
      for (int i = 0; i < all.length; i++) out[i] = all[i].label;
      return out;
    }
  }

  static final class Options {
    Kind kind = Kind.LINED;
    Paper paper = Paper.LETTER;
    float spacingMm = 7f;
    boolean dark;
    int pages = 1;
    Calendar start = Calendar.getInstance();
    int firstDayOfWeek = Calendar.getInstance().getFirstDayOfWeek();

    String fileName() {
      String base = kind.label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
      return base + (pages > 1 ? "-" + pages + "p" : "") + ".pdf";
    }
  }

  static final String[] ABILITIES = {"Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma"};
  static final String[][] SKILLS = {
      {"Acrobatics", "Dex"}, {"Animal Handling", "Wis"}, {"Arcana", "Int"}, {"Athletics", "Str"},
      {"Deception", "Cha"}, {"History", "Int"}, {"Insight", "Wis"}, {"Intimidation", "Cha"},
      {"Investigation", "Int"}, {"Medicine", "Wis"}, {"Nature", "Int"}, {"Perception", "Wis"},
      {"Performance", "Cha"}, {"Persuasion", "Cha"}, {"Religion", "Int"}, {"Sleight of Hand", "Dex"},
      {"Stealth", "Dex"}, {"Survival", "Wis"}};

  /** Draws page {@code page} (0-based) of the template into a w×h point area. */
  static void draw(Canvas c, float w, float h, Options o, int page) {
    new Painter(c, w, h, o).draw(page);
  }

  private static final class Painter {
    final Canvas c;
    final float w, h, s, margin;
    final Options o;
    final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    Painter(Canvas c, float w, float h, Options o) {
      this.c = c; this.w = w; this.h = h; this.o = o;
      s = Math.min(w / 612f, h / 792f);
      margin = Math.min(Paper.mm(10), w * 0.06f);
      int ruleColor = o.dark ? 0xFF000000 : 0xFF8C8C8C;
      rule.setStyle(Paint.Style.STROKE);
      rule.setColor(ruleColor);
      rule.setStrokeWidth(o.dark ? 0.6f : 0.5f);
      frame.setStyle(Paint.Style.STROKE);
      frame.setColor(0xFF000000);
      frame.setStrokeWidth(Math.max(0.6f, 1.1f * s));
      dot.setStyle(Paint.Style.FILL);
      dot.setColor(o.dark ? 0xFF000000 : 0xFF6E6E6E);
      bold.setColor(0xFF000000);
      bold.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
      label.setColor(0xFF000000);
      label.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    float L() { return margin; }
    float T() { return margin; }
    float R() { return w - margin; }
    float B() { return h - margin; }

    void draw(int page) {
      switch (o.kind) {
        case LINED: lined(new RectF(L(), T(), R(), B()), true); break;
        case DOTS: dots(); break;
        case GRAPH: graph(); break;
        case CORNELL: cornell(); break;
        case DAILY: daily(Cal.plusDays(o.start, page)); break;
        case WEEKLY: weekly(Cal.plusDays(Cal.weekStart(o.start, o.firstDayOfWeek), 7 * page)); break;
        case MONTHLY: monthly(page); break;
        case CHARACTER: character(); break;
        case SESSION: session(); break;
      }
    }

    // ---- ruled ----

    void lined(RectF r, boolean marginLine) {
      float sp = Paper.mm(o.spacingMm);
      for (float y = r.top + sp * 1.5f; y <= r.bottom + 0.1f; y += sp) c.drawLine(r.left, y, r.right, y, rule);
      if (marginLine && r.width() > Paper.mm(60)) {
        float x = r.left + Paper.mm(18);
        c.drawLine(x, r.top, x, r.bottom, rule);
      }
    }

    void dots() {
      float sp = Paper.mm(Math.max(4f, o.spacingMm - 2f));
      float rad = Math.max(0.55f, sp * 0.045f);
      int cols = (int) ((R() - L()) / sp), rows = (int) ((B() - T()) / sp);
      float x0 = (w - cols * sp) / 2, y0 = (h - rows * sp) / 2;
      for (int i = 0; i <= cols; i++)
        for (int j = 0; j <= rows; j++) c.drawCircle(x0 + i * sp, y0 + j * sp, rad, dot);
    }

    void graph() {
      float sp = Paper.mm(Math.max(4f, o.spacingMm - 2f));
      int cols = (int) ((R() - L()) / sp), rows = (int) ((B() - T()) / sp);
      float x0 = (w - cols * sp) / 2, y0 = (h - rows * sp) / 2;
      Paint major = new Paint(rule);
      major.setStrokeWidth(rule.getStrokeWidth() * 2f);
      for (int i = 0; i <= cols; i++)
        c.drawLine(x0 + i * sp, y0, x0 + i * sp, y0 + rows * sp, i % 5 == 0 ? major : rule);
      for (int j = 0; j <= rows; j++)
        c.drawLine(x0, y0 + j * sp, x0 + cols * sp, y0 + j * sp, j % 5 == 0 ? major : rule);
    }

    void cornell() {
      float titleB = T() + (B() - T()) * 0.08f;
      float summaryT = B() - (B() - T()) * 0.2f;
      float cueR = L() + (R() - L()) * 0.3f;
      c.drawLine(L(), titleB, R(), titleB, frame);
      c.drawLine(L(), summaryT, R(), summaryT, frame);
      c.drawLine(cueR, titleB, cueR, summaryT, frame);
      text("Topic", L(), T() + 9 * s, 8 * s, false);
      text("Date", R() - (R() - L()) * 0.25f, T() + 9 * s, 8 * s, false);
      text("Cues", L(), titleB + 10 * s, 8 * s, false);
      text("Notes", cueR + 4, titleB + 10 * s, 8 * s, false);
      text("Summary", L(), summaryT + 10 * s, 8 * s, false);
      lined(new RectF(cueR + 4, titleB, R(), summaryT), false);
      lined(new RectF(L(), summaryT + 6 * s, R(), B()), false);
    }

    // ---- planners ----

    void daily(Calendar day) {
      String[] wd = Cal.weekdayNames(Calendar.SUNDAY, true);
      String head = wd[day.get(Calendar.DAY_OF_WEEK) - 1] + ", " + Cal.MONTHS[day.get(Calendar.MONTH)] + " "
          + day.get(Calendar.DAY_OF_MONTH) + ", " + day.get(Calendar.YEAR);
      text(head, L(), T() + 20 * s, 20 * s, true);
      float y = T() + 34 * s;
      float avail = B() - y;
      RectF top3 = new RectF(L(), y, R(), y + avail * 0.2f);
      RectF todo = new RectF(L(), top3.bottom + 10 * s, R(), top3.bottom + 10 * s + avail * 0.45f);
      RectF notes = new RectF(L(), todo.bottom + 10 * s, R(), B());
      box(top3, "Top 3");
      rows(top3, 3, true, true);
      box(todo, "To do");
      int n = Math.max(4, (int) ((todo.height() - 14 * s) / Paper.mm(8)));
      rows(todo, n, false, true);
      box(notes, "Notes");
      lined(new RectF(notes.left + 4, notes.top + 8 * s, notes.right - 4, notes.bottom - 4), false);
    }

    void weekly(Calendar first) {
      text("Week of " + Cal.MONTHS[first.get(Calendar.MONTH)] + " " + first.get(Calendar.DAY_OF_MONTH) + ", "
          + first.get(Calendar.YEAR), L(), T() + 20 * s, 20 * s, true);
      String[] names = Cal.weekdayNames(o.firstDayOfWeek, false);
      float top = T() + 32 * s, gap = 6 * s;
      float cw = (R() - L() - gap) / 2, ch = (B() - top - 3 * gap) / 4;
      for (int i = 0; i < 8; i++) {
        float x = L() + (i % 2) * (cw + gap), y = top + (i / 2) * (ch + gap);
        RectF r = new RectF(x, y, x + cw, y + ch);
        String title = i < 7 ? names[i] + " " + Cal.plusDays(first, i).get(Calendar.DAY_OF_MONTH) : "Notes";
        box(r, title);
        lined(new RectF(r.left + 4, r.top + 10 * s, r.right - 4, r.bottom - 4), false);
      }
    }

    void monthly(int page) {
      Calendar m = (Calendar) o.start.clone();
      m.set(Calendar.DAY_OF_MONTH, 1);
      m.add(Calendar.MONTH, page);
      int year = m.get(Calendar.YEAR), month = m.get(Calendar.MONTH);
      text(Cal.MONTHS[month] + " " + year, L(), T() + 24 * s, 24 * s, true);
      String[] names = Cal.weekdayNames(o.firstDayOfWeek, false);
      float top = T() + 36 * s, headH = 14 * s;
      int weeks = Cal.weeks(year, month, o.firstDayOfWeek);
      float cw = (R() - L()) / 7, ch = (B() - top - headH) / weeks;
      for (int i = 0; i < 7; i++) text(names[i], L() + i * cw + 3, top + headH - 4 * s, 9 * s, true);
      float gt = top + headH;
      for (int i = 0; i <= 7; i++) c.drawLine(L() + i * cw, gt, L() + i * cw, gt + weeks * ch, frame);
      for (int j = 0; j <= weeks; j++) c.drawLine(L(), gt + j * ch, R(), gt + j * ch, frame);
      int col = Cal.firstColumn(year, month, o.firstDayOfWeek), days = Cal.daysInMonth(year, month);
      for (int d = 1; d <= days; d++) {
        int cell = col + d - 1;
        text(String.valueOf(d), L() + (cell % 7) * cw + 4 * s, gt + (cell / 7) * ch + 12 * s, 10 * s, false);
      }
    }

    // ---- D&D ----

    void character() {
      float gap = 5 * s;
      float rowH = 30 * s;
      float W = R() - L();
      String[][] rowsTop = {{"Character name", "Class & level", "Species", "Background"},
          {"Alignment", "Experience points", "Proficiency bonus", "Inspiration"}};
      float[] widths = {0.4f, 0.2f, 0.2f, 0.2f};
      for (int r = 0; r < 2; r++) {
        float x = L(), y = T() + r * (rowH + gap);
        for (int i = 0; i < 4; i++) {
          float cw = W * widths[i] - (i < 3 ? gap : 0);
          box(new RectF(x, y, x + cw, y + rowH), rowsTop[r][i]);
          x += cw + gap;
        }
      }
      float bodyTop = T() + 2 * (rowH + gap) + gap;
      float bottomH = (B() - T()) * 0.2f;
      float bodyB = B() - bottomH - gap;
      float bodyH = bodyB - bodyTop;

      // Column 1: ability scores.
      float c1 = W * 0.2f, c2 = W * 0.34f;
      float ah = (bodyH - 5 * gap) / 6;
      for (int i = 0; i < 6; i++) {
        RectF r = new RectF(L(), bodyTop + i * (ah + gap), L() + c1, bodyTop + i * (ah + gap) + ah);
        box(r, ABILITIES[i]);
        float rad = Math.min(r.width(), r.height()) * 0.17f;
        c.drawCircle(r.centerX(), r.bottom - rad - 3 * s, rad, frame);
      }

      // Column 2: saving throws, skills, passive perception.
      float x2 = L() + c1 + gap, r2 = x2 + c2;
      float passiveH = 26 * s;
      float lineH = (bodyH - 2 * gap - passiveH) / (6 + 18 + 3.2f);
      RectF saves = new RectF(x2, bodyTop, r2, bodyTop + lineH * 7.6f);
      box(saves, "Saving throws");
      for (int i = 0; i < 6; i++) checkLine(x2, saves.top + lineH * (1.6f + i), lineH, ABILITIES[i]);
      RectF skills = new RectF(x2, saves.bottom + gap, r2, saves.bottom + gap + lineH * 19.6f);
      box(skills, "Skills");
      for (int i = 0; i < 18; i++)
        checkLine(x2, skills.top + lineH * (1.6f + i), lineH, SKILLS[i][0] + " (" + SKILLS[i][1] + ")");
      box(new RectF(x2, bodyB - passiveH, r2, bodyB), "Passive Perception");

      // Column 3: combat.
      float x3 = r2 + gap, r3 = R(), c3 = r3 - x3;
      float statH = 40 * s, third = (c3 - 2 * gap) / 3;
      String[] stats = {"Armor class", "Initiative", "Speed"};
      for (int i = 0; i < 3; i++) {
        float x = x3 + i * (third + gap);
        box(new RectF(x, bodyTop, x + third, bodyTop + statH), stats[i]);
      }
      float y = bodyTop + statH + gap;
      RectF hp = new RectF(x3, y, r3, y + 70 * s);
      box(hp, "Hit points: max ____   current");
      float tempY = hp.bottom + gap;
      float half = (c3 - gap) / 2;
      box(new RectF(x3, tempY, x3 + half, tempY + 34 * s), "Temp HP");
      RectF hd = new RectF(x3 + half + gap, tempY, r3, tempY + 34 * s);
      box(hd, "Hit dice");
      float dsY = tempY + 34 * s + gap;
      RectF ds = new RectF(x3, dsY, r3, dsY + 30 * s);
      box(ds, "Death saves");
      float rad = 4 * s;
      float cy = ds.bottom - 9 * s;
      float sx = text("Successes", ds.left + 4, cy + 3 * s, 7 * s, false) + 3 * s + rad;
      for (int i = 0; i < 3; i++) c.drawCircle(sx + i * 2.6f * rad, cy, rad, frame);
      float fx = text("Failures", ds.centerX() + 2, cy + 3 * s, 7 * s, false) + 3 * s + rad;
      for (int i = 0; i < 3; i++) c.drawCircle(fx + i * 2.6f * rad, cy, rad, frame);
      float atY = ds.bottom + gap;
      RectF atk = new RectF(x3, atY, r3, atY + 90 * s);
      box(atk, "Attacks");
      float colB = atk.left + c3 * 0.5f, colD = atk.left + c3 * 0.68f;
      text("Name", atk.left + 4, atk.top + 22 * s, 7 * s, false);
      text("Bonus", colB + 2, atk.top + 22 * s, 7 * s, false);
      text("Damage / type", colD + 2, atk.top + 22 * s, 7 * s, false);
      for (int i = 0; i < 5; i++) {
        float ly = atk.top + 24 * s + (i + 1) * (atk.height() - 26 * s) / 5;
        c.drawLine(atk.left + 4, ly, atk.right - 4, ly, rule);
      }
      c.drawLine(colB, atk.top + 14 * s, colB, atk.bottom - 3, rule);
      c.drawLine(colD, atk.top + 14 * s, colD, atk.bottom - 3, rule);
      RectF feats = new RectF(x3, atk.bottom + gap, r3, bodyB);
      box(feats, "Features & traits");
      lined(new RectF(feats.left + 4, feats.top + 6 * s, feats.right - 4, feats.bottom - 3), false);

      // Bottom: equipment and notes.
      float bt = bodyB + gap;
      RectF eq = new RectF(L(), bt, L() + (W - gap) / 2, B());
      RectF notes = new RectF(eq.right + gap, bt, R(), B());
      box(eq, "Equipment & coins");
      lined(new RectF(eq.left + 4, eq.top + 6 * s, eq.right - 4, eq.bottom - 3), false);
      box(notes, "Notes");
      lined(new RectF(notes.left + 4, notes.top + 6 * s, notes.right - 4, notes.bottom - 3), false);
    }

    void session() {
      text("Session log", L(), T() + 22 * s, 22 * s, true);
      float gap = 6 * s, top = T() + 32 * s, rowH = 28 * s, W = R() - L();
      String[] head = {"Session #", "Date", "Players"};
      float[] widths = {0.18f, 0.27f, 0.55f};
      float x = L();
      for (int i = 0; i < 3; i++) {
        float cw = W * widths[i] - (i < 2 ? gap : 0);
        box(new RectF(x, top, x + cw, top + rowH), head[i]);
        x += cw + gap;
      }
      float y = top + rowH + gap;
      float avail = B() - y;
      RectF recap = new RectF(L(), y, R(), y + avail * 0.4f);
      box(recap, "What happened");
      lined(new RectF(recap.left + 4, recap.top + 6 * s, recap.right - 4, recap.bottom - 3), false);
      float y2 = recap.bottom + gap, half = (W - gap) / 2, cellH = (B() - y2 - gap) / 2;
      String[] cells = {"NPCs met", "Places", "Loot & XP", "Quests & hooks"};
      for (int i = 0; i < 4; i++) {
        float cx = L() + (i % 2) * (half + gap), cy = y2 + (i / 2) * (cellH + gap);
        RectF r = new RectF(cx, cy, cx + half, cy + cellH);
        box(r, cells[i]);
        lined(new RectF(r.left + 4, r.top + 6 * s, r.right - 4, r.bottom - 3), false);
      }
    }

    // ---- primitives ----

    void box(RectF r, String title) {
      c.drawRect(r, frame);
      text(title, r.left + 4 * s, r.top + 9 * s, 7.5f * s, false);
    }

    /** Evenly spaced writing rows inside a box, optionally numbered or with checkboxes. */
    void rows(RectF r, int n, boolean numbered, boolean checkbox) {
      float top = r.top + 14 * s, sp = (r.bottom - top) / n;
      for (int i = 1; i <= n; i++) {
        float y = top + i * sp;
        float x = r.left + 6 * s;
        if (numbered) {
          text(i + ".", x, y - sp * 0.25f, Math.min(12 * s, sp * 0.5f), true);
          x += 16 * s;
        } else if (checkbox) {
          float b = Math.min(9 * s, sp * 0.5f);
          c.drawRect(x, y - sp * 0.3f - b, x + b, y - sp * 0.3f, frame);
          x += b + 6 * s;
        }
        c.drawLine(x, y - sp * 0.2f, r.right - 6 * s, y - sp * 0.2f, rule);
      }
    }

    /** One proficiency row: circle, blank for the bonus, then the name. */
    void checkLine(float left, float y, float lineH, String name) {
      float size = Math.min(lineH * 0.62f, 9 * s);
      float rad = size * 0.38f;
      float cy = y + lineH * 0.5f;
      c.drawCircle(left + 5 * s + rad, cy, rad, frame);
      float bx = left + 9 * s + 2 * rad;
      c.drawLine(bx, cy + size * 0.45f, bx + 14 * s, cy + size * 0.45f, rule);
      text(name, bx + 17 * s, cy + size * 0.35f, size, false);
    }

    /** Draws text and returns the x where it ends. */
    float text(String t, float x, float y, float size, boolean heavy) {
      Paint p = heavy ? bold : label;
      p.setTextSize(Math.max(3f, size));
      c.drawText(t, x, y, p);
      return x + p.measureText(t);
    }
  }
}
