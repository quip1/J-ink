package dev.jacob.weather;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Open-Meteo responses turned into plain values, plus WMO weather-code descriptions. */
final class Forecast {
  static final class Hour {
    String time; // "2026-10-02T14:00"
    double temp;
    int rainChance, code;
  }

  static final class Day {
    String date; // "2026-10-02"
    double max, min;
    int rainChance, code;
    String sunrise, sunset;
  }

  static final class Place {
    String name, region, country;
    double lat, lon;

    String label() {
      StringBuilder b = new StringBuilder(name);
      if (region != null && !region.isEmpty() && !region.equals(name)) b.append(", ").append(region);
      if (country != null && !country.isEmpty()) b.append(", ").append(country);
      return b.toString();
    }
  }

  double temp, feelsLike, wind;
  int humidity, code;
  boolean day = true;
  String tempUnit = "°C", windUnit = "km/h";
  final List<Hour> hours = new ArrayList<>();
  final List<Day> days = new ArrayList<>();

  static String forecastUrl(double lat, double lon, boolean imperial) {
    return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon)
        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
        + "&hourly=temperature_2m,precipitation_probability,weather_code"
        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
        + "&timezone=auto&forecast_days=7"
        + (imperial ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
  }

  static String searchUrl(String query) throws java.io.UnsupportedEncodingException {
    return "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&name="
        + java.net.URLEncoder.encode(query.trim(), "UTF-8");
  }

  /** Parses a forecast. Keeps only hours from {@code nowHour} ("2026-10-02T14:00") onwards, up to 24. */
  static Forecast parse(JSONObject o, String nowHour) throws JSONException {
    Forecast f = new Forecast();
    JSONObject cur = o.getJSONObject("current");
    f.temp = cur.getDouble("temperature_2m");
    f.feelsLike = cur.optDouble("apparent_temperature", f.temp);
    f.humidity = cur.optInt("relative_humidity_2m");
    f.wind = cur.optDouble("wind_speed_10m");
    f.code = cur.optInt("weather_code");
    f.day = cur.optInt("is_day", 1) == 1;
    JSONObject units = o.optJSONObject("current_units");
    if (units != null) {
      f.tempUnit = units.optString("temperature_2m", f.tempUnit);
      f.windUnit = units.optString("wind_speed_10m", f.windUnit);
    }
    JSONObject h = o.getJSONObject("hourly");
    JSONArray times = h.getJSONArray("time"), temps = h.getJSONArray("temperature_2m");
    JSONArray rain = h.optJSONArray("precipitation_probability"), codes = h.optJSONArray("weather_code");
    for (int i = 0; i < times.length() && f.hours.size() < 24; i++) {
      String t = times.getString(i);
      if (nowHour != null && t.compareTo(nowHour) < 0) continue;
      Hour x = new Hour();
      x.time = t;
      x.temp = temps.getDouble(i);
      x.rainChance = rain == null ? 0 : rain.optInt(i);
      x.code = codes == null ? 0 : codes.optInt(i);
      f.hours.add(x);
    }
    JSONObject d = o.getJSONObject("daily");
    JSONArray dates = d.getJSONArray("time");
    for (int i = 0; i < dates.length(); i++) {
      Day x = new Day();
      x.date = dates.getString(i);
      x.max = d.getJSONArray("temperature_2m_max").getDouble(i);
      x.min = d.getJSONArray("temperature_2m_min").getDouble(i);
      x.rainChance = d.has("precipitation_probability_max") ? d.getJSONArray("precipitation_probability_max").optInt(i) : 0;
      x.code = d.getJSONArray("weather_code").optInt(i);
      x.sunrise = d.has("sunrise") ? d.getJSONArray("sunrise").optString(i) : "";
      x.sunset = d.has("sunset") ? d.getJSONArray("sunset").optString(i) : "";
      f.days.add(x);
    }
    return f;
  }

  static List<Place> parsePlaces(JSONObject o) {
    List<Place> out = new ArrayList<>();
    JSONArray r = o.optJSONArray("results");
    for (int i = 0; r != null && i < r.length(); i++) {
      JSONObject j = r.optJSONObject(i);
      if (j == null) continue;
      Place p = new Place();
      p.name = j.optString("name");
      p.region = j.optString("admin1");
      p.country = j.optString("country");
      p.lat = j.optDouble("latitude");
      p.lon = j.optDouble("longitude");
      out.add(p);
    }
    return out;
  }

  /** WMO weather interpretation codes, as used by Open-Meteo. */
  static String describe(int code) {
    switch (code) {
      case 0: return "Clear";
      case 1: return "Mostly clear";
      case 2: return "Partly cloudy";
      case 3: return "Overcast";
      case 45: case 48: return "Fog";
      case 51: case 53: case 55: return "Drizzle";
      case 56: case 57: return "Freezing drizzle";
      case 61: return "Light rain";
      case 63: return "Rain";
      case 65: return "Heavy rain";
      case 66: case 67: return "Freezing rain";
      case 71: return "Light snow";
      case 73: return "Snow";
      case 75: return "Heavy snow";
      case 77: return "Snow grains";
      case 80: case 81: return "Showers";
      case 82: return "Heavy showers";
      case 85: case 86: return "Snow showers";
      case 95: return "Thunderstorm";
      case 96: case 99: return "Thunderstorm with hail";
      default: return "Unknown";
    }
  }

  /** A text symbol for the weather; the variation selector keeps them as plain glyphs, not colour emoji. */
  static String symbol(int code, boolean day) {
    String s;
    if (code == 0 || code == 1) s = day ? "☀" : "☾";
    else if (code == 2) s = "⛅";
    else if (code == 3) s = "☁";
    else if (code == 45 || code == 48) s = "≡";
    else if (code >= 71 && code <= 77 || code == 85 || code == 86) s = "❄";
    else if (code >= 95) s = "⚡";
    else if (code >= 51) s = "☂";
    else s = "?";
    return s + "︎";
  }

  static String round(double v) { return String.valueOf(Math.round(v)); }
}
