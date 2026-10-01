package dev.jacob.inkprint;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Ui;
import java.util.Calendar;

public class MainActivity extends InkActivity {
  private static final float[] SPACINGS = {6f, 7.1f, 8.7f};
  private static final String[] SPACING_NAMES = {"Narrow", "College", "Wide"};

  private final Templates.Options opts = new Templates.Options();
  private SharedPreferences prefs;
  private TextView kindBtn, paperBtn, pagesBtn, dateBtn, lightBtn, darkBtn;
  private final TextView[] spacingBtns = new TextView[SPACINGS.length];
  private LinearLayout spacingRow, dateRow;
  private ImageView preview;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("inkprint", MODE_PRIVATE);
    opts.kind = Templates.Kind.values()[clamp(prefs.getInt("kind", 0), Templates.Kind.values().length)];
    opts.paper = Paper.values()[clamp(prefs.getInt("paper", 0), Paper.values().length)];
    opts.spacingMm = prefs.getFloat("spacing", 7.1f);
    opts.dark = prefs.getBoolean("dark", false);
    opts.pages = prefs.getInt("pages", 1);
    build();
    handleIncoming(getIntent());
  }

  @Override protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    handleIncoming(i);
  }

  private static int clamp(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

  private void build() {
    LinearLayout page = Ui.column(this);
    page.addView(header("Inkprint", refreshButton()), Ui.fill());

    kindBtn = Ui.button(this, "", v -> Dialogs.choose(this, "Template", Templates.Kind.labels(), i -> {
      opts.kind = Templates.Kind.values()[i];
      changed();
    }));
    paperBtn = Ui.button(this, "", v -> Dialogs.choose(this, "Paper", Paper.labels(), i -> {
      opts.paper = Paper.values()[i];
      changed();
    }));
    Ui.add(page, labelled("Template", kindBtn), 10);
    Ui.add(page, labelled("Paper (for Save PDF)", paperBtn), 6);

    for (int i = 0; i < SPACINGS.length; i++) {
      final int k = i;
      spacingBtns[i] = Ui.button(this, SPACING_NAMES[i], v -> { opts.spacingMm = SPACINGS[k]; changed(); });
    }
    Ui.add(page, labelled("Line spacing", Ui.buttons(this, spacingBtns)), 6);
    spacingRow = (LinearLayout) page.getChildAt(page.getChildCount() - 1);

    lightBtn = Ui.button(this, "Light grey", v -> { opts.dark = false; changed(); });
    darkBtn = Ui.button(this, "Black", v -> { opts.dark = true; changed(); });
    Ui.add(page, labelled("Lines", Ui.buttons(this, lightBtn, darkBtn)), 6);

    pagesBtn = Ui.button(this, "", v -> Dialogs.number(this, "Number of pages", opts.pages, n -> {
      opts.pages = Math.max(1, Math.min(366, n));
      changed();
    }));
    TextView minus = Ui.button(this, "−", v -> { if (opts.pages > 1) { opts.pages--; changed(); } });
    TextView plus = Ui.button(this, "+", v -> { if (opts.pages < 366) { opts.pages++; changed(); } });
    LinearLayout pagesRow = Ui.row(this);
    pagesRow.addView(minus);
    LinearLayout.LayoutParams mid = Ui.weight(1);
    mid.leftMargin = mid.rightMargin = Ui.dp(this, 6);
    pagesRow.addView(pagesBtn, mid);
    pagesRow.addView(plus);
    Ui.add(page, labelled("Pages", pagesRow), 6);

    dateBtn = Ui.button(this, "", v -> pickDate());
    Ui.add(page, labelled("Starting", dateBtn), 6);
    dateRow = (LinearLayout) page.getChildAt(page.getChildCount() - 1);

    preview = new ImageView(this);
    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1);
    pp.topMargin = Ui.dp(this, 10);
    page.addView(preview, pp);
    preview.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
      if (r - l != or - ol || bt - t != ob - ot) v.post(this::renderPreview);
    });

    TextView save = Ui.button(this, "Save PDF…", v -> savePdf());
    TextView print = Ui.button(this, "Print…", v -> printTemplate());
    Ui.add(page, Ui.buttons(this, save, print), 10);
    TextView file = Ui.button(this, "Print a file (PDF, image, text)…", v ->
        openDocument(new String[]{"application/pdf", "image/*", "text/*"}, this::printUri));
    Ui.add(page, file, 6);

    setPage(page);
    changed();
  }

  /** A small caption above a control, so each option reads "Paper / [US Letter]". */
  private LinearLayout labelled(String caption, View control) {
    LinearLayout col = Ui.column(this);
    col.addView(Ui.muted(this, caption), Ui.fill());
    col.addView(control, Ui.fill());
    return col;
  }

  private void changed() {
    kindBtn.setText(opts.kind.label);
    paperBtn.setText(opts.paper.label);
    pagesBtn.setText(opts.pages + (opts.kind == Templates.Kind.MONTHLY ? " month" : opts.kind == Templates.Kind.WEEKLY
        ? " week" : opts.kind == Templates.Kind.DAILY ? " day" : " page") + (opts.pages == 1 ? "" : "s"));
    for (int i = 0; i < SPACINGS.length; i++) Ui.setActive(spacingBtns[i], Math.abs(SPACINGS[i] - opts.spacingMm) < 0.05f);
    Ui.setActive(lightBtn, !opts.dark);
    Ui.setActive(darkBtn, opts.dark);
    spacingRow.setVisibility(opts.kind.ruled() ? View.VISIBLE : View.GONE);
    dateRow.setVisibility(opts.kind.dated() ? View.VISIBLE : View.GONE);
    Calendar s = opts.start;
    dateBtn.setText(opts.kind == Templates.Kind.MONTHLY
        ? Cal.MONTHS[s.get(Calendar.MONTH)] + " " + s.get(Calendar.YEAR)
        : s.get(Calendar.DAY_OF_MONTH) + " " + Cal.MONTHS[s.get(Calendar.MONTH)] + " " + s.get(Calendar.YEAR));
    prefs.edit().putInt("kind", opts.kind.ordinal()).putInt("paper", opts.paper.ordinal())
        .putFloat("spacing", opts.spacingMm).putBoolean("dark", opts.dark).putInt("pages", opts.pages).apply();
    renderPreview();
  }

  private void pickDate() {
    Calendar s = opts.start;
    new DatePickerDialog(this, (v, y, m, d) -> {
      Calendar c = Calendar.getInstance();
      c.clear();
      c.set(y, m, d);
      opts.start = c;
      changed();
    }, s.get(Calendar.YEAR), s.get(Calendar.MONTH), s.get(Calendar.DAY_OF_MONTH)).show();
  }

  /** Draws the first page at the preview's size, using the same code as the PDF. */
  private void renderPreview() {
    int vw = preview.getWidth(), vh = preview.getHeight();
    if (vw <= 0 || vh <= 0) return;
    float scale = Math.min(vw / (float) opts.paper.width, vh / (float) opts.paper.height);
    int bw = Math.max(1, Math.round(opts.paper.width * scale)), bh = Math.max(1, Math.round(opts.paper.height * scale));
    Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(0xFFFFFFFF);
    c.save();
    c.scale(scale, scale);
    Templates.draw(c, opts.paper.width, opts.paper.height, opts, 0);
    c.restore();
    Paint border = new Paint();
    border.setStyle(Paint.Style.STROKE);
    border.setStrokeWidth(2);
    c.drawRect(1, 1, bw - 1, bh - 1, border);
    preview.setImageBitmap(bmp);
  }

  private void savePdf() {
    Templates.Options snapshot = copy(opts);
    createDocument("application/pdf", snapshot.fileName(), uri -> {
      toast("Saving…");
      new Thread(() -> {
        String err = null;
        try { TemplateDoc.save(this, snapshot, uri); } catch (Exception e) { err = e.getMessage(); }
        String m = err;
        runOnUiThread(() -> toast(m == null ? "Saved " + snapshot.pages + " page" + (snapshot.pages == 1 ? "" : "s")
            : "Couldn't save: " + m));
      }).start();
    });
  }

  private void printTemplate() {
    Templates.Options snapshot = copy(opts);
    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
    pm.print(snapshot.fileName(), TemplateDoc.printAdapter(this, snapshot), new PrintAttributes.Builder()
        .setMediaSize(snapshot.paper == Paper.A4 ? PrintAttributes.MediaSize.ISO_A4
            : snapshot.paper == Paper.A5 ? PrintAttributes.MediaSize.ISO_A5 : PrintAttributes.MediaSize.NA_LETTER)
        .build());
  }

  private static Templates.Options copy(Templates.Options o) {
    Templates.Options c = new Templates.Options();
    c.kind = o.kind;
    c.paper = o.paper;
    c.spacingMm = o.spacingMm;
    c.dark = o.dark;
    c.pages = o.pages;
    c.start = (Calendar) o.start.clone();
    c.firstDayOfWeek = o.firstDayOfWeek;
    return c;
  }

  // ---- printing other apps' files ----

  @SuppressWarnings("deprecation")
  private void handleIncoming(Intent i) {
    if (i == null) return;
    Uri uri = null;
    if (Intent.ACTION_SEND.equals(i.getAction())) uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
    else if (Intent.ACTION_VIEW.equals(i.getAction())) uri = i.getData();
    if (uri != null) {
      Uri u = uri;
      setIntent(new Intent());
      preview.post(() -> printUri(u));
    }
  }

  private void printUri(Uri uri) {
    String name = displayName(uri);
    String mime = getContentResolver().getType(uri);
    FilePrint.Type type = FilePrint.typeOf(mime, name);
    if (type == FilePrint.Type.UNSUPPORTED) {
      toast("Inkprint can print PDFs, images and plain text. Open other files in their own app and print from there.");
      return;
    }
    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
    pm.print(name, FilePrint.forUri(this, uri, type, name), null);
  }

  private String displayName(Uri uri) {
    try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
    } catch (RuntimeException ignored) {
      // Some providers don't support queries; fall back to the path.
    }
    String last = uri.getLastPathSegment();
    return last == null ? "document" : last;
  }
}
