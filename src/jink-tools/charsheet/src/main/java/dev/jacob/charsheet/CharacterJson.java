package dev.jacob.charsheet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Converts characters to and from JSON. Kept apart from Character so the rules stay testable on the JVM. */
final class CharacterJson {
  private CharacterJson() {}

  static JSONObject write(Character c) throws JSONException {
    JSONObject o = new JSONObject()
        .put("id", c.id).put("name", c.name).put("class", c.klass).put("species", c.species)
        .put("background", c.background).put("alignment", c.alignment).put("level", c.level).put("xp", c.xp)
        .put("scores", ints(c.scores)).put("skillProf", ints(c.skillProf))
        .put("ac", c.ac).put("speed", c.speed).put("initExtra", c.initExtra)
        .put("hp", c.hp).put("maxHp", c.maxHp).put("tempHp", c.tempHp)
        .put("hitDice", c.hitDice).put("hitDiceUsed", c.hitDiceUsed)
        .put("deathS", c.deathSuccesses).put("deathF", c.deathFailures)
        .put("spellAbility", c.spellAbility).put("slotMax", ints(c.slotMax)).put("slotUsed", ints(c.slotUsed))
        .put("spells", c.spells).put("coins", ints(c.coins)).put("features", c.features).put("notes", c.notes);
    JSONArray saves = new JSONArray();
    for (boolean b : c.saveProf) saves.put(b);
    o.put("saveProf", saves);
    JSONArray atk = new JSONArray();
    for (Character.Attack a : c.attacks)
      atk.put(new JSONObject().put("name", a.name).put("bonus", a.bonus).put("damage", a.damage));
    o.put("attacks", atk);
    JSONArray items = new JSONArray();
    for (Character.Item i : c.items) items.put(new JSONObject().put("name", i.name).put("qty", i.qty));
    o.put("items", items);
    return o;
  }

  static Character read(JSONObject o) {
    Character c = new Character();
    c.id = o.optString("id", c.id);
    c.name = o.optString("name", c.name);
    c.klass = o.optString("class");
    c.species = o.optString("species");
    c.background = o.optString("background");
    c.alignment = o.optString("alignment");
    c.level = o.optInt("level", 1);
    c.xp = o.optInt("xp");
    readInts(o.optJSONArray("scores"), c.scores);
    readInts(o.optJSONArray("skillProf"), c.skillProf);
    JSONArray saves = o.optJSONArray("saveProf");
    for (int i = 0; saves != null && i < Math.min(6, saves.length()); i++) c.saveProf[i] = saves.optBoolean(i);
    c.ac = o.optInt("ac", 10);
    c.speed = o.optInt("speed", 30);
    c.initExtra = o.optInt("initExtra");
    c.hp = o.optInt("hp", 10);
    c.maxHp = o.optInt("maxHp", 10);
    c.tempHp = o.optInt("tempHp");
    c.hitDice = o.optString("hitDice", "1d8");
    c.hitDiceUsed = o.optInt("hitDiceUsed");
    c.deathSuccesses = o.optInt("deathS");
    c.deathFailures = o.optInt("deathF");
    c.spellAbility = o.optInt("spellAbility", -1);
    readInts(o.optJSONArray("slotMax"), c.slotMax);
    readInts(o.optJSONArray("slotUsed"), c.slotUsed);
    c.spells = o.optString("spells");
    readInts(o.optJSONArray("coins"), c.coins);
    c.features = o.optString("features");
    c.notes = o.optString("notes");
    JSONArray atk = o.optJSONArray("attacks");
    for (int i = 0; atk != null && i < atk.length(); i++) {
      JSONObject j = atk.optJSONObject(i);
      if (j == null) continue;
      Character.Attack a = new Character.Attack();
      a.name = j.optString("name");
      a.bonus = j.optString("bonus");
      a.damage = j.optString("damage");
      c.attacks.add(a);
    }
    JSONArray items = o.optJSONArray("items");
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject j = items.optJSONObject(i);
      if (j == null) continue;
      Character.Item it = new Character.Item();
      it.name = j.optString("name");
      it.qty = j.optInt("qty", 1);
      c.items.add(it);
    }
    return c;
  }

  private static JSONArray ints(int[] a) {
    JSONArray j = new JSONArray();
    for (int v : a) j.put(v);
    return j;
  }

  private static void readInts(JSONArray j, int[] into) {
    for (int i = 0; j != null && i < Math.min(j.length(), into.length); i++) into[i] = j.optInt(i, into[i]);
  }
}
