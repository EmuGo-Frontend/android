package org.emugo.frontend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java half of the RetroAchievements runtime (rcheevos rc_client in ra.cpp). Native code calls
 * {@link #http} for server requests and {@link #onEvent} for unlocks and status messages.
 */
final class RaBridge {
  static {
    System.loadLibrary("emugo");
  }

  interface Listener {
    void onRaEvent(int kind, int value, String title, String text, String imageUrl);
  }

  static final int UNLOCK = 1, LOADED = 2, LEADERBOARD = 3, COMPLETED = 4, ERROR = 5,
                   CONNECTION = 6, LOGIN_FAILED = 7;
  static final int NO_GAME_LOADED = -29;

  private static final ExecutorService network = Executors.newFixedThreadPool(2);
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static volatile Listener listener;
  private static volatile String userAgent = "EmuGo";
  private static String loggedUser, loggedToken;

  private static native void nativeInit();
  private static native String nativeUserAgentClause();
  private static native void nativeLogin(String user, String token);
  private static native void nativeLogout();
  private static native boolean nativeLoggedIn();
  private static native void nativeConfigure(int console, boolean hardcore);
  private static native void nativeHttpDone(long callback, long data, int status, byte[] body);
  static native String nativeRichPresence();
  static native String[] nativeAchievements();
  static native String nativeSummary();

  static int bucketLabel(int type) {
    switch (type) {
      case 1:
        return R.string.locked;
      case 2:
        return R.string.unlocked;
      case 3:
        return R.string.unsupported;
      case 4:
        return R.string.unofficial;
      case 5:
        return R.string.recently_unlocked;
      case 6:
        return R.string.active_challenges;
      case 7:
        return R.string.almost_there;
      case 8:
        return R.string.waiting_to_sync;
      default:
        return R.string.achievements;
    }
  }

  private RaBridge() {}

  static int consoleId(String system) {
    Library.SystemInfo info = system == null ? null : Library.system(system);
    return info == null ? 0 : info.achievements;
  }

  static boolean signedIn(Library library) {
    return !library.get("raToken", "").isEmpty() && !library.get("raUser", "").isEmpty();
  }

  /** Prepares the runtime for the next game. Returns false when achievements are off. */
  static synchronized boolean prepare(Context context, Library library, String system,
      Listener target) {
    listener = target;
    int console = consoleId(system);
    nativeInit();
    if (!signedIn(library) || console == 0) {
      nativeConfigure(0, false);
      return false;
    }
    userAgent = Version.userAgent(context) + " " + nativeUserAgentClause();
    String user = library.get("raUser", ""), token = library.get("raToken", "");
    if (!user.equals(loggedUser) || !token.equals(loggedToken) || !nativeLoggedIn()) {
      if (loggedUser != null) nativeLogout();
      loggedUser = user;
      loggedToken = token;
      nativeLogin(user, token);
    }
    nativeConfigure(console, false); // EmuGo! runs RetroAchievements in softcore only
    return true;
  }

  static void detach(Listener target) {
    if (listener == target) listener = null;
  }

  /** Forgets the cached session, e.g. after signing out in settings. */
  static synchronized void signOut() {
    if (loggedUser != null) {
      try {
        nativeLogout();
      } catch (UnsatisfiedLinkError ignored) {
      }
    }
    loggedUser = null;
    loggedToken = null;
  }

  // Called from native code.
  static void http(String url, String post, String contentType, long callback, long data) {
    network.execute(() -> {
      int status;
      byte[] body = new byte[0];
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", userAgent);
        if (post != null) {
          connection.setRequestMethod("POST");
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type",
              contentType != null ? contentType : "application/x-www-form-urlencoded");
          try (OutputStream out = connection.getOutputStream()) {
            out.write(post.getBytes(StandardCharsets.UTF_8));
          }
        }
        status = connection.getResponseCode();
        InputStream stream =
            status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream != null) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          try (InputStream in = stream) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          }
          body = out.toByteArray();
        }
      } catch (Exception e) {
        status = -2; // RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR
      }
      nativeHttpDone(callback, data, status, body);
    });
  }

  static void onEvent(int kind, int value, String title, String text, String imageUrl) {
    main.post(() -> {
      Listener target = listener;
      if (target != null) target.onRaEvent(kind, value, title, text, imageUrl);
    });
  }

  static void onLogin(boolean success, String message) {
    if (success) return;
    synchronized (RaBridge.class) {
      loggedUser = null;
      loggedToken = null;
    }
    onEvent(LOGIN_FAILED, 0, null, message, null);
  }
}
