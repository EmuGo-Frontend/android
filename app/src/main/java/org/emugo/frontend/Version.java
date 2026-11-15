package org.emugo.frontend;

import android.content.Context;

/** The installed versionName, so only the build scripts carry the number. */
final class Version {
  private static String cached;

  private Version() {}

  static String name(Context context) {
    if (cached == null) {
      try {
        cached = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
      } catch (Exception e) {
        cached = "0";
      }
    }
    return cached;
  }

  static String userAgent(Context context) {
    return "EmuGo/" + name(context) + " (Android " + android.os.Build.VERSION.RELEASE + ")";
  }
}
