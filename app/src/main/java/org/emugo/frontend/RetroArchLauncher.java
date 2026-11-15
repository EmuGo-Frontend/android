package org.emugo.frontend;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional launch path for systems normally hosted by EmuGo's Libretro runner. */
final class RetroArchLauncher {
  private static final String[] PACKAGES = {"com.retroarch.aarch64", "com.retroarch"};
  private static final String ACTIVITY =
      "com.retroarch.browser.retroactivity.RetroActivityFuture";

  static void launch(Activity activity, Library.Game game) {
    PackageManager manager = activity.getPackageManager();
    ApplicationInfo retroArch = null;
    for (String name : PACKAGES) {
      try {
        retroArch = manager.getApplicationInfo(name, 0);
        break;
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    }
    if (retroArch == null) {
      new PocketDialog.Builder(activity)
          .setTitle("RetroArch")
          .setMessage("Install RetroArch to use this option.")
          .setPositiveButton("Close", null)
          .show();
      return;
    }

    ApplicationInfo installed = retroArch;
    String coreName = game.system.core;
    AtomicBoolean completed = new AtomicBoolean(false);
    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent response) {
        if (!completed.compareAndSet(false, true)) return;
        activity.unregisterReceiver(this);
        String[] cores = response.getStringArrayExtra("CORES");
        if (cores != null && Arrays.asList(cores).contains(coreName)) {
          startGame(activity, installed, game);
        } else {
          new PocketDialog.Builder(activity)
              .setTitle("RetroArch core missing")
              .setMessage(L10n.format(activity, "Install %s in RetroArch first.", coreName))
              .setPositiveButton("Open RetroArch", (dialog, which) -> {
                Intent open = manager.getLaunchIntentForPackage(installed.packageName);
                if (open != null) activity.startActivity(open);
              })
              .setNegativeButton("Close", null)
              .show();
        }
      }
    };
    IntentFilter responseFilter = new IntentFilter("com.retroarch.INSTALLED_CORES_RESULT");
    if (Build.VERSION.SDK_INT >= 33)
      activity.registerReceiver(receiver, responseFilter, Context.RECEIVER_EXPORTED);
    else
      activity.registerReceiver(receiver, responseFilter);
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      if (completed.compareAndSet(false, true)) {
        activity.unregisterReceiver(receiver);
        startGame(activity, installed, game);
      }
    }, 1200);
    activity.sendBroadcast(new Intent("com.retroarch.QUERY_INSTALLED_CORES")
        .setPackage(installed.packageName));
  }

  private static void startGame(Activity activity, ApplicationInfo retroArch, Library.Game game) {
    String coreFile = game.system.core + "_libretro_android.so";
    String corePath = retroArch.dataDir + "/cores/" + coreFile;
    Intent intent = new Intent(Intent.ACTION_MAIN)
        .setComponent(new ComponentName(retroArch.packageName, ACTIVITY))
        .setData(game.uri)
        .putExtra("ROM", game.uri.toString())
        .putExtra("LIBRETRO", corePath)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newUri(activity.getContentResolver(), "Game", game.uri));
    try {
      activity.startActivity(intent);
    } catch (Exception error) {
      PocketNotice.show(activity, L10n.format(activity,
          "RetroArch could not open this game: %s", error.getMessage()));
    }
  }

  private RetroArchLauncher() {}
}
