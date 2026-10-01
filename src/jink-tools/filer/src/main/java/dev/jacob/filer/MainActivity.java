package dev.jacob.filer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jacob.jink.Dialogs;
import dev.jacob.jink.InkActivity;
import dev.jacob.jink.Pager;
import dev.jacob.jink.Store;
import dev.jacob.jink.Ui;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A file manager: browse, open, rename, copy, move, delete, and work inside zip files. Copy and
 * Move work like a clipboard: pick files, choose Copy or Move, go to the destination, tap Paste.
 */
public class MainActivity extends InkActivity {
  private enum Screen { BROWSE, ZIP, EDIT }

  private SharedPreferences prefs;
  private Screen screen = Screen.BROWSE;
  private File dir;
  private final Set<File> selected = new LinkedHashSet<>();
  private Pager<?> pager;

  // Clipboard for Copy / Move.
  private final List<File> clipboard = new ArrayList<>();
  private boolean clipboardMove;

  // Zip being browsed, the folder inside it, and its selection.
  private File zip;
  private String zipFolder = "";
  private final Set<String> zipSelected = new LinkedHashSet<>();
  /** When set, the browser is picking files to add into this zip folder. */
  private File addTargetZip;
  private String addTargetFolder;

  // Text editor.
  private File editing;
  private EditText editor;
  private boolean editorDirty;
  private Screen editorReturn = Screen.BROWSE;

