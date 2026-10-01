package dev.jacob.jink;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** One-line wrappers for the handful of dialogs every app needs. */
public final class Dialogs {
  private Dialogs() {}

  public static AlertDialog.Builder builder(Context c) {
    return new AlertDialog.Builder(c, android.R.style.Theme_Material_Light_Dialog_Alert);
  }

  public static void prompt(Context c, String title, String initial, Consumer<String> ok) {
    prompt(c, title, initial, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, ok);
  }

  public static void prompt(Context c, String title, String initial, int inputType, Consumer<String> ok) {
    EditText e = Ui.input(c, "", initial == null ? "" : initial);
    e.setInputType(inputType);
    if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
      e.setSingleLine(false);
      e.setMinLines(4);
    }
    e.setSelectAllOnFocus(true);
    FrameLayout wrap = new FrameLayout(c);
    int p = Ui.dp(c, 20);
    wrap.setPadding(p, Ui.dp(c, 8), p, 0);
    wrap.addView(e);
    builder(c).setTitle(title).setView(wrap)
        .setPositiveButton("OK", (d, w) -> ok.accept(e.getText().toString()))
        .setNegativeButton("Cancel", null)
        .show();
    e.requestFocus();
  }

  public static void number(Context c, String title, int initial, IntConsumer ok) {
    prompt(c, title, String.valueOf(initial), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED,
        s -> ok.accept(Ui.parseInt(s, initial)));
  }

  public static void confirm(Context c, String message, String action, Runnable ok) {
    builder(c).setMessage(message)
        .setPositiveButton(action, (d, w) -> ok.run())
        .setNegativeButton("Cancel", null)
        .show();
  }

  public static void choose(Context c, String title, String[] items, IntConsumer pick) {
    builder(c).setTitle(title).setItems(items, (d, which) -> pick.accept(which)).show();
  }

  public static void message(Context c, String title, String message) {
    builder(c).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
  }
}
