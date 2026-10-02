package dev.jacob.calc;

/** Unit conversion tables. Every unit is a factor to its category's base unit, except temperature. */
final class Units {
  private Units() {}

  static final class Unit {
    final String name, symbol;
    final double factor;
    Unit(String name, String symbol, double factor) { this.name = name; this.symbol = symbol; this.factor = factor; }
  }

  static final class Category {
    final String name;
    final Unit[] units;
    Category(String name, Unit... units) { this.name = name; this.units = units; }
  }

  static final Category[] ALL = {
      new Category("Length",
          new Unit("Millimetre", "mm", 0.001), new Unit("Centimetre", "cm", 0.01), new Unit("Metre", "m", 1),
          new Unit("Kilometre", "km", 1000), new Unit("Inch", "in", 0.0254), new Unit("Foot", "ft", 0.3048),
          new Unit("Yard", "yd", 0.9144), new Unit("Mile", "mi", 1609.344), new Unit("Nautical mile", "nmi", 1852)),
      new Category("Weight",
          new Unit("Milligram", "mg", 1e-6), new Unit("Gram", "g", 0.001), new Unit("Kilogram", "kg", 1),
          new Unit("Tonne", "t", 1000), new Unit("Ounce", "oz", 0.028349523125), new Unit("Pound", "lb", 0.45359237),
          new Unit("Stone", "st", 6.35029318)),
      new Category("Temperature",
          new Unit("Celsius", "°C", 0), new Unit("Fahrenheit", "°F", 0), new Unit("Kelvin", "K", 0)),
      new Category("Volume",
          new Unit("Millilitre", "ml", 0.001), new Unit("Litre", "l", 1), new Unit("Teaspoon (US)", "tsp", 0.00492892159375),
          new Unit("Tablespoon (US)", "tbsp", 0.01478676478125), new Unit("Cup (US)", "cup", 0.2365882365),
          new Unit("Fluid ounce (US)", "fl oz", 0.0295735295625), new Unit("Pint (US)", "pt", 0.473176473),
          new Unit("Gallon (US)", "gal", 3.785411784), new Unit("Pint (UK)", "pt UK", 0.56826125),
          new Unit("Gallon (UK)", "gal UK", 4.54609), new Unit("Cubic metre", "m³", 1000)),
      new Category("Speed",
          new Unit("Metres per second", "m/s", 1), new Unit("Kilometres per hour", "km/h", 1 / 3.6),
          new Unit("Miles per hour", "mph", 0.44704), new Unit("Knot", "kn", 1852.0 / 3600)),
      new Category("Area",
          new Unit("Square metre", "m²", 1), new Unit("Square kilometre", "km²", 1e6),
          new Unit("Square foot", "ft²", 0.09290304), new Unit("Square mile", "mi²", 2589988.110336),
          new Unit("Acre", "ac", 4046.8564224), new Unit("Hectare", "ha", 10000)),
      new Category("Data",
          new Unit("Byte", "B", 1), new Unit("Kilobyte", "KB", 1024), new Unit("Megabyte", "MB", 1048576),
          new Unit("Gigabyte", "GB", 1073741824), new Unit("Terabyte", "TB", 1099511627776.0),
          new Unit("Bit", "bit", 0.125)),
      new Category("Time",
          new Unit("Second", "s", 1), new Unit("Minute", "min", 60), new Unit("Hour", "h", 3600),
          new Unit("Day", "d", 86400), new Unit("Week", "wk", 604800), new Unit("Year (365 d)", "yr", 31536000)),
  };

  static double convert(Category c, int from, int to, double v) {
    if (c.name.equals("Temperature")) {
      double celsius = from == 0 ? v : from == 1 ? (v - 32) * 5 / 9 : v - 273.15;
      return to == 0 ? celsius : to == 1 ? celsius * 9 / 5 + 32 : celsius + 273.15;
    }
    return v * c.units[from].factor / c.units[to].factor;
  }
}
