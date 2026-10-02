package dev.jacob.weather;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Weather from Open-Meteo (free, no account). The last forecast is kept, so it still shows
 * offline, with the time it was fetched.
 */
public class MainActivity extends InkActivity {
  private static final long STALE_MS = 30 * 60 * 1000;

  private SharedPreferences prefs;
  private final List<Forecast.Place> places = new ArrayList<>();
  private int selected;
  private LinearLayout body;
  private TextView placeBtn, updated;
  private boolean loading;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("weather", MODE_PRIVATE);
    loadPlaces();
    selected = Math.max(0, Math.min(places.size() - 1, prefs.getInt("selected", 0)));
    LinearLayout page = Ui.column(this);
    placeBtn = Ui.button(this, "", v -> placesMenu());
    page.addView(header("Weather", Ui.button(this, "Update", v -> fetch()), refreshButton()), Ui.fill());
    Ui.add(page, placeBtn, 8);
    updated = Ui.muted(this, "");
    updated.setGravity(Gravity.CENTER);
    Ui.add(page, updated, 4);
    body = Ui.column(this);
    page.addView(Ui.scroll(this, body), new LinearLayout.LayoutParams(-1, 0, 1));
    setPage(page);
    show();
  }

  @Override protected void onResume() {
    super.onResume();
    if (!places.isEmpty() && System.currentTimeMillis() - cachedAt() > STALE_MS) fetch();
  }

  private Forecast.Place place() { return places.isEmpty() ? null : places.get(selected); }

  private String cacheName() {
    Forecast.Place p = place();
    return String.format(Locale.US, "fc_%.3f_%.3f_%b.json", p.lat, p.lon, imperial());
  }

  private long cachedAt() {
    return place() == null ? 0 : Store.readObject(this, cacheName()).optLong("_fetched", 0);
  }

  private boolean imperial() { return prefs.getBoolean("imperial", Locale.getDefault().getCountry().equals("US")); }

  // ---- display ----

  private void show() {
    body.removeAllViews();
    Forecast.Place p = place();
    placeBtn.setText(p == null ? "Choose a place…" : p.label() + "  ▾");
    if (p == null) {
      Ui.add(body, Ui.text(this, "Tap “Choose a place” and search for your town."), 20);
      updated.setText("");
      return;
    }
    JSONObject cached = Store.readObject(this, cacheName());
    long at = cached.optLong("_fetched", 0);
    updated.setText(loading ? "Updating…" : at == 0 ? "Not fetched yet"
        : "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at))
        + (System.currentTimeMillis() - at > 6 * 3600_000 ? " on " + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(at)) : ""));
    if (at == 0) return;
    Forecast f;
    try {
      f = Forecast.parse(cached, LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 16));
    } catch (JSONException e) {
      Ui.add(body, Ui.text(this, "The saved forecast couldn't be read. Tap Update."), 20);
      return;
    }

    // Now
    LinearLayout now = Ui.row(this);
    TextView sym = Ui.text(this, Forecast.symbol(f.code, f.day), 3.2f);
    now.addView(sym);
    LinearLayout nowText = Ui.column(this);
    TextView temp = Ui.text(this, Forecast.round(f.temp) + f.tempUnit, 3.2f);
    temp.setTypeface(Typeface.DEFAULT_BOLD);
    nowText.addView(temp);
    nowText.addView(Ui.text(this, Forecast.describe(f.code)));
    LinearLayout.LayoutParams np = Ui.weight(1);
    np.leftMargin = Ui.dp(this, 16);
    now.addView(nowText, np);
    Ui.add(body, now, 12);
    Ui.add(body, Ui.text(this, "Feels like " + Forecast.round(f.feelsLike) + f.tempUnit + " · Wind "
        + Forecast.round(f.wind) + " " + f.windUnit + " · Humidity " + f.humidity + "%"), 6);
    if (!f.days.isEmpty() && f.days.get(0).sunrise.length() >= 16) {
      Ui.add(body, Ui.muted(this, "Sunrise " + f.days.get(0).sunrise.substring(11) + " · Sunset "
          + f.days.get(0).sunset.substring(11)), 4);
    }

    // Next hours
    Ui.add(body, Ui.title(this, "Next hours"), 18);
    int every = Ui.large(this) ? 1 : 2;
    int shown = 0;
    LinearLayout hours = Ui.row(this);
    for (int i = 0; i < f.hours.size() && shown < 8; i += every, shown++) {
      Forecast.Hour h = f.hours.get(i);
      LinearLayout col = Ui.column(this);
      col.setGravity(Gravity.CENTER_HORIZONTAL);
      for (String line : new String[]{h.time.substring(11, 13) + "h", Forecast.symbol(h.code, true),
          Forecast.round(h.temp) + "°", h.rainChance + "%"}) {
        TextView t = Ui.text(this, line, 0.9f);
        t.setGravity(Gravity.CENTER);
        col.addView(t, Ui.fill());
      }
      hours.addView(col, Ui.weight(1));
    }
    Ui.add(body, hours, 6);
    Ui.add(body, Ui.muted(this, "Hour · sky · temperature · chance of rain"), 2);

    // Week
    Ui.add(body, Ui.title(this, "This week"), 18);
    DateTimeFormatter dayName = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault());
    for (Forecast.Day d : f.days) {
      LinearLayout r = Ui.row(this);
      int pad = Ui.dp(this, 6);
      r.setPadding(0, pad, 0, pad);
      LocalDate date = LocalDate.parse(d.date);
      TextView name = Ui.text(this, date.equals(LocalDate.now()) ? "Today" : date.format(dayName));
      name.setMinWidth(Ui.dp(this, 80));
      name.setTypeface(Typeface.DEFAULT_BOLD);
      r.addView(name);
      r.addView(Ui.text(this, Forecast.symbol(d.code, true) + "  " + Forecast.describe(d.code)), Ui.weight(1));
      r.addView(Ui.text(this, Forecast.round(d.max) + "° / " + Forecast.round(d.min) + "°  " + d.rainChance + "%"));
      body.addView(r, Ui.fill());
      body.addView(Ui.rule(this));
    }
    Ui.add(body, Ui.muted(this, "Weather data by Open-Meteo.com"), 14);
  }

  // ---- network ----

  private void fetch() {
    Forecast.Place p = place();
    if (p == null || loading) return;
    loading = true;
    show();
    String url = Forecast.forecastUrl(p.lat, p.lon, imperial());
    String cache = cacheName();
    new Thread(() -> {
      String err = null;
      try {
        JSONObject o = new JSONObject(get(url));
        o.put("_fetched", System.currentTimeMillis());
        Store.write(this, cache, o);
      } catch (IOException | JSONException e) {
        err = e.getMessage();
      }
      String m = err;
      runOnUiThread(() -> {
        loading = false;
        if (m != null) toast("Couldn't update: " + m + (cachedAt() > 0 ? ". Showing the last forecast." : ""));
        show();
      });
    }).start();
  }

  static String get(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setRequestProperty("User-Agent", "J-ink Weather");
    try {
      int code = c.getResponseCode();
      if (code != 200) throw new IOException("server said " + code);
      try (InputStream in = c.getInputStream()) { return Store.readAll(in); }
    } finally {
      c.disconnect();
    }
  }

  // ---- places ----

  private void placesMenu() {
    List<String> items = new ArrayList<>();
    for (Forecast.Place p : places) items.add(p.label());
    items.add("+ Add a place…");
    if (!places.isEmpty()) items.add("Remove this place");
    items.add(imperial() ? "Use °C and km/h" : "Use °F and mph");
    Dialogs.choose(this, "Places", items.toArray(new String[0]), i -> {
      if (i < places.size()) {
        selected = i;
        savePlaces();
        show();
        if (System.currentTimeMillis() - cachedAt() > STALE_MS) fetch();
        return;
      }
      String choice = items.get(i);
      if (choice.startsWith("+")) search();
      else if (choice.startsWith("Remove")) {
        places.remove(selected);
        selected = 0;
        savePlaces();
        show();
      } else {
        prefs.edit().putBoolean("imperial", !imperial()).apply();
        show();
        if (cachedAt() == 0) fetch();
      }
    });
  }

  private void search() {
    Dialogs.prompt(this, "Town or city", "", q -> {
      if (q.trim().isEmpty()) return;
      toast("Searching…");
      new Thread(() -> {
        List<Forecast.Place> found = null;
        String err = null;
        try { found = Forecast.parsePlaces(new JSONObject(get(Forecast.searchUrl(q)))); }
        catch (IOException | JSONException e) { err = e.getMessage(); }
        List<Forecast.Place> f = found;
        String m = err;
        runOnUiThread(() -> {
          if (m != null) { toast("Search failed: " + m); return; }
          if (f.isEmpty()) { toast("No places called “" + q.trim() + "”"); return; }
          String[] labels = new String[f.size()];
          for (int k = 0; k < labels.length; k++) labels[k] = f.get(k).label();
          Dialogs.choose(this, "Which one?", labels, k -> {
            places.add(f.get(k));
            selected = places.size() - 1;
            savePlaces();
            show();
            fetch();
          });
        });
      }).start();
    });
  }

  private void loadPlaces() {
    JSONArray a = Store.readArray(this, "places.json");
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Forecast.Place p = new Forecast.Place();
      p.name = o.optString("name");
      p.region = o.optString("region");
      p.country = o.optString("country");
      p.lat = o.optDouble("lat");
      p.lon = o.optDouble("lon");
      places.add(p);
    }
  }

  private void savePlaces() {
    JSONArray a = new JSONArray();
    try {
      for (Forecast.Place p : places)
        a.put(new JSONObject().put("name", p.name).put("region", p.region).put("country", p.country).put("lat", p.lat).put("lon", p.lon));
    } catch (JSONException e) {
      return;
    }
    Store.write(this, "places.json", a);
    prefs.edit().putInt("selected", selected).apply();
  }
}
