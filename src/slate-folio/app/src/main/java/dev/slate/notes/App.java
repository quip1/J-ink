package dev.slate.notes;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import java.io.File;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {
  /** Data root: Notebooks/, Templates/, Annotations/, Exports/ live under here. */
  static File base;
  static Context ctx;

  @Override public void onCreate() {
    super.onCreate();
    ctx = this;
    refreshBase(this);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try { HiddenApiBypass.addHiddenApiExemptions(""); } catch (Throwable ignored) {}
    }
    Flavor.init(this);
  }

  /** Switches to the public Documents/Slate folder when access exists, migrating once. */
  static void refreshBase(Context c) {
    if (Storage.hasAccess(c)) {
      File pub = Storage.publicRoot();
      pub.mkdirs();
      if (!pub.equals(base)) Storage.migrate(c);
      base = pub;
    } else {
      base = Storage.privateBase(c);
    }
  }

  static File dir(String name) {
    File d = new File(base, name);
    d.mkdirs();
    return d;
  }
}
