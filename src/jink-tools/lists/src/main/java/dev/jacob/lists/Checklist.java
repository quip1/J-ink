package dev.jacob.lists;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One list of items. Plain Java so it can be unit tested. */
final class Checklist {
  static final class Item {
    String text;
    boolean done;
    Item(String text, boolean done) { this.text = text; this.done = done; }
  }

  String id = UUID.randomUUID().toString();
  String name;
  final List<Item> items = new ArrayList<>();

  Checklist(String name) { this.name = name; }

  /** New items go above the first finished one, so the to-do part stays together at the top. */
  void add(String text) {
    String t = text.trim();
    if (t.isEmpty()) return;
    int at = 0;
    while (at < items.size() && !items.get(at).done) at++;
    items.add(at, new Item(t, false));
  }

  /** Ticking sinks an item to the top of the done section; unticking floats it to the bottom of the to-do part. */
  void toggle(Item it) {
    items.remove(it);
    it.done = !it.done;
    int at = 0;
    while (at < items.size() && !items.get(at).done) at++;
    items.add(at, it);
  }

  /** Moves an item one place up or down (dir -1 / +1), staying within its own section. */
  boolean move(Item it, int dir) {
    int i = items.indexOf(it), j = i + dir;
    if (i < 0 || j < 0 || j >= items.size() || items.get(j).done != it.done) return false;
    items.set(i, items.get(j));
    items.set(j, it);
    return true;
  }

  int clearDone() {
    int before = items.size();
    items.removeIf(it -> it.done);
    return before - items.size();
  }

  /** Unticks everything, for reusable lists like packing or groceries. */
  void uncheckAll() { for (Item it : items) it.done = false; }

  int remaining() {
    int n = 0;
    for (Item it : items) if (!it.done) n++;
    return n;
  }

  /** Plain-text export, one item per line with [ ] or [x]. */
  String toText() {
    StringBuilder b = new StringBuilder(name).append('\n');
    for (Item it : items) b.append(it.done ? "[x] " : "[ ] ").append(it.text).append('\n');
    return b.toString();
  }

  /** Adds one item per non-empty line; lines starting with [x] / [ ] / - / * keep their state. */
  int addLines(String text) {
    int n = 0;
    for (String line : text.split("\n")) {
      String l = line.trim();
      boolean done = false;
      if (l.startsWith("[x]") || l.startsWith("[X]")) { done = true; l = l.substring(3).trim(); }
      else if (l.startsWith("[ ]")) l = l.substring(3).trim();
      else if (l.startsWith("- ") || l.startsWith("* ")) l = l.substring(2).trim();
      if (l.isEmpty()) continue;
      if (done) items.add(new Item(l, true)); else add(l);
      n++;
    }
    return n;
  }
}
