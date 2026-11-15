package org.emugo.frontend;

import android.app.Activity;

/**
 * Launch order for every game: EmuGo!'s built-in core when the system has one and it is turned on,
 * then the first installed standalone app, then RetroArch. When none of them can run the game, a
 * message names the app to install.
 */
final class GameLauncher {
  static void launch(Activity activity, Library library, Library.Game game) {
    if (game.system.core != null && library.builtIn(game.system)) {
      CoreActivity.start(activity, game);
      return;
    }
    withoutBuiltIn(activity, game, null);
  }

  /**
   * The rest of the order, also used when the built-in core fails. done runs once the game was
   * handed on or the message was closed.
   */
  static void withoutBuiltIn(Activity activity, Library.Game game, Runnable done) {
    ExternalEmulators.Installed app = ExternalEmulators.find(activity, game.system);
    if (app != null) {
      ExternalEmulators.start(activity, game, app);
      if (done != null)
        done.run();
      return;
    }
    if (RetroArchLauncher.canRun(activity, game)) {
      RetroArchLauncher.launch(activity, game, done);
      return;
    }
    String suggestion = ExternalEmulators.suggestion(game.system);
    String message = suggestion != null
        ? activity.getString(R.string.install_x_to_play_y, suggestion, game.system.name)
        : game.system.retroArch != null
        ? activity.getString(R.string.install_retroarch_to_play_x, game.system.name)
        : activity.getString(R.string.no_android_emulator_x, game.system.name);
    Runnable finished = () -> {
      if (done != null)
        done.run();
    };
    new PocketDialog.Builder(activity)
        .setMessage(message)
        .setPositiveButton(R.string.ok, (d, w) -> finished.run())
        .setOnCancelListener(d -> finished.run())
        .show();
  }

  private GameLauncher() {}
}
