package org.emugo.frontend;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class Library {
  static final class SystemInfo {
    /**
     * core runs inside EmuGo!, external names a standalone app, retroArch is the Libretro core
     * RetroArch uses when neither is available. A system with only retroArch always opens there.
     */
    final String name, folder, core, external, retroArch;
    final String[] extensions;
    SystemInfo(String n, String f, String c, String e, String x) {
      this(n, f, c, e, c, x);
    }
    SystemInfo(String n, String f, String c, String e, String r, String x) {
      name = n;
      folder = f;
      core = c;
      external = e;
      retroArch = r;
      extensions = x.split(",");
    }
  }
  static final SystemInfo[] SYSTEMS = {
      new SystemInfo("Game Boy", "gb", "gambatte", null, "gb,zip"),
      new SystemInfo("Game Boy Color", "gbc", "gambatte", null, "gbc,zip"),
      new SystemInfo("Game Boy Advance", "gba", "mgba", null, "gba,zip"),
      new SystemInfo("Nintendo Entertainment System", "nes", "nestopia", null, "nes,zip"),
      new SystemInfo("Super Nintendo", "snes", "snes9x", null, "sfc,smc,zip"),
      new SystemInfo("Nintendo 64", "n64", "parallel_n64", null, "n64,z64,v64,zip"),
      new SystemInfo("Sega Genesis", "genesis", "genesis_plus_gx", null, "md,gen,bin,zip"),
      new SystemInfo("Sega Master System", "mastersystem", "genesis_plus_gx", null, "sms,zip"),
      new SystemInfo("Sega Game Gear", "gamegear", "genesis_plus_gx", null, "gg,zip"),
      new SystemInfo("PlayStation", "psx", "pcsx_rearmed", null, "chd,cue,pbp,iso,bin"),
      new SystemInfo("PlayStation Portable", "psp", null, "ppsspp", "ppsspp", "iso,cso,chd,pbp"),
      new SystemInfo("PlayStation 2", "ps2", null, "nethersx2", "play", "iso,chd,cso,bin"),
      new SystemInfo("GameCube", "gc", null, "dolphin", "dolphin", "iso,rvz,gcm,ciso"),
      new SystemInfo("Wii", "wii", null, "dolphin", "dolphin", "iso,rvz,wbfs"),
      new SystemInfo("Nintendo DS", "nds", null, "melonds", "melondsds", "nds,dsi,zip,7z"),
      new SystemInfo("Nintendo 3DS", "3ds", null, "azahar", "citra", "3ds,cci,cxi,cia"),
      new SystemInfo("Nintendo Switch", "switch", null, "eden", "nsp,xci"),
  };
  static final class Game {
    final SystemInfo system;
    final String title;
    final String saveStem;
    /** Title before the article fix; keeps work folders and state file names stable. */
    final String fileTitle;
    final Uri uri;
    Game(SystemInfo s, String t, String stem, Uri u) {
      this(s, t, t, stem, u);
    }
    Game(SystemInfo s, String t, String file, String stem, Uri u) {
      system = s;
      title = t;
      fileTitle = file;
      saveStem = stem;
      uri = u;
    }
  }
  final Context context;
  final SharedPreferences local;
  volatile Uri root;
  JSONObject settings;
  private volatile Map<String, List<Item>> indexedFolders = new HashMap<>();
  private volatile boolean hasScanned;
  private final Map<String, List<Game>> gameCache = new HashMap<>();
  // "Legend of Zelda, The - A Link to the Past" -> "The Legend of Zelda - A Link to the Past"
  private static final Pattern TRAILING_ARTICLE = Pattern.compile(
      "^(.+?), (The|A|An|Der|Die|Das|Le|La|Les|L'|El|Los|Las|Il|Lo|Gli)(\\s*(?: - |: |~).*)?$",
      Pattern.CASE_INSENSITIVE);
  static String fixArticle(String title) {
    java.util.regex.Matcher m = TRAILING_ARTICLE.matcher(title);
    if (!m.matches()) return title;
    String article = m.group(2);
    String rest = m.group(3) == null ? "" : m.group(3);
    return article + (article.endsWith("'") ? "" : " ") + m.group(1) + rest;
  }
  /** Lowercase letters and digits only, after article fix and bracket removal. */
  static String matchKey(String title) {
    String value = BRACKET_METADATA.matcher(fixArticle(title)).replaceAll("");
    value = value.replaceAll("~[^~]*~", "");
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
  private static final Pattern BRACKET_METADATA =
      Pattern.compile("\\s*\\([^)]*\\)|\\s*\\[[^]]*\\]");
  private volatile boolean scanning;
  Library(Context c) {
    context = c;
    local = c.getSharedPreferences("local", 0);
    String saved = local.getString("root", null);
    if (saved != null)
      root = Uri.parse(saved);
    loadSettings();
    loadIndex();
  }
  void setRoot(Uri u) {
    root = u;
    indexedFolders = new HashMap<>();
    hasScanned = false;
    synchronized (this) {
      gameCache.clear();
    }
    local.edit().putString("root", u.toString()).apply();
    loadSettings();
    ensureFolder("saves");
    ensureFolder("cores");
    saveSettings();
    loadIndex();
  }
  void loadSettings() {
    settings = new JSONObject();
    try {
      String raw = local.getString("settings", "{}");
      settings = new JSONObject(raw);
      Uri file = findChild(ensureEmugo(), "settings.json");
      if (file != null) {
        try (InputStream in = context.getContentResolver().openInputStream(file)) {
          byte[] b = new byte[65536];
          int n = in.read(b);
          if (n > 0)
            settings = new JSONObject(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        }
      }
    } catch (Exception ignored) {
    }
  }
  void put(String key, Object value) {
    try {
      settings.put(key, value);
      synchronized (this) {
        gameCache.clear();
      }
      local.edit().putString("settings", settings.toString()).apply();
      saveSettings();
    } catch (Exception ignored) {
    }
  }
  /** Favourites are stored in the synced settings as "system folder/ROM file stem" lines. */
  static String favouriteKey(Game game) {
    return gameKey(game.system.folder, game.saveStem);
  }
  /** Identifies a game across devices: "system folder/ROM file stem". */
  static String gameKey(String system, String saveStem) {
    return system + "/" + saveStem;
  }
  static SystemInfo system(String folder) {
    for (SystemInfo system : SYSTEMS)
      if (system.folder.equals(folder))
        return system;
    return null;
  }
  // Per-game automatic states, synced in settings.json as {"gameKey": true|false}. A game without
  // an entry follows the global "autoStates" setting.
  private static final String STATE_OVERRIDES = "autoStatesOverrides";
  synchronized Boolean autoStatesOverride(String gameKey) {
    JSONObject overrides = settings.optJSONObject(STATE_OVERRIDES);
    return overrides == null || !overrides.has(gameKey) ? null : overrides.optBoolean(gameKey);
  }
  boolean autoStates(String gameKey) {
    Boolean override = autoStatesOverride(gameKey);
    return override != null ? override : get("autoStates", false);
  }
  /** null removes the override, so the game follows the global setting again. */
  synchronized void setAutoStatesOverride(String gameKey, Boolean value) {
    JSONObject overrides = settings.optJSONObject(STATE_OVERRIDES);
    if (overrides == null)
      overrides = new JSONObject();
    try {
      if (value == null)
        overrides.remove(gameKey);
      else
        overrides.put(gameKey, value.booleanValue());
    } catch (Exception ignored) {
    }
    put(STATE_OVERRIDES, overrides);
  }
  synchronized int autoStatesOverrideCount() {
    JSONObject overrides = settings.optJSONObject(STATE_OVERRIDES);
    return overrides == null ? 0 : overrides.length();
  }
  synchronized void clearAutoStatesOverrides() {
    put(STATE_OVERRIDES, new JSONObject());
  }
  synchronized Set<String> favourites() {
    Set<String> out = new java.util.LinkedHashSet<>();
    for (String line : get("favourites", "").split("\n"))
      if (!line.trim().isEmpty()) out.add(line.trim());
    return out;
  }
  boolean isFavourite(Game game) {
    return favourites().contains(favouriteKey(game));
  }
  /** Returns true when the game is a favourite afterwards. */
  synchronized boolean toggleFavourite(Game game) {
    Set<String> set = favourites();
    String key = favouriteKey(game);
    boolean added = set.add(key);
    if (!added) set.remove(key);
    put("favourites", String.join("\n", set));
    return added;
  }
  /** All favourite games that are in the library right now, sorted by title. */
  List<Game> favouriteGames() {
    Set<String> set = favourites();
    List<Game> out = new ArrayList<>();
    if (set.isEmpty()) return out;
    for (SystemInfo system : visibleSystems())
      for (Game game : games(system))
        if (set.contains(favouriteKey(game))) out.add(game);
    out.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
    return out;
  }
  String get(String key, String fallback) {
    return settings.optString(key, fallback);
  }
  boolean get(String key, boolean fallback) {
    return settings.optBoolean(key, fallback);
  }
  void saveSettings() {
    try {
      Uri dir = ensureEmugo();
      if (dir == null)
        return;
      Uri file = findChild(dir, "settings.json");
      if (file == null)
        file = DocumentsContract.createDocument(
            context.getContentResolver(), dir, "application/json", "settings.json");
      if (file != null)
        try (OutputStream out = context.getContentResolver().openOutputStream(file, "wt")) {
          out.write(settings.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    } catch (Exception ignored) {
    }
  }
  Uri rootDocument() {
    if (root == null)
      return null;
    try {
      return DocumentsContract.buildDocumentUriUsingTree(
          root, DocumentsContract.getTreeDocumentId(root));
    } catch (Exception e) {
      return root;
    }
  }
  Uri ensureEmugo() {
    if (root == null)
      return null;
    try {
      Uri dir = findChild(rootDocument(), "emugo");
      return dir != null ? dir
                         : DocumentsContract.createDocument(context.getContentResolver(),
                               rootDocument(), DocumentsContract.Document.MIME_TYPE_DIR, "emugo");
    } catch (Exception e) {
      return null;
    }
  }
  Uri ensureFolder(String name) {
    Uri parent = ensureEmugo();
    if (parent == null)
      return null;
    try {
      Uri existing = findChild(parent, name);
      return existing != null ? existing
                              : DocumentsContract.createDocument(context.getContentResolver(),
                                    parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
    } catch (Exception e) {
      return null;
    }
  }
  Uri findChild(Uri parent, String name) {
    if (parent == null)
      return null;
    for (Item i : children(parent))
      if (i.name.equalsIgnoreCase(name))
        return i.uri;
    return null;
  }
  static final class Item {
    final String name, mime;
    final Uri uri;
    Item(String n, String m, Uri u) {
      name = n;
      mime = m;
      uri = u;
    }
    boolean directory() {
      return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    }
  }
  List<Item> children(Uri parent) {
    return childrenInTree(root, parent);
  }
  List<Item> childrenInTree(Uri tree, Uri parent) {
    ArrayList<Item> out = new ArrayList<>();
    if (parent == null || tree == null)
      return out;
    try {
      String id = parent.getPathSegments().contains("document")
          ? DocumentsContract.getDocumentId(parent)
          : DocumentsContract.getTreeDocumentId(parent);
      Uri child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
      try (Cursor c = context.getContentResolver().query(child,
               new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                   DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                   DocumentsContract.Document.COLUMN_MIME_TYPE},
               null, null, null)) {
        while (c != null && c.moveToNext())
          out.add(new Item(c.getString(1), c.getString(2),
              DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))));
      }
    } catch (Exception ignored) {
    }
    return out;
  }
  List<SystemInfo> visibleSystems() {
    ArrayList<SystemInfo> out = new ArrayList<>();
    for (SystemInfo s : SYSTEMS)
      if (indexedFolders.containsKey(s.folder))
        out.add(s);
    return out;
  }
  synchronized List<Game> games(SystemInfo system) {
    List<Game> cached = gameCache.get(system.folder);
    if (cached != null)
      return cached;
    List<Item> entries = indexedFolders.get(system.folder);
    if (entries == null)
      return new ArrayList<>();
    List<Game> out = buildGames(system, entries);
    gameCache.put(system.folder, out);
    return out;
  }
  private List<Game> buildGames(SystemInfo system, List<Item> entries) {
    ArrayList<Game> out = new ArrayList<>();
    Set<String> extensions = new HashSet<>(Arrays.asList(system.extensions));
    boolean mergeCue = get("mergeCue", true);
    boolean hideExtensions = get("hideExtensions", true);
    boolean removeBrackets = get("removeBrackets", true);
    boolean fixArticles = get("fixArticles", true);
    Set<String> names = new HashSet<>();
    for (Item item : entries) names.add(item.name.toLowerCase(Locale.ROOT));
    for (Item i : entries) {
      int dot = i.name.lastIndexOf('.');
      if (dot < 0)
        continue;
      String ext = i.name.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (!extensions.contains(ext))
        continue;
      if (ext.equals("bin") && mergeCue
          && names.contains(i.name.substring(0, dot).toLowerCase(Locale.ROOT) + ".cue"))
        continue;
      String title = hideExtensions ? i.name.substring(0, dot) : i.name;
      if (removeBrackets)
        title = BRACKET_METADATA.matcher(title).replaceAll("").trim();
      String display = fixArticles ? fixArticle(title) : title;
      out.add(new Game(system, display, title, i.name.substring(0, dot), i.uri));
    }
    out.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
    return out;
  }
  synchronized void scanAsync(Runnable onComplete) {
    if (root == null || scanning)
      return;
    scanning = true;
    Uri scanningRoot = root;
    new Thread(() -> {
      try {
        Map<String, Item> directories = new HashMap<>();
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            scanningRoot, DocumentsContract.getTreeDocumentId(scanningRoot));
        for (Item item : childrenInTree(scanningRoot, rootDocument))
          if (item.directory())
            directories.put(item.name.toLowerCase(Locale.ROOT), item);
        Map<String, List<Item>> found = new HashMap<>();
        for (SystemInfo system : SYSTEMS) {
          Item directory = directories.get(system.folder);
          if (directory == null && system.folder.equals("psx"))
            directory = directories.get("ps1");
          if (directory != null)
            found.put(system.folder, scanFiles(scanningRoot, directory.uri));
        }
        if (scanningRoot.equals(root)) {
          Map<String, List<Game>> readyGames = new HashMap<>();
          for (SystemInfo system : SYSTEMS) {
            List<Item> entries = found.get(system.folder);
            if (entries != null)
              readyGames.put(system.folder, buildGames(system, entries));
          }
          synchronized (this) {
            indexedFolders = found;
            gameCache.clear();
            gameCache.putAll(readyGames);
            hasScanned = true;
          }
          new Handler(Looper.getMainLooper()).post(onComplete);
          saveIndex(found, scanningRoot);
        }
      } finally {
        scanning = false;
        if (!scanningRoot.equals(root))
          scanAsync(onComplete);
      }
    }, "EmuGo-ROM-index").start();
  }
  boolean isScanning() {
    return scanning;
  }
  boolean hasIndex() {
    return hasScanned;
  }
  private List<Item> scanFiles(Uri tree, Uri first) {
    ArrayList<Item> games = new ArrayList<>();
    ArrayDeque<Uri> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(first);
    while (!pending.isEmpty()) {
      Uri folder = pending.removeFirst();
      if (!visited.add(folder.toString()))
        continue;
      for (Item item : childrenInTree(tree, folder)) {
        if (item.directory())
          pending.add(item.uri);
        else
          games.add(item);
      }
    }
    return games;
  }
  private File localIndexFile() {
    return new File(context.getFilesDir(), "rom-index.json");
  }
  private void loadIndex() {
    if (root == null)
      return;
    try (FileInputStream in = new FileInputStream(localIndexFile())) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) > 0) bytes.write(buffer, 0, count);
      JSONObject json = new JSONObject(bytes.toString("UTF-8"));
      if (!root.toString().equals(json.optString("root")))
        return;
      Map<String, List<Item>> loaded = new HashMap<>();
      JSONArray folders = json.getJSONArray("folders");
      for (int folder = 0; folder < folders.length(); folder++) {
        JSONObject entry = folders.getJSONObject(folder);
        ArrayList<Item> items = new ArrayList<>();
        JSONArray files = entry.getJSONArray("files");
        for (int file = 0; file < files.length(); file++) {
          JSONObject game = files.getJSONObject(file);
          items.add(new Item(game.getString("name"), "application/octet-stream",
              Uri.parse(game.getString("uri"))));
        }
        loaded.put(entry.getString("system"), items);
      }
      synchronized (this) {
        indexedFolders = loaded;
        gameCache.clear();
        hasScanned = true;
      }
    } catch (Exception ignored) {
    }
  }
  private void saveIndex(Map<String, List<Item>> found, Uri scannedRoot) {
    try {
      JSONObject json = new JSONObject();
      json.put("root", scannedRoot.toString());
      JSONArray folders = new JSONArray();
      for (Map.Entry<String, List<Item>> entry : found.entrySet()) {
        JSONObject folder = new JSONObject();
        folder.put("system", entry.getKey());
        JSONArray files = new JSONArray();
        for (Item item : entry.getValue()) {
          JSONObject game = new JSONObject();
          game.put("name", item.name);
          game.put("uri", item.uri.toString());
          files.put(game);
        }
        folder.put("files", files);
        folders.put(folder);
      }
      json.put("folders", folders);
      byte[] data = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try (FileOutputStream out = new FileOutputStream(localIndexFile())) {
        out.write(data);
      }
      Uri emugo = ensureEmugo();
      Uri target = findChild(emugo, "index.json");
      if (target == null)
        target = DocumentsContract.createDocument(
            context.getContentResolver(), emugo, "application/json", "index.json");
      if (target != null)
        try (OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
          out.write(data);
        }
    } catch (Exception ignored) {
    }
  }
  void keepPermission(Uri uri, int grantedFlags) {
    int access = grantedFlags
        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if (access == 0) return;
    context.getContentResolver().takePersistableUriPermission(
        uri, access);
  }
}
