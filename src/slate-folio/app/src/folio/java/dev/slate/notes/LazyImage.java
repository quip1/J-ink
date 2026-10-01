package dev.slate.notes;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** An inline book image that only decodes when drawn, from a shared, size-bounded cache. */
final class LazyImage extends Drawable {
  interface Loader { byte[] load() throws Exception; }

  private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
    @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
  };
  private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

  private final String key;
  private final Loader loader;
  private final int srcW, srcH;

  LazyImage(String key, Loader loader, int srcW, int srcH, int w, int h) {
    this.key = key; this.loader = loader; this.srcW = srcW; this.srcH = srcH;
    setBounds(0, 0, w, h);
  }

  @Override public void draw(Canvas c) {
    Rect b = getBounds();
    Bitmap bmp = CACHE.get(key);
    if (bmp == null) {
      try {
        byte[] d = loader.load();
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (srcW / (sample * 2) >= b.width() && srcH / (sample * 2) >= b.height()) sample *= 2;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        bmp = BitmapFactory.decodeByteArray(d, 0, d.length, o);
        if (bmp != null) CACHE.put(key, bmp);
      } catch (Exception ignored) {}
    }
    if (bmp != null) c.drawBitmap(bmp, null, b, PAINT);
  }

  @Override public void setAlpha(int a) {}
  @Override public void setColorFilter(ColorFilter f) {}
  @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
