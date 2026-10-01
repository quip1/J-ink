package dev.jacob.inkprint;

import android.content.Context;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Turns template options into a PDF file, or into pages for the system print dialog. */
final class TemplateDoc {
  private TemplateDoc() {}

  /** Writes every page at the chosen paper size. Call off the UI thread. */
  static void save(Context c, Templates.Options o, Uri dest) throws IOException {
    PdfDocument doc = new PdfDocument();
    try {
      for (int i = 0; i < o.pages; i++) {
        PdfDocument.Page p = doc.startPage(new PdfDocument.PageInfo.Builder(o.paper.width, o.paper.height, i + 1).create());
        Templates.draw(p.getCanvas(), o.paper.width, o.paper.height, o, i);
        doc.finishPage(p);
      }
      try (OutputStream out = c.getContentResolver().openOutputStream(dest, "wt")) {
        if (out == null) throw new IOException("Can't write to that location");
        doc.writeTo(out);
      }
    } finally {
      doc.close();
    }
  }

  /** Print adapter that lays the template out on whatever paper the printer dialog picks. */
  static PrintDocumentAdapter printAdapter(Context c, Templates.Options o) {
    return new PrintDocumentAdapter() {
      PrintAttributes attrs;

      @Override public void onLayout(PrintAttributes old, PrintAttributes next, CancellationSignal cancel,
          LayoutResultCallback cb, Bundle extras) {
        if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
        attrs = next;
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(o.fileName())
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(o.pages)
            .build();
        cb.onLayoutFinished(info, !next.equals(old));
      }

      @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel,
          WriteResultCallback cb) {
        PrintedPdfDocument doc = new PrintedPdfDocument(c, attrs);
        try {
          for (int i = 0; i < o.pages; i++) {
            if (!contains(ranges, i)) continue;
            if (cancel.isCanceled()) { cb.onWriteCancelled(); return; }
            PdfDocument.Page p = doc.startPage(i);
            android.graphics.Rect r = p.getInfo().getContentRect();
            Templates.draw(p.getCanvas(), r.width(), r.height(), o, i);
            doc.finishPage(p);
          }
          try (FileOutputStream out = new FileOutputStream(dest.getFileDescriptor())) { doc.writeTo(out); }
          cb.onWriteFinished(ranges);
        } catch (IOException e) {
          cb.onWriteFailed(e.getMessage());
        } finally {
          doc.close();
        }
      }
    };
  }

  static boolean contains(PageRange[] ranges, int page) {
    for (PageRange r : ranges) {
      if (r.equals(PageRange.ALL_PAGES)) return true;
      if (page >= r.getStart() && page <= r.getEnd()) return true;
    }
    return false;
  }
}
