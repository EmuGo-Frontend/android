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
  /** How a game file reaches the built-in host. */
  enum Media {
    /** Copied into the work folder; a .zip is unpacked first. Small cartridge games. */
    COPY,
    /** Read in place under its own name, with the disc's other tracks next to it. */
    LINK,
    /** Read in place as an archive the core opens itself (arcade sets, DOS games). */
    ARCHIVE,
    /** The whole folder around the game file is read in place (ScummVM). */
    FOLDER
  }
  static final class SystemInfo {
    final String name, folder;
    final String[] extensions;
    /** Libretro core for EmuGo!'s own host, or null when the host cannot run this system. */
    String core;
    /** Libretro core RetroArch uses when neither EmuGo! nor a standalone app can. */
    String retroArch;
    /** Standalone apps (keys in ExternalEmulators), tried in this order. */
    String[] apps = {};
    /** Other folder names people use for this system. */
    String[] aliases = {};
    /** RetroAchievements console id, 0 without achievements. */
    int achievements;
    Media media = Media.COPY;
    /** Pairs of file name in ROMs/bios and path in the core's system folder. */
    String[][] bios = {};
    /** libretro asset pack the core needs in its system folder (buildbot assets/system). */
    String assets;
    /** The core writes its own save files, which are synced as a folder per game. */
    boolean ownSaves;
    /** Files in the system folder that are BIOS sets, not games. */
    Set<String> hidden = new HashSet<>();
    SystemInfo(String name, String folder, String extensions) {
      this.name = name;
      this.folder = folder;
      this.extensions = extensions.split(",");
    }
    SystemInfo core(String value) {
      core = value;
      retroArch = value;
      return this;
    }
    SystemInfo retroArch(String value) {
      retroArch = value;
      return this;
    }
    SystemInfo apps(String... value) {
      apps = value;
      return this;
    }
    SystemInfo aliases(String... value) {
      aliases = value;
      return this;
    }
    SystemInfo achievements(int console) {
      achievements = console;
      return this;
    }
    SystemInfo media(Media value) {
      media = value;
      return this;
    }
    SystemInfo bios(String... pairs) {
      bios = new String[pairs.length / 2][];
      for (int index = 0; index < bios.length; index++)
        bios[index] = new String[] {pairs[index * 2], pairs[index * 2 + 1]};
      return this;
    }
    SystemInfo assets(String value) {
      assets = value;
      return this;
    }
    SystemInfo ownSaves() {
      ownSaves = true;
      return this;
    }
    SystemInfo hidden(String... names) {
      hidden.addAll(Arrays.asList(names));
      return this;
    }
  }
  // BIOS archives that sit next to arcade sets.
  private static final String[] ARCADE_BIOS = {"neogeo.zip", "pgm.zip", "skns.zip", "decocass.zip",
      "isgsm.zip", "nmk004.zip", "qsound.zip", "ym2608.zip", "cchip.zip", "bubsys.zip",
      "namcoc69.zip", "namcoc70.zip", "namcoc75.zip", "midssio.zip", "coleco.zip", "spec128.zip",
      "spectrum.zip", "msx.zip", "neocdz.zip", "ngp.zip", "channelf.zip", "fdsbios.zip"};
  private static SystemInfo arcade(String name, String folder) {
    return new SystemInfo(name, folder, "zip,7z")
        .core("fbneo")
        .achievements(27)
        .media(Media.ARCHIVE)
        .bios("neogeo.zip", "fbneo/neogeo.zip", "pgm.zip", "fbneo/pgm.zip")
        .hidden(ARCADE_BIOS);
  }
  /**
   * Launch order for every game: the built-in core (when it is turned on), then the first
   * installed standalone app, then RetroArch.
   */
  static final SystemInfo[] SYSTEMS = {
      new SystemInfo("Game Boy", "gb", "gb,zip").core("gambatte").achievements(4),
      new SystemInfo("Game Boy Color", "gbc", "gbc,zip").core("gambatte").achievements(6),
      new SystemInfo("Game Boy Advance", "gba", "gba,zip").core("mgba").achievements(5),
      new SystemInfo("Nintendo Entertainment System", "nes", "nes,zip")
          .core("nestopia")
          .achievements(7)
          .aliases("famicom"),
      new SystemInfo("Super Nintendo", "snes", "sfc,smc,zip")
          .core("snes9x")
          .achievements(3)
          .aliases("sfc"),
      new SystemInfo("Nintendo 64", "n64", "n64,z64,v64,zip")
          .core("mupen64plus_next_gles3")
          .apps("mupen64plusfz")
          .achievements(2),
      new SystemInfo("Nintendo DS", "nds", "nds,dsi,zip")
          .core("melondsds")
          .apps("melonds")
          .achievements(18)
          .media(Media.LINK)
          .bios("bios7.bin", "melonDS DS/bios7.bin", "bios9.bin", "melonDS DS/bios9.bin",
              "firmware.bin", "melonDS DS/firmware.bin"),
      new SystemInfo("Virtual Boy", "virtualboy", "vb,zip")
          .core("mednafen_vb")
          .achievements(28)
          .aliases("vb"),
      new SystemInfo("Pokémon Mini", "pokemini", "min,zip")
          .core("pokemini")
          .achievements(24)
          .bios("bios.min", "bios.min"),
      new SystemInfo("Game & Watch", "gameandwatch", "mgw,zip").core("gw").achievements(60),
      new SystemInfo("Sega Genesis", "genesis", "md,gen,bin,zip")
          .core("genesis_plus_gx")
          .achievements(1)
          .aliases("megadrive"),
      new SystemInfo("Sega Master System", "mastersystem", "sms,zip")
          .core("genesis_plus_gx")
          .achievements(11),
      new SystemInfo("Sega Game Gear", "gamegear", "gg,zip")
          .core("genesis_plus_gx")
          .achievements(15),
      new SystemInfo("Sega SG-1000", "sg-1000", "sg,zip")
          .core("genesis_plus_gx")
          .achievements(33)
          .aliases("sg1000"),
      new SystemInfo("Sega CD", "segacd", "chd,cue,iso")
          .core("genesis_plus_gx")
          .achievements(9)
          .media(Media.LINK)
          .aliases("megacd")
          .bios("bios_CD_U.bin", "bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_E.bin", "bios_CD_J.bin",
              "bios_CD_J.bin"),
      new SystemInfo("Sega 32X", "sega32x", "32x,zip").core("picodrive").achievements(10),
      new SystemInfo("Sega Saturn", "saturn", "chd,cue,iso,ccd,mds")
          .core("yabasanshiro")
          .apps("yabasanshiro")
          .achievements(39)
          .media(Media.LINK)
          .bios("saturn_bios.bin", "saturn_bios.bin")
          .ownSaves(),
      new SystemInfo("Sega Dreamcast", "dreamcast", "chd,gdi,cdi,cue")
          .core("flycast")
          .apps("redream", "flycast")
          .achievements(40)
          .media(Media.LINK)
          .bios("dc_boot.bin", "dc/dc_boot.bin", "dc_flash.bin", "dc/dc_flash.bin")
          .ownSaves(),
      new SystemInfo("PC Engine", "pcengine", "pce,zip")
          .core("mednafen_pce_fast")
          .achievements(8)
          .aliases("tg16", "turbografx16"),
      new SystemInfo("PC Engine CD", "pcenginecd", "chd,cue")
          .core("mednafen_pce_fast")
          .achievements(76)
          .media(Media.LINK)
          .aliases("tg-cd", "tgcd")
          .bios("syscard3.pce", "syscard3.pce"),
      new SystemInfo("Neo Geo Pocket", "ngp", "ngp,zip").core("mednafen_ngp").achievements(14),
      new SystemInfo("Neo Geo Pocket Color", "ngpc", "ngc,ngpc,zip")
          .core("mednafen_ngp")
          .achievements(14),
      new SystemInfo("WonderSwan", "wonderswan", "ws,zip").core("mednafen_wswan").achievements(53),
      new SystemInfo("WonderSwan Color", "wonderswancolor", "wsc,zip")
          .core("mednafen_wswan")
          .achievements(53),
      new SystemInfo("Atari 2600", "atari2600", "a26,bin,zip").core("stella2014").achievements(25),
      new SystemInfo("Atari 7800", "atari7800", "a78,bin,zip")
          .core("prosystem")
          .achievements(51)
          .bios("7800 BIOS (U).rom", "7800 BIOS (U).rom"),
      new SystemInfo("Atari Lynx", "atarilynx", "lnx,zip")
          .core("handy")
          .achievements(13)
          .aliases("lynx")
          .bios("lynxboot.img", "lynxboot.img"),
      new SystemInfo("ColecoVision", "colecovision", "col,zip")
          .core("gearcoleco")
          .achievements(44)
          .aliases("coleco")
          .bios("colecovision.rom", "colecovision.rom"),
      new SystemInfo("MSX", "msx", "rom,mx1,mx2,dsk,zip")
          .core("bluemsx")
          .achievements(29)
          .assets("blueMSX"),
      arcade("Arcade", "arcade"),
      arcade("FinalBurn Neo", "fbneo"),
      arcade("Neo Geo", "neogeo"),
      arcade("Capcom Play System", "cps1"),
      arcade("Capcom Play System II", "cps2"),
      arcade("Capcom Play System III", "cps3"),
      new SystemInfo("DOS", "dos", "zip,dosz,exe,com,bat")
          .core("dosbox_pure")
          .achievements(26)
          .media(Media.ARCHIVE)
          .aliases("pc")
          .ownSaves(),
      new SystemInfo("ScummVM", "scummvm", "scummvm,svm")
          .core("scummvm")
          .apps("scummvm")
          .media(Media.FOLDER)
          .assets("ScummVM")
          .ownSaves(),
      new SystemInfo("PlayStation", "psx", "chd,cue,pbp,iso,bin")
          .core("pcsx_rearmed")
          .apps("duckstation")
          .achievements(12)
          .media(Media.LINK)
          .aliases("ps1"),
      new SystemInfo("PlayStation Portable", "psp", "iso,cso,chd,pbp")
          .core("ppsspp")
          .apps("ppsspp")
          .achievements(41)
          .media(Media.LINK)
          .assets("PPSSPP")
          .ownSaves(),
      new SystemInfo("PlayStation 2", "ps2", "iso,chd,cso,bin")
          .apps("nethersx2")
          .retroArch("play")
          .achievements(21),
      new SystemInfo("PlayStation 3", "ps3", "iso,pkg").apps("aps3e", "rpcsx"),
      new SystemInfo("PlayStation Vita", "psvita", "vpk,zip").apps("vita3k").aliases("vita"),
      new SystemInfo("GameCube", "gc", "iso,rvz,gcm,ciso")
          .apps("dolphin")
          .retroArch("dolphin")
          .achievements(16)
          .aliases("gamecube"),
      new SystemInfo("Wii", "wii", "iso,rvz,wbfs").apps("dolphin").retroArch("dolphin"),
      new SystemInfo("Wii U", "wiiu", "wud,wux,wua,rpx,wuhb").apps("cemu"),
      new SystemInfo("Nintendo 3DS", "3ds", "3ds,cci,cxi,cia")
          .apps("azahar")
          .retroArch("citra")
          .achievements(62)
          .aliases("n3ds"),
      new SystemInfo("Nintendo Switch", "switch", "nsp,xci").apps("eden"),
      new SystemInfo("Xbox", "xbox", "iso,xiso"),
      new SystemInfo("Xbox 360", "xbox360", "iso,xex,zar").apps("ax360e"),
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
  // Overrides are synced in settings.json as {"key": true|false}; a missing key follows the
  // global setting. Per game: automatic states, keyed by gameKey. Per system: the built-in core,
  // keyed by system folder.
  static final String STATE_OVERRIDES = "autoStatesOverrides";
  static final String BUILT_IN_OVERRIDES = "builtInOverrides";
  synchronized Boolean override(String setting, String key) {
    JSONObject overrides = settings.optJSONObject(setting);
    return overrides == null || !overrides.has(key) ? null : overrides.optBoolean(key);
  }
  /** null removes the override, so the global setting applies again. */
  synchronized void setOverride(String setting, String key, Boolean value) {
    JSONObject overrides = settings.optJSONObject(setting);
    if (overrides == null)
      overrides = new JSONObject();
    try {
      if (value == null)
        overrides.remove(key);
      else
        overrides.put(key, value.booleanValue());
    } catch (Exception ignored) {
    }
    put(setting, overrides);
  }
  synchronized int overrideCount(String setting) {
    JSONObject overrides = settings.optJSONObject(setting);
    return overrides == null ? 0 : overrides.length();
  }
  synchronized void clearOverrides(String setting) {
    put(setting, new JSONObject());
  }
  Boolean autoStatesOverride(String gameKey) {
    return override(STATE_OVERRIDES, gameKey);
  }
  boolean autoStates(String gameKey) {
    Boolean override = autoStatesOverride(gameKey);
    return override != null ? override : get("autoStates", false);
  }
  void setAutoStatesOverride(String gameKey, Boolean value) {
    setOverride(STATE_OVERRIDES, gameKey, value);
  }
  /** Global "Built-in emulators" switch; the old "Use RetroArch" setting turned it off. */
  boolean builtInDefault() {
    return get("builtIn", !get("useRetroArch", false));
  }
  boolean builtIn(SystemInfo system) {
    Boolean override = override(BUILT_IN_OVERRIDES, system.folder);
    return override != null ? override : builtInDefault();
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
      if (!extensions.contains(ext) || system.hidden.contains(i.name.toLowerCase(Locale.ROOT)))
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
          for (int alias = 0; directory == null && alias < system.aliases.length; alias++)
            directory = directories.get(system.aliases[alias]);
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
