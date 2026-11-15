package org.emugo.frontend;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standalone emulator apps that EmuGo! hands games to. */
final class ExternalEmulators {
  static final class App {
    final String key, name;
    /** Known package ids, most common first. */
    final String[] packages;
    /** Lowercase parts of a package id, for builds whose id is not listed above. */
    final String[] keywords;
    App(String key, String name, String[] packages, String... keywords) {
      this.key = key;
      this.name = name;
      this.packages = packages;
      this.keywords = keywords;
    }
  }

  private static final Map<String, App> APPS = new HashMap<>();

  private static void add(String key, String name, String[] packages, String... keywords) {
    APPS.put(key, new App(key, name, packages, keywords));
  }

  static {
    add("dolphin", "Dolphin",
        new String[] {"org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinemu.mmjr"}, "dolphin");
    add("azahar", "Azahar",
        new String[] {"org.azahar_emu.azahar", "io.github.lime3ds.android", "org.citra.citra_emu"},
        "azahar", "lime3ds", "citra");
    add("eden", "Eden",
        new String[] {"dev.eden.eden_emulator", "org.eden.eden_emulator", "org.yuzu.yuzu_emu"},
        "eden");
    add("ppsspp", "PPSSPP", new String[] {"org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"}, "ppsspp");
    add("melonds", "melonDS", new String[] {"me.magnum.melonds", "me.magnum.melonds.nightly"},
        "melonds");
    add("nethersx2", "NetherSX2", new String[] {"xyz.nethersx2.android", "xyz.aethersx2.android"},
        "nethersx2", "aethersx2");
    add("mupen64plusfz", "Mupen64Plus FZ",
        new String[] {"org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro"},
        "mupen64plus");
    add("redream", "Redream", new String[] {"io.recompiled.redream"}, "redream");
    add("flycast", "Flycast", new String[] {"com.flycast.emulator"}, "flycast");
    add("yabasanshiro", "Yaba Sanshiro 2",
        new String[] {"org.devmiyax.yabasanshioro2", "org.devmiyax.yabasanshioro2.pro"},
        "yabasanshi");
    add("duckstation", "DuckStation", new String[] {"com.github.stenzek.duckstation"},
        "duckstation");
    add("scummvm", "ScummVM", new String[] {"org.scummvm.scummvm"}, "scummvm");
    add("cemu", "Cemu", new String[] {"info.cemu.cemu"}, "cemu");
    add("ax360e", "aX360e", new String[] {"aenu.ax360e"}, "x360");
    add("aps3e", "aPS3e", new String[] {"aenu.aps3e"}, "aps3e");
    add("rpcsx", "RPCSX", new String[] {"net.rpcsx"}, "rpcsx");
    add("vita3k", "Vita3K", new String[] {"org.vita3k.emulator"}, "vita3k");
  }

  /** An app that is installed, with the package it was found under. */
  static final class Installed {
    final App app;
    final String pkg;
    Installed(App app, String pkg) {
      this.app = app;
      this.pkg = pkg;
    }
  }

  /** The first of the system's apps that is installed, or null. */
  static Installed find(Context context, Library.SystemInfo system) {
    if (system.apps.length == 0)
      return null;
    PackageManager pm = context.getPackageManager();
    List<ResolveInfo> launchable = null;
    for (String key : system.apps) {
      App app = APPS.get(key);
      if (app == null)
        continue;
      for (String pkg : app.packages) {
        try {
          pm.getPackageInfo(pkg, 0);
          return new Installed(app, pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      }
      if (launchable == null)
        launchable = pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
      for (ResolveInfo info : launchable) {
        String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
        for (String keyword : app.keywords)
          if (pkg.contains(keyword))
            return new Installed(app, info.activityInfo.packageName);
      }
    }
    return null;
  }

  /** The app to suggest when nothing that can play the system is installed. */
  static String suggestion(Library.SystemInfo system) {
    App app = system.apps.length == 0 ? null : APPS.get(system.apps[0]);
    return app == null ? null : app.name;
  }

  static void start(Activity activity, Library.Game game, Installed installed) {
    String pkg = installed.pkg;
    String key = installed.app.key;
    Intent intent =
        new Intent(Intent.ACTION_VIEW).setPackage(pkg).setDataAndType(game.uri, mime(game.uri));
    if (key.equals("melonds")) {
      intent.setComponent(new ComponentName(pkg, "me.magnum.melonds.ui.emulator.EmulatorActivity"));
      intent.setData(game.uri);
    } else if (key.equals("nethersx2")) {
      intent.setComponent(new ComponentName(pkg, pkg + ".EmulationActivity"));
      intent.setAction(Intent.ACTION_MAIN);
      intent.putExtra("bootPath", game.uri.toString());
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.setClipData(ClipData.newUri(activity.getContentResolver(), "Game", game.uri));
    // Some emulators read content through a second resolver path. An explicit grant helps.
    activity.grantUriPermission(pkg, game.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    String name = installed.app.name;
    try {
      activity.startActivity(intent);
    } catch (Exception e) {
      Intent fallback = activity.getPackageManager().getLaunchIntentForPackage(pkg);
      if (fallback != null) {
        activity.startActivity(fallback);
        PocketNotice.show(
            activity, activity.getString(R.string.emulator_rejects_game_intents, name));
      } else
        PocketNotice.show(
            activity, activity.getString(R.string.could_not_launch_x_x, name, e.getMessage()));
    }
  }

  private static String mime(Uri u) {
    String p = u.toString().toLowerCase(Locale.ROOT);
    if (p.endsWith(".chd"))
      return "application/x-chd";
    if (p.endsWith(".iso"))
      return "application/x-iso9660-image";
    return "application/octet-stream";
  }

  private ExternalEmulators() {}
}
