package org.emugo.frontend;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.List;

final class ExternalEmulators {
  static void launch(Activity activity, Library library, Library.Game game) {
    PackageManager pm = activity.getPackageManager();
    String key = game.system.external;
    String[] hints;
    switch (key) {
      case "dolphin":
        hints = new String[] {"org.dolphinemu.dolphinemu", "org.dolphinemu.dolphinemu.mmjr"};
        break;
      case "azahar":
        hints = new String[] {
            "io.github.lime3ds.android", "org.azahar_emu.azahar", "org.citra.citra_emu"};
        break;
      case "eden":
        hints =
            new String[] {"dev.eden.eden_emulator", "org.eden.eden_emulator", "org.yuzu.yuzu_emu"};
        break;
      case "ppsspp":
        hints = new String[] {"org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"};
        break;
      case "melonds":
        hints = new String[] {"me.magnum.melonds", "me.magnum.melonds.nightly"};
        break;
      case "nethersx2":
        hints = new String[] {"xyz.aethersx2.android", "xyz.nethersx2.android"};
        break;
      default:
        hints = new String[0];
    }
    String pkg = null;
    for (String h : hints) {
      try {
        pm.getPackageInfo(h, 0);
        pkg = h;
        break;
      } catch (Exception ignored) {
      }
    }
    if (pkg == null) {
      Intent all =
          new Intent(Intent.ACTION_VIEW).setDataAndType(game.uri, "application/octet-stream");
      all.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      List<ResolveInfo> matches = pm.queryIntentActivities(all, 0);
      for (ResolveInfo m : matches) {
        String p = m.activityInfo.packageName;
        if (p.toLowerCase().contains(key)) {
          pkg = p;
          break;
        }
      }
    }
    if (pkg == null && RetroArchLauncher.canRun(activity, game)) {
      RetroArchLauncher.launch(activity, game);
      return;
    }
    if (pkg == null) {
      new PocketDialog.Builder(activity)
          .setMessage(activity.getString(R.string.no_compatible_x_app_was_found, key))
          .setPositiveButton(R.string.ok, null)
          .show();
      return;
    }
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
    try {
      activity.startActivity(intent);
    } catch (Exception e) {
      Intent fallback = pm.getLaunchIntentForPackage(pkg);
      if (fallback != null) {
        activity.startActivity(fallback);
        PocketNotice.show(
            activity, activity.getString(R.string.emulator_rejects_game_intents, key));
      } else
        PocketNotice.show(
            activity, activity.getString(R.string.could_not_launch_x_x, key, e.getMessage()));
    }
  }
  private static String mime(Uri u) {
    String p = u.toString().toLowerCase();
    if (p.endsWith(".chd"))
      return "application/x-chd";
    if (p.endsWith(".iso"))
      return "application/x-iso9660-image";
    return "application/octet-stream";
  }
}