  private final DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("filer", MODE_PRIVATE);
    String last = prefs.getString("dir", null);
    dir = last != null && new File(last).isDirectory() ? new File(last) : Environment.getExternalStorageDirectory();
  }

  @Override protected void onResume() {
    super.onResume();
    if (!hasAccess()) { showAccessScreen(); return; }
    if (screen == Screen.BROWSE) showBrowser();
    else if (screen == Screen.ZIP && zip != null && zip.isFile()) showZip();
  }

  @Override protected void onPause() {
    super.onPause();
    if (dir != null) prefs.edit().putString("dir", dir.getAbsolutePath()).apply();
  }

  @Override protected boolean onPageKey(int d) { return screen != Screen.EDIT && pager != null && pager.turn(d); }

  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() {
    switch (screen) {
      case EDIT: leaveEditor(); return;
      case ZIP:
        if (!zipSelected.isEmpty()) { zipSelected.clear(); showZip(); }
        else if (!zipFolder.isEmpty()) { zipFolder = parentOf(zipFolder); showZip(); }
        else closeZip();
        return;
      default:
        if (!selected.isEmpty()) { selected.clear(); showBrowser(); }
        else if (addTargetZip != null) { cancelAddToZip(); }
        else if (dir.getParentFile() != null && !isRoot(dir)) { goTo(dir.getParentFile()); }
        else super.onBackPressed();
    }
  }

  // ---- storage permission ----

  private boolean hasAccess() {
    if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
    return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
  }

  private void showAccessScreen() {
    LinearLayout page = Ui.column(this);
    page.addView(header("Filer"), Ui.fill());
    Ui.add(page, Ui.text(this, "Filer needs permission to see and change the files on this device."
        + "\n\nOn the next screen, turn on “Allow access to manage all files” for Filer, then come back."), 16);
    Ui.add(page, Ui.button(this, "Grant access", v -> requestAccess()), 16);
    setPage(page);
  }

  @SuppressWarnings("deprecation")
  private void requestAccess() {
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
      } catch (ActivityNotFoundException e) {
        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      }
    } else {
      requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }
  }

  @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
    super.onRequestPermissionsResult(req, perms, results);
    if (hasAccess()) showBrowser();
  }

  // ---- browser ----

  private boolean isRoot(File f) {
    for (File r : roots()) if (r.equals(f)) return true;
    return f.getParentFile() == null;
  }

  /** Internal storage plus any SD cards, found through each volume's app-specific folder. */
  private List<File> roots() {
    List<File> out = new ArrayList<>();
    out.add(Environment.getExternalStorageDirectory());
    for (File app : getExternalFilesDirs(null)) {
      if (app == null) continue;
      String p = app.getAbsolutePath();
      int i = p.indexOf("/Android/data/");
      if (i > 0) {
        File root = new File(p.substring(0, i));
        if (!out.contains(root)) out.add(root);
      }
    }
    return out;
  }

  private String title(File f) {
    if (f.equals(Environment.getExternalStorageDirectory())) return "Internal storage";
    for (File r : roots()) if (r.equals(f)) return "SD card";
    return f.getName();
  }

  private void goTo(File d) {
    dir = d;
    selected.clear();
    showBrowser();
  }

  private FileOps.Sort sort() {
    try { return FileOps.Sort.valueOf(prefs.getString("sort", "NAME")); }
    catch (IllegalArgumentException e) { return FileOps.Sort.NAME; }
  }

  private void showBrowser() {
    screen = Screen.BROWSE;
    if (!dir.isDirectory()) dir = Environment.getExternalStorageDirectory();
    LinearLayout page = Ui.column(this);
    TextView up = Ui.button(this, "↑", v -> { if (!isRoot(dir) && dir.getParentFile() != null) goTo(dir.getParentFile()); });
    page.addView(header(title(dir), up, Ui.button(this, "⋯", v -> browserMenu()), refreshButton()), Ui.fill());
    TextView path = Ui.muted(this, dir.getAbsolutePath());
    path.setSingleLine(true);
    path.setEllipsize(android.text.TextUtils.TruncateAt.START);
    Ui.add(page, path, 4);

    View bar = actionBar();
    if (bar != null) Ui.add(page, bar, 8);

    List<File> files = FileOps.list(dir, prefs.getBoolean("hidden", false), sort());
    Pager<File> p = new Pager<>(this, Pager.fit(this, 64, bar == null ? 220 : 300), "This folder is empty.", this::fileRow);
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 6);
    page.addView(p.view(), lp);
    p.setItems(files, prefs.getInt("page:" + dir.getAbsolutePath(), 0));
    setPage(page);
  }

  /** The bar of actions that changes with what you're doing: picking files, pasting, or adding to a zip. */
  private View actionBar() {
    if (addTargetZip != null) {
      LinearLayout col = Ui.column(this);
      col.addView(Ui.text(this, "Pick files to add to " + addTargetZip.getName()
          + (addTargetFolder.isEmpty() ? "" : " / " + addTargetFolder)), Ui.fill());
      TextView add = Ui.button(this, "Add " + selected.size(), v -> addSelectedToZip());
      add.setEnabled(!selected.isEmpty());
      Ui.add(col, Ui.buttons(this, add, Ui.button(this, "Cancel", v -> cancelAddToZip())), 4);
      return col;
    }
    if (!clipboard.isEmpty() && selected.isEmpty()) {
      String what = clipboard.size() == 1 ? "“" + clipboard.get(0).getName() + "”" : clipboard.size() + " items";
      TextView paste = Ui.button(this, (clipboardMove ? "Move " : "Paste ") + what + " here", v -> paste());
      TextView cancel = Ui.button(this, "Cancel", v -> { clipboard.clear(); showBrowser(); });
      LinearLayout r = Ui.row(this);
      r.addView(paste, Ui.weight(3));
      LinearLayout.LayoutParams cp = Ui.weight(1);
      cp.leftMargin = Ui.dp(this, 6);
      r.addView(cancel, cp);
      return r;
    }
    if (selected.isEmpty()) return null;
    LinearLayout col = Ui.column(this);
    col.addView(Ui.text(this, selected.size() + " selected"), Ui.fill());
    TextView copy = Ui.button(this, "Copy", v -> toClipboard(false));
    TextView move = Ui.button(this, "Move", v -> toClipboard(true));
    TextView zipBtn = Ui.button(this, "Zip", v -> zipSelected());
    TextView del = Ui.button(this, "Delete", v -> deleteSelected());
    TextView more = Ui.button(this, "⋯", v -> selectionMenu());
    Ui.add(col, Ui.buttons(this, copy, move, zipBtn, del, more), 4);
    return col;
  }

  private View fileRow(File f) {
    boolean sel = selected.contains(f);
    LinearLayout row = Ui.row(this);
    int pad = Ui.dp(this, 6);
    row.setPadding(0, pad, 0, pad);
    TextView box = Ui.button(this, sel ? "✓" : "", v -> toggle(f));
    Ui.setActive(box, sel);
    row.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
    LinearLayout text = Ui.column(this);
    TextView name = Ui.text(this, (f.isDirectory() ? "▸ " : "") + f.getName());
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    if (f.isDirectory()) name.setTypeface(Typeface.DEFAULT_BOLD);
    text.addView(name);
    String sub;
    if (f.isDirectory()) {
      String[] kids = f.list();
      sub = (kids == null ? 0 : kids.length) + " items";
    } else {
      sub = FileOps.size(f.length()) + " · " + dates.format(new Date(f.lastModified()));
    }
    text.addView(Ui.muted(this, sub));
    LinearLayout.LayoutParams tp = Ui.weight(1);
    tp.leftMargin = Ui.dp(this, 10);
    row.addView(text, tp);
    row.setOnClickListener(v -> {
      if (!selected.isEmpty() || addTargetZip != null) toggle(f);
      else open(f);
    });
    row.setOnLongClickListener(v -> { toggle(f); return true; });
    return row;
  }

  private void toggle(File f) {
    if (!selected.remove(f)) selected.add(f);
    rememberPage();
    showBrowser();
  }

  private void rememberPage() {
    if (pager != null && dir != null) prefs.edit().putInt("page:" + dir.getAbsolutePath(), pager.page()).apply();
  }

  private void open(File f) {
    if (f.isDirectory()) { rememberPage(); goTo(f); return; }
    if (FileOps.isZip(f)) { zip = f; zipFolder = ""; zipSelected.clear(); showZip(); return; }
    if (FileOps.isText(f)) { openEditor(f, Screen.BROWSE); return; }
    openWith(f);
  }

  private void openWith(File f) {
    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(f), FilesProvider.mimeOf(f.getName()))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try { startActivity(i); }
    catch (ActivityNotFoundException e) { toast("No app can open " + f.getName()); }
  }

  private void browserMenu() {
    boolean hidden = prefs.getBoolean("hidden", false);
    String[] items = {"New folder…", "New text file…", "Go to…", "Sort: " + sort().name().toLowerCase()
        + "…", hidden ? "Hide hidden files" : "Show hidden files", "Select all"};
    Dialogs.choose(this, title(dir), items, i -> {
      switch (i) {
        case 0: Dialogs.prompt(this, "New folder", "New folder", n -> {
          try { FileOps.mkdir(dir, n); showBrowser(); } catch (IOException e) { toast(e.getMessage()); }
        }); break;
        case 1: Dialogs.prompt(this, "New text file", "notes.txt", n -> {
          String err = FileOps.checkName(dir, n, null);
          if (err != null) { toast(err); return; }
          File f = new File(dir, n.trim());
          try { Store.writeText(f, ""); openEditor(f, Screen.BROWSE); } catch (IOException e) { toast(e.getMessage()); }
        }); break;
        case 2: goToMenu(); break;
        case 3: Dialogs.choose(this, "Sort by", new String[]{"Name", "Newest first", "Largest first"}, s -> {
          prefs.edit().putString("sort", FileOps.Sort.values()[s].name()).apply();
          showBrowser();
        }); break;
        case 4: prefs.edit().putBoolean("hidden", !hidden).apply(); showBrowser(); break;
        default: selected.addAll(FileOps.list(dir, hidden, sort())); showBrowser();
      }
    });
  }

  private void goToMenu() {
    List<File> places = new ArrayList<>(roots());
    File home = Environment.getExternalStorageDirectory();
    for (String sub : new String[]{Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, "Books"}) {
      File f = new File(home, sub);
      if (f.isDirectory()) places.add(f);
    }
    String[] labels = new String[places.size()];
    for (int i = 0; i < places.size(); i++) labels[i] = i < roots().size() ? title(places.get(i)) : places.get(i).getName();
    Dialogs.choose(this, "Go to", labels, i -> goTo(places.get(i)));
  }

  private void selectionMenu() {
    List<File> sel = new ArrayList<>(selected);
    List<String> items = new ArrayList<>();
    if (sel.size() == 1) items.add("Rename…");
    items.add("Share…");
    items.add("Details");
    if (sel.size() == 1 && sel.get(0).isFile()) items.add("Open with…");
    items.add("Select all");
    items.add("Clear selection");
    Dialogs.choose(this, sel.size() + " selected", items.toArray(new String[0]), i -> {
      switch (items.get(i)) {
        case "Rename…": renameFile(sel.get(0)); break;
        case "Share…": share(sel); break;
        case "Details": details(sel); break;
        case "Open with…": openWith(sel.get(0)); break;
        case "Select all": selected.addAll(FileOps.list(dir, prefs.getBoolean("hidden", false), sort())); showBrowser(); break;
        default: selected.clear(); showBrowser();
      }
    });
  }

  private void renameFile(File f) {
    Dialogs.prompt(this, "Rename", f.getName(), InputType.TYPE_CLASS_TEXT, n -> {
      try { FileOps.rename(f, n); selected.clear(); showBrowser(); } catch (IOException e) { toast(e.getMessage()); }
    });
  }

  private void details(List<File> sel) {
    long[] total = new long[3];
    for (File f : sel) { long[] m = FileOps.measure(f); for (int k = 0; k < 3; k++) total[k] += m[k]; }
    String msg = (sel.size() == 1 ? sel.get(0).getAbsolutePath() + "\n\n" : "")
        + FileOps.size(total[0]) + "\n" + total[1] + " file" + (total[1] == 1 ? "" : "s")
        + (total[2] > 0 ? ", " + total[2] + " folder" + (total[2] == 1 ? "" : "s") : "")
        + (sel.size() == 1 ? "\nModified " + dates.format(new Date(sel.get(0).lastModified())) : "");
    Dialogs.message(this, sel.size() == 1 ? sel.get(0).getName() : sel.size() + " items", msg);
  }

  private void share(List<File> sel) {
    ArrayList<Uri> uris = new ArrayList<>();
    for (File f : sel) if (f.isFile()) uris.add(FilesProvider.uriFor(f));
    if (uris.isEmpty()) { toast("Folders can't be shared. Zip them first."); return; }
    Intent i;
    if (uris.size() == 1) {
      i = new Intent(Intent.ACTION_SEND).setType(FilesProvider.mimeOf(sel.get(0).getName())).putExtra(Intent.EXTRA_STREAM, uris.get(0));
    } else {
      i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
    }
    ClipData clip = ClipData.newRawUri("", uris.get(0));
    for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
    i.setClipData(clip);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(i, "Share"));
  }

  // ---- copy / move / delete / zip ----

  private void toClipboard(boolean move) {
    clipboard.clear();
    clipboard.addAll(selected);
    clipboardMove = move;
    selected.clear();
    toast((move ? "Go to where they should move to" : "Go to where the copies should go") + ", then tap the button at the top.");
    showBrowser();
  }

  private void paste() {
    List<File> items = new ArrayList<>(clipboard);
    File dest = dir;
    boolean move = clipboardMove;
    runTask(move ? "Moving" : "Copying", p -> {
      if (move) FileOps.move(items, dest, p); else FileOps.copy(items, dest, p);
    }, () -> { clipboard.clear(); showBrowser(); });
  }

  private void deleteSelected() {
    List<File> items = new ArrayList<>(selected);
    String what = items.size() == 1 ? "“" + items.get(0).getName() + "”" : items.size() + " items";
    Dialogs.confirm(this, "Delete " + what + "? This can't be undone.", "Delete", () ->
        runTask("Deleting", p -> { for (File f : items) FileOps.delete(f, p); }, () -> { selected.clear(); showBrowser(); }));
  }

  private void zipSelected() {
    List<File> items = new ArrayList<>(selected);
    String suggested = (items.size() == 1 ? items.get(0).getName().replaceFirst("\\.[^.]*$", "") : dir.getName()) + ".zip";
    Dialogs.prompt(this, "Zip file name", FileOps.uniqueName(dir, suggested).getName(), InputType.TYPE_CLASS_TEXT, n -> {
      String name = n.trim().toLowerCase().endsWith(".zip") ? n.trim() : n.trim() + ".zip";
      String err = FileOps.checkName(dir, name, null);
      if (err != null) { toast(err); return; }
      File out = new File(dir, name);
      runTask("Zipping", p -> Zips.create(items, out, p), () -> { selected.clear(); showBrowser(); });
    });
  }

  // ---- zip browsing and editing ----

  private void showZip() {
    screen = Screen.ZIP;
    Map<String, Long> entries;
    try { entries = Zips.entries(zip); }
    catch (IOException e) { toast("Can't open " + zip.getName() + ": " + e.getMessage()); closeZip(); return; }
    LinearLayout page = Ui.column(this);
    page.addView(header(zip.getName(), Ui.button(this, "←", v -> onBackPressed()),
        Ui.button(this, "⋯", v -> zipMenu()), refreshButton()), Ui.fill());
    Ui.add(page, Ui.muted(this, "Inside: /" + zipFolder), 4);
    if (!zipSelected.isEmpty()) {
      LinearLayout col = Ui.column(this);
      col.addView(Ui.text(this, zipSelected.size() + " selected"), Ui.fill());
      TextView extract = Ui.button(this, "Extract", v -> extractFromZip(new LinkedHashSet<>(zipSelected)));
      TextView rename = Ui.button(this, "Rename", v -> renameInZip());
      rename.setEnabled(zipSelected.size() == 1);
      TextView del = Ui.button(this, "Delete", v -> deleteInZip());
      TextView clear = Ui.button(this, "Clear", v -> { zipSelected.clear(); showZip(); });
      Ui.add(col, Ui.buttons(this, extract, rename, del, clear), 4);
      Ui.add(page, col, 8);
    }
    List<Zips.Item> items = Zips.children(entries, zipFolder);
    Pager<Zips.Item> p = new Pager<>(this, Pager.fit(this, 64, zipSelected.isEmpty() ? 220 : 300), "Empty.", this::zipRow);
    pager = p;
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.topMargin = Ui.dp(this, 6);
    page.addView(p.view(), lp);
    p.setItems(items);
    setPage(page);
  }

  private View zipRow(Zips.Item it) {
    boolean sel = zipSelected.contains(it.path);
    LinearLayout row = Ui.row(this);
    int pad = Ui.dp(this, 6);
    row.setPadding(0, pad, 0, pad);
    TextView box = Ui.button(this, sel ? "✓" : "", v -> toggleZip(it.path));
    Ui.setActive(box, sel);
    row.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
    LinearLayout text = Ui.column(this);
    TextView name = Ui.text(this, (it.folder ? "▸ " : "") + it.name);
    name.setSingleLine(true);
    if (it.folder) name.setTypeface(Typeface.DEFAULT_BOLD);
    text.addView(name);
    if (!it.folder) text.addView(Ui.muted(this, FileOps.size(it.size)));
    LinearLayout.LayoutParams tp = Ui.weight(1);
    tp.leftMargin = Ui.dp(this, 10);
    row.addView(text, tp);
    row.setOnClickListener(v -> {
      if (!zipSelected.isEmpty()) toggleZip(it.path);
      else if (it.folder) { zipFolder = it.path; showZip(); }
      else previewZipEntry(it);
    });
    row.setOnLongClickListener(v -> { toggleZip(it.path); return true; });
    return row;
  }

  private void toggleZip(String path) {
    if (!zipSelected.remove(path)) zipSelected.add(path);
    showZip();
  }

  private void closeZip() {
    zip = null;
    zipSelected.clear();
    showBrowser();
  }

  private void zipMenu() {
    String[] items = {"Extract everything", "Add files here…", "Select all here"};
    Dialogs.choose(this, zip.getName(), items, i -> {
      if (i == 0) extractFromZip(null);
      else if (i == 1) {
        addTargetZip = zip;
        addTargetFolder = zipFolder;
        dir = zip.getParentFile();
        selected.clear();
        showBrowser();
      } else {
        try { for (Zips.Item it : Zips.children(Zips.entries(zip), zipFolder)) zipSelected.add(it.path); }
        catch (IOException e) { toast(e.getMessage()); }
        showZip();
      }
    });
  }

  /** Extracts into a new folder named after the zip, next to it. */
  private void extractFromZip(Set<String> paths) {
    File parent = zip.getParentFile();
    File dest = FileOps.uniqueName(parent, zip.getName().replaceFirst("\\.[^.]*$", ""));
    File z = zip;
    String base = paths == null ? "" : zipFolder;
    runTask("Extracting", p -> {
      if (!dest.mkdirs()) throw new IOException("Couldn't create " + dest.getName());
      Zips.extract(z, paths, base, dest, p);
    }, () -> {
      zipSelected.clear();
      Dialogs.builder(this).setTitle("Extracted").setMessage("Saved to the folder “" + dest.getName() + "” next to the zip.")
          .setPositiveButton("Open folder", (d, w) -> { zip = null; goTo(dest); })
          .setNegativeButton("Stay here", (d, w) -> showZip()).show();
    });
  }

  private void renameInZip() {
    String path = zipSelected.iterator().next();
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String current = trimmed.substring(trimmed.lastIndexOf('/') + 1);
    Dialogs.prompt(this, "Rename inside zip", current, InputType.TYPE_CLASS_TEXT, n -> {
      File z = zip;
      runTask("Renaming", p -> Zips.rename(z, path, n.trim()), () -> { zipSelected.clear(); showZip(); });
    });
  }

  private void deleteInZip() {
    Set<String> paths = new LinkedHashSet<>(zipSelected);
    Dialogs.confirm(this, "Remove " + paths.size() + " item" + (paths.size() == 1 ? "" : "s") + " from the zip?", "Remove", () -> {
      File z = zip;
      runTask("Removing", p -> Zips.remove(z, paths), () -> { zipSelected.clear(); showZip(); });
    });
  }

  private void addSelectedToZip() {
    List<File> items = new ArrayList<>(selected);
    File z = addTargetZip;
    String folder = addTargetFolder;
    for (File f : items) {
      try { if (f.getCanonicalFile().equals(z.getCanonicalFile())) { toast("A zip can't contain itself"); return; } }
      catch (IOException e) { toast(e.getMessage()); return; }
    }
    runTask("Adding", p -> Zips.add(z, items, folder, p), () -> {
      selected.clear();
      addTargetZip = null;
      zip = z;
      zipFolder = folder;
      showZip();
    });
  }

  private void cancelAddToZip() {
    File z = addTargetZip;
    addTargetZip = null;
    selected.clear();
    zip = z;
    showZip();
  }

  /** Shows small text files inside the zip; anything else can be extracted. */
  private void previewZipEntry(Zips.Item it) {
    if (FileOps.isText(new File(it.name)) || it.size < 64 * 1024 && FileOps.extension(it.name).isEmpty()) {
      try (ZipFile z = new ZipFile(zip)) {
        ZipEntry e = z.getEntry(it.path);
        if (e != null) {
          try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 200_000) buf.write(b, 0, n);
            String text = buf.toString("UTF-8") + (buf.size() >= 200_000 ? "\n…" : "");
            Dialogs.builder(this).setTitle(it.name).setMessage(text).setPositiveButton("Close", null)
                .setNeutralButton("Extract", (d, w) -> extractFromZip(java.util.Collections.singleton(it.path))).show();
            return;
          }
        }
      } catch (IOException ignored) {
        // Fall through to offering extraction.
      }
    }
    Dialogs.confirm(this, "Extract “" + it.name + "” (" + FileOps.size(it.size) + ")?", "Extract",
        () -> extractFromZip(java.util.Collections.singleton(it.path)));
  }

  // ---- text editor ----

  private void openEditor(File f, Screen returnTo) {
    String text;
    try { text = Store.readText(f); } catch (IOException e) { toast("Can't read " + f.getName()); return; }
    screen = Screen.EDIT;
    editing = f;
    editorReturn = returnTo;
    editorDirty = false;
    LinearLayout page = Ui.column(this);
    TextView save = Ui.button(this, "Save", v -> saveEditor());
    page.addView(header(f.getName(), Ui.button(this, "←", v -> leaveEditor()), save), Ui.fill());
    editor = new EditText(this);
    editor.setText(text);
    editor.setTextColor(Ui.INK);
    editor.setTypeface(Typeface.MONOSPACE);
    editor.setTextSize(Ui.body(this) * 0.85f);
    editor.setGravity(Gravity.TOP | Gravity.START);
    editor.setBackground(null);
    editor.setHorizontallyScrolling(false);
    editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    editor.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) { editorDirty = true; }
    });
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, 0, 1);
    ep.topMargin = Ui.dp(this, 6);
    page.addView(editor, ep);
    setPage(page);
  }

  private boolean saveEditor() {
    try {
      Store.writeText(editing, editor.getText().toString());
      editorDirty = false;
      toast("Saved");
      return true;
    } catch (IOException e) {
      toast("Couldn't save: " + e.getMessage());
      return false;
    }
  }

  private void leaveEditor() {
    Runnable back = () -> {
      editor = null;
      editing = null;
      if (editorReturn == Screen.ZIP && zip != null) showZip(); else showBrowser();
    };
    if (!editorDirty) { back.run(); return; }
    AlertDialog d = Dialogs.builder(this).setMessage("Save changes to " + editing.getName() + "?")
        .setPositiveButton("Save", (x, w) -> { if (saveEditor()) back.run(); })
        .setNegativeButton("Discard", (x, w) -> back.run())
        .setNeutralButton("Cancel", null).create();
    d.show();
  }

  // ---- background work ----

  private interface Task { void run(FileOps.Progress p) throws IOException; }

  /** Runs file work off the UI thread with a small "Copying… name" dialog, then calls {@code done}. */
  private void runTask(String verb, Task task, Runnable done) {
    TextView msg = Ui.text(this, verb + "…");
    int pad = Ui.dp(this, 20);
    msg.setPadding(pad, pad, pad, pad);
    AlertDialog d = Dialogs.builder(this).setView(msg).setCancelable(false).create();
    d.show();
    long[] lastUpdate = {0};
    FileOps.Progress p = name -> {
      long now = System.currentTimeMillis();
      if (now - lastUpdate[0] < 700) return; // e-ink: don't redraw for every small file
      lastUpdate[0] = now;
      runOnUiThread(() -> msg.setText(verb + "…\n" + name));
    };
    new Thread(() -> {
      String err = null;
      try { task.run(p); } catch (IOException | RuntimeException e) { err = e.getMessage(); }
      String m = err;
      runOnUiThread(() -> {
        d.dismiss();
        if (m != null) toast(verb + " failed: " + m);
        done.run();
      });
    }).start();
  }

  private static String parentOf(String zipPath) {
    String t = zipPath.endsWith("/") ? zipPath.substring(0, zipPath.length() - 1) : zipPath;
    int slash = t.lastIndexOf('/');
    return slash < 0 ? "" : t.substring(0, slash + 1);
  }
}
