package org.emugo.frontend;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only RetroAchievements account data for the in-app profile. Uses the same token-based
 * dorequest.php endpoints as the emulator runtime, so no separate web API key is needed.
 * All methods block; call them off the main thread.
 */
final class RaProfile {
  private static final String ENDPOINT = "https://retroachievements.org/dorequest.php";
  private static final int[] CONSOLES = {4, 6, 5, 7, 3, 2, 1, 11, 15, 12};

  static final class Profile {
    String user, avatarUrl;
    long hardcorePoints, softcorePoints;
    int unreadMessages;
  }

  static final class Game {
    int id, console, total, unlocked, unlockedHardcore;
    String title, iconUrl;
  }

  static final class Achievement {
    String title, description, badgeUrl, lockedBadgeUrl;
    int points;
    boolean unlocked, hardcore;
  }

  private RaProfile() {}

  static String consoleName(int console) {
    switch (console) {
      case 1: return "Sega Genesis";
      case 2: return "Nintendo 64";
      case 3: return "Super Nintendo";
      case 4: return "Game Boy";
      case 5: return "Game Boy Advance";
      case 6: return "Game Boy Color";
      case 7: return "Nintendo Entertainment System";
      case 11: return "Sega Master System";
      case 12: return "PlayStation";
      case 15: return "Sega Game Gear";
      default: return "";
    }
  }

  private static JSONObject request(Context context, String... params) throws Exception {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i + 1 < params.length; i += 2) {
      if (body.length() > 0) body.append('&');
      body.append(params[i]).append('=').append(URLEncoder.encode(params[i + 1], "UTF-8"));
    }
    HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    c.setRequestProperty("User-Agent", Version.userAgent(context));
    try (OutputStream out = c.getOutputStream()) {
      out.write(body.toString().getBytes(StandardCharsets.UTF_8));
    }
    int status = c.getResponseCode();
    InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (stream != null)
      try (InputStream in = stream) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
      }
    JSONObject json = new JSONObject(out.toString("UTF-8"));
    if (!json.optBoolean("Success", false))
      throw new IOException(json.optString("Error", "HTTP " + status));
    return json;
  }

  static Profile profile(Context context, String user, String token) throws Exception {
    JSONObject json = request(context, "r", "login2", "u", user, "t", token);
    Profile p = new Profile();
    p.user = json.optString("User", user);
    p.hardcorePoints = json.optLong("Score");
    p.softcorePoints = json.optLong("SoftcoreScore");
    p.unreadMessages = json.optInt("Messages");
    p.avatarUrl = json.optString("AvatarUrl",
        "https://media.retroachievements.org/UserPic/" + p.user + ".png");
    return p;
  }

  /** Games with any progress on the systems EmuGo! runs, most completed first. */
  static List<Game> games(Context context, String user, String token) throws Exception {
    Map<Integer, Game> games = new HashMap<>();
    for (int console : CONSOLES) {
      JSONObject json;
      try {
        json = request(context, "r", "allprogress", "u", user, "t", token,
            "c", String.valueOf(console));
      } catch (Exception e) {
        continue;
      }
      JSONObject response = json.optJSONObject("Response");
      if (response == null) continue;
      for (Iterator<String> keys = response.keys(); keys.hasNext();) {
        String key = keys.next();
        JSONObject entry = response.optJSONObject(key);
        if (entry == null) continue;
        Game game = new Game();
        game.id = Integer.parseInt(key);
        game.console = console;
        game.total = entry.optInt("Achievements");
        game.unlocked = entry.optInt("Unlocked");
        game.unlockedHardcore = entry.optInt("UnlockedHardcore");
        if (game.unlocked > 0 || game.unlockedHardcore > 0) games.put(game.id, game);
      }
    }
    List<Integer> ids = new ArrayList<>(games.keySet());
    for (int start = 0; start < ids.size(); start += 100) {
      StringBuilder list = new StringBuilder();
      for (int i = start; i < Math.min(ids.size(), start + 100); i++) {
        if (list.length() > 0) list.append(',');
        list.append(ids.get(i));
      }
      JSONObject json = request(context, "r", "gameinfolist", "g", list.toString());
      JSONArray response = json.optJSONArray("Response");
      for (int i = 0; response != null && i < response.length(); i++) {
        JSONObject info = response.getJSONObject(i);
        Game game = games.get(info.optInt("ID"));
        if (game == null) continue;
        game.title = info.optString("Title");
        String url = info.optString("ImageUrl", "");
        game.iconUrl = !url.isEmpty() ? url
            : "https://media.retroachievements.org" + info.optString("ImageIcon", "");
      }
    }
    List<Game> out = new ArrayList<>(games.values());
    out.removeIf(g -> g.title == null);
    out.sort((a, b) -> {
      float pa = a.total == 0 ? 0 : Math.max(a.unlocked, a.unlockedHardcore) / (float) a.total;
      float pb = b.total == 0 ? 0 : Math.max(b.unlocked, b.unlockedHardcore) / (float) b.total;
      int byProgress = Float.compare(pb, pa);
      return byProgress != 0 ? byProgress : a.title.compareToIgnoreCase(b.title);
    });
    return out;
  }

  /** Core achievements of a game with the user's unlock state; unlocked ones first. */
  static List<Achievement> achievements(Context context, String user, String token, int gameId)
      throws Exception {
    Set<Integer> hardcore = unlocks(context, user, token, gameId, true);
    Set<Integer> softcore = unlocks(context, user, token, gameId, false);
    JSONObject json = request(context, "r", "achievementsets", "u", user, "t", token,
        "g", String.valueOf(gameId));
    List<Achievement> out = new ArrayList<>();
    JSONArray sets = json.optJSONArray("Sets");
    for (int s = 0; sets != null && s < sets.length(); s++) {
      JSONArray list = sets.getJSONObject(s).optJSONArray("Achievements");
      for (int i = 0; list != null && i < list.length(); i++) {
        JSONObject item = list.getJSONObject(i);
        if (item.optInt("Flags", 3) != 3) continue; // core set only
        int id = item.optInt("ID");
        if (id >= 101000001) continue; // server-injected emulator warnings
        Achievement a = new Achievement();
        a.title = item.optString("Title");
        a.description = item.optString("Description");
        a.points = item.optInt("Points");
        a.badgeUrl = item.optString("BadgeURL");
        a.lockedBadgeUrl = item.optString("BadgeLockedURL", a.badgeUrl);
        a.hardcore = hardcore.contains(id);
        a.unlocked = a.hardcore || softcore.contains(id);
        out.add(a);
      }
    }
    out.sort((a, b) -> Boolean.compare(b.unlocked, a.unlocked));
    return out;
  }

  private static Set<Integer> unlocks(Context context, String user, String token, int gameId,
      boolean hardcore) throws Exception {
    JSONObject json = request(context, "r", "unlocks", "u", user, "t", token,
        "g", String.valueOf(gameId), "h", hardcore ? "1" : "0");
    Set<Integer> ids = new HashSet<>();
    JSONArray list = json.optJSONArray("UserUnlocks");
    for (int i = 0; list != null && i < list.length(); i++) ids.add(list.optInt(i));
    return ids;
  }
}
