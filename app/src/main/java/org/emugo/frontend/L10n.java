package org.emugo.frontend;

import android.content.Context;
import android.content.res.Configuration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Applies the in-app language setting to Android's string resources. The setting lives in the
 * synced settings.json; a copy is kept in local preferences so activities can read it in
 * attachBaseContext, before the library is loaded.
 */
final class L10n {
  private static final String KEY = "language";
  // Setting values are stored in English in settings.json; these are their display labels.
  private static final Map<String, Integer> OPTIONS = new HashMap<>();

  static {
    OPTIONS.put("System", R.string.system);
    OPTIONS.put("English", R.string.english);
    OPTIONS.put("Default", R.string.default_theme);
    OPTIONS.put("Dark", R.string.dark);
    OPTIONS.put("Light", R.string.light);
    OPTIONS.put("On", R.string.on);
    OPTIONS.put("Off", R.string.off);
    OPTIONS.put("Primary", R.string.primary);
    OPTIONS.put("Secondary", R.string.secondary);
    OPTIONS.put("Both", R.string.both);
    OPTIONS.put("orange", R.string.orange);
    OPTIONS.put("purple", R.string.purple);
    OPTIONS.put("black", R.string.black);
    OPTIONS.put("red", R.string.red);
    OPTIONS.put("pink", R.string.pink);
    OPTIONS.put("grey", R.string.grey);
    OPTIONS.put("green", R.string.green);
    OPTIONS.put("cyan", R.string.cyan);
    OPTIONS.put("blue", R.string.blue);
  }

  private L10n() {}

  /** For attachBaseContext: a context whose resources use the chosen language. */
  static Context wrap(Context base) {
    Locale locale = locale(base.getSharedPreferences("local", 0).getString(KEY, "System"));
    if (locale == null)
      return base;
    Configuration configuration = new Configuration(base.getResources().getConfiguration());
    configuration.setLocale(locale);
    return base.createConfigurationContext(configuration);
  }

  /**
   * Stores the language from settings.json. Returns true when it changed, so a running activity
   * knows to recreate itself with the new resources.
   */
  static boolean remember(Context context, String language) {
    android.content.SharedPreferences local = context.getSharedPreferences("local", 0);
    if (language.equals(local.getString(KEY, "System")))
      return false;
    local.edit().putString(KEY, language).commit();
    return true;
  }

  /** Display label for a stored setting value; names such as themes stay as they are. */
  static String option(Context context, String value) {
    Integer id = OPTIONS.get(value);
    return id == null ? value : context.getString(id);
  }

  static String[] options(Context context, String[] values) {
    String[] labels = new String[values.length];
    for (int index = 0; index < values.length; index++)
      labels[index] = option(context, values[index]);
    return labels;
  }

  private static Locale locale(String language) {
    if ("English".equals(language))
      return Locale.ENGLISH;
    if ("Deutsch".equals(language))
      return Locale.GERMAN;
    return null;
  }
}
