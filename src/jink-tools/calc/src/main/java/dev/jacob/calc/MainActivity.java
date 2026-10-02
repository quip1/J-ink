package dev.jacob.calc;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.util.ArrayList;
import java.util.List;

/** A big-button calculator and a unit converter. */
public class MainActivity extends InkActivity {
  private static final String[][] KEYS = {
      {"C", "(", ")", "÷"},
      {"7", "8", "9", "×"},
      {"4", "5", "6", "−"},
      {"1", "2", "3", "+"},
      {"0", ".", "⌫", "="},
      {"√", "^", "%", "ans"}};

  private SharedPreferences prefs;
  private boolean unitsMode;
  private TextView calcBtn, unitsBtn;
  private LinearLayout body;

  // Calculator state.
  private final StringBuilder expr = new StringBuilder();
  private TextView exprView, resultView;
  private String lastAnswer = "0";
  private boolean justEvaluated;
  private final List<String> history = new ArrayList<>();

  // Converter state.
  private int category, from, to = 1;
  private EditText amount;
  private TextView converted;
  private LinearLayout table;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("calc", MODE_PRIVATE);
    unitsMode = prefs.getBoolean("units", false);
    category = Math.max(0, Math.min(Units.ALL.length - 1, prefs.getInt("category", 0)));
    from = prefs.getInt("from", 0);
    to = prefs.getInt("to", 1);
    clampUnits();
    lastAnswer = prefs.getString("ans", "0");
    LinearLayout page = Ui.column(this);
    calcBtn = Ui.button(this, "Calculator", v -> setMode(false));
    unitsBtn = Ui.button(this, "Units", v -> setMode(true));
    page.addView(header("Calc", calcBtn, unitsBtn, refreshButton()), Ui.fill());
    body = Ui.column(this);
    page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    setPage(page);
    render();
  }

  private void setMode(boolean units) {
    unitsMode = units;
    prefs.edit().putBoolean("units", units).apply();
    render();
  }

  private void render() {
    Ui.setActive(calcBtn, !unitsMode);
    Ui.setActive(unitsBtn, unitsMode);
    body.removeAllViews();
    if (unitsMode) buildUnits(); else buildCalculator();
  }

  // ---- calculator ----

  private void buildCalculator() {
    exprView = Ui.text(this, "", 1.4f);
    exprView.setGravity(Gravity.END);
    exprView.setMinLines(2);
    resultView = Ui.text(this, "", Ui.large(this) ? 2.6f : 2.2f);
    resultView.setTypeface(Typeface.DEFAULT_BOLD);
    resultView.setGravity(Gravity.END);
    resultView.setSingleLine(true);
    resultView.setOnClickListener(v -> showHistory());
    Ui.add(body, exprView, 12);
    body.addView(resultView, Ui.fill());
    Ui.add(body, Ui.muted(this, "Tap the result for history"), 2);
    LinearLayout pad = Ui.column(this);
    for (String[] row : KEYS) {
      TextView[] buttons = new TextView[row.length];
      for (int i = 0; i < row.length; i++) {
        String k = row[i];
        buttons[i] = Ui.button(this, k, v -> key(k));
        buttons[i].setTextSize(Ui.body(this) * 1.4f);
        buttons[i].setMinHeight(Ui.dp(this, Ui.large(this) ? 76 : 60));
        if (k.equals("=")) Ui.setActive(buttons[i], true);
      }
      Ui.add(pad, Ui.buttons(this, buttons), 6);
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    body.addView(new LinearLayout(this), lp); // push the keypad to the bottom, near your thumbs
    body.addView(pad, Ui.fill());
    showExpr();
  }

  private void key(String k) {
    switch (k) {
      case "C": expr.setLength(0); resultView.setText(""); justEvaluated = false; break;
      case "⌫": if (expr.length() > 0) expr.setLength(expr.length() - 1); break;
      case "=": evaluate(); return;
      case "ans": append(lastAnswer); break;
      default:
        // Typing a digit right after "=" starts fresh; typing an operator continues from the answer.
        boolean operator = "+−×÷^%".contains(k);
        if (justEvaluated && !operator) expr.setLength(0);
        if (justEvaluated && operator) { expr.setLength(0); expr.append(lastAnswer); }
        append(k);
    }
    justEvaluated = false;
    showExpr();
  }

  private void append(String s) { expr.append(s); }

  private void showExpr() {
    exprView.setText(expr.length() == 0 ? "0" : expr.toString());
    if (expr.length() > 0 && !justEvaluated) {
      // Live preview of the answer while you type, when the expression is complete enough.
      try { resultView.setText("= " + Calculator.format(Calculator.eval(expr.toString()))); }
      catch (IllegalArgumentException e) { resultView.setText(""); }
    }
  }

  private void evaluate() {
    if (expr.length() == 0) return;
    try {
      String r = Calculator.format(Calculator.eval(expr.toString()));
      history.add(0, expr + " = " + r);
      while (history.size() > 30) history.remove(history.size() - 1);
      lastAnswer = r;
      prefs.edit().putString("ans", r).apply();
      resultView.setText(r);
      justEvaluated = true;
    } catch (IllegalArgumentException e) {
      resultView.setText(e.getMessage());
    }
  }

  private void showHistory() {
    if (history.isEmpty()) { toast("No calculations yet"); return; }
    String[] items = history.toArray(new String[0]);
    Dialogs.choose(this, "History (tap to reuse)", items, i -> {
      String h = history.get(i);
      expr.setLength(0);
      expr.append(h.substring(0, h.lastIndexOf(" = ")));
      justEvaluated = false;
      showExpr();
    });
  }

  // ---- unit converter ----

  private void clampUnits() {
    int n = Units.ALL[category].units.length;
    from = Math.max(0, Math.min(n - 1, from));
    to = Math.max(0, Math.min(n - 1, to));
  }

  private void buildUnits() {
    Units.Category c = Units.ALL[category];
    TextView cat = Ui.button(this, c.name, v -> {
      String[] names = new String[Units.ALL.length];
      for (int i = 0; i < names.length; i++) names[i] = Units.ALL[i].name;
      Dialogs.choose(this, "Convert", names, i -> { category = i; from = 0; to = 1; saveUnits(); render(); });
    });
    Ui.add(body, cat, 10);
    amount = Ui.numberInput(this, "Amount", prefs.getString("amount", "1"));
    amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    amount.setTextSize(Ui.body(this) * 1.4f);
    TextView fromBtn = Ui.button(this, c.units[from].name, v -> pickUnit(true));
    Ui.add(body, amount, 10);
    Ui.add(body, fromBtn, 6);
    TextView swap = Ui.button(this, "⇅ Swap", v -> { int t = from; from = to; to = t; saveUnits(); render(); });
    Ui.add(body, swap, 6);
    TextView toBtn = Ui.button(this, c.units[to].name, v -> pickUnit(false));
    Ui.add(body, toBtn, 6);
    converted = Ui.text(this, "", 2f);
    converted.setTypeface(Typeface.DEFAULT_BOLD);
    converted.setGravity(Gravity.CENTER);
    Ui.add(body, converted, 12);
    Ui.add(body, Ui.muted(this, "In every unit"), 14);
    table = Ui.column(this);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
    body.addView(Ui.scroll(this, table), tp);
    amount.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
      @Override public void afterTextChanged(Editable s) {
        prefs.edit().putString("amount", s.toString()).apply();
        convert();
      }
    });
    convert();
  }

  private void pickUnit(boolean isFrom) {
    Units.Category c = Units.ALL[category];
    String[] names = new String[c.units.length];
    for (int i = 0; i < names.length; i++) names[i] = c.units[i].name + " (" + c.units[i].symbol + ")";
    Dialogs.choose(this, isFrom ? "From" : "To", names, i -> {
      if (isFrom) from = i; else to = i;
      saveUnits();
      render();
    });
  }

  private void saveUnits() {
    prefs.edit().putInt("category", category).putInt("from", from).putInt("to", to).apply();
  }

  private void convert() {
    Units.Category c = Units.ALL[category];
    double v;
    try { v = Double.parseDouble(amount.getText().toString().trim()); }
    catch (NumberFormatException e) { converted.setText(""); table.removeAllViews(); return; }
    converted.setText(Calculator.format(Units.convert(c, from, to, v)) + " " + c.units[to].symbol);
    table.removeAllViews();
    for (int i = 0; i < c.units.length; i++) {
      LinearLayout row = Ui.row(this);
      int p = Ui.dp(this, 6);
      row.setPadding(0, p, 0, p);
      row.addView(Ui.text(this, c.units[i].name), Ui.weight(1));
      TextView val = Ui.text(this, Calculator.format(Units.convert(c, from, i, v)) + " " + c.units[i].symbol);
      val.setTypeface(Typeface.DEFAULT_BOLD);
      row.addView(val);
      table.addView(row, Ui.fill());
      table.addView(Ui.rule(this));
    }
  }
}
