package org.emugo.frontend;

import android.app.*;
import android.content.*;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class UpdateChecker {
  private static final String ENDPOINT =
      "https://api.github.com/repos/EmuGo-Frontend/android/releases/latest";
  static void check(Activity activity, boolean manual) {
    android.content.SharedPreferences prefs = activity.getSharedPreferences("local", 0);
    long now = System.currentTimeMillis();
    if (!manual && now - prefs.getLong("lastUpdateCheck", 0) < 86400000L)
      return;
    prefs.edit().putLong("lastUpdateCheck", now).apply();
    new Thread(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "EmuGo-Android/" + Version.name(activity));
        int status = connection.getResponseCode();
        if (status == 404) {
          if (manual)
            message(activity, "No published release yet");
          return;
        }
        if (status != 200)
          throw new IOException(L10n.format(activity, "GitHub API returned %d", status));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
          byte[] b = new byte[4096];
          int n;
          while ((n = in.read(b)) > 0) output.write(b, 0, n);
        }
        JSONObject release = new JSONObject(output.toString("UTF-8"));
        String version = release.optString("tag_name", "");
        String page = release.optString("html_url", "");
        String current =
            activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        if (newer(version, current))
          activity.runOnUiThread(
              ()
                  -> new PocketDialog.Builder(activity)
                      .setTitle("EmuGo!OS update available")
                      .setMessage(L10n.format(activity, "%s is available. Open its release page?",
                          version))
                      .setPositiveButton("Open release",
                          (d, w)
                              -> activity.startActivity(
                                  new Intent(Intent.ACTION_VIEW, Uri.parse(page))))
                      .setNegativeButton("Later", null)
                      .show());
        else if (manual)
          message(activity, L10n.format(activity, "EmuGo!OS is up to date (%s)", current));
      } catch (Exception e) {
        if (manual)
          message(activity, L10n.format(activity, "Could not check updates: %s",
              e.getMessage()));
      }
    }, "EmuGo-update-check").start();
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
  private static void message(Activity activity, String value) {
    activity.runOnUiThread(()
                               -> new PocketDialog.Builder(activity)
                                   .setMessage(value)
                                   .setPositiveButton("OK", null)
                                   .show());
  }
}
