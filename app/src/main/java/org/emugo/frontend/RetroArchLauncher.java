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

/**
 * Opens games in RetroArch: when "Use RetroArch" is on, and as the fallback when the built-in
 * core fails, no standalone emulator is installed or EmuGo! has no emulator for the system.
 */
final class RetroArchLauncher {
  private static final String[] PACKAGES = {"com.retroarch.aarch64", "com.retroarch"};
  private static final String ACTIVITY =
      "com.retroarch.browser.retroactivity.RetroActivityFuture";

  private static ApplicationInfo find(PackageManager manager) {
    for (String name : PACKAGES) {
      try {
        return manager.getApplicationInfo(name, 0);
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    }
    return null;
  }

  /** True when RetroArch is installed and has a core for this game's system. */
  static boolean canRun(Context context, Library.Game game) {
    return game.system.retroArch != null && find(context.getPackageManager()) != null;
  }

  static void launch(Activity activity, Library.Game game) {
    launch(activity, game, null);
  }

  /** done runs once the game was handed to RetroArch or the user closed the message. */
  static void launch(Activity activity, Library.Game game, Runnable done) {
    Runnable finished = () -> {
      if (done != null)
        done.run();
    };
    PackageManager manager = activity.getPackageManager();
    ApplicationInfo retroArch = find(manager);
    if (retroArch == null || game.system.retroArch == null) {
      new PocketDialog.Builder(activity)
          .setTitle("RetroArch")
          .setMessage(retroArch == null
                  ? activity.getString(R.string.install_retroarch_to_use_this_option)
                  : activity.getString(R.string.no_compatible_x_app_was_found, game.system.name))
          .setPositiveButton(R.string.close, (d, w) -> finished.run())
          .setOnCancelListener(d -> finished.run())
          .show();
      return;
    }

    ApplicationInfo installed = retroArch;
    String coreName = game.system.retroArch;
    AtomicBoolean completed = new AtomicBoolean(false);
    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent response) {
        if (!completed.compareAndSet(false, true)) return;
        activity.unregisterReceiver(this);
        String[] cores = response.getStringArrayExtra("CORES");
        if (cores != null && Arrays.asList(cores).contains(coreName)) {
          startGame(activity, installed, game);
          finished.run();
        } else {
          new PocketDialog.Builder(activity)
              .setTitle(R.string.retroarch_core_missing)
              .setMessage(activity.getString(R.string.install_x_in_retroarch_first, coreName))
              .setPositiveButton(R.string.open_retroarch,
                  (dialog, which) -> {
                    Intent open = manager.getLaunchIntentForPackage(installed.packageName);
                    if (open != null)
                      activity.startActivity(open);
                    finished.run();
                  })
              .setNegativeButton(R.string.close, (dialog, which) -> finished.run())
              .setOnCancelListener(dialog -> finished.run())
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
        finished.run();
      }
    }, 1200);
    activity.sendBroadcast(new Intent("com.retroarch.QUERY_INSTALLED_CORES")
        .setPackage(installed.packageName));
  }

  private static void startGame(Activity activity, ApplicationInfo retroArch, Library.Game game) {
    String coreFile = game.system.retroArch + "_libretro_android.so";
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
      PocketNotice.show(activity,
          activity.getString(R.string.retroarch_could_not_open_this_game_x, error.getMessage()));
    }
  }

  private RetroArchLauncher() {}
}
