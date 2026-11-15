package org.emugo.frontend;

import android.app.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class RetroAchievements {
  static void signIn(Activity activity, Library library, Runnable done) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(1);
    box.setPadding(32, 8, 32, 0);
    EditText user = new EditText(activity);
    user.setSingleLine(true);
    user.setHint(activity.getString(R.string.username));
    user.setText(library.get("raUser", ""));
    box.addView(user);
    EditText password = new EditText(activity);
    password.setSingleLine(true);
    password.setHint(activity.getString(R.string.password));
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(password);
    new PocketDialog.Builder(activity)
        .setTitle("RetroAchievements")
        .setView(box)
        .setMessage(R.string.ra_sign_in_explanation)
        .setPositiveButton(R.string.sign_in,
            (d, w) -> {
              String username = user.getText().toString().trim(),
                     secret = password.getText().toString();
              password.setText("");
              new Thread(() -> {
                try {
                  String post = "r=login2&u=" + URLEncoder.encode(username, "UTF-8")
                      + "&p=" + URLEncoder.encode(secret, "UTF-8");
                  HttpURLConnection connection =
                      (HttpURLConnection) new URL("https://retroachievements.org/dorequest.php")
                          .openConnection();
                  connection.setRequestMethod("POST");
                  connection.setDoOutput(true);
                  connection.setConnectTimeout(15000);
                  connection.setReadTimeout(15000);
                  connection.setRequestProperty(
                      "Content-Type", "application/x-www-form-urlencoded");
                  connection.setRequestProperty("User-Agent",
                      Version.userAgent(activity));
                  try (OutputStream out = connection.getOutputStream()) {
                    out.write(post.getBytes(StandardCharsets.UTF_8));
                  }
                  if (connection.getResponseCode() != 200)
                    throw new IOException("HTTP " + connection.getResponseCode());
                  ByteArrayOutputStream out = new ByteArrayOutputStream();
                  try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                  }
                  JSONObject response = new JSONObject(out.toString("UTF-8"));
                  if (!response.optBoolean("Success", false))
                    throw new IOException(
                        response.optString("Error", activity.getString(R.string.sign_in_failed)));
                  String token = response.getString("Token");
                  String canonical = response.optString("User", username);
                  activity.runOnUiThread(() -> {
                    library.put("raUser", canonical);
                    library.put("raToken", token);
                    PocketNotice.show(
                        activity, activity.getString(R.string.signed_in_as_x, canonical));
                    done.run();
                  });
                } catch (Exception e) {
                  activity.runOnUiThread(()
                                             -> new PocketDialog.Builder(activity)
                                                    .setTitle(R.string.ra_sign_in_failed)
                                                    .setMessage(e.getMessage())
                                                    .setPositiveButton(R.string.ok, null)
                                                    .show());
                }
              }, "EmuGo-RA-login").start();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
