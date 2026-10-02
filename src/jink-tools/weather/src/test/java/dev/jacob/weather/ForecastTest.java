package dev.jacob.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ForecastTest {
  // A trimmed real-shaped Open-Meteo response.
  static final String SAMPLE = "{"
      + "\"current_units\":{\"temperature_2m\":\"°F\",\"wind_speed_10m\":\"mp/h\"},"
      + "\"current\":{\"temperature_2m\":61.3,\"apparent_temperature\":58.9,\"relative_humidity_2m\":72,"
      + "\"wind_speed_10m\":9.4,\"weather_code\":3,\"is_day\":0},"
      + "\"hourly\":{\"time\":[\"2026-10-02T12:00\",\"2026-10-02T13:00\",\"2026-10-02T14:00\"],"
      + "\"temperature_2m\":[60,61,62],\"precipitation_probability\":[10,20,30],\"weather_code\":[3,61,63]},"
      + "\"daily\":{\"time\":[\"2026-10-02\",\"2026-10-03\"],\"weather_code\":[61,0],"
      + "\"temperature_2m_max\":[64.4,70.1],\"temperature_2m_min\":[52,49.5],"
      + "\"precipitation_probability_max\":[80,5],\"sunrise\":[\"2026-10-02T07:01\",\"2026-10-03T07:02\"],"
      + "\"sunset\":[\"2026-10-02T18:40\",\"2026-10-03T18:38\"]}}";

  @Test public void parsesCurrentHourlyAndDaily() throws Exception {
    Forecast f = Forecast.parse(new JSONObject(SAMPLE), "2026-10-02T13:00");
    assertEquals(61.3, f.temp, 1e-9);
    assertEquals(72, f.humidity);
    assertEquals("°F", f.tempUnit);
    assertTrue(!f.day);
    assertEquals(2, f.hours.size()); // 12:00 is in the past
    assertEquals(63, f.hours.get(1).code);
    assertEquals(2, f.days.size());
    assertEquals(80, f.days.get(0).rainChance);
    assertEquals("2026-10-03T18:38", f.days.get(1).sunset);
  }

  @Test public void parsesPlaces() throws Exception {
    List<Forecast.Place> p = Forecast.parsePlaces(new JSONObject("{\"results\":[{\"name\":\"Portland\","
        + "\"admin1\":\"Oregon\",\"country\":\"United States\",\"latitude\":45.52,\"longitude\":-122.68}]}"));
    assertEquals(1, p.size());
    assertEquals("Portland, Oregon, United States", p.get(0).label());
    assertEquals(0, Forecast.parsePlaces(new JSONObject("{}")).size());
  }

  @Test public void urlsAndCodes() throws Exception {
    String u = Forecast.forecastUrl(45.52, -122.68, true);
    assertTrue(u.contains("latitude=45.5200&longitude=-122.6800"));
    assertTrue(u.contains("temperature_unit=fahrenheit"));
    assertTrue(Forecast.searchUrl("São Paulo").endsWith("name=S%C3%A3o+Paulo"));
    assertEquals("Thunderstorm", Forecast.describe(95));
    assertEquals("Unknown", Forecast.describe(42));
    assertEquals("☂︎", Forecast.symbol(63, true));
  }
}
