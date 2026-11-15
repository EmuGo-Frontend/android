package org.emugo.frontend;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Discord Rich Presence through the Discord Social SDK.
 *
 * <p>Sign-in is OAuth2 with PKCE for a public client, so no client secret exists anywhere in
 * EmuGo!. A temporary server on 127.0.0.1 receives the browser redirect, then the SDK exchanges
 * the code for tokens. Tokens stay on this device (never in the synced settings.json).
 */
final class DiscordPresence {
  static final long APPLICATION_ID = 1571263997787775056L;
  static final int REDIRECT_PORT = 53134;
  static final String REDIRECT_URI = "http://127.0.0.1:" + REDIRECT_PORT + "/callback";
  private static final String SCOPES = "openid sdk.social_layer_presence";
  private static final int STATUS_READY = 3;

  interface Listener {
    void onDiscordChanged();
  }

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static boolean initialized, available;
  private static Context app;
  private static int status;
  private static String pendingVerifier;
  private static Listener listener;
  private static volatile ServerSocket server;
  // Desired presence, applied whenever the client becomes ready.
  private static String details, state, largeImage, largeText;
  private static long startMillis;
  // Presence is only shown while an EmuGo! activity is in front (see Foreground): a locked
  // screen or another app clears it.

  private static native boolean nativeInit(long id);
  private static native void nativeGetToken(long id, String code, String verifier, String redirect);
  private static native void nativeRefresh(long id, String refresh);
  private static native void nativeConnect(String access);
  private static native void nativeDisconnect();
  private static native String nativeUserName();
  private static native void nativeSetPresence(String details, String state, String largeImage,
      String largeText, String smallImage, String smallText, long startMillis);
  private static native void nativeClearPresence();
  private static native void nativeTick();

  private static final Runnable ticker = new Runnable() {
    public void run() {
      nativeTick();
      main.postDelayed(this, 250);
    }
  };

  private DiscordPresence() {}

  private static SharedPreferences prefs() {
    return app.getSharedPreferences("discord", 0);
  }

  /** Call once from the long-lived launcher activity; the SDK binds its service to it. */
  static void init(Activity activity) {
    if (initialized) return;
    initialized = true;
    app = activity.getApplicationContext();
    Foreground.addListener(new Foreground.Listener() {
      @Override
      public void onReturned() {
        apply(app);
      }
      @Override
      public void onLeft() {
        if (available && connected()) nativeClearPresence();
      }
    });
    try {
      Class<?> cls = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit");
      cls.getMethod("setEngineActivity", Activity.class).invoke(null, activity);
      System.loadLibrary("emugo");
      available = nativeInit(APPLICATION_ID);
    } catch (Throwable e) {
      available = false;
    }
    if (!available) return;
    main.post(ticker);
    String refresh = prefs().getString("refresh", null);
    if (refresh == null) return;
    long expires = prefs().getLong("expires", 0);
    String access = prefs().getString("access", null);
    if (access != null && expires - System.currentTimeMillis() > 86400000L)
      nativeConnect(access);
    else
      nativeRefresh(APPLICATION_ID, refresh);
  }

  static boolean available() {
    return available;
  }

  static boolean signedIn() {
    return app != null && prefs().getString("refresh", null) != null;
  }

  static boolean connected() {
    return status == STATUS_READY;
  }

  static String userName() {
    return available && connected() ? nativeUserName() : "";
  }

  static void setListener(Listener value) {
    listener = value;
  }

  private static void changed() {
    if (listener != null) listener.onDiscordChanged();
  }

  private static boolean enabled(Context context) {
    return new Library(context).get("discordPresence", true);
  }

  /** Launcher presence. */
  static void browsing(Context context) {
    show(context, "Browsing the library", null, null, null, 0);
  }

  /** In-game presence; state is the system name or RetroAchievements rich presence. */
  static void playing(Context context, String game, String stateText, String image,
      String imageText, long since) {
    show(context, game, stateText, image, imageText, since);
  }

  private static void show(Context context, String d, String s, String image, String imageText,
      long since) {
    details = d;
    state = s;
    largeImage = image;
    largeText = imageText;
    startMillis = since;
    apply(context);
  }

  static void apply(Context context) {
    if (!available || !connected()) return;
    if (!Foreground.visible() || !enabled(context) || details == null) {
      nativeClearPresence();
      return;
    }
    // Only image URLs are sent: an asset key that is not uploaded in the developer portal makes
    // Discord reject the whole activity.
    boolean image = largeImage != null && largeImage.startsWith("https://");
    nativeSetPresence(limit(details), limit(state), image ? largeImage : "",
        image && largeText != null ? largeText : "", "", "", startMillis);
  }

  /** Opens Discord's consent page in the browser and waits for the redirect on 127.0.0.1. */
  static void signIn(Activity activity) {
    if (!available) {
      PocketNotice.show(activity, "Discord is not available in this build");
      return;
    }
    stopServer();
    String verifier = random(64);
    String challenge = base64Url(sha256(verifier));
    String expectedState = random(24);
    pendingVerifier = verifier;
    ServerSocket socket;
    try {
      socket = new ServerSocket(REDIRECT_PORT, 1, InetAddress.getByName("127.0.0.1"));
      socket.setSoTimeout(300000);
    } catch (Exception e) {
      PocketNotice.show(activity, L10n.format(activity, "Discord sign-in failed: %s",
          String.valueOf(e.getMessage())));
      return;
    }
    server = socket;
    new Thread(() -> awaitRedirect(activity, socket, expectedState), "EmuGo-discord-auth").start();
    Uri url = Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
                  .appendQueryParameter("client_id", String.valueOf(APPLICATION_ID))
                  .appendQueryParameter("response_type", "code")
                  .appendQueryParameter("redirect_uri", REDIRECT_URI)
                  .appendQueryParameter("scope", SCOPES)
                  .appendQueryParameter("state", expectedState)
                  .appendQueryParameter("code_challenge", challenge)
                  .appendQueryParameter("code_challenge_method", "S256")
                  .build();
    try {
      activity.startActivity(customTab(activity, url));
    } catch (Exception e) {
      try {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
      } catch (Exception none) {
        stopServer();
        PocketNotice.show(activity, "No browser available");
      }
    }
  }

