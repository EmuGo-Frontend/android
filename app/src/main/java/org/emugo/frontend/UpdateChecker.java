package org.emugo.frontend;

import android.app.*;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Release updates from GitHub: checks every 15 minutes (a background job while EmuGo! is closed,
 * a timer while it is open), shows the changelog, downloads the release APK and installs it
 * through PackageInstaller. Each version is announced only once; the manual check always asks.
 */
final class UpdateChecker {
  private static final String ENDPOINT =
      "https://api.github.com/repos/EmuGo-Frontend/android/releases/latest";
  static final long INTERVAL_MILLIS = 15 * 60 * 1000L;
  static final String CHANNEL = "updates";
  private static final int JOB_ID = 4801;
  private static final int NOTIFICATION_ID = 4802;
  static final String EXTRA_SHOW_UPDATE = "org.emugo.frontend.SHOW_UPDATE";
  private static volatile boolean busy;

  static final class Release {
    String version, notes, page, apkUrl;
    long apkSize;
  }

  private UpdateChecker() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("local", 0);
  }

  /** Latest published release, or null when the repository has none yet. */
  static Release fetch(Context context) throws IOException {
    SharedPreferences prefs = prefs(context);
    HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("User-Agent", "EmuGo-Android/" + Version.name(context));
    // Conditional request: an unchanged release answers 304, which GitHub does not count
    // against the hourly limit for anonymous clients.
    String etag = prefs.getString("releaseEtag", null);
    String cached = prefs.getString("releaseJson", null);
    if (etag != null && cached != null) connection.setRequestProperty("If-None-Match", etag);
    int status = connection.getResponseCode();
    if (status == 304 && cached != null) return parse(cached);
    if (status == 404) return null;
    if (status == 403 || status == 429) {
      Release fallback = fromReleasePage(context, cached);
      if (fallback != null) return fallback;
      throw new RateLimitException(context, connection.getHeaderField("X-RateLimit-Reset"));
    }
    if (status != 200) throw new IOException(L10n.format(context, "GitHub API returned %d", status));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream in = connection.getInputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) output.write(b, 0, n);
    }
    String json = output.toString("UTF-8");
    prefs.edit()
        .putString("releaseJson", json)
        .putString("releaseEtag", connection.getHeaderField("ETag"))
        .apply();
    return parse(json);
  }

  /** The last release the API returned, if any. */
  static Release cachedRelease(Context context) {
    String cached = prefs(context).getString("releaseJson", null);
    try {
      return cached == null ? null : parse(cached);
    } catch (IOException e) {
      return null;
    }
  }

  private static Release parse(String text) throws IOException {
    try {
      JSONObject json = new JSONObject(text);
      Release release = new Release();
      release.version = json.optString("tag_name", "");
      release.notes = json.optString("body", "");
      release.page = json.optString("html_url", "");
      JSONArray assets = json.optJSONArray("assets");
      for (int i = 0; assets != null && i < assets.length(); i++) {
        JSONObject asset = assets.getJSONObject(i);
        if (asset.optString("name").toLowerCase().endsWith(".apk")) {
          release.apkUrl = asset.optString("browser_download_url");
          release.apkSize = asset.optLong("size");
          break;
        }
      }
      return release;
    } catch (org.json.JSONException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * When the API is rate limited: github.com/.../releases/latest is not, and it redirects to
   * the newest tag. Release assets follow the EmuGo-&lt;version&gt;.apk naming. The changelog
   * comes from the last API answer when it is for the same version.
   */
  private static Release fromReleasePage(Context context, String cached) {
    try {
      HttpURLConnection c = (HttpURLConnection) new URL(
          "https://github.com/EmuGo-Frontend/android/releases/latest").openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(10000);
      c.setReadTimeout(15000);
      c.setRequestProperty("User-Agent", "EmuGo-Android/" + Version.name(context));
      String location = c.getHeaderField("Location");
      if (location == null || !location.contains("/releases/tag/")) return null;
      Release release = new Release();
      release.version = Uri.decode(location.substring(location.lastIndexOf('/') + 1));
      release.page = location;
      release.apkUrl = "https://github.com/EmuGo-Frontend/android/releases/download/"
          + release.version + "/EmuGo-" + release.version.replaceFirst("^[vV]", "") + ".apk";
      Release previous = cached == null ? null : parse(cached);
      release.notes = previous != null && release.version.equals(previous.version)
          ? previous.notes
          : L10n.format(context, "The changelog is on the release page: %s", location);
      return release;
    } catch (Exception e) {
      return null;
    }
  }

  /** GitHub's anonymous API limit; the message says when checks work again. */
  static final class RateLimitException extends IOException {
    RateLimitException(Context context, String resetEpochSeconds) {
      super(message(context, resetEpochSeconds));
    }

    private static String message(Context context, String reset) {
      long minutes = 60;
      try {
        long seconds = Long.parseLong(reset) - System.currentTimeMillis() / 1000;
        minutes = Math.max(1, (seconds + 59) / 60);
      } catch (Exception ignored) {
      }
      return L10n.format(context,
          "GitHub is limiting update checks right now. Try again in %d minutes.", minutes);
    }
  }

  static boolean newer(Context context, Release release) {
    return release != null && newer(release.version, Version.name(context));
  }

  /**
   * Manual checks open the updater dialog. Automatic checks only post the Android notification,
   * at most once per version, shared with the background job.
   */
  static void check(Activity activity, boolean manual) {
    if (busy) return;
    busy = true;
    new Thread(() -> {
      try {
        if (!manual) {
          notifyIfNewer(activity);
          return;
        }
        Release release = fetch(activity);
        if (release == null) {
          message(activity, "No published release yet");
        } else if (newer(activity, release)) {
          NotificationManager manager = activity.getSystemService(NotificationManager.class);
          if (manager != null) manager.cancel(NOTIFICATION_ID);
          activity.runOnUiThread(() -> offer(activity, release));
        } else {
          message(activity, L10n.format(activity, "EmuGo! is up to date (%s)",
              Version.name(activity)));
        }
      } catch (RateLimitException e) {
        if (manual) message(activity, e.getMessage());
      } catch (Exception e) {
        if (manual)
          message(activity, L10n.format(activity, "Could not check updates: %s", e.getMessage()));
      } finally {
        busy = false;
      }
    }, "EmuGo-update-check").start();
  }

  /** The changelog with a download button. */
  static void offer(Activity activity, Release release) {
    if (activity.isFinishing()) return;
    PocketDialog.Builder dialog = new PocketDialog.Builder(activity)
        .setTitle(L10n.format(activity, "EmuGo! %s is available", release.version))
        .setMessage(changelog(activity, release.notes));
    if (release.apkUrl != null && !release.apkUrl.isEmpty())
      dialog.setPositiveButton("Download and install", (d, w) -> download(activity, release));
    else
      dialog.setPositiveButton("Open release",
          (d, w) -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.page))));
    dialog.setNegativeButton("Later", null).show();
  }

  /** GitHub release notes are Markdown; show them as plain text with bullets. */
  static String changelog(Context context, String notes) {
    if (notes == null || notes.trim().isEmpty()) return L10n.text(context, "No changelog provided");
    StringBuilder out = new StringBuilder();
    for (String line : notes.replace("\r", "").split("\n")) {
      String text = line.trim();
      if (text.matches("^[-*+] .*")) text = "•  " + text.substring(2).trim();
      text = text.replaceAll("^#+\\s*", "").replace("**", "").replace("__", "").replace("`", "")
                 .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
      if (out.length() > 0) out.append('\n');
      out.append(text);
    }
    String result = out.toString().trim();
    return result.length() > 3000 ? result.substring(0, 2999) + "…" : result;
  }

  private static void download(Activity activity, Release release) {
    TextView progress = new TextView(activity);
    progress.setText(L10n.text(activity, "Starting download…"));
    Dialog dialog = new PocketDialog.Builder(activity)
        .setTitle(L10n.format(activity, "Downloading EmuGo! %s", release.version))
        .setView(progress)
        .show();
    dialog.setCancelable(false);
    new Thread(() -> {
      File dir = new File(activity.getCacheDir(), "updates");
      dir.mkdirs();
      File[] old = dir.listFiles();
      if (old != null) for (File f : old) f.delete();
      File apk = new File(dir, "EmuGo-" + release.version + ".apk");
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "EmuGo-Android/" + Version.name(activity));
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : release.apkSize;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
          byte[] buffer = new byte[65536];
          long done = 0;
          int lastPercent = -1, n;
          while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            done += n;
            int percent = total > 0 ? (int) (done * 100 / total) : -1;
            if (percent != lastPercent) {
              lastPercent = percent;
              String text = percent >= 0 ? percent + "%" : (done / 1024) + " KB";
              activity.runOnUiThread(() -> progress.setText(text));
            }
          }
        }
        activity.runOnUiThread(() -> {
          dialog.dismiss();
          install(activity, apk);
        });
      } catch (Exception e) {
        apk.delete();
        activity.runOnUiThread(() -> {
          dialog.dismiss();
          message(activity, L10n.format(activity, "Download failed: %s", e.getMessage()));
        });
      }
    }, "EmuGo-update-download").start();
  }

  private static File pendingApk;

  /** Continues an install that waited for the "install unknown apps" permission. */
  static void resumePendingInstall(Activity activity) {
    File apk = pendingApk;
    if (apk != null && apk.exists() && activity.getPackageManager().canRequestPackageInstalls()) {
      pendingApk = null;
      install(activity, apk);
    }
  }

  private static void install(Activity activity, File apk) {
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      pendingApk = apk;
      new PocketDialog.Builder(activity)
          .setTitle("Allow updates")
          .setMessage("Android needs your permission before EmuGo! can install its own updates. "
              + "Turn on \"Allow from this source\" and come back.")
          .setPositiveButton("Open settings", (d, w) -> activity.startActivity(
              new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                  Uri.parse("package:" + activity.getPackageName()))))
          .setNegativeButton("Cancel", null)
          .show();
      return;
    }
    try {
      PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
      PackageInstaller.SessionParams params =
          new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      params.setAppPackageName(activity.getPackageName());
      params.setSize(apk.length());
      int id = installer.createSession(params);
      try (PackageInstaller.Session session = installer.openSession(id)) {
        try (InputStream in = new FileInputStream(apk);
            OutputStream out = session.openWrite("EmuGo.apk", 0, apk.length())) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          session.fsync(out);
        }
        Intent result = new Intent(activity, InstallResultReceiver.class);
        PendingIntent callback = PendingIntent.getBroadcast(activity, id, result,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        session.commit(callback.getIntentSender());
      }
    } catch (Exception e) {
      message(activity, L10n.format(activity, "Install failed: %s", e.getMessage()));
    }
  }

  // Background checks.

  /** Schedules the 15-minute background check (Android's minimum periodic job interval). */
  static void schedule(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
    scheduler.schedule(new JobInfo.Builder(JOB_ID,
                           new ComponentName(context, UpdateJobService.class))
                           .setPeriodic(INTERVAL_MILLIS)
                           .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                           .setPersisted(true)
                           .build());
  }

  /** Posts the update notification once per version. Runs on a worker thread. */
  static void notifyIfNewer(Context context) throws IOException {
    Release release = fetch(context);
    if (!newer(context, release)) return;
    if (release.version.equals(prefs(context).getString("updateNotified", ""))) return;
    prefs(context).edit().putString("updateNotified", release.version).apply();
    post(context, release);
  }

  /**
   * When the user comes back to EmuGo! (screen unlocked, back from another app), a pending
   * update is announced once more for that return. Skipped while the notification is still
   * showing, so it never piles up.
   */
  static void remind(Context context) {
    Context app = context.getApplicationContext();
    new Thread(() -> {
      try {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (android.service.notification.StatusBarNotification shown :
            manager.getActiveNotifications())
          if (shown.getId() == NOTIFICATION_ID) return;
        Release release = fetch(app);
        if (!newer(app, release)) return;
        prefs(app).edit().putString("updateNotified", release.version).apply();
        post(app, release);
      } catch (Exception ignored) {
      }
    }, "EmuGo-update-remind").start();
  }

  private static void post(Context context, Release release) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    L10n.setLanguage(new Library(context).get("language", "System"));
    manager.createNotificationChannel(new NotificationChannel(
        CHANNEL, L10n.text(context, "Updates"), NotificationManager.IMPORTANCE_DEFAULT));
    Intent open = new Intent(context, MainActivity.class)
                      .putExtra(EXTRA_SHOW_UPDATE, true)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent tap = PendingIntent.getActivity(context, 0, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(context, CHANNEL)
        .setSmallIcon(context.getResources().getIdentifier(
            "ic_emugo_mark", "drawable", context.getPackageName()))
        .setContentTitle(L10n.format(context, "EmuGo! %s is available", release.version))
        .setContentText(L10n.text(context, "Tap to see what's new and install it"))
        .setStyle(new Notification.BigTextStyle().bigText(changelog(context, release.notes)))
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build();
    try {
      manager.notify(NOTIFICATION_ID, notification);
    } catch (SecurityException ignored) {
      // Notifications not allowed (Android 13+ without permission).
    }
  }

  private static boolean newer(String remote, String local) {
    String[] a = remote.replaceFirst("^[vV]", "").split("\\.");
    String[] b = local.replaceFirst("^[vV]", "").split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = number(a, i), y = number(b, i);
      if (x != y)
        return x > y;
    }
    return false;
  }
  private static int number(String[] parts, int i) {
    if (i >= parts.length)
      return 0;
    try {
      return Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
    } catch (Exception e) {
      return 0;
    }
  }
  static void message(Activity activity, String value) {
    activity.runOnUiThread(()
                               -> new PocketDialog.Builder(activity)
                                   .setMessage(value)
                                   .setPositiveButton("OK", null)
                                   .show());
  }
}
