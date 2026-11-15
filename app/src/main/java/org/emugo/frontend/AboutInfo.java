package org.emugo.frontend;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Data behind the About page: device details, changelog, installed cores and storage use. */
final class AboutInfo {
  static final String REPOSITORY = "https://github.com/EmuGo-Frontend/android";
  // Work-folder files that hold the player's data; everything else there can be recreated.
  private static final Set<String> KEEP = new java.util.HashSet<>(
      java.util.Arrays.asList("save.srm", "save.rtc", "state.sav"));

  static final class Core {
    String name, systems;
    long bytes;
    boolean used;
  }

  private AboutInfo() {}

  /** A plain-text block that can be pasted into a bug report. */
  static String deviceInfo(Activity activity) {
    DisplayMetrics metrics = new DisplayMetrics();
    activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
    float refresh = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
    String chip = Build.VERSION.SDK_INT >= 31
        ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim()
        : Build.HARDWARE;
    return "EmuGo! " + Version.name(activity) + "\n"
        + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
        + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
        + "Chip: " + chip + "\n"
        + "Screen: " + metrics.widthPixels + " × " + metrics.heightPixels + ", "
        + metrics.densityDpi + " dpi, " + Math.round(refresh) + " Hz\n"
        + "ABI: " + String.join(", ", Build.SUPPORTED_ABIS);
  }

  /** Release notes of the installed version (test suffixes like "-test" are ignored). */
  static String changelog(Context context) throws IOException {
    String version = Version.name(context).replaceFirst("-.*$", "");
    HttpURLConnection c = (HttpURLConnection) new URL(
        "https://api.github.com/repos/EmuGo-Frontend/android/releases/tags/v" + version)
                              .openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestProperty("Accept", "application/vnd.github+json");
    c.setRequestProperty("User-Agent", "EmuGo-Android/" + Version.name(context));
    int status = c.getResponseCode();
    if (status == 404)
      return L10n.text(context, "No release notes for this version");
    if (status == 403 || status == 429) {
      // Rate limited: the updater's last answer covers the latest release.
      UpdateChecker.Release cached = UpdateChecker.cachedRelease(context);
      if (cached != null && cached.version.replaceFirst("^[vV]", "").equals(version))
        return UpdateChecker.changelog(context, cached.notes);
      throw new UpdateChecker.RateLimitException(context, c.getHeaderField("X-RateLimit-Reset"));
    }
    if (status != 200) throw new IOException("HTTP " + status);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = c.getInputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
    try {
      return UpdateChecker.changelog(context,
          new JSONObject(out.toString("UTF-8")).optString("body", ""));
    } catch (org.json.JSONException e) {
      throw new IOException(e.getMessage());
    }
  }

  static File coresDir(Context context) {
    return new File(context.getFilesDir(), "cores");
  }

  /** Downloaded cores; a core is "used" when one of the library's systems runs on it. */
  static List<Core> cores(Context context, Library library) {
    Set<String> used = new LinkedHashSet<>();
    for (Library.SystemInfo system : library.visibleSystems())
      if (system.core != null) used.add(system.core);
    List<Core> out = new ArrayList<>();
    File[] files = coresDir(context).listFiles();
    if (files == null) return out;
    for (File file : files) {
      if (!file.getName().endsWith("_libretro_android.so")) continue;
      Core core = new Core();
      core.name = file.getName().replace("_libretro_android.so", "");
      core.bytes = file.length();
      File archive = new File(file.getPath() + ".zip");
      if (archive.exists()) core.bytes += archive.length();
      core.used = used.contains(core.name);
      List<String> systems = new ArrayList<>();
      for (Library.SystemInfo system : Library.SYSTEMS)
        if (core.name.equals(system.core)) systems.add(system.name);
      core.systems = String.join(", ", systems);
      out.add(core);
    }
    out.sort((a, b) -> a.name.compareTo(b.name));
    return out;
  }

  static void removeCore(Context context, String name) {
    new File(coresDir(context), name + "_libretro_android.so").delete();
    new File(coresDir(context), name + "_libretro_android.so.zip").delete();
  }

  static long size(File file) {
    if (file == null || !file.exists()) return 0;
    if (file.isFile()) return file.length();
    long total = 0;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) total += size(child);
    return total;
  }

  static File imageCache(Context context) {
    return new File(context.getCacheDir(), "images");
  }

  static File updateCache(Context context) {
    return new File(context.getCacheDir(), "updates");
  }

  static File workFolders(Context context) {
    return new File(context.getFilesDir(), "run");
  }

  /** Space taken by game copies, extracted archives and BIOS copies in the work folders. */
  static long temporaryGameFiles(Context context) {
    long total = 0;
    File[] games = workFolders(context).listFiles();
    if (games == null) return 0;
    for (File game : games) {
      File[] files = game.listFiles();
      if (files == null) continue;
      for (File file : files)
        if (!KEEP.contains(file.getName())) total += size(file);
    }
    return total;
  }

  /** Clears image and update caches plus temporary game files; saves and states stay. */
  static void clearCache(Context context) {
    delete(imageCache(context));
    delete(updateCache(context));
    File[] games = workFolders(context).listFiles();
    if (games == null) return;
    for (File game : games) {
      File[] files = game.listFiles();
      if (files == null) continue;
      for (File file : files)
        if (!KEEP.contains(file.getName())) delete(file);
    }
  }

  private static void delete(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    file.delete();
  }

  static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024f);
    if (bytes < 1024L * 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024));
    return String.format(Locale.ROOT, "%.2f GB", bytes / (1024f * 1024 * 1024));
  }
}
