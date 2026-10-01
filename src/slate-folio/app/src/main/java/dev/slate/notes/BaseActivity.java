package dev.slate.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.util.List;
import java.util.function.Consumer;

/** File picking plus the shared template and font pickers. */
abstract class BaseActivity extends Activity {
  private static final int REQ_OPEN = 71;
  private Consumer<Uri> pendingOpen;

  interface Importer { String run(Uri uri) throws Exception; }

  void openDocument(String[] mimes, Consumer<Uri> cb) {
    pendingOpen = cb;
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mimes.length == 1 ? mimes[0] : "*/*");
    if (mimes.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
    try { startActivityForResult(i, REQ_OPEN); }
    catch (ActivityNotFoundException e) { pendingOpen = null; toast("No file picker available"); }
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != REQ_OPEN) return;
    Consumer<Uri> cb = pendingOpen;
    pendingOpen = null;
    if (res == RESULT_OK && data != null && data.getData() != null && cb != null) cb.accept(data.getData());
    else onPickerClosed();
  }

  /** Hook for activities that paused something while a picker was open. */
  void onPickerClosed() {}

  void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

  /** Imports on a worker thread, then hands the new key back on the UI thread. */
  void importThen(Uri uri, Importer imp, Consumer<String> done) {
    toast("Importing…");
    new Thread(() -> {
      String key = null, err = null;
      try { key = imp.run(uri); } catch (Exception e) { err = e.getMessage(); }
      String k = key, m = err;
      runOnUiThread(() -> {
        if (k != null) done.accept(k);
        else { toast("Import failed" + (m != null ? ": " + m : "")); onPickerClosed(); }
      });
    }).start();
  }

  void pickTemplate(String current, Consumer<String> onPick, Runnable onCancel) {
    List<String> keys = Templates.keys();
    String[] labels = new String[keys.size() + 1];
    int sel = -1;
    for (int i = 0; i < keys.size(); i++) {
      labels[i] = Templates.name(keys.get(i));
      if (keys.get(i).equals(current)) sel = i;
    }
    labels[keys.size()] = "Import image or PDF…";
    new AlertDialog.Builder(this)
        .setTitle("Template")
        .setSingleChoiceItems(labels, sel, (d, which) -> {
          d.dismiss();
          if (which < keys.size()) onPick.accept(keys.get(which));
          else openDocument(new String[]{"image/*", "application/pdf"},
              uri -> importThen(uri, u -> Templates.importUri(this, u), onPick));
        })
        .setNegativeButton("Cancel", (d, w) -> { if (onCancel != null) onCancel.run(); })
        .setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); })
        .show();
  }

  void pickFont(String current, Consumer<String> onPick, Runnable onCancel) {
    List<String> keys = Fonts.keys();
    String[] labels = new String[keys.size() + 1];
    int sel = -1;
    for (int i = 0; i < keys.size(); i++) {
      labels[i] = Fonts.name(keys.get(i));
      if (keys.get(i).equals(current)) sel = i;
    }
    labels[keys.size()] = "Import font (.ttf / .otf)…";
    new AlertDialog.Builder(this)
        .setTitle("Font")
        .setSingleChoiceItems(labels, sel, (d, which) -> {
          d.dismiss();
          if (which < keys.size()) onPick.accept(keys.get(which));
          else openDocument(new String[]{"*/*"}, uri -> importThen(uri, u -> Fonts.importUri(this, u), onPick));
        })
        .setNegativeButton("Cancel", (d, w) -> { if (onCancel != null) onCancel.run(); })
        .setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); })
        .show();
  }
}