  /**
   * A Custom Tab: the browser's page runs on top of EmuGo!'s own task instead of switching to
   * the full browser app. Uses the Custom Tabs intent protocol directly (no AndroidX).
   */
  private static Intent customTab(Activity activity, Uri url) {
    Intent intent = new Intent(Intent.ACTION_VIEW, url);
    android.os.Bundle extras = new android.os.Bundle();
    extras.putBinder("android.support.customtabs.extra.SESSION", null);
    intent.putExtras(extras);
    intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR",
        ThemePalette.resolve(activity, new Library(activity)).background);
    intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
    android.content.pm.PackageManager pm = activity.getPackageManager();
    java.util.Set<String> supported = new java.util.HashSet<>();
    for (android.content.pm.ResolveInfo info : pm.queryIntentServices(
             new Intent("android.support.customtabs.action.CustomTabsService"), 0))
      supported.add(info.serviceInfo.packageName);
    android.content.pm.ResolveInfo preferred =
        pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://")), 0);
    String browser = preferred != null && supported.contains(preferred.activityInfo.packageName)
        ? preferred.activityInfo.packageName
        : supported.contains("com.android.chrome") ? "com.android.chrome"
        : supported.isEmpty() ? null : supported.iterator().next();
    if (browser != null) intent.setPackage(browser);
    return intent;
  }

  private static void awaitRedirect(Activity activity, ServerSocket socket, String expectedState) {
    try {
      while (true) {
        try (Socket client = socket.accept()) {
          BufferedReader in = new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
          String line = in.readLine();
          if (line == null || !line.startsWith("GET ")) continue;
          Uri request = Uri.parse("http://127.0.0.1" + line.split(" ")[1]);
          if (!"/callback".equals(request.getPath())) {
            respond(client, 404, "Not found");
            continue;
          }
          String code = request.getQueryParameter("code");
          String error = request.getQueryParameter("error");
          boolean valid = code != null && expectedState.equals(request.getQueryParameter("state"));
          respond(client, 200, valid
              ? "Signed in to Discord. You can return to EmuGo! now."
              : "Discord sign-in was cancelled. You can return to EmuGo! now.");
          String failure = valid ? null : error != null ? error : "invalid response";
          main.post(() -> {
            bringToFront(activity);
            if (valid) {
              nativeGetToken(APPLICATION_ID, code, pendingVerifier, REDIRECT_URI);
            } else {
              PocketNotice.show(activity, L10n.format(activity, "Discord sign-in failed: %s", failure));
            }
          });
          return;
        }
      }
    } catch (SocketTimeoutException timeout) {
      // The user never finished; nothing to report.
    } catch (Exception ignored) {
    } finally {
      stopServer();
    }
  }

  private static void respond(Socket client, int code, String message) throws Exception {
    String html = "<!doctype html><meta charset=utf-8><meta name=viewport "
        + "content='width=device-width'><title>EmuGo!</title><body style='font-family:sans-serif;"
        + "background:#111;color:#eee;display:grid;place-items:center;height:90vh'><p>" + message
        + "</p>";
    byte[] body = html.getBytes(StandardCharsets.UTF_8);
    OutputStream out = client.getOutputStream();
    out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\n"
        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  private static void bringToFront(Activity activity) {
    try {
      // MainActivity is singleTask: bringing it back removes the Custom Tab above it.
      Intent intent = new Intent(activity, MainActivity.class)
                          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                              | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      activity.startActivity(intent);
    } catch (Exception ignored) {
    }
  }

  private static void stopServer() {
    ServerSocket socket = server;
    server = null;
    if (socket != null)
      try {
        socket.close();
      } catch (Exception ignored) {
      }
  }

  static void signOut() {
    if (app == null) return;
    prefs().edit().clear().apply();
    if (available) nativeDisconnect();
    status = 0;
    changed();
  }

  // Called from native code on the main thread.
  static void onToken(boolean ok, String access, String refresh, int expiresIn, String error) {
    if (!ok) {
      // A refresh token that no longer works means the user must sign in again.
      prefs().edit().clear().apply();
      if (app != null)
        main.post(() -> changed());
      android.util.Log.w("EmuGo-Discord", "Token request failed: " + error);
      return;
    }
    prefs().edit()
        .putString("access", access)
        .putString("refresh", refresh)
        .putLong("expires", System.currentTimeMillis() + expiresIn * 1000L)
        .apply();
    nativeConnect(access);
    changed();
  }

  static void onStatus(int value, int error, int detail) {
    status = value;
    if (value == STATUS_READY && app != null) apply(app);
    changed();
  }

  /** Discord accepts 2 to 128 characters for activity text. */
  private static String limit(String value) {
    if (value == null) return "";
    value = value.trim();
    return value.length() <= 128 ? value : value.substring(0, 127) + "…";
  }

  private static String random(int length) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    SecureRandom random = new SecureRandom();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return out.toString();
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String base64Url(byte[] data) {
    return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
  }
}
