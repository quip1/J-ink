package dev.slate.notes;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class NoteActivity extends BaseActivity {
  static final int[] PALETTE = {
      0xFF000000, 0xFF505050, 0xFF8C8C8C, 0xFFC4C4C4,   // greyscale
      0xFFC62828, 0xFF1565C0, 0xFF2E7D32, 0xFFEF6C00};  // red, blue, green, orange
  private static final float[] WIDTHS_DP = {1.0f, 1.8f, 3.2f};
  private static final String[] WIDTH_LABELS = {"Pen ·", "Pen •", "Pen ●"};
  private static final int MAX_UNDO = 100;
  private static final int MAX_TEXT = 20000;

  enum Tool { PEN, ERASE, TEXT, SELECT }

  // document
  private Source src;
  private boolean prepared;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler ui = new Handler(Looper.getMainLooper());
  private TextView loading;
  private int pageIdx;
  private Page page;
  private boolean dirty;
  private final ArrayDeque<Action> undo = new ArrayDeque<>();

  // screen
  private SurfaceView surface;
  private FrameLayout overlay;
  private LinearLayout toolbarBox, textBar;
  private TextView penBtn, eraseBtn, textBtn, selBtn, colorBtn, pageLabel, fontBtn, sizeLabel;
  private LinearLayout selBar;

  // lasso selection
  private final List<Stroke> selS = new ArrayList<>();
  private final List<TextItem> selT = new ArrayList<>();
  private RectF selBox, selShown;
  private Bitmap selBmp;
  private float dragDX, dragDY, dragX0, dragY0;
  private boolean dragging;
  private long lastDragBlit;
  private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  // saving happens off the UI thread; loads wait only for their own file
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Map<String, Future<?>> pendingSaves = new HashMap<>();
  private boolean surfaceStale;   // ink added since the screen surface was last fully painted
  private Bitmap bmp, bgBmp;
  private Canvas bmpCanvas;
  private String bgKey;
  private float spacing, density;

  // pen state
  private TouchHelper touch;
  private Tool tool = Tool.PEN;
  private int widthIdx, color = PALETTE[0];
  private int penHold;          // >0 while a dialog/popup is up
  private boolean resumed;
  private boolean barHidden;    // two-finger double tap hides the hotbar
  private int turnFingers;      // 1..3
  private int turnMode;         // 0 tap, 1 swipe, 2 both
  private boolean usePressure, useTilt, preciseErase;
  private android.content.SharedPreferences prefs;

  // text state
  private EditText editor;
  private TextItem editingOrig;
  private float editX, editY, editW;
  private String font = "sans";
  private int textSizeDp = 22;

  // fallback stylus input (only if the Onyx raw layer never reports)
  private volatile boolean sdkAlive;
  private volatile long sdkStrokeAt;
  private boolean fallbackToastShown;
  private float[] fbBuf = new float[512];
  private int fbLen;
  private long fbStart;
  private boolean fbErasing;
  private float[] fbPress = new float[256], fbTilt = new float[256];
  private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  // finger gestures (taps and swipes with 1-3 fingers)
  private int gMax;
  private long gDownAt, gLast2Tap;
  private boolean gMoved;
  private int gId0;
  private float gX0, gY0, gLastX, gLastY;
  private Runnable pending2Tap;

  // speech
  private TextToSpeech tts;
  private boolean ttsReady, speaking;
  private int speakPage = -1;
  private List<int[]> sentences = new ArrayList<>();
  private List<RectF> hl;       // lines of the sentence being read
  private TextView readBtn;
  private int emptyRun;

  /** Undo record: what was added and what was removed in one step. */
  private static final class Action {
    final List<Stroke> addS, remS; final List<TextItem> addT, remT;
    Action(List<Stroke> addS, List<Stroke> remS, List<TextItem> addT, List<TextItem> remT) {
      this.addS = addS; this.remS = remS; this.addT = addT; this.remT = remT;
    }
  }

  // ======================================================================
  // setup
  // ======================================================================

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    String dir = getIntent().getStringExtra("dir"), book = getIntent().getStringExtra("book");
    if (dir != null) {
      Notebook nb = Notebook.open(new File(dir));
      if (nb != null) src = new NotebookSource(nb);
    } else if (book != null && new File(book).exists()) {
      src = Flavor.openBook(new File(book));
    }
    if (src == null) { finish(); return; }
    density = getResources().getDisplayMetrics().density;
    spacing = Ui.dp(this, 32);
    livePaint.setStrokeCap(Paint.Cap.ROUND);
    prefs = getSharedPreferences("slate", MODE_PRIVATE);
    usePressure = prefs.getBoolean("pressure", false);
    useTilt = prefs.getBoolean("tilt", false);
    preciseErase = prefs.getBoolean("preciseErase", false);
    widthIdx = Math.max(0, Math.min(WIDTHS_DP.length - 1, prefs.getInt("width", 0)));
    turnFingers = Math.max(1, Math.min(3, prefs.getInt("turnFingers", 1)));
    turnMode = Math.max(0, Math.min(2, prefs.getInt("turnMode", 2)));

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.WHITE);
    surface = new SurfaceView(this);
    root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
    overlay = new FrameLayout(this);
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    toolbarBox = buildToolbars();
    root.addView(toolbarBox, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    loading = new TextView(this);
    loading.setText("Opening…");
    loading.setTextSize(20);
    loading.setTextColor(Color.BLACK);
    loading.setGravity(Gravity.CENTER);
    loading.setBackgroundColor(Color.WHITE);
    root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);

    toolbarBox.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
      if (bt - t != ob - ot || r - l != or - ol) restartPen();
    });

    GestureDetector penTap = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onDown(MotionEvent e) { return true; }
      @Override public boolean onSingleTapUp(MotionEvent e) { textTap(e.getX(), e.getY()); return true; }
    });

    surface.setOnTouchListener((v, e) -> {
      int toolType = e.getToolType(0);
      if (selBox != null && e.getPointerCount() == 1 && selectionTouch(e)) return true;
      if (toolType == MotionEvent.TOOL_TYPE_FINGER) { fingerGesture(e); return true; }
      if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
        if (tool == Tool.TEXT) return penTap.onTouchEvent(e);
        if (tool == Tool.SELECT) return true;
        return fallbackPen(e);
      }
      return false;
    });

    surface.getHolder().addCallback(new SurfaceHolder.Callback() {
      @Override public void surfaceCreated(SurfaceHolder h) {}
      @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        boolean resized = bmp == null || bmp.getWidth() != w || bmp.getHeight() != hh;
        if (resized) {
          if (bmp != null) bmp.recycle();
          bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
          bmpCanvas = new Canvas(bmp);
          bgKey = null;
          prepareSource(w, hh);
        }
        restartPen();
        redraw();
      }
      @Override public void surfaceDestroyed(SurfaceHolder h) { if (touch != null) touch.closeRawDrawing(); }
    });
    updateLabels();
  }

  private LinearLayout buildToolbars() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setBackgroundColor(Color.WHITE);
    box.setClickable(true); // taps on the bar never fall through to the page
    LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-2, -1);

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(Ui.button(this, "‹", v -> onBackPressed()), wrap);
    penBtn = Ui.button(this, WIDTH_LABELS[0], v -> { if (tool == Tool.PEN) cycleWidth(); else setTool(Tool.PEN); });
    eraseBtn = Ui.button(this, "Erase", v -> {
      if (tool == Tool.ERASE) { preciseErase = !preciseErase; savePrefs(); applyPen(); updateLabels(); }
      else setTool(Tool.ERASE);
    });
    penBtn.setOnLongClickListener(v -> { penSettings(); return true; });
    textBtn = Ui.button(this, "Text", v -> setTool(tool == Tool.TEXT ? Tool.PEN : Tool.TEXT));
    selBtn = Ui.button(this, "Select", v -> setTool(tool == Tool.SELECT ? Tool.PEN : Tool.SELECT));
    colorBtn = Ui.button(this, "■", v -> showPalette(v));
    colorBtn.setTextSize(26);
    bar.addView(penBtn, wrap);
    bar.addView(eraseBtn, wrap);
    bar.addView(textBtn, wrap);
    bar.addView(selBtn, wrap);
    bar.addView(colorBtn, wrap);
    bar.addView(Ui.button(this, "Undo", v -> doUndo()), wrap);
    bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    if (src.reflowable()) bar.addView(Ui.button(this, "Aa", v -> readerSettings()), wrap);
    if (!src.isNotebook()) {
      readBtn = Ui.button(this, "Read", v -> { if (speaking) stopReading(); else startReading(); });
      readBtn.setOnLongClickListener(v -> { speechSettings(); return true; });
      bar.addView(readBtn, wrap);
    }
    bar.addView(Ui.button(this, "◀", v -> goTo(pageIdx - 1)), wrap);
    pageLabel = Ui.button(this, "", v -> pageMenu());
    bar.addView(pageLabel, wrap);
    bar.addView(Ui.button(this, "▶", v -> goTo(pageIdx + 1)), wrap);
    if (src.isNotebook()) bar.addView(Ui.button(this, "+", v -> addPage()), wrap);
    box.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

    textBar = new LinearLayout(this);
    textBar.setGravity(Gravity.CENTER_VERTICAL);
    fontBtn = Ui.button(this, "", v -> {
      holdPen(true);
      pickFont(font, k -> { font = k; applyEditorStyle(); updateLabels(); holdPen(false); }, () -> holdPen(false));
    });
    textBar.addView(fontBtn, wrap);
    textBar.addView(Ui.button(this, "A−", v -> bumpSize(-2)), wrap);
    sizeLabel = Ui.button(this, "", null);
    textBar.addView(sizeLabel, wrap);
    textBar.addView(Ui.button(this, "A+", v -> bumpSize(2)), wrap);
    textBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    TextView hint = new TextView(this);
    hint.setText("Esc: done  ·  Ctrl+Enter: next box");
    hint.setTextColor(0xFF555555);
    hint.setTextSize(13);
    hint.setGravity(Gravity.CENTER_VERTICAL);
    textBar.addView(hint, wrap);
    textBar.addView(Ui.button(this, "Done", v -> commitText()), wrap);
    textBar.setVisibility(View.GONE);
    selBar = new LinearLayout(this);
    selBar.setGravity(Gravity.CENTER_VERTICAL);
    TextView selHint = new TextView(this);
    selHint.setText("  Drag to move  ·  pick a color to recolor");
    selHint.setTextColor(0xFF555555);
    selHint.setTextSize(13);
    selHint.setGravity(Gravity.CENTER_VERTICAL);
    selBar.addView(selHint, new LinearLayout.LayoutParams(0, -1, 1));
    selBar.addView(Ui.button(this, "Delete", v -> deleteSelection()), wrap);
    selBar.addView(Ui.button(this, "Done", v -> clearSelection()), wrap);
    selBar.setVisibility(View.GONE);
    box.addView(textBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    box.addView(selBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    box.addView(Ui.rule(this));
    return box;
  }

  // ======================================================================
  // pen layer
  // ======================================================================

  private float penPx() { return WIDTHS_DP[widthIdx] * density; }

  /** (Re)opens raw drawing with the current page area, excluding the toolbar. */
  private void restartPen() {
    if (surface.getWidth() == 0) return;
    if (touch == null) touch = TouchHelper.create(surface, callback);
    else touch.closeRawDrawing();
    Rect limit = new Rect(0, 0, surface.getWidth(), surface.getHeight());
    List<Rect> exclude = new ArrayList<>();
    if (toolbarBox.getVisibility() == View.VISIBLE && toolbarBox.getHeight() > 0)
      exclude.add(new Rect(0, 0, toolbarBox.getWidth(), toolbarBox.getHeight()));
    touch.setStrokeWidth(penPx()).setLimitRect(limit, exclude).openRawDrawing();
    try {
      touch.enableFingerTouch(false);
      touch.enableSideBtnErase(true);
      touch.setFilterRepeatMovePoint(true);
    } catch (Throwable ignored) {}  // not every firmware has these
    applyPen();
  }

  private boolean penShouldRun() {
    return touch != null && resumed && penHold == 0 && tool != Tool.TEXT && !(tool == Tool.SELECT && selBox != null);
  }

  private void applyPen() {
    if (touch == null) return;
    boolean on = penShouldRun();
    touch.setRawDrawingEnabled(on);
    if (!on) return;
    if (tool == Tool.SELECT) {
      // The lasso: a thin dashed grey line that's cleared once the selection is made.
      touch.setStrokeStyle(TouchHelper.STROKE_STYLE_DASH);
      touch.setStrokeWidth(1.5f * density);
      touch.setStrokeColor(0xFF777777);
      touch.setRawDrawingRenderEnabled(true);
    } else if (tool == Tool.PEN) {
      touch.setStrokeStyle(usePressure ? TouchHelper.STROKE_STYLE_FOUNTAIN : TouchHelper.STROKE_STYLE_PENCIL);
      touch.setStrokeWidth(penPx());
      touch.setStrokeColor(color);
      touch.setRawDrawingRenderEnabled(true);
    } else if (preciseErase) {
      // Show the precise eraser's footprint as a light trail; the repaint afterwards clears it.
      touch.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL);
      touch.setStrokeWidth(eraseRadius() * 2);
      touch.setStrokeColor(0xFFD8D8D8);
      touch.setRawDrawingRenderEnabled(true);
    } else {
      touch.setRawDrawingRenderEnabled(false);
    }
  }

  private float eraseRadius() { return Ui.dp(this, preciseErase ? 5 : 10); }

  private void savePrefs() {
    prefs.edit().putBoolean("pressure", usePressure).putBoolean("tilt", useTilt)
        .putBoolean("preciseErase", preciseErase).putInt("width", widthIdx).apply();
  }

  private void penSettings() {
    holdPen(true);
    boolean[] checked = {usePressure, useTilt};
    new AlertDialog.Builder(this)
        .setTitle("Pen")
        .setMultiChoiceItems(new String[]{"Pressure sensitivity", "Tilt widens the stroke"}, checked,
            (d, which, on) -> checked[which] = on)
        .setPositiveButton("OK", (d, w) -> {
          usePressure = checked[0]; useTilt = checked[1]; savePrefs(); updateLabels();
        })
        .setNegativeButton("Cancel", null)
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  /** Width multipliers from pressure (0..1) and tilt amount (0..1), or null for a uniform stroke. */
  private float[] widthModel(float[] press, float[] tilt, int n) {
    if ((!usePressure && !useTilt) || n == 0) return null;
    float[] raw = new float[n];
    for (int i = 0; i < n; i++) {
      float f = 1f;
      if (usePressure) f = 0.25f + 1.15f * (float) Math.pow(Math.max(0f, Math.min(1f, press[i])), 1.6); // heavy hand
      if (useTilt) f *= 1f + 1.5f * Math.max(0f, Math.min(1f, tilt[i]));
      raw[i] = f;
    }
    float[] out = new float[n];   // light smoothing so sensor noise doesn't show as lumps
    for (int i = 0; i < n; i++) {
      float a = raw[Math.max(0, i - 1)], b = raw[i], c = raw[Math.min(n - 1, i + 1)];
      out[i] = (a + 2 * b + c) / 4f;
    }
    return out;
  }

  private static float normPressure(float p) {
    if (p <= 1.001f) return p;          // already normalised
    return Math.min(1f, p / 4095f);     // raw EMR scale
  }

  /** Suspends the pen while a dialog or popup is on screen. Calls must be paired. */
  private void holdPen(boolean hold) {
    penHold = Math.max(0, penHold + (hold ? 1 : -1));
    applyPen();
    if (!hold && penHold == 0) redraw();
  }

  @Override void onPickerClosed() { if (penHold > 0) holdPen(false); }

  private final RawInputCallback callback = new RawInputCallback() {
    @Override public void onBeginRawDrawing(boolean b, TouchPoint p) { sdkStrokeAt = System.currentTimeMillis(); sdkAlive = true; }
    @Override public void onEndRawDrawing(boolean b, TouchPoint p) {}
    @Override public void onRawDrawingTouchPointMoveReceived(TouchPoint p) {}
    @Override public void onRawDrawingTouchPointListReceived(TouchPointList list) {
      sdkAlive = true;
      float[][] d = capture(list);
      surface.post(() -> {
        if (tool == Tool.ERASE) erase(d[0]);
        else if (tool == Tool.PEN) addStroke(d[0], d[1], d[2]);
        else if (tool == Tool.SELECT) lassoSelect(d[0]);
      });
    }
    @Override public void onBeginRawErasing(boolean b, TouchPoint p) { sdkStrokeAt = System.currentTimeMillis(); sdkAlive = true; }
    @Override public void onEndRawErasing(boolean b, TouchPoint p) {}
    @Override public void onRawErasingTouchPointMoveReceived(TouchPoint p) {}
    @Override public void onRawErasingTouchPointListReceived(TouchPointList list) {
      sdkAlive = true;
      float[] pts = capture(list)[0];
      surface.post(() -> erase(pts));
    }
    @Override public void onPenUpRefresh(RectF r) { surface.post(() -> blit(null)); }
  };

  /** {x,y pairs, pressure 0..1, tilt amount 0..1} */
  private static float[][] capture(TouchPointList list) {
    List<TouchPoint> ps = list.getPoints();
    int n = ps.size();
    float[] xy = new float[n * 2], pr = new float[n], tl = new float[n];
    for (int i = 0; i < n; i++) {
      TouchPoint p = ps.get(i);
      xy[i * 2] = p.x; xy[i * 2 + 1] = p.y;
      pr[i] = normPressure(p.pressure);
      tl[i] = Math.min(1f, (float) Math.hypot(p.tiltX, p.tiltY) / 60f);
    }
    return new float[][]{xy, pr, tl};
  }

  /** Standard stylus input, used only while the SDK hasn't shown signs of life. */
  private boolean fallbackPen(MotionEvent e) {
    if (sdkAlive || tool == Tool.TEXT || penHold > 0) return false;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        fbLen = 0; fbStart = System.currentTimeMillis();
        fbErasing = tool == Tool.ERASE || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        fbAdd(e.getX(), e.getY(), e.getPressure(), e.getAxisValue(MotionEvent.AXIS_TILT));
        return true;
      case MotionEvent.ACTION_MOVE: {
        int from = fbLen;
        for (int h = 0; h < e.getHistorySize(); h++)
          fbAdd(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h),
              e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h));
        fbAdd(e.getX(), e.getY(), e.getPressure(), e.getAxisValue(MotionEvent.AXIS_TILT));
        if (!fbErasing) drawLive(from);
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        float[] pts = Arrays.copyOf(fbBuf, fbLen);
        float[] pr = Arrays.copyOf(fbPress, fbLen / 2), tl = Arrays.copyOf(fbTilt, fbLen / 2);
        long start = fbStart;
        boolean erasing = fbErasing;
        surface.postDelayed(() -> {
          if (sdkAlive && sdkStrokeAt >= start - 50) { redraw(); return; } // SDK had it after all
          if (erasing) erase(pts); else { addStroke(pts, pr, tl); redraw(); }
          if (!fallbackToastShown) {
            fallbackToastShown = true;
            toast("Low-latency pen unavailable, using standard input");
          }
        }, 120);
        return true;
      }
    }
    return true;
  }

  private void fbAdd(float x, float y, float p, float tilt) {
    if (fbLen + 2 > fbBuf.length) fbBuf = Arrays.copyOf(fbBuf, fbBuf.length * 2);
    int i = fbLen / 2;
    if (i >= fbPress.length) { fbPress = Arrays.copyOf(fbPress, fbPress.length * 2); fbTilt = Arrays.copyOf(fbTilt, fbTilt.length * 2); }
    fbPress[i] = normPressure(p);
    fbTilt[i] = Math.min(1f, tilt / (float) (Math.PI / 3));
    fbBuf[fbLen++] = x; fbBuf[fbLen++] = y;
  }

  private void drawLive(int from) {
    if (bmpCanvas == null || fbLen < 4) return;
    livePaint.setColor(color);
    livePaint.setStrokeWidth(penPx());
    int start = Math.max(0, from - 2);
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (int i = start; i + 3 < fbLen; i += 2) {
      bmpCanvas.drawLine(fbBuf[i], fbBuf[i + 1], fbBuf[i + 2], fbBuf[i + 3], livePaint);
      minX = Math.min(minX, Math.min(fbBuf[i], fbBuf[i + 2])); maxX = Math.max(maxX, Math.max(fbBuf[i], fbBuf[i + 2]));
      minY = Math.min(minY, Math.min(fbBuf[i + 1], fbBuf[i + 3])); maxY = Math.max(maxY, Math.max(fbBuf[i + 1], fbBuf[i + 3]));
    }
    if (minX == Float.MAX_VALUE) return;
    int pad = (int) penPx() + 4;
    blit(new Rect((int) minX - pad, (int) minY - pad, (int) maxX + pad, (int) maxY + pad));
  }

  // ======================================================================
  // gestures
  // ======================================================================

  /**
   * One engine for all finger input: taps and swipes with 1-3 fingers.
   * Page turns use the configured finger count and gesture; a two-finger
   * double tap always toggles the hotbar.
   */
  private void fingerGesture(MotionEvent e) {
    float slop = Ui.dp(this, 24);
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        gMax = 1; gMoved = false; gDownAt = e.getEventTime();
        gId0 = e.getPointerId(0);
        gX0 = gLastX = e.getX(); gY0 = gLastY = e.getY();
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
        gMax = Math.max(gMax, e.getPointerCount());
        break;
      case MotionEvent.ACTION_MOVE: {
        int idx = e.findPointerIndex(gId0);
        if (idx >= 0) { gLastX = e.getX(idx); gLastY = e.getY(idx); }
        if (Math.hypot(gLastX - gX0, gLastY - gY0) > slop) gMoved = true;
        break;
      }
      case MotionEvent.ACTION_POINTER_UP:
        if (e.getPointerId(e.getActionIndex()) == gId0) { gLastX = e.getX(e.getActionIndex()); gLastY = e.getY(e.getActionIndex()); }
        break;
      case MotionEvent.ACTION_UP: {
        if (e.getPointerId(0) == gId0) { gLastX = e.getX(); gLastY = e.getY(); }
        float dx = gLastX - gX0, dy = gLastY - gY0;
        long dur = e.getEventTime() - gDownAt;
        if (!gMoved && dur < 400) onFingerTap(gMax, gX0, gY0, e.getEventTime());
        else if (Math.abs(dx) > Ui.dp(this, 80) && Math.abs(dx) > Math.abs(dy) * 1.5f) onFingerSwipe(gMax, dx);
        break;
      }
      case MotionEvent.ACTION_CANCEL:
        gMax = 0;
        break;
    }
  }

  private boolean turnsByTap(int fingers) { return fingers == turnFingers && turnMode != 1; }

  private void onFingerTap(int fingers, float x, float y, long at) {
    if (fingers == 2) {
      if (at - gLast2Tap < 450) {            // second tap of a two-finger double tap
        gLast2Tap = 0;
        if (pending2Tap != null) { ui.removeCallbacks(pending2Tap); pending2Tap = null; }
        toggleHotbar();
        return;
      }
      gLast2Tap = at;
      if (turnsByTap(2)) {                   // wait briefly in case it becomes a double tap
        pending2Tap = () -> { pending2Tap = null; turnByTap(x); };
        ui.postDelayed(pending2Tap, 330);
      }
      return;
    }
    if (fingers == 1 && tool == Tool.TEXT) { textTap(x, y); return; }
    if (turnsByTap(fingers)) turnByTap(x);
  }

  private void turnByTap(float x) {
    commitText();
    goTo(x < surface.getWidth() / 3f ? pageIdx - 1 : pageIdx + 1);
  }

  private void onFingerSwipe(int fingers, float dx) {
    if (fingers != turnFingers || turnMode == 0) return;
    goTo(dx < 0 ? pageIdx + 1 : pageIdx - 1);
  }

  private void toggleHotbar() {
    barHidden = !barHidden;
    toolbarBox.setVisibility(barHidden ? View.GONE : View.VISIBLE);
    restartPen();
    redraw();
  }

  // ======================================================================
  // tools
  // ======================================================================

  private void setTool(Tool t) {
    if (t == tool) return;
    if (tool == Tool.TEXT) commitText();
    if (tool == Tool.SELECT) clearSelection();
    tool = t;
    textBar.setVisibility(t == Tool.TEXT ? View.VISIBLE : View.GONE);
    applyPen();
    updateLabels();
  }

  private void cycleWidth() {
    widthIdx = (widthIdx + 1) % WIDTHS_DP.length;
    savePrefs();
    if (touch != null) touch.setStrokeWidth(penPx());
    updateLabels();
  }

  private void setColor(int c) {
    color = c;
    if (selBox != null) { recolorSelection(c); }
    if (touch != null) touch.setStrokeColor(c);
    applyEditorStyle();
    updateLabels();
  }

  private void showPalette(View anchor) {
    LinearLayout row = new LinearLayout(this);
    row.setBackground(boxBg(Color.WHITE, Color.BLACK, 3));
    int pad = Ui.dp(this, 8), sz = Ui.dp(this, 44);
    row.setPadding(pad, pad, pad, pad);
    PopupWindow pw = new PopupWindow(row, -2, -2, true);
    for (int c : PALETTE) {
      View sw = new View(this);
      sw.setBackground(boxBg(c, Color.BLACK, c == color ? Ui.dp(this, 4) : 2));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
      lp.setMargins(pad / 2, 0, pad / 2, 0);
      sw.setOnClickListener(v -> { setColor(c); pw.dismiss(); });
      row.addView(sw, lp);
    }
    pw.setAnimationStyle(0);
    pw.setOnDismissListener(() -> holdPen(false));
    holdPen(true);
    pw.showAsDropDown(anchor);
  }

  private static GradientDrawable boxBg(int fill, int stroke, int strokeW) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setStroke(strokeW, stroke);
    return g;
  }

  // ======================================================================
  // editing
  // ======================================================================

  private void addStroke(float[] pts, float[] press, float[] tilt) {
    if (pts.length < 2 || page == null) return;
    Stroke s = new Stroke(penPx(), color, pts, widthModel(press, tilt, pts.length / 2));
    page.strokes.add(s);
    if (bmpCanvas != null) s.draw(bmpCanvas); // SDK already inked the screen; mirror into our bitmap
    push(new Action(Collections.singletonList(s), null, null, null));
    dirty = true;
    surfaceStale = true;
  }

  private void erase(float[] input) {
    float[] pts = input;
    if (page == null || pts.length < 2) return;
    float r = eraseRadius();
    if (preciseErase) pts = Stroke.densify(pts, r / 2f);
    RectF eb = new RectF(pts[0], pts[1], pts[0], pts[1]);
    for (int i = 2; i + 1 < pts.length; i += 2) eb.union(pts[i], pts[i + 1]);
    List<Stroke> goneS = new ArrayList<>(), pieces = new ArrayList<>();
    if (preciseErase) {
      for (Stroke s : page.strokes) {
        List<Stroke> left = s.cut(pts, eb, r);
        if (left != null) { goneS.add(s); pieces.addAll(left); }
      }
    } else {
      for (Stroke s : page.strokes) if (s.hitAny(pts, eb, r)) goneS.add(s);
    }
    List<TextItem> goneT = new ArrayList<>();
    for (TextItem t : page.texts) {
      if (preciseErase) break; // precise mode only cuts ink; use the stroke eraser for text boxes
      if (!RectF.intersects(t.bounds(), eb)) continue;
      for (int i = 0; i + 1 < pts.length; i += 2) if (t.contains(pts[i], pts[i + 1], 0)) { goneT.add(t); break; }
    }
    RectF trail = new RectF(eb);
    trail.inset(-r - 4, -r - 4);
    if (goneS.isEmpty() && goneT.isEmpty()) { if (preciseErase) redrawRegion(trail); return; } // clear the trail
    page.strokes.removeAll(goneS);
    page.strokes.addAll(pieces);
    page.texts.removeAll(goneT);
    Action a = new Action(pieces.isEmpty() ? null : pieces, goneS, null, goneT);
    push(a);
    dirty = true;
    RectF area = extent(a);
    if (preciseErase) area.union(trail);
    redrawRegion(area);
  }

  private void doUndo() {
    if (editor != null) { commitText(); return; }
    clearSelection();
    Action a = undo.pollLast();
    if (a == null || page == null) return;
    if (a.addS != null) page.strokes.removeAll(a.addS);
    if (a.addT != null) page.texts.removeAll(a.addT);
    if (a.remS != null) page.strokes.addAll(a.remS);
    if (a.remT != null) page.texts.addAll(a.remT);
    dirty = true;
    redrawRegion(extent(a));
  }

  /** Everything an action touched, for a partial repaint. */
  private static RectF extent(Action a) {
    RectF r = null;
    for (List<Stroke> l : Arrays.asList(a.addS, a.remS)) if (l != null) for (Stroke s : l) {
      if (r == null) r = s.inkBounds(); else r.union(s.inkBounds());
    }
    for (List<TextItem> l : Arrays.asList(a.addT, a.remT)) if (l != null) for (TextItem t : l) {
      if (r == null) r = t.bounds(); else r.union(t.bounds());
    }
    return r;
  }

  private void push(Action a) {
    undo.addLast(a);
    while (undo.size() > MAX_UNDO) undo.pollFirst();
  }

  // ======================================================================
  // lasso selection
  // ======================================================================

  private static boolean inPoly(float[] poly, float x, float y) {
    boolean in = false;
    int n = poly.length / 2;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      float xi = poly[2 * i], yi = poly[2 * i + 1], xj = poly[2 * j], yj = poly[2 * j + 1];
      if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) in = !in;
    }
    return in;
  }

  private void lassoSelect(float[] poly) {
    if (page == null) return;
    if (poly.length < 6) { redraw(); return; }
    RectF pb = new RectF(poly[0], poly[1], poly[0], poly[1]);
    for (int i = 2; i + 1 < poly.length; i += 2) pb.union(poly[i], poly[i + 1]);
    selS.clear(); selT.clear();
    for (Stroke st : page.strokes) {
      if (!RectF.intersects(st.bounds, pb) && !pb.contains(st.bounds)) continue;
      int n = st.pts.length / 2, step = Math.max(1, n / 40), inside = 0, total = 0;
      for (int k = 0; k < n; k += step) { total++; if (inPoly(poly, st.pts[k * 2], st.pts[k * 2 + 1])) inside++; }
      if (total > 0 && inside * 2 >= total) selS.add(st);
    }
    for (TextItem t : page.texts) { RectF b = t.bounds(); if (inPoly(poly, b.centerX(), b.centerY())) selT.add(t); }
    if (selS.isEmpty() && selT.isEmpty()) { redraw(); return; }
    buildSelection();
    selBar.setVisibility(View.VISIBLE);
    redraw();   // repaints without the selected items; they ride on top as an overlay
  }

  private void buildSelection() {
    RectF box = null;
    for (Stroke st : selS) { if (box == null) box = st.inkBounds(); else box.union(st.inkBounds()); }
    for (TextItem t : selT) { if (box == null) box = t.bounds(); else box.union(t.bounds()); }
    box.inset(-Ui.dp(this, 6), -Ui.dp(this, 6));
    selBox = box;
    if (selBmp != null) selBmp.recycle();
    selBmp = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(box.width())), Math.max(1, (int) Math.ceil(box.height())), Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(selBmp);
    c.translate(-box.left, -box.top);
    for (Stroke st : selS) st.draw(c);
    for (TextItem t : selT) t.draw(c);
    dragDX = dragDY = 0;
  }

  private Set<Object> selectionSet() {
    if (selBox == null) return null;
    Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
    set.addAll(selS);
    set.addAll(selT);
    return set;
  }

  /** Drag inside the box moves the selection; a touch outside ends it. */
  private boolean selectionTouch(MotionEvent e) {
    float x = e.getX(), y = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (selBox.contains(x, y)) { dragging = true; dragX0 = x; dragY0 = y; return true; }
        clearSelection();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging) return true;
        dragDX = x - dragX0; dragDY = y - dragY0;
        if (e.getEventTime() - lastDragBlit > 50) { lastDragBlit = e.getEventTime(); blitSelection(); }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (dragging) { dragging = false; dragDX = x - dragX0; dragDY = y - dragY0; commitMove(); }
        return true;
    }
    return true;
  }

  private void blitSelection() {
    RectF now = new RectF(selBox);
    now.offset(dragDX, dragDY);
    if (selShown != null) now.union(selShown);
    Rect r = new Rect();
    now.roundOut(r);
    r.inset(-4, -4);
    blit(r);
  }

  private void commitMove() {
    float dx = dragDX, dy = dragDY;
    if (Math.abs(dx) < 2 && Math.abs(dy) < 2) { dragDX = dragDY = 0; blitSelection(); return; }
    List<Stroke> ms = new ArrayList<>();
    for (Stroke st : selS) ms.add(st.moved(dx, dy));
    List<TextItem> mt = new ArrayList<>();
    for (TextItem t : selT) mt.add(t.moved(dx, dy));
    replaceSelection(ms, mt);
    RectF old = new RectF(selBox);
    selBox.offset(dx, dy);
    dragDX = dragDY = 0;
    old.union(selBox);
    Rect r = new Rect();
    old.roundOut(r);
    r.inset(-4, -4);
    blit(r);
  }

  /** Swaps the selected items for new versions, as one undoable step. */
  private void replaceSelection(List<Stroke> ns, List<TextItem> nt) {
    page.strokes.removeAll(selS); page.strokes.addAll(ns);
    page.texts.removeAll(selT); page.texts.addAll(nt);
    push(new Action(ns.isEmpty() ? null : ns, selS.isEmpty() ? null : new ArrayList<>(selS),
        nt.isEmpty() ? null : nt, selT.isEmpty() ? null : new ArrayList<>(selT)));
    selS.clear(); selS.addAll(ns);
    selT.clear(); selT.addAll(nt);
    dirty = true;
  }

  private void recolorSelection(int c) {
    List<Stroke> ns = new ArrayList<>();
    for (Stroke st : selS) ns.add(st.recolored(c));
    List<TextItem> nt = new ArrayList<>();
    for (TextItem t : selT) nt.add(t.recolored(c));
    replaceSelection(ns, nt);
    RectF b = new RectF(selBox);
    buildSelection();
    selBox = b;
    blitSelection();
  }

  private void deleteSelection() {
    if (selBox == null) return;
    page.strokes.removeAll(selS);
    page.texts.removeAll(selT);
    push(new Action(null, new ArrayList<>(selS), null, new ArrayList<>(selT)));
    dirty = true;
    selS.clear(); selT.clear();
    clearSelection();
  }

  private void clearSelection() {
    if (selBox == null) return;
    selS.clear(); selT.clear();
    selBox = null; selShown = null;
    dragging = false;
    if (selBmp != null) { selBmp.recycle(); selBmp = null; }
    selBar.setVisibility(View.GONE);
    redraw();
  }

  // ======================================================================
  // text boxes
  // ======================================================================

  private void textTap(float x, float y) {
    if (page == null) return;
    boolean wasEditing = editor != null;
    commitText();
    for (int i = page.texts.size() - 1; i >= 0; i--) {
      TextItem t = page.texts.get(i);
      if (t.contains(x, y, Ui.dp(this, 8))) { beginText(t, t.x, t.y, t.w); return; }
    }
    if (wasEditing) return; // first tap outside just finishes the box
    float size = textSizeDp * density;
    float margin = Ui.dp(this, 24), minW = Ui.dp(this, 220);
    float bx = Math.min(x, surface.getWidth() - margin - minW);
    bx = Math.max(margin / 2, bx);
    beginText(null, bx, Math.max(topInset(), y - size * 0.7f), surface.getWidth() - margin - bx);
  }

  private float topInset() {
    return toolbarBox.getVisibility() == View.VISIBLE ? toolbarBox.getHeight() + Ui.dp(this, 4) : Ui.dp(this, 4);
  }

  private void beginText(TextItem orig, float x, float y, float w) {
    editingOrig = orig;
    editX = x; editY = y; editW = w;
    if (orig != null) {
      font = orig.font;
      textSizeDp = Math.round(orig.size / density);
      color = orig.color;
      if (touch != null) touch.setStrokeColor(color);
      page.texts.remove(orig);
      redraw();
    }
    editor = new EditText(this);
    editor.setBackground(boxBg(Color.TRANSPARENT, 0xFF999999, 1));
    editor.setPadding(0, 0, 0, 0);
    editor.setIncludeFontPadding(true);
    editor.setGravity(Gravity.TOP | Gravity.START);
    editor.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
    editor.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
    editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
    editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT)});
    editor.setMinHeight(Math.round(textSizeDp * density * 1.4f));
    applyEditorStyle();
    if (orig != null) editor.setText(orig.text);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w), -2);
    lp.leftMargin = Math.round(x);
    lp.topMargin = Math.round(y);
    overlay.addView(editor, lp);
    editor.requestFocus();
    editor.setSelection(editor.getText().length());
    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    editor.post(() -> { if (editor != null) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT); });
    updateLabels();
  }

  private void applyEditorStyle() {
    if (editor == null) return;
    editor.setTypeface(Fonts.typeface(font));
    editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeDp * density);
    editor.setTextColor(color);
  }

  private void bumpSize(int d) {
    textSizeDp = Math.max(10, Math.min(96, textSizeDp + d));
    applyEditorStyle();
    updateLabels();
  }

  /** Turns the live editor into a TextItem on the page. Returns the item, or null. */
  private TextItem commitText() {
    if (editor == null) return null;
    EditText ed = editor;
    editor = null;
    String t = ed.getText().toString().replaceAll("\\s+$", "");
    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(ed.getWindowToken(), 0);
    overlay.removeView(ed);
    TextItem made = null;
    List<TextItem> removed = editingOrig != null ? Collections.singletonList(editingOrig) : null;
    if (!t.isEmpty()) {
      made = new TextItem(editX, editY, editW, textSizeDp * density, color, font, t);
      page.texts.add(made);
      push(new Action(null, null, Collections.singletonList(made), removed));
      dirty = true;
    } else if (removed != null) {
      push(new Action(null, null, null, removed));
      dirty = true;
    }
    editingOrig = null;
    RectF area = null;
    if (made != null) area = made.bounds();
    if (removed != null) { RectF b = removed.get(0).bounds(); if (area == null) area = b; else area.union(b); }
    if (area != null) redrawRegion(area);
    return made;
  }

  /** Ctrl+Enter: finish this box and start a fresh one just below it. */
  private void commitAndNext() {
    float x = editX, w = editW, y = editY;
    TextItem made = commitText();
    float nextY = made != null ? made.bounds().bottom + textSizeDp * density * 0.6f : y;
    if (nextY < surface.getHeight() - textSizeDp * density * 2) beginText(null, x, nextY, w);
  }

  // ======================================================================
  // keyboard
  // ======================================================================

  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
    int k = e.getKeyCode();
    boolean ctrl = e.isCtrlPressed();
    if (editor != null) {
      if (k == KeyEvent.KEYCODE_ESCAPE) { commitText(); return true; }
      if (ctrl && k == KeyEvent.KEYCODE_ENTER) { commitAndNext(); return true; }
      if (ctrl && (k == KeyEvent.KEYCODE_EQUALS || k == KeyEvent.KEYCODE_PLUS)) { bumpSize(2); return true; }
      if (ctrl && k == KeyEvent.KEYCODE_MINUS) { bumpSize(-2); return true; }
      return super.dispatchKeyEvent(e);
    }
    if (ctrl && k == KeyEvent.KEYCODE_Z) { doUndo(); return true; }
    if (ctrl && k == KeyEvent.KEYCODE_N) { addPage(); return true; }
    switch (k) {
      case KeyEvent.KEYCODE_PAGE_DOWN: goTo(pageIdx + 1); return true;
      case KeyEvent.KEYCODE_PAGE_UP: goTo(pageIdx - 1); return true;
      case KeyEvent.KEYCODE_P: setTool(Tool.PEN); return true;
      case KeyEvent.KEYCODE_E: setTool(Tool.ERASE); return true;
      case KeyEvent.KEYCODE_T: setTool(Tool.TEXT); return true;
      case KeyEvent.KEYCODE_ENTER:
        // With a keyboard and no pen: Enter starts a box under the last one (or at the top).
        if (tool == Tool.TEXT && page != null) {
          float y = topInset() + Ui.dp(this, 12);
          for (TextItem t : page.texts) y = Math.max(y, t.bounds().bottom + textSizeDp * density * 0.6f);
          float m = Ui.dp(this, 24);
          beginText(null, m, y, surface.getWidth() - 2 * m);
          return true;
        }
    }
    return super.dispatchKeyEvent(e);
  }

  // ======================================================================
  // pages
  // ======================================================================

  private Notebook nb() { return src instanceof NotebookSource ? ((NotebookSource) src).nb : null; }

  /** Opens / paginates the source off the UI thread, then shows the remembered page. */
  private void prepareSource(int w, int h) {
    int keepPage = prepared ? pageIdx : -1;
    String keepAnchor = prepared ? src.anchor(pageIdx) : null;
    if (prepared) { savePage(); awaitAllSaves(); }
    prepared = false;
    loading.setVisibility(View.VISIBLE);
    worker.submit(() -> {
      String err = null;
      try { src.prepare(w, h); } catch (Throwable t) { err = t.getMessage() != null ? t.getMessage() : t.toString(); }
      String e = err;
      ui.post(() -> {
        if (isFinishing()) return;
        if (e != null) { toast("Can't open: " + e); finish(); return; }
        prepared = true;
        int target = keepAnchor != null ? src.pageForAnchor(keepAnchor) : src.lastPage();
        pageIdx = Math.max(0, Math.min(target, src.pageCount() - 1));
        preKey = null; bgKey = null;
        loadPage();
        loading.setVisibility(View.GONE);
        redraw();
        updateLabels();
        prefetch(pageIdx + 1);
      });
    });
  }

  private void loadPage() {
    int w = bmp != null ? bmp.getWidth() : 1404, h = bmp != null ? bmp.getHeight() : 1872;
    clearSelectionQuietly();
    File f = src.annotationFile(pageIdx);
    awaitSave(f);
    page = Page.load(f, w, h);
    undo.clear();
    dirty = false;
  }

  /** Writes the page in the background; a snapshot keeps editing and saving independent. */
  private void savePage() {
    if (page == null || !dirty) return;
    Page snap = page.snapshot();
    File f = src.annotationFile(pageIdx);
    if (nb() != null) nb().noteSaved(pageIdx);
    dirty = false;
    pendingSaves.put(f.getPath(), io.submit(() -> {
      try { snap.save(f); } catch (IOException e) { ui.post(() -> toast("Save failed")); }
    }));
  }

  private void awaitSave(File f) {
    Future<?> fu = pendingSaves.remove(f.getPath());
    if (fu != null) try { fu.get(); } catch (Exception ignored) {}
  }

  /** Before anything that renames page files or reads them all (add/delete page, export, leaving). */
  private void awaitAllSaves() {
    for (Future<?> fu : pendingSaves.values()) try { fu.get(); } catch (Exception ignored) {}
    pendingSaves.clear();
  }

  private void clearSelectionQuietly() {
    if (selBox == null) return;
    selS.clear(); selT.clear();
    selBox = null; selShown = null; dragging = false;
    if (selBmp != null) { selBmp.recycle(); selBmp = null; }
    selBar.setVisibility(View.GONE);
  }

  private void goTo(int i) {
    commitText();
    if (!prepared || i < 0 || i >= src.pageCount() || i == pageIdx) return;
    savePage();
    pageIdx = i;
    loadPage();
    redraw();
    updateLabels();
    prefetch(i + 1 < src.pageCount() ? i + 1 : i - 1);
    if (speaking && speakPage != pageIdx) readPage();
  }

  private void addPage() {
    Notebook nb = nb();
    if (nb == null || !prepared) return;
    commitText();
    dirty = true;          // make sure the current page exists on disk so numbering holds
    savePage();
    awaitAllSaves();
    nb.insertPageAt(pageIdx + 1);
    pageIdx++;
    page = new Page(bmp != null ? bmp.getWidth() : 1404, bmp != null ? bmp.getHeight() : 1872);
    dirty = true;
    savePage();
    undo.clear();
    preKey = null;
    redraw();
    updateLabels();
  }

  private void pageMenu() {
    if (!prepared) return;
    commitText();
    List<String> items = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    items.add("Go to page…"); acts.add(this::goToDialog);
    items.add("Search…"); acts.add(this::searchDialog);
    List<String> marks = src.bookmarks();
    if (marks != null) {
      boolean on = isBookmarked();
      items.add(on ? "Remove bookmark" : "Bookmark this page"); acts.add(this::toggleBookmark);
      if (!marks.isEmpty()) { items.add("Bookmarks (" + marks.size() + ")"); acts.add(this::bookmarkList); }
    }
    if (src.canCrop()) {
      items.add(src.cropped() ? "Show full pages" : "Crop page margins"); acts.add(() -> {
        savePage(); awaitAllSaves();
        src.setCropped(!src.cropped());
        bgKey = null; preKey = null;
        loadPage();
        redraw();
        prefetch(pageIdx + 1);
      });
    }
    if (src.chapters() != null) { items.add("Chapters"); acts.add(this::chapterList); }
    items.add("Export…"); acts.add(this::exportDialog);
    items.add(nb() != null ? "Clear page" : "Clear my notes on this page"); acts.add(() -> {
      if (!page.isEmpty()) {
        push(new Action(null, new ArrayList<>(page.strokes), null, new ArrayList<>(page.texts)));
        page.strokes.clear(); page.texts.clear();
        dirty = true;
      }
    });
    Notebook nb = nb();
    if (nb != null) {
      items.add("Delete page"); acts.add(() -> {
        if (nb.pageCount() > 1) {
          savePage();
          awaitAllSaves();
          nb.deletePage(pageIdx);
          pageIdx = Math.min(pageIdx, nb.pageCount() - 1);
          preKey = null; bgKey = null;
          loadPage();
        } else {
          page.strokes.clear(); page.texts.clear();
          dirty = true; savePage(); undo.clear();
        }
      });
      items.add("Change template…"); acts.add(() -> {
        holdPen(true); // held again for the picker; released by it
        pickTemplate(nb.template, k -> {
          nb.template = k; nb.saveMeta(); bgKey = null; preKey = null; holdPen(false);
        }, () -> holdPen(false));
      });
    }
    holdPen(true);
    new AlertDialog.Builder(this)
        .setTitle("Page " + (pageIdx + 1) + " of " + src.pageCount())
        .setItems(items.toArray(new String[0]), (d, which) -> { acts.get(which).run(); updateLabels(); })
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  private void goToDialog() {
    EditText e = new EditText(this);
    e.setInputType(InputType.TYPE_CLASS_NUMBER);
    e.setHint("1 – " + src.pageCount());
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Go to page").setView(e)
        .setPositiveButton("Go", (d, w) -> {
          try { goTo(Integer.parseInt(e.getText().toString().trim()) - 1); } catch (NumberFormatException ignored) {}
        })
        .setNegativeButton("Cancel", null)
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  private void chapterList() {
    List<Object[]> ch = src.chapters();
    String[] names = new String[ch.size()];
    int current = 0;
    for (int i = 0; i < ch.size(); i++) {
      names[i] = ch.get(i)[0] + "   ·  p" + ((int) ch.get(i)[1] + 1);
      if ((int) ch.get(i)[1] <= pageIdx) current = i;
    }
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Chapters")
        .setSingleChoiceItems(names, current, (d, which) -> { d.dismiss(); goTo((int) ch.get(which)[1]); })
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  // ======================================================================
  // search & bookmarks
  // ======================================================================

  private void searchDialog() {
    EditText q = new EditText(this);
    q.setHint(src.isNotebook() ? "Find in typed text" : "Find in document");
    q.setSingleLine(true);
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Search").setView(q)
        .setPositiveButton("Search", (d, w) -> runSearch(q.getText().toString().trim()))
        .setNegativeButton("Cancel", null)
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  private void runSearch(String query) {
    if (query.isEmpty()) return;
    savePage();
    awaitAllSaves();
    ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    List<Integer> hits = new ArrayList<>();
    boolean[] cancel = {false};
    holdPen(true);
    AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Searching…")
        .setAdapter(ad, (d, which) -> goTo(hits.get(which)))
        .setNegativeButton("Close", null)
        .setOnDismissListener(d -> { cancel[0] = true; holdPen(false); })
        .show();
    String ql = query.toLowerCase(Locale.ROOT);
    worker.submit(() -> {
      int n = src.pageCount(), found = 0;
      for (int i = 0; i < n && !cancel[0] && found < 300; i++) {
        String t = src.pageText(i);
        if (t != null) {
          int at = t.toLowerCase(Locale.ROOT).indexOf(ql);
          if (at >= 0) {
            found++;
            int a = Math.max(0, at - 40), b = Math.min(t.length(), at + ql.length() + 60);
            String snip = (a > 0 ? "…" : "") + t.substring(a, b).replaceAll("\\s+", " ").trim() + (b < t.length() ? "…" : "");
            int pg = i;
            ui.post(() -> { hits.add(pg); ad.add("p" + (pg + 1) + "   " + snip); });
          }
        }
        if (i % 8 == 0) { int done = i + 1; ui.post(() -> dlg.setTitle("Searching… " + done + " / " + n)); }
      }
      int total = found;
      ui.post(() -> dlg.setTitle(total == 0 ? "No matches" : total + (total == 1 ? " match" : " matches")));
    });
  }

  private boolean isBookmarked() {
    if (!prepared) return false;
    List<String> m = src.bookmarks();
    if (m == null) return false;
    for (String a : m) if (src.pageForAnchor(a) == pageIdx) return true;
    return false;
  }

  private void toggleBookmark() {
    List<String> m = new ArrayList<>(src.bookmarks());
    boolean removed = m.removeIf(a -> src.pageForAnchor(a) == pageIdx);
    if (!removed) m.add(src.anchor(pageIdx));
    src.setBookmarks(m);
    updateLabels();
  }

  private void bookmarkList() {
    List<String> m = new ArrayList<>(src.bookmarks());
    List<Integer> pages = new ArrayList<>();
    for (String a : m) pages.add(src.pageForAnchor(a));
    Collections.sort(pages);
    String[] names = new String[pages.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = "Page " + (pages.get(i) + 1);
    }
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Bookmarks")
        .setItems(names, (d, which) -> goTo(pages.get(which)))
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  // ======================================================================
  // export
  // ======================================================================

  private void exportDialog() {
    savePage();
    awaitAllSaves();
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 20);
    box.setPadding(p, p / 2, p, 0);
    RadioGroup scope = radios(box, "Pages", new String[]{"This page", "All pages", "Choose pages"}, 0);
    EditText range = new EditText(this);
    range.setHint("e.g. 1-3, 7, 10-12");
    range.setVisibility(View.GONE);
    box.addView(range);
    scope.setOnCheckedChangeListener((g, id) -> range.setVisibility(id == 2 ? View.VISIBLE : View.GONE));
    RadioGroup fmt = radios(box, "As", new String[]{"PDF", "Slate package (.slnote)", src.isNotebook() ? "New notebook" : "New Slate notebook"}, 0);
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Export").setView(box)
        .setPositiveButton("Export", (d, w) -> {
          int n = src.pageCount();
          List<Integer> pages;
          int sc = scope.getCheckedRadioButtonId();
          if (sc == 0) pages = Collections.singletonList(pageIdx);
          else if (sc == 1) { pages = new ArrayList<>(); for (int i = 0; i < n; i++) pages.add(i); }
          else pages = Exporter.parseRange(range.getText().toString(), n);
          if (pages.isEmpty()) { toast("No pages selected"); return; }
          runExport(pages, fmt.getCheckedRadioButtonId());
        })
        .setNegativeButton("Cancel", null)
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  private RadioGroup radios(LinearLayout box, String label, String[] opts, int checked) {
    TextView t = new TextView(this);
    t.setText(label);
    t.setTextColor(0xFF555555);
    t.setPadding(0, Ui.dp(this, 8), 0, 0);
    box.addView(t);
    RadioGroup g = new RadioGroup(this);
    for (int i = 0; i < opts.length; i++) {
      RadioButton rb = new RadioButton(this);
      rb.setText(opts[i]);
      rb.setId(i);
      g.addView(rb);
    }
    g.check(checked);
    box.addView(g);
    return g;
  }

  private void runExport(List<Integer> pages, int format) {
    int w = bmp.getWidth(), h = bmp.getHeight();
    toast("Exporting " + pages.size() + (pages.size() == 1 ? " page…" : " pages…"));
    worker.submit(() -> {
      String msg;
      try {
        if (format == 0) msg = "Saved " + Exporter.pdf(src, pages, w, h).getPath();
        else if (format == 1) msg = "Saved " + Exporter.slnote(src, pages, w, h).getPath();
        else {
          File pkg = Exporter.slnote(src, pages, w, h);
          Notebook made;
          try (java.io.InputStream in = new java.io.FileInputStream(pkg)) { made = Exporter.importPackage(in); }
          pkg.delete();
          msg = "Made notebook “" + (made != null ? made.name : src.title()) + "”";
        }
      } catch (Throwable t) {
        msg = "Export failed: " + t.getMessage();
      }
      String m = msg;
      ui.post(() -> toast(m));
    });
  }

  // ======================================================================
  // reading settings
  // ======================================================================

  private void readerSettings() {
    commitText();
    android.content.SharedPreferences rp = Source.readerPrefs();
    String[] font = {rp.getString("font", "serif")};
    int[] size = {rp.getInt("size", 20)}, margin = {rp.getInt("margin", 1)}, spacingIdx = {rp.getInt("spacing", 1)};
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int p = Ui.dp(this, 20);
    box.setPadding(p, p / 2, p, 0);
    TextView fontRow = new TextView(this);
    fontRow.setTextSize(18);
    fontRow.setTextColor(Color.BLACK);
    fontRow.setPadding(0, p / 2, 0, p / 2);
    fontRow.setText("Font: " + Fonts.name(font[0]) + "  ▸");
    fontRow.setOnClickListener(v -> pickFont(font[0], k -> { font[0] = k; fontRow.setText("Font: " + Fonts.name(k) + "  ▸"); }, null));
    box.addView(fontRow);
    LinearLayout sizeRow = new LinearLayout(this);
    sizeRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView sizeLbl = new TextView(this);
    sizeLbl.setTextSize(18);
    sizeLbl.setTextColor(Color.BLACK);
    sizeLbl.setText("Size " + size[0]);
    sizeRow.addView(sizeLbl, new LinearLayout.LayoutParams(0, -2, 1));
    sizeRow.addView(Ui.button(this, "A−", v -> { size[0] = Math.max(10, size[0] - 1); sizeLbl.setText("Size " + size[0]); }),
        new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
    sizeRow.addView(Ui.button(this, "A+", v -> { size[0] = Math.min(48, size[0] + 1); sizeLbl.setText("Size " + size[0]); }),
        new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
    box.addView(sizeRow);
    RadioGroup mg = radios(box, "Margins", new String[]{"Narrow", "Normal", "Wide"}, margin[0]);
    RadioGroup sg = radios(box, "Line spacing", new String[]{"1.0", "1.2", "1.4", "1.6"}, spacingIdx[0]);
    TextView note = new TextView(this);
    note.setText("Notes you write are kept per layout: switching back brings them back.");
    note.setTextColor(0xFF666666);
    note.setTextSize(13);
    box.addView(note);
    ScrollView sv = new ScrollView(this);
    sv.addView(box);
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Reading").setView(sv)
        .setPositiveButton("Apply", (d, w) -> {
          rp.edit().putString("font", font[0]).putInt("size", size[0])
              .putInt("margin", mg.getCheckedRadioButtonId()).putInt("spacing", sg.getCheckedRadioButtonId()).apply();
          stopReading();
          prepareSource(bmp.getWidth(), bmp.getHeight());
        })
        .setNegativeButton("Cancel", null)
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  // ======================================================================
  // speech
  // ======================================================================

  private void startReading() {
    if (!prepared) return;
    emptyRun = 0;
    commitText();
    speaking = true;
    updateLabels();
    if (tts == null) {
      tts = new TextToSpeech(getApplicationContext(), status -> ui.post(() -> {
        if (status != TextToSpeech.SUCCESS) { toast("No text-to-speech engine installed"); stopReading(); return; }
        ttsReady = true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
          @Override public void onStart(String id) { ui.post(() -> onSentence(id)); }
          @Override public void onDone(String id) { ui.post(() -> onSentenceDone(id)); }
          @Override public void onError(String id) { ui.post(() -> onSentenceDone(id)); }
        });
        if (speaking) readPage();
      }));
    } else if (ttsReady) readPage();
  }

  private void stopReading() {
    speaking = false;
    speakPage = -1;
    if (tts != null) tts.stop();
    if (hl != null) { hl = null; blit(null); }
    updateLabels();
  }

  /** Queues every sentence on the current page; highlights follow onStart. */
  private void readPage() {
    if (!speaking || tts == null || !ttsReady) return;
    final int p = pageIdx;
    speakPage = p;
    tts.stop();
    android.content.SharedPreferences sp = prefs;
    tts.setSpeechRate(sp.getFloat("ttsRate", 1.0f));
    tts.setPitch(sp.getFloat("ttsPitch", 1.0f));
    worker.submit(() -> {
      String text = src.pageText(p);
      ui.post(() -> {
        if (!speaking || speakPage != p) return;
        sentences = new ArrayList<>();
        if (text != null) {
          BreakIterator bi = BreakIterator.getSentenceInstance();
          bi.setText(text);
          for (int a = bi.first(), b = bi.next(); b != BreakIterator.DONE; a = b, b = bi.next())
            if (!text.substring(a, b).trim().isEmpty()) sentences.add(new int[]{a, b});
        }
        if (sentences.isEmpty()) {
          // Scanned or blank pages: skip a few, then give up rather than racing to the end.
          if (text == null || ++emptyRun > 3) {
            toast(text == null ? "No readable text here" : "No text on these pages (scanned?)");
            stopReading();
            return;
          }
          advanceReading();
          return;
        }
        emptyRun = 0;
        for (int k = 0; k < sentences.size(); k++) {
          int[] r = sentences.get(k);
          tts.speak(text.substring(r[0], r[1]), TextToSpeech.QUEUE_ADD, null, "p" + p + "s" + k);
        }
      });
    });
  }

  private void onSentence(String id) {
    int[] ps = parseId(id);
    if (ps == null || ps[0] != pageIdx || ps[1] >= sentences.size()) return;
    int[] r = sentences.get(ps[1]);
    hl = src.textRects(pageIdx, r[0], r[1]);
    blit(null);
  }

  private void onSentenceDone(String id) {
    int[] ps = parseId(id);
    if (!speaking || ps == null || ps[0] != speakPage) return;
    if (ps[1] == sentences.size() - 1) advanceReading();
  }

  private void advanceReading() {
    if (pageIdx + 1 < src.pageCount()) goTo(pageIdx + 1);
    else { stopReading(); toast("End of document"); }
  }

  private static int[] parseId(String id) {
    try {
      int s = id.indexOf('s');
      return new int[]{Integer.parseInt(id.substring(1, s)), Integer.parseInt(id.substring(s + 1))};
    } catch (Exception e) { return null; }
  }

  private void speechSettings() {
    float[] rates = {0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.2f};
    String[] labels = new String[rates.length + 1];
    float cur = prefs.getFloat("ttsRate", 1.0f);
    int sel = 1;
    for (int i = 0; i < rates.length; i++) { labels[i] = "Speed " + rates[i] + "×"; if (Math.abs(rates[i] - cur) < 0.01f) sel = i; }
    labels[rates.length] = "Voice & engine settings…";
    holdPen(true);
    new AlertDialog.Builder(this).setTitle("Read aloud")
        .setSingleChoiceItems(labels, sel, (d, which) -> {
          d.dismiss();
          if (which < rates.length) {
            prefs.edit().putFloat("ttsRate", rates[which]).apply();
            if (speaking) readPage();
          } else {
            try { startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS")); }
            catch (Exception e) { toast("Open Settings › Accessibility › Text-to-speech"); }
          }
        })
        .setOnDismissListener(d -> holdPen(false))
        .show();
  }

  // ======================================================================
  // screen
  // ======================================================================

  private Bitmap preBmp;
  private volatile String preKey;
  private final Object bgLock = new Object();

  /** Renders the next page's background in the background, so turning forward is instant. */
  private void prefetch(int i) {
    if (!prepared || bmp == null || i < 0 || i >= src.pageCount()) return;
    int w = bmp.getWidth(), h = bmp.getHeight();
    String k = src.bgKey(i, w, h);
    if (k.equals(bgKey) || k.equals(preKey)) return;
    worker.submit(() -> {
      synchronized (bgLock) {
        if (k.equals(preKey)) return;
        if (preBmp == null || preBmp.getWidth() != w || preBmp.getHeight() != h) {
          if (preBmp != null) preBmp.recycle();
          preBmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        }
        preKey = null;
        src.renderBackground(i, new Canvas(preBmp), w, h);
        preKey = k;
      }
    });
  }

  private void ensureBackground() {
    int w = bmp.getWidth(), h = bmp.getHeight();
    String key = src.bgKey(pageIdx, w, h);
    if (key.equals(bgKey) && bgBmp != null) return;
    synchronized (bgLock) {
      if (key.equals(preKey) && preBmp != null) {   // prefetched: swap in
        Bitmap t = bgBmp; bgBmp = preBmp; preBmp = t;
        preKey = bgKey != null && preBmp != null && preBmp.getWidth() == w && preBmp.getHeight() == h ? bgKey : null;
        bgKey = key;
        return;
      }
      if (bgBmp == null || bgBmp.getWidth() != w || bgBmp.getHeight() != h) {
        if (bgBmp != null) bgBmp.recycle();
        bgBmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
      }
      src.renderBackground(pageIdx, new Canvas(bgBmp), w, h);
      bgKey = key;
    }
  }

  /** Rebuilds the page bitmap and shows it. Raw drawing is toggled so the SDK's ink overlay clears. */
  private void redraw() {
    if (bmp == null || page == null || !prepared) return;
    ensureBackground();
    bmpCanvas.drawBitmap(bgBmp, 0, 0, null);
    float k = page.w > 0 && page.w != bmp.getWidth() ? bmp.getWidth() / (float) page.w : 1f;
    Set<Object> skip = selectionSet();
    if (k != 1f) { bmpCanvas.save(); bmpCanvas.scale(k, k); page.drawContent(bmpCanvas, skip, null); bmpCanvas.restore(); }
    else page.drawContent(bmpCanvas, skip, null);
    if (speakPage != pageIdx) hl = null;
    if (touch != null && penShouldRun()) touch.setRawDrawingEnabled(false);
    blit(null);
    applyPen();
  }

  /** Repaints only r: background, then just the items that touch it. */
  private void redrawRegion(RectF r) {
    if (r == null || bmp == null || page == null || !prepared || surfaceStale
        || (page.w > 0 && page.w != bmp.getWidth())) { redraw(); return; }
    ensureBackground();
    Rect ir = new Rect();
    r.roundOut(ir);
    ir.inset(-4, -4);
    if (!ir.intersect(0, 0, bmp.getWidth(), bmp.getHeight())) return;
    bmpCanvas.save();
    bmpCanvas.clipRect(ir);
    bmpCanvas.drawBitmap(bgBmp, 0, 0, null);
    page.drawContent(bmpCanvas, selectionSet(), new RectF(ir));
    bmpCanvas.restore();
    if (touch != null && penShouldRun()) touch.setRawDrawingEnabled(false);
    blit(ir);
    applyPen();
  }

  private final Paint hlPaint = new Paint();

  private void blit(Rect dirtyRect) {
    if (bmp == null) return;
    SurfaceHolder h = surface.getHolder();
    Canvas c;
    try { c = dirtyRect == null ? h.lockCanvas() : h.lockCanvas(dirtyRect); }
    catch (IllegalArgumentException e) { return; }
    if (c == null) return;
    c.drawBitmap(bmp, 0, 0, null);
    if (dirtyRect == null) surfaceStale = false;
    if (selBox != null && selBmp != null) {
      RectF at = new RectF(selBox);
      at.offset(dragDX, dragDY);
      c.drawBitmap(selBmp, at.left, at.top, null);
      selPaint.setStyle(Paint.Style.STROKE);
      selPaint.setColor(0xFF555555);
      selPaint.setStrokeWidth(2f);
      selPaint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));
      c.drawRect(at, selPaint);
      selShown = at;
    }
    if (hl != null && !hl.isEmpty()) {   // a bar in the margin beside the sentence being read
      hlPaint.setColor(Color.BLACK);
      float x = Math.max(Ui.dp(this, 3), hl.get(0).left - Ui.dp(this, 10));
      c.drawRect(x - Ui.dp(this, 4), hl.get(0).top, x, hl.get(hl.size() - 1).bottom, hlPaint);
    }
    h.unlockCanvasAndPost(c);
  }

  private void updateLabels() {
    penBtn.setText(WIDTH_LABELS[widthIdx] + (usePressure || useTilt ? "~" : ""));
    eraseBtn.setText(preciseErase ? "Erase fine" : "Erase");
    Ui.setActive(penBtn, tool == Tool.PEN);
    Ui.setActive(eraseBtn, tool == Tool.ERASE);
    Ui.setActive(textBtn, tool == Tool.TEXT);
    Ui.setActive(selBtn, tool == Tool.SELECT);
    colorBtn.setTextColor(color);
    pageLabel.setText(prepared ? (pageIdx + 1) + "/" + src.pageCount() + (isBookmarked() ? " ★" : "") : "…");
    fontBtn.setText(Fonts.name(font) + " ▾");
    sizeLabel.setText(String.valueOf(textSizeDp));
    if (readBtn != null) { readBtn.setText(speaking ? "Stop" : "Read"); Ui.setActive(readBtn, speaking); }
  }

  // ======================================================================
  // lifecycle
  // ======================================================================

  @Override public void onBackPressed() {
    if (editor != null) { commitText(); return; }
    super.onBackPressed();
  }

  @Override protected void onPause() {
    super.onPause();
    resumed = false;
    commitText();
    savePage();
    awaitAllSaves();
    if (prepared) src.rememberPage(pageIdx);
    applyPen();
  }

  @Override protected void onResume() {
    super.onResume();
    resumed = true;
    turnFingers = Math.max(1, Math.min(3, prefs.getInt("turnFingers", 1)));
    turnMode = Math.max(0, Math.min(2, prefs.getInt("turnMode", 2)));
    if (bmp != null) redraw(); else applyPen();
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    if (tts != null) { tts.stop(); tts.shutdown(); }
    if (touch != null) touch.closeRawDrawing();
    worker.submit(() -> {
      if (src != null) src.close();
      synchronized (bgLock) { if (preBmp != null) preBmp.recycle(); }
    });
    worker.shutdown();
    io.shutdown();
    if (bmp != null) bmp.recycle();
    if (bgBmp != null) bgBmp.recycle();
  }
}
