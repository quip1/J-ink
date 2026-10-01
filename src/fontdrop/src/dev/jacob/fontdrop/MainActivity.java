package dev.jacob.fontdrop;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_LEGACY_PERM = 2;
    private static final Set<String> FONT_EXT =
            new HashSet<>(Arrays.asList("ttf", "otf", "ttc", "otc"));
    private static final Set<String> WEB_FONT_EXT =
            new HashSet<>(Arrays.asList("woff", "woff2", "eot"));

    private TextView statusView, logView, permButton;
    private CheckBox overwriteBox;
    private ScrollView logScroll;
    private final List<Uri> pending = new ArrayList<>();
    private volatile boolean busy = false;
    private SharedPreferences prefs;

    // per-run counters
    private int added, skipped, renamed, replaced, ignoredWeb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("fontdrop", MODE_PRIVATE);
        getWindow().setWindowAnimations(0);
        buildUi();
        collectUris(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        collectUris(intent);
        maybeProcessPending();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        maybeProcessPending();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(16), dp(12), dp(16));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.WHITE);
        normal.setStroke(dp(2), Color.BLACK);
        normal.setCornerRadius(dp(6));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.BLACK);
        pressed.setCornerRadius(dp(6));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        t.setBackground(sl);
        t.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{Color.WHITE, Color.BLACK}));
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Font Drop");
        title.setTextSize(30);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Pull every font out of ZIP files and into your fonts folder.");
        sub.setTextSize(15);
        sub.setTextColor(Color.BLACK);
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.BLACK);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setStroke(dp(1), Color.BLACK);
        statusView.setBackground(box);
        root.addView(statusView);

        permButton = button("Grant storage access", v -> requestStorage());
        root.addView(permButton);

        root.addView(button("Choose ZIP files…", v -> pickFiles()));

        overwriteBox = new CheckBox(this);
        overwriteBox.setText("Replace fonts that already exist with the same name");
        overwriteBox.setTextSize(15);
        overwriteBox.setTextColor(Color.BLACK);
        overwriteBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.BLACK));
        overwriteBox.setChecked(prefs.getBoolean("overwrite", false));
        overwriteBox.setOnCheckedChangeListener((c, on) ->
                prefs.edit().putBoolean("overwrite", on).apply());
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbl.topMargin = dp(10);
        overwriteBox.setLayoutParams(cbl);
        root.addView(overwriteBox);

        TextView logLabel = new TextView(this);
        logLabel.setText("LOG");
        logLabel.setTextSize(13);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setTextColor(Color.BLACK);
        logLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(logLabel);

        logScroll = new ScrollView(this);
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(Color.WHITE);
        lb.setStroke(dp(1), Color.BLACK);
        logScroll.setBackground(lb);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(13);
        logView.setTextColor(Color.BLACK);
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(button("Clear log", v -> logView.setText("")));

        setContentView(root);
    }

    private File fontsDir() {
        return new File(Environment.getExternalStorageDirectory(), "fonts");
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_LEGACY_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        refreshStatus();
        maybeProcessPending();
    }

    private void refreshStatus() {
        boolean ok = hasStorage();
        permButton.setVisibility(ok ? View.GONE : View.VISIBLE);
        File dir = fontsDir();
        StringBuilder sb = new StringBuilder();
        sb.append("Target: ").append(dir.getAbsolutePath()).append('\n');
        if (!ok) {
            sb.append("Storage access: NOT GRANTED — tap the button below.");
        } else {
            int n = 0;
            File[] list = dir.listFiles();
            if (list != null) for (File f : list) if (FONT_EXT.contains(ext(f.getName()))) n++;
            sb.append(dir.isDirectory() ? ("Fonts installed: " + n)
                    : "Folder doesn't exist yet — it will be created.");
        }
        statusView.setText(sb.toString());
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(g, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == REQ_PICK && res == RESULT_OK && data != null) {
            collectUris(data);
            maybeProcessPending();
        }
    }

    private void collectUris(Intent in) {
        if (in == null) return;
        String a = in.getAction();
        if (Intent.ACTION_MAIN.equals(a)) return;
        List<Uri> found = new ArrayList<>();
        if (in.getData() != null) found.add(in.getData());
        ClipData cd = in.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) {
            Uri u = cd.getItemAt(i).getUri();
            if (u != null && !found.contains(u)) found.add(u);
        }
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null && !found.contains(u)) found.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> us = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) for (Uri u : us) if (!found.contains(u)) found.add(u);
        }
        pending.addAll(found);
        in.setAction(Intent.ACTION_MAIN); // don't reprocess on rotation
        in.setData(null);
        in.setClipData(null);
    }

    private void maybeProcessPending() {
        if (pending.isEmpty() || busy) return;
        if (!hasStorage()) {
            log("Waiting for storage access before installing " + pending.size() + " file(s)…");
            return;
        }
        final List<Uri> batch = new ArrayList<>(pending);
        pending.clear();
        final boolean overwrite = overwriteBox.isChecked();
        busy = true;
        new Thread(() -> {
            runBatch(batch, overwrite);
            busy = false;
            runOnUiThread(() -> { refreshStatus(); maybeProcessPending(); });
        }).start();
    }

    // ---------------------------------------------------------------- work

    private void runBatch(List<Uri> uris, boolean overwrite) {
        added = skipped = renamed = replaced = ignoredWeb = 0;
        File dir = fontsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log("ERROR: couldn't create " + dir.getAbsolutePath());
            return;
        }
        for (Uri u : uris) {
            String name = displayName(u);
            log("▸ " + name);
            try {
                String e = ext(name);
                if (FONT_EXT.contains(e)) {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        install(in, name, dir, overwrite);
                    }
                } else {
                    if (!processZipUri(u, dir, overwrite, StandardCharsets.UTF_8)) {
                        // Retry with the legacy DOS code page for archives
                        // that have non-UTF-8 filenames.
                        log("  (retrying with legacy filename encoding)");
                        processZipUri(u, dir, overwrite, Charset.forName("Cp437"));
                    }
                }
            } catch (Exception ex) {
                log("  ERROR: " + ex.getMessage());
            }
        }
        StringBuilder s = new StringBuilder("Done. ").append(added).append(" added");
        if (replaced > 0) s.append(", ").append(replaced).append(" replaced");
        if (renamed > 0) s.append(", ").append(renamed).append(" renamed (name clash)");
        if (skipped > 0) s.append(", ").append(skipped).append(" already installed");
        s.append('.');
        if (ignoredWeb > 0) s.append("\n").append(ignoredWeb)
                .append(" web font(s) (.woff/.woff2/.eot) skipped — e-readers can't use those.");
        log(s.toString());
        log("");
    }

    /** @return false if the archive had filenames that don't decode in this charset. */
    private boolean processZipUri(Uri u, File dir, boolean overwrite, Charset cs) throws IOException {
        try (InputStream raw = getContentResolver().openInputStream(u)) {
            if (raw == null) throw new IOException("can't open file");
            BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024);
            in.mark(4);
            byte[] sig = new byte[4];
            int n = in.read(sig);
            in.reset();
            if (n < 4 || sig[0] != 'P' || sig[1] != 'K') {
                log("  not a ZIP or font file — skipped");
                return true;
            }
            int before = added + skipped + renamed + replaced;
            try {
                walkZip(new ZipInputStream(in, cs), dir, overwrite, cs, 0);
            } catch (IllegalArgumentException malformedName) {
                return false;
            }
            if (added + skipped + renamed + replaced == before)
                log("  no .ttf/.otf/.ttc fonts found inside");
            return true;
        }
    }

    private void walkZip(ZipInputStream zin, File dir, boolean overwrite, Charset cs, int depth)
            throws IOException {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String path = e.getName().replace('\\', '/');
            String base = path.substring(path.lastIndexOf('/') + 1);
            if (path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")
                    || base.startsWith("._") || base.isEmpty()) continue;
            String x = ext(base);
            if (FONT_EXT.contains(x)) {
                install(new NoClose(zin), base, dir, overwrite);
            } else if (x.equals("zip") && depth < 4) {
                log("  ↳ nested " + base);
                walkZip(new ZipInputStream(new NoClose(zin), cs), dir, overwrite, cs, depth + 1);
            } else if (WEB_FONT_EXT.contains(x)) {
                ignoredWeb++;
            }
        }
    }

    private void install(InputStream in, String name, File dir, boolean overwrite) throws IOException {
        name = sanitize(name);
        File tmp = new File(dir, ".fontdrop-" + System.nanoTime() + ".tmp");
        long len = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); len += r; }
        }
        File dest = new File(dir, name);
        if (dest.exists()) {
            if (sameFile(dest, tmp)) {
                tmp.delete();
                skipped++;
                log("  = " + name + " (already installed)");
                return;
            }
            if (overwrite) {
                dest.delete();
                if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("couldn't write " + name); }
                replaced++;
                log("  ↻ " + name);
                return;
            }
            String stem = name.substring(0, name.lastIndexOf('.'));
            String ex = name.substring(name.lastIndexOf('.'));
            int i = 2;
            do { dest = new File(dir, stem + " (" + i++ + ")" + ex); } while (dest.exists());
            if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("couldn't write " + name); }
            renamed++;
            log("  + " + dest.getName() + " (renamed — different file had that name)");
            return;
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("couldn't write " + name); }
        added++;
        log("  + " + name + "  " + (len / 1024) + " KB");
    }

    private static boolean sameFile(File a, File b) throws IOException {
        if (a.length() != b.length()) return false;
        try (InputStream x = new BufferedInputStream(new FileInputStream(a));
             InputStream y = new BufferedInputStream(new FileInputStream(b))) {
            byte[] ba = new byte[32 * 1024], bb = new byte[32 * 1024];
            while (true) {
                int ra = readFully(x, ba), rb = readFully(y, bb);
                if (ra != rb) return false;
                if (ra <= 0) return true;
                for (int i = 0; i < ra; i++) if (ba[i] != bb[i]) return false;
            }
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static String sanitize(String n) {
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        // normalise extension to lower case so readers pick it up
        int dot = n.lastIndexOf('.');
        return n.substring(0, dot) + n.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    private String displayName(Uri u) {
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String s = c.getString(0);
                    if (s != null) return s;
                }
            } catch (Exception ignored) { }
        }
        String p = u.getLastPathSegment();
        return p == null ? "file" : p.substring(p.lastIndexOf('/') + 1);
    }

    private void log(String s) {
        runOnUiThread(() -> {
            logView.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** Lets us hand a ZipInputStream entry to code that closes its stream. */
    private static class NoClose extends FilterInputStream {
        NoClose(InputStream in) { super(in); }
        @Override public void close() { }
    }
}
