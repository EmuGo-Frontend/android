package org.emugo.frontend;

import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

/**
 * EmuGo!'s own screen brightness with DS-style steps. It is a window override, so it needs no
 * system permission and covers the launcher, dialogs and the built-in emulators. Values stay at
 * or below 0.8 because some panels (Ayn Thor) treat anything above their cap as minimum.
 * Stored per device, not synced.
 */
final class Brightness {
  static final String[] LABELS = {"System", "Low", "Mid", "High", "Max"};
  private static final float[] VALUES = {
      WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, 0.06f, 0.2f, 0.45f, 0.8f};

  private Brightness() {}

  /** 0 = follow the system, 1..4 = Low, Mid, High, Max. */
  static int level(Context context) {
    int value = context.getSharedPreferences("local", 0).getInt("brightnessLevel", 0);
    return Math.max(0, Math.min(LABELS.length - 1, value));
  }

  static String label(Context context) {
    return LABELS[level(context)];
  }

  static void step(Context context, int direction) {
    int next = Math.max(0, Math.min(LABELS.length - 1, level(context) + direction));
    context.getSharedPreferences("local", 0).edit().putInt("brightnessLevel", next).apply();
  }

  static void apply(Context context, Window window) {
    if (window == null) return;
    WindowManager.LayoutParams params = window.getAttributes();
    params.screenBrightness = VALUES[level(context)];
    window.setAttributes(params);
  }
}
