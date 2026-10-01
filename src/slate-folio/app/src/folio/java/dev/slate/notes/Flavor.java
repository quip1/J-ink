package dev.slate.notes;

import android.content.Context;
import java.io.File;
import java.util.Locale;

/** Folio: the document engines. */
final class Flavor {
  static void init(Context c) {
    try { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c); } catch (Throwable ignored) {}
  }

  static Source openBook(File f) {
    String n = f.getName().toLowerCase(Locale.US);
    if (n.endsWith(".pdf")) return new PdfSource(f);
    if (n.endsWith(".cbz")) return new CbzSource(f);
    return new ReflowSource(f);
  }
}
