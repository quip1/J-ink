package dev.slate.notes;

import android.content.Context;
import java.io.File;

/** Slate: notebooks only, no document engines compiled in. */
final class Flavor {
  static void init(Context c) {}
  static Source openBook(File f) { return null; }
}
