package dev.jacob.inkprint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import dev.jacob.jink.Store;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Print adapters for files the user opens or shares: PDFs, images and plain text. */
final class FilePrint {
  private FilePrint() {}

  enum Type { PDF, IMAGE, TEXT, UNSUPPORTED }

  static Type typeOf(String mime, String name) {
    String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
    if ("application/pdf".equals(mime) || n.endsWith(".pdf")) return Type.PDF;
    if ((mime != null && mime.startsWith("image/")) || n.matches(".*\\.(png|jpe?g|gif|webp|bmp)$")) return Type.IMAGE;
    if ((mime != null && mime.startsWith("text/")) || n.matches(".*\\.(txt|md|markdown|csv|log)$")) return Type.TEXT;
    return Type.UNSUPPORTED;
  }

  static PrintDocumentAdapter forUri(Context c, Uri uri, Type type, String name) {
    switch (type) {
      case PDF: return pdf(c, uri, name);
      case IMAGE: return image(c, uri, name);
      case TEXT: return text(c, uri, name);
      default: throw new IllegalArgumentException("Unsupported file");
    }
  }

  /** A PDF is already print-ready, so it's streamed straight to the print service. */
  static PrintDocumentAdapter pdf(Context c, Uri uri, String name) {
    return new PrintDocumentAdapter() {
      @Override public void onLayout(PrintAttributes old, PrintAttributes next, CancellationSignal cancel,
          LayoutResultCallback cb, Bundle extras) {
        if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
        cb.onLayoutFinished(new PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), false);
      }

      @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel,
          WriteResultCallback cb) {
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
          if (in == null) throw new IOException("Can't open file");
          Store.copy(in, out);
          cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (IOException e) {
          cb.onWriteFailed(e.getMessage());
        }
      }
    };
  }

  /** One page, image scaled to fit and centred. Landscape images are turned to fill a portrait page. */
  static PrintDocumentAdapter image(Context c, Uri uri, String name) {
    return new PagedAdapter(c, name) {
      Bitmap bmp;

      @Override int paginate(int w, int h) throws IOException {
        if (bmp == null) bmp = decode(c, uri, 3000);
        return 1;
      }

      @Override void drawPage(Canvas canvas, int w, int h, int page) {
        boolean rotate = (bmp.getWidth() > bmp.getHeight()) != (w > h);
        float bw = rotate ? bmp.getHeight() : bmp.getWidth(), bh = rotate ? bmp.getWidth() : bmp.getHeight();
        float scale = Math.min(w / bw, h / bh);
        Matrix m = new Matrix();
        if (rotate) {
          m.postRotate(90);
          m.postTranslate(bmp.getHeight(), 0);
        }
        m.postScale(scale, scale);
        m.postTranslate((w - bw * scale) / 2, (h - bh * scale) / 2);
        canvas.drawBitmap(bmp, m, new Paint(Paint.FILTER_BITMAP_FLAG));
      }
    };
  }

  /** Plain text, wrapped and split into pages at line boundaries. */
  static PrintDocumentAdapter text(Context c, Uri uri, String name) {
    return new PagedAdapter(c, name) {
      String body;
      StaticLayout layout;
      final List<Integer> tops = new ArrayList<>();

      @Override int paginate(int w, int h) throws IOException {
        if (body == null) {
          try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open file");
            body = Store.readAll(in);
          }
        }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(11);
        tp.setTypeface(Typeface.SERIF);
        layout = StaticLayout.Builder.obtain(body, 0, body.length(), tp, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.2f).build();
        tops.clear();
        tops.add(0);
        int pageTop = 0;
        for (int line = 0; line < layout.getLineCount(); line++) {
          if (layout.getLineBottom(line) - pageTop > h) {
            pageTop = layout.getLineTop(line);
            tops.add(pageTop);
          }
        }
        tops.add(layout.getHeight());
        return tops.size() - 1;
      }

      @Override void drawPage(Canvas canvas, int w, int h, int page) {
        int top = tops.get(page), bottom = tops.get(page + 1);
        canvas.save();
        canvas.clipRect(0, 0, w, bottom - top);
        canvas.translate(0, -top);
        layout.draw(canvas);
        canvas.restore();
      }
    };
  }

  static Bitmap decode(Context c, Uri uri, int maxSide) throws IOException {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > maxSide) opts.inSampleSize *= 2;
    try (InputStream in = c.getContentResolver().openInputStream(uri)) {
      Bitmap b = BitmapFactory.decodeStream(in, null, opts);
      if (b == null) throw new IOException("Not a readable image");
      return b;
    }
  }

  /** Shared adapter for content we lay out ourselves: paginate for the paper, then draw each page. */
  abstract static class PagedAdapter extends PrintDocumentAdapter {
    final Context c;
    final String name;
    PrintAttributes attrs;
    int pageCount;

    PagedAdapter(Context c, String name) { this.c = c; this.name = name; }

    abstract int paginate(int contentWidth, int contentHeight) throws IOException;

    abstract void drawPage(Canvas canvas, int w, int h, int page);

    @Override public void onLayout(PrintAttributes old, PrintAttributes next, CancellationSignal cancel,
        LayoutResultCallback cb, Bundle extras) {
      if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
      attrs = next;
      try {
        // PrintedPdfDocument works out the content area from the media size and margins.
        PrintedPdfDocument probe = new PrintedPdfDocument(c, next);
        PdfDocument.Page p = probe.startPage(0);
        Rect r = p.getInfo().getContentRect();
        probe.finishPage(p);
        probe.close();
        pageCount = paginate(r.width(), r.height());
        cb.onLayoutFinished(new PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build(), true);
      } catch (IOException | RuntimeException e) {
        cb.onLayoutFailed(e.getMessage());
      }
    }

    @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel,
        WriteResultCallback cb) {
      PrintedPdfDocument doc = new PrintedPdfDocument(c, attrs);
      try {
        for (int i = 0; i < pageCount; i++) {
          if (!TemplateDoc.contains(ranges, i)) continue;
          if (cancel.isCanceled()) { cb.onWriteCancelled(); return; }
          PdfDocument.Page p = doc.startPage(i);
          Rect r = p.getInfo().getContentRect();
          drawPage(p.getCanvas(), r.width(), r.height(), i);
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
  }
}
