package dev.slate.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/**
 * One pen stroke: base width, color, x,y pairs in page pixels, and an optional
 * per-point width multiplier (from pressure/tilt). Immutable.
 */
final class Stroke {
  private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
  static {
    PAINT.setStyle(Paint.Style.STROKE);
    PAINT.setStrokeCap(Paint.Cap.ROUND);
    PAINT.setStrokeJoin(Paint.Join.ROUND);
  }

  final float width;
  final int color;
  final float[] pts;
  final float[] wm;      // null = uniform width; else one multiplier per point
  final RectF bounds = new RectF();
  private final float maxW;
  private Path path;

  Stroke(float width, int color, float[] pts) { this(width, color, pts, null); }

  Stroke(float width, int color, float[] pts, float[] wm) {
    this.width = width;
    this.color = color;
    this.pts = pts;
    this.wm = wm;
    float m = 1f;
    if (wm != null) for (float v : wm) m = Math.max(m, v);
    maxW = width * m;
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (int i = 0; i + 1 < pts.length; i += 2) {
      minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
      minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
    }
    bounds.set(minX, minY, maxX, maxY);
  }

  /** Bounds including the ink's own thickness. */
  RectF inkBounds() {
    RectF r = new RectF(bounds);
    r.inset(-maxW / 2f - 2, -maxW / 2f - 2);
    return r;
  }

  Stroke moved(float dx, float dy) {
    float[] p = new float[pts.length];
    for (int i = 0; i + 1 < p.length; i += 2) { p[i] = pts[i] + dx; p[i + 1] = pts[i + 1] + dy; }
    return new Stroke(width, color, p, wm);
  }

  Stroke recolored(int c) { return new Stroke(width, c, pts, wm); }

  void draw(Canvas c) {
    PAINT.setColor(color);
    int n = pts.length / 2;
    if (wm == null || n < 2) {
      PAINT.setStrokeWidth(wm == null ? width : width * wm[0]);
      c.drawPath(path(), PAINT);
      return;
    }
    // Variable width: short round-capped segments blend into a smooth taper.
    for (int i = 0; i < n - 1; i++) {
      PAINT.setStrokeWidth(width * (wm[i] + wm[i + 1]) * 0.5f);
      c.drawLine(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], PAINT);
    }
  }

  private Path path() {
    if (path != null) return path;
    Path p = new Path();
    int n = pts.length / 2;
    if (n == 0) return path = p;
    p.moveTo(pts[0], pts[1]);
    if (n == 1) { p.lineTo(pts[0] + 0.1f, pts[1]); return path = p; }
    for (int i = 1; i < n - 1; i++) { // quadratic smoothing through midpoints
      float x = pts[i * 2], y = pts[i * 2 + 1], nx = pts[i * 2 + 2], ny = pts[i * 2 + 3];
      p.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f);
    }
    p.lineTo(pts[pts.length - 2], pts[pts.length - 1]);
    return path = p;
  }

  private boolean near(RectF eb, float reach) {
    return !(eb.right < bounds.left - reach || eb.left > bounds.right + reach
        || eb.bottom < bounds.top - reach || eb.top > bounds.bottom + reach);
  }

  /** True if any eraser point lies within r of this stroke's ink. eb = eraser path bounds. */
  boolean hitAny(float[] e, RectF eb, float r) {
    float reach = r + maxW / 2f;
    if (!near(eb, reach)) return false;
    float r2 = reach * reach;
    int n = pts.length / 2;
    for (int k = 0; k + 1 < e.length; k += 2) {
      float x = e[k], y = e[k + 1];
      if (x < bounds.left - reach || x > bounds.right + reach || y < bounds.top - reach || y > bounds.bottom + reach) continue;
      if (n == 1) { if (d2(x, y, pts[0], pts[1]) <= r2) return true; continue; }
      for (int i = 0; i < n - 1; i++)
        if (segD2(x, y, pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3]) <= r2) return true;
    }
    return false;
  }

  /**
   * Precise erase: removes only the ink within r of the eraser path.
   * Returns null if untouched, else the surviving pieces (possibly empty).
   * e must already be densified (see densify) so gaps can't slip through.
   */
  List<Stroke> cut(float[] e, RectF eb, float r) {
    if (!hitAny(e, eb, r)) return null;
    float step = Math.max(1f, r / 2f);
    float[][] dense = densifyWith(pts, wm, step);
    float[] dp = dense[0], dw = dense[1];
    int n = dp.length / 2;
    boolean[] gone = new boolean[n];
    boolean any = false;
    for (int i = 0; i < n; i++) {
      float x = dp[i * 2], y = dp[i * 2 + 1];
      float reach = r + width * (dw != null ? dw[i] : 1f) / 2f;
      if (x < eb.left - reach || x > eb.right + reach || y < eb.top - reach || y > eb.bottom + reach) continue;
      float r2 = reach * reach;
      for (int k = 0; k + 1 < e.length; k += 2) {
        if (d2(x, y, e[k], e[k + 1]) <= r2) { gone[i] = true; any = true; break; }
      }
    }
    if (!any) return null;
    List<Stroke> out = new ArrayList<>();
    int i = 0;
    while (i < n) {
      while (i < n && gone[i]) i++;
      int start = i;
      while (i < n && !gone[i]) i++;
      int len = i - start;
      if (len >= 2) {
        float[] np = new float[len * 2];
        System.arraycopy(dp, start * 2, np, 0, len * 2);
        float[] nw = null;
        if (dw != null) { nw = new float[len]; System.arraycopy(dw, start, nw, 0, len); }
        out.add(new Stroke(width, color, np, nw));
      }
    }
    return out;
  }

  /** Inserts points so no two neighbours are more than step apart. */
  static float[] densify(float[] p, float step) { return densifyWith(p, null, step)[0]; }

  private static float[][] densifyWith(float[] p, float[] w, float step) {
    int n = p.length / 2;
    if (n < 2) return new float[][]{p, w};
    List<Float> op = new ArrayList<>(p.length * 2);
    List<Float> ow = w != null ? new ArrayList<>(n * 2) : null;
    for (int i = 0; i < n - 1; i++) {
      float ax = p[i * 2], ay = p[i * 2 + 1], bx = p[i * 2 + 2], by = p[i * 2 + 3];
      int parts = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / step));
      for (int s = 0; s < parts; s++) {
        float t = s / (float) parts;
        op.add(ax + (bx - ax) * t); op.add(ay + (by - ay) * t);
        if (ow != null) ow.add(w[i] + (w[i + 1] - w[i]) * t);
      }
    }
    op.add(p[p.length - 2]); op.add(p[p.length - 1]);
    if (ow != null) ow.add(w[n - 1]);
    float[] rp = new float[op.size()];
    for (int i = 0; i < rp.length; i++) rp[i] = op.get(i);
    float[] rw = null;
    if (ow != null) { rw = new float[ow.size()]; for (int i = 0; i < rw.length; i++) rw[i] = ow.get(i); }
    return new float[][]{rp, rw};
  }

  static float d2(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return dx * dx + dy * dy; }

  private static float segD2(float px, float py, float ax, float ay, float bx, float by) {
    float vx = bx - ax, vy = by - ay, len = vx * vx + vy * vy;
    float t = len == 0 ? 0 : ((px - ax) * vx + (py - ay) * vy) / len;
    t = Math.max(0, Math.min(1, t));
    return d2(px, py, ax + t * vx, ay + t * vy);
  }
}
