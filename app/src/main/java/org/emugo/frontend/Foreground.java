package org.emugo.frontend;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks whether any EmuGo! activity is in front. "Left" fires once nothing has been resumed for
 * a short grace period (screen locked, another app opened); "returned" fires when an activity
 * resumes after that. Switching between the launcher and a game does not count as leaving.
 */
final class Foreground {
  interface Listener {
    void onReturned();
    void onLeft();
  }

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final List<Listener> listeners = new ArrayList<>();
  private static boolean registered, visible = true;
  private static int resumed;
  private static final Runnable leave = () -> {
    visible = false;
    for (Listener listener : new ArrayList<>(listeners)) listener.onLeft();
  };

  private Foreground() {}

  /** Call from the launcher's onCreate, before its first onResume. */
  static void register(Application application) {
    if (registered) return;
    registered = true;
    application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
      @Override
      public void onActivityResumed(Activity activity) {
        resumed++;
        main.removeCallbacks(leave);
        if (!visible) {
          visible = true;
          for (Listener listener : new ArrayList<>(listeners)) listener.onReturned();
        }
      }
      @Override
      public void onActivityPaused(Activity activity) {
        resumed = Math.max(0, resumed - 1);
        if (resumed == 0) main.postDelayed(leave, 1500);
      }
      @Override
      public void onActivityCreated(Activity activity, Bundle state) {}
      @Override
      public void onActivityStarted(Activity activity) {}
      @Override
      public void onActivityStopped(Activity activity) {}
      @Override
      public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
      @Override
      public void onActivityDestroyed(Activity activity) {}
    });
  }

  static boolean visible() {
    return visible;
  }

  static void addListener(Listener listener) {
    listeners.add(listener);
  }
}
