package dev.jacob.filer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilerTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  static File write(File f, String text) throws IOException {
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

  /** A small tree: docs/a.txt, docs/sub/b.md, pic.png, .hidden */
  File tree() throws IOException {
    File root = tmp.newFolder("root");
    write(new File(root, "docs/a.txt"), "alpha");
    write(new File(root, "docs/sub/b.md"), "# bravo");
    write(new File(root, "pic.png"), "png!");
    write(new File(root, ".hidden"), "secret");
    return root;
  }

  // ---- files ----

  @Test public void listingPutsFoldersFirstAndHidesDotFiles() throws IOException {
    File root = tree();
    List<File> l = FileOps.list(root, false, FileOps.Sort.NAME);
    assertEquals(Arrays.asList("docs", "pic.png"), names(l));
    assertEquals(3, FileOps.list(root, true, FileOps.Sort.NAME).size());
  }

  @Test public void uniqueNamesCountUp() throws IOException {
    File root = tree();
    assertEquals("pic (1).png", FileOps.uniqueName(root, "pic.png").getName());
    write(new File(root, "pic (1).png"), "x");
    assertEquals("pic (2).png", FileOps.uniqueName(root, "pic.png").getName());
    assertEquals("docs (1)", FileOps.uniqueName(root, "docs").getName());
    assertEquals("new.txt", FileOps.uniqueName(root, "new.txt").getName());
  }

  @Test public void nameChecks() throws IOException {
    File root = tree();
    assertNotNull(FileOps.checkName(root, "", null));
    assertNotNull(FileOps.checkName(root, "a/b", null));
    assertNotNull(FileOps.checkName(root, "..", null));
    assertNotNull(FileOps.checkName(root, "pic.png", null));
    assertNull(FileOps.checkName(root, "fresh.txt", null));
    File pic = new File(root, "pic.png");
    assertNull(FileOps.checkName(root, "PIC.png", pic)); // just changing case is fine
  }

  @Test public void renameAndMkdir() throws IOException {
    File root = tree();
    File renamed = FileOps.rename(new File(root, "pic.png"), "photo.png");
    assertTrue(renamed.isFile());
    assertFalse(new File(root, "pic.png").exists());
    try { FileOps.rename(renamed, "docs"); fail("clash allowed"); } catch (IOException expected) { /* ok */ }
    assertTrue(FileOps.mkdir(root, "New folder").isDirectory());
  }

  @Test public void copyFoldersAndRenameOnClash() throws IOException {
    File root = tree();
    File dest = tmp.newFolder("dest");
    FileOps.copy(Arrays.asList(new File(root, "docs"), new File(root, "pic.png")), dest, FileOps.SILENT);
    assertEquals("# bravo", read(new File(dest, "docs/sub/b.md")));
    assertTrue(new File(root, "docs/a.txt").exists()); // original untouched
    List<File> again = FileOps.copy(Collections.singletonList(new File(root, "pic.png")), dest, FileOps.SILENT);
    assertEquals("pic (1).png", again.get(0).getName());
  }

  @Test public void cannotCopyOrMoveAFolderIntoItself() throws IOException {
    File root = tree();
    File docs = new File(root, "docs");
    try { FileOps.copy(Collections.singletonList(docs), new File(docs, "sub"), FileOps.SILENT); fail(); }
    catch (IOException expected) { /* ok */ }
    try { FileOps.move(Collections.singletonList(docs), docs, FileOps.SILENT); fail(); }
    catch (IOException expected) { /* ok */ }
  }

  @Test public void moveAndDelete() throws IOException {
    File root = tree();
    File dest = tmp.newFolder("dest");
    FileOps.move(Collections.singletonList(new File(root, "docs")), dest, FileOps.SILENT);
    assertFalse(new File(root, "docs").exists());
    assertEquals("alpha", read(new File(dest, "docs/a.txt")));
    FileOps.delete(new File(dest, "docs"), FileOps.SILENT);
    assertFalse(new File(dest, "docs").exists());
  }

  @Test public void sizesAndTypes() throws IOException {
    assertEquals("512 B", FileOps.size(512));
    assertEquals("1.5 KB", FileOps.size(1536));
    assertEquals("20 MB", FileOps.size(20L * 1024 * 1024));
    File root = tree();
    assertTrue(FileOps.isText(new File(root, "docs/a.txt")));
    assertFalse(FileOps.isText(new File(root, "pic.png")));
    assertTrue(FileOps.isZip(new File("book.EPUB")));
    long[] m = FileOps.measure(new File(root, "docs"));
    assertEquals(12, m[0]); // "alpha" + "# bravo"
    assertEquals(2, m[1]);
  }

  // ---- zips ----

  File zipOfTree() throws IOException {
    File root = tree();
    File zip = new File(tmp.getRoot(), "out.zip");
    Zips.create(Arrays.asList(new File(root, "docs"), new File(root, "pic.png")), zip, FileOps.SILENT);
    return zip;
  }

  @Test public void createAndBrowse() throws IOException {
    File zip = zipOfTree();
    Map<String, Long> e = Zips.entries(zip);
    assertTrue(e.containsKey("docs/sub/b.md"));
    assertEquals(Arrays.asList("docs", "pic.png"), itemNames(Zips.children(e, "")));
    assertEquals(Arrays.asList("sub", "a.txt"), itemNames(Zips.children(e, "docs/")));
    assertFalse(new File(zip.getPath() + ".part").exists());
  }

  @Test public void implicitFoldersShowUp() throws IOException {
    File zip = new File(tmp.getRoot(), "bare.zip");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
      z.putNextEntry(new ZipEntry("x/y/z.txt"));
      z.write("deep".getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
    }
    Map<String, Long> e = Zips.entries(zip);
    assertEquals(Collections.singletonList("x"), itemNames(Zips.children(e, "")));
    assertEquals(Collections.singletonList("y"), itemNames(Zips.children(e, "x/")));
  }

  @Test public void extractAllOrPart() throws IOException {
    File zip = zipOfTree();
    File all = tmp.newFolder("all");
    assertEquals(3, Zips.extract(zip, null, "", all, FileOps.SILENT));
    assertEquals("# bravo", read(new File(all, "docs/sub/b.md")));
    File part = tmp.newFolder("part");
    Zips.extract(zip, new HashSet<>(Collections.singletonList("docs/sub/")), "docs/", part, FileOps.SILENT);
    assertEquals("# bravo", read(new File(part, "sub/b.md")));
    assertFalse(new File(part, "a.txt").exists());
  }

  @Test public void refusesZipSlip() throws IOException {
    File zip = new File(tmp.getRoot(), "evil.zip");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
      z.putNextEntry(new ZipEntry("../../escaped.txt"));
      z.write(1);
      z.closeEntry();
    }
    File dest = tmp.newFolder("safe");
    try { Zips.extract(zip, null, "", dest, FileOps.SILENT); fail("zip slip allowed"); }
    catch (IOException expected) { /* ok */ }
    assertFalse(new File(tmp.getRoot().getParentFile(), "escaped.txt").exists());
  }

  @Test public void editingAZip() throws IOException {
    File zip = zipOfTree();
    // Add a file into a folder, replacing nothing.
    File extra = write(new File(tmp.getRoot(), "c.txt"), "charlie");
    Zips.add(zip, Collections.singletonList(extra), "docs/", FileOps.SILENT);
    assertTrue(Zips.entries(zip).containsKey("docs/c.txt"));
    // Adding again with new content replaces the entry instead of duplicating it.
    write(extra, "charlie 2");
    Zips.add(zip, Collections.singletonList(extra), "docs/", FileOps.SILENT);
    File out = tmp.newFolder("check");
    Zips.extract(zip, null, "", out, FileOps.SILENT);
    assertEquals("charlie 2", read(new File(out, "docs/c.txt")));
    // Rename a folder: everything under it moves.
    Zips.rename(zip, "docs/sub/", "notes");
    Map<String, Long> e = Zips.entries(zip);
    assertTrue(e.containsKey("docs/notes/b.md"));
    assertFalse(e.containsKey("docs/sub/b.md"));
    // Rename a file onto an existing name is refused.
    try { Zips.rename(zip, "docs/a.txt", "c.txt"); fail(); } catch (IOException expected) { /* ok */ }
    // Delete a folder and a file.
    Zips.remove(zip, new HashSet<>(Arrays.asList("docs/notes/", "pic.png")));
    e = Zips.entries(zip);
    assertFalse(e.containsKey("docs/notes/b.md"));
    assertFalse(e.containsKey("pic.png"));
    assertTrue(e.containsKey("docs/a.txt"));
    assertFalse(new File(zip.getPath() + ".bak").exists());
  }

  static List<String> names(List<File> l) {
    List<String> out = new ArrayList<>();
    for (File f : l) out.add(f.getName());
    return out;
  }

  static List<String> itemNames(List<Zips.Item> l) {
    List<String> out = new ArrayList<>();
    for (Zips.Item i : l) out.add(i.name);
    return out;
  }
}
