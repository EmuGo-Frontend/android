package org.emugo.frontend;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.*;

public final class CoreActivity extends Activity {
  static {
    System.loadLibrary("emugo");
  }
  static native String nativeRun(
      CoreActivity owner, String core, String rom, String work, String saves, boolean autoStates);
  static native void nativeStop();
  static native void nativePause(boolean value);
  static native void nativeButtons(int bits);
  static native void nativeAutoStates(boolean value);
  static native void nativeSetVariable(String key, String value);
  static native void nativeAxes(int leftX, int leftY, int rightX, int rightY);
  /** Serves fd under path through the VFS; returns the host's duplicate of fd, or -1. */
  static native int nativeMapFile(String path, int fd);
  static native void nativePointer(int x, int y, boolean pressed);
  static native void nativeClearFiles();
  static native void nativeFlush();
  private final java.util.concurrent.ExecutorService saveSync =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private FrameView frame;
  private PocketDialog.Builder pauseMenu;
  private Dialog pauseDialog;
  private Runnable pauseBack;
  private AudioTrack audio;
  private int bits;
  private boolean firstHeld, secondHeld, comboTriggered, menuShown;
  private int firstKey, secondKey;
  private Library library;
  private String gameTitle;
  private String fileTitle;
  private String saveStem;
  private volatile boolean autoStates;
  private File work;
  /** Where the core writes save files of its own: work/saves for ownSaves systems. */
  private File saves;
  private Library.SystemInfo info;
  private final Handler main = new Handler(Looper.getMainLooper());
  private String system;
  private boolean analogSystem;
  private int axisBits;
  private Banner raPopup;
  private long playStarted;
  private String raGameImage;
  private final Runnable presenceTicker = new Runnable() {
    public void run() {
      updatePresence();
      main.postDelayed(this, 30000);
    }
  };
  private final RaBridge.Listener raListener = this::onRaEvent;
  private volatile boolean raActive;
  static void start(Activity from, Library.Game game) {
    Intent i = new Intent(from, CoreActivity.class);
    i.putExtra("uri", game.uri.toString());
    i.putExtra("core", game.system.core);
    i.putExtra("system", game.system.folder);
    i.putExtra("systemName", game.system.name);
    i.putExtra("title", game.title);
    i.putExtra("fileTitle", game.fileTitle);
    i.putExtra("saveStem", game.saveStem);
    // No slide animation into the game; finish() does the same on the way out.
    from.startActivity(i, ActivityOptions.makeCustomAnimation(from, 0, 0).toBundle());
  }
  @Override
  public void finish() {
    super.finish();
    if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
  }
  @Override
  protected void attachBaseContext(android.content.Context base) {
    super.attachBaseContext(L10n.wrap(base));
  }
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
    getWindow().getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
    library = new Library(this);
    Brightness.apply(this, getWindow());
    gameTitle = getIntent().getStringExtra("title");
    fileTitle = getIntent().getStringExtra("fileTitle");
    if (fileTitle == null) fileTitle = gameTitle;
    system = getIntent().getStringExtra("system");
    analogSystem = "n64".equals(system);
    saveStem = getIntent().getStringExtra("saveStem");
    autoStates = library.autoStates(Library.gameKey(system, saveStem));
    String combo = library.get("menuCombo", "Start + Select");
    firstKey =
        combo.equals("L3 + R3") ? KeyEvent.KEYCODE_BUTTON_THUMBL : KeyEvent.KEYCODE_BUTTON_SELECT;
    secondKey = combo.equals("L3 + R3") ? KeyEvent.KEYCODE_BUTTON_THUMBR
        : combo.equals("Select + X")    ? KeyEvent.KEYCODE_BUTTON_X
                                        : KeyEvent.KEYCODE_BUTTON_START;
    playStarted = System.currentTimeMillis();
    updatePresence();
    frame = new FrameView();
    setContentView(frame);
    TextView loading = new TextView(this);
    loading.setText(getString(R.string.preparing_x, gameTitle));
    loading.setTextColor(-1);
    loading.setGravity(Gravity.CENTER);
    addContentView(loading, new ViewGroup.LayoutParams(-1, -1));
    raPopup = new Banner(this);
    new Thread(() -> prepare(loading), "EmuGo-core").start();
  }
  private void prepare(TextView loading) {
    info = Library.system(system);
    try {
      String coreName = getIntent().getStringExtra("core");
      String raw = getIntent().getStringExtra("uri");
      if (coreName == null || raw == null)
        throw new IOException(getString(R.string.missing_game));
      work = new File(getFilesDir(), "run/" + fileTitle.replaceAll("[^a-zA-Z0-9._-]", "_"));
      work.mkdirs();
      saves = info != null && info.ownSaves ? new File(work, "saves") : work;
      saves.mkdirs();
      if (!autoStates) new File(work, "state.sav").delete();
      restoreSave();
      File core;
      try {
        core = downloadCore(coreName);
      } catch (IOException e) {
        throw new CoreFailure(e.getMessage());
      }
      Library.Media media = info == null ? Library.Media.COPY : info.media;
      Uri game = Uri.parse(raw);
      File rom = media == Library.Media.LINK ? linkDisc(game)
          : media == Library.Media.ARCHIVE   ? linkArchive(game)
          : media == Library.Media.FOLDER    ? linkFolder(game)
                                             : copyGame(game);
      prepareBios();
      try {
        prepareAssets();
      } catch (IOException e) {
        throw new CoreFailure(e.getMessage());
      }
      if ("n64".equals(system)) {
        // The host draws in software only, so parallel_n64 uses its software RDP.
        nativeSetVariable("parallel-n64-gfxplugin", "angrylion");
        nativeSetVariable("parallel-n64-rspplugin", "auto");
      }
      raActive = RaBridge.prepare(this, library, system, raListener);
      main.post(() -> ((ViewGroup) loading.getParent()).removeView(loading));
      String error = nativeRun(this, core.getAbsolutePath(), rom.getAbsolutePath(),
          work.getAbsolutePath(), saves.getAbsolutePath(), autoStates);
      if (!error.isEmpty())
        throw new CoreFailure(error);
      saveSync.shutdown();
      try {
        saveSync.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
      }
      syncSave(true);
      main.post(this::finish);
    } catch (Exception e) {
      Library.Game game = currentGame();
      if (e instanceof CoreFailure && game != null
          && (ExternalEmulators.find(this, game.system) != null
              || RetroArchLauncher.canRun(this, game))) {
        // The built-in core is unavailable or broken: the standalone app or RetroArch takes
        // over without asking.
        main.post(() -> GameLauncher.withoutBuiltIn(this, game, this::finish));
        return;
      }
      String message = e.getMessage();
      main.post(()
                    -> new PocketDialog.Builder(this)
                           .setTitle(R.string.could_not_start_game)
                           .setMessage(message)
                           .setPositiveButton(R.string.ok, (d, w) -> finish())
                           .show());
    }
  }
  /**
   * Copies the user's own BIOS files from ROMs/bios into the core's system folder. With a BIOS
   * present, gambatte and mGBA run the real boot sequence (logo and chime); "Boot intros" off
   * tells them to skip it.
   */
  private void prepareBios() {
    // Other systems: each listed file is copied from ROMs/bios when present, and removed from
    // the core's system folder when it is not.
    Uri biosFolder = library.findChild(library.rootDocument(), "bios");
    if (info != null)
      for (String[] entry : info.bios) {
        File destination = new File(work, entry[1]);
        destination.getParentFile().mkdirs();
        destination.delete();
        try {
          Uri bios = library.findChild(biosFolder, entry[0]);
          if (bios != null)
            try (InputStream in = getContentResolver().openInputStream(bios);
                 OutputStream out = new FileOutputStream(destination)) {
              copy(in, out);
            }
        } catch (Exception ignored) {
          destination.delete();
        }
      }
    String source = "gb".equals(system) ? "gb.bin"
        : "gbc".equals(system)          ? "gbc.bin"
        : "gba".equals(system)          ? "gba.bin"
        : "psx".equals(system)          ? "ps1.bin"
                                        : null;
    if (source == null) return;
    // pcsx_rearmed looks for known BIOS names and falls back to its built-in open HLE BIOS.
    String target = "psx".equals(system) ? "scph5501.bin" : source.replace(".bin", "_bios.bin");
    File destination = new File(work, target);
    destination.delete();
    try {
      Uri bios = library.findChild(library.findChild(library.rootDocument(), "bios"), source);
      if (bios != null)
        try (InputStream in = getContentResolver().openInputStream(bios);
            OutputStream out = new FileOutputStream(destination)) {
          copy(in, out);
        }
    } catch (Exception ignored) {
      destination.delete();
    }
    boolean intro = library.get("bootIntros", true) && destination.length() > 0;
    nativeSetVariable("gambatte_gb_bootloader", intro ? "enabled" : "disabled");
    nativeSetVariable("mgba_use_bios", destination.length() > 0 ? "ON" : "OFF");
    nativeSetVariable("mgba_skip_bios", intro ? "OFF" : "ON");
    nativeSetVariable("pcsx_rearmed_bios", "auto");
    nativeSetVariable("pcsx_rearmed_show_bios_bootlogo", intro ? "enabled" : "disabled");
  }
  /** Cartridge games: copied into the work folder, a .zip unpacked to its first file. */
  private File copyGame(Uri uri) throws IOException {
    File rom = new File(work, "game" + extension(uri));
    try (InputStream in = getContentResolver().openInputStream(uri);
         OutputStream out = new FileOutputStream(rom)) {
      if (in == null)
        throw new IOException(getString(R.string.could_not_open_rom));
      copy(in, out);
    }
    if (!rom.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))
      return rom;
    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(rom))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory())
          continue;
        String filename = new File(entry.getName()).getName();
        if (filename.isEmpty())
          continue;
        File extracted = new File(work, filename);
        try (OutputStream out = new FileOutputStream(extracted)) {
          copy(zip, out);
        }
        return extracted;
      }
    }
    throw new IOException(getString(R.string.archive_has_no_game_file));
  }
  private static String documentName(Uri uri) {
    String id = android.provider.DocumentsContract.getDocumentId(uri);
    String name = id.substring(id.lastIndexOf('/') + 1);
    return name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
  }
  private Uri parentFolder(Uri uri) {
    String id = android.provider.DocumentsContract.getDocumentId(uri);
    int slash = id.lastIndexOf('/');
    return slash < 0 ? null
                     : android.provider.DocumentsContract.buildDocumentUriUsingTree(
                           library.root, id.substring(0, slash));
  }
  /** An empty folder in the work folder for linked files; earlier links are dropped. */
  private File freshFolder(String name) {
    File folder = new File(work, name);
    nativeClearFiles();
    deleteTree(folder);
    folder.mkdirs();
    return folder;
  }
  private static void deleteTree(File file) {
    // Links are deleted themselves, never followed.
    File[] children = java.nio.file.Files.isSymbolicLink(file.toPath()) ? null : file.listFiles();
    if (children != null)
      for (File child : children) deleteTree(child);
    file.delete();
  }
  /**
   * Disc games are read in place: each file is opened through SAF and appears in the work folder
   * under its own name. A .cue or .gdi is copied (it is tiny) and the tracks it lists are linked
   * next to it; .ccd and .mds images bring their files with the same name.
   */
  private File linkDisc(Uri uri) throws IOException {
    File disc = freshFolder("disc");
    String name = documentName(uri);
    String lower = name.toLowerCase(Locale.ROOT);
    File target = new File(disc, name);
    boolean sheet = lower.endsWith(".cue") || lower.endsWith(".gdi");
    if (!sheet)
      link(target, uri);
    java.util.List<Library.Item> siblings = null;
    if (lower.endsWith(".ccd") || lower.endsWith(".mds")) {
      String stem = lower.substring(0, lower.lastIndexOf('.') + 1);
      siblings = library.children(parentFolder(uri));
      for (Library.Item item : siblings)
        if (item.name.toLowerCase(Locale.ROOT).startsWith(stem) && !item.name.equals(name))
          link(new File(disc, item.name), item.uri);
    }
    if (!sheet)
      return target;
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null)
        throw new IOException(getString(R.string.could_not_open_rom));
      copy(in, text);
    }
    try (OutputStream out = new FileOutputStream(target)) {
      text.writeTo(out);
    }
    siblings = library.children(parentFolder(uri));
    // cue: FILE "Track 01.bin" BINARY. gdi: 1 0 4 2352 "track01.bin" 0 (first line is a count).
    java.util.regex.Matcher m = lower.endsWith(".cue")
        ? java.util.regex.Pattern.compile("(?im)^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))")
              .matcher(text.toString("UTF-8"))
        : java.util.regex.Pattern
              .compile("(?m)^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(?:\"([^\"]+)\"|(\\S+))")
              .matcher(text.toString("UTF-8"));
    while (m.find()) {
      String track = new File(m.group(1) != null ? m.group(1) : m.group(2)).getName();
      Library.Item match = null;
      for (Library.Item item : siblings)
        if (item.name.equalsIgnoreCase(track)) match = item;
      if (match == null)
        throw new IOException(getString(R.string.missing_disc_file_x, track));
      link(new File(disc, track), match.uri);
    }
    return target;
  }
  /**
   * Arcade sets and DOS games: the archive keeps its name, which identifies the game. BIOS sets
   * and parent sets from the same folder are linked next to it, so clones find their parent.
   */
  private File linkArchive(Uri uri) throws IOException {
    File content = freshFolder("content");
    String name = documentName(uri);
    File target = new File(content, name);
    link(target, uri);
    String stem = name.toLowerCase(Locale.ROOT);
    stem = stem.contains(".") ? stem.substring(0, stem.lastIndexOf('.')) : stem;
    for (Library.Item item : library.children(parentFolder(uri))) {
      String other = item.name.toLowerCase(Locale.ROOT);
      if (other.equals(name.toLowerCase(Locale.ROOT)) || item.directory())
        continue;
      String otherStem = other.contains(".") ? other.substring(0, other.lastIndexOf('.')) : other;
      if ((info != null && info.hidden.contains(other))
          || (!otherStem.isEmpty() && stem.startsWith(otherStem)))
        link(new File(content, item.name), item.uri);
    }
    return target;
  }
  /** ScummVM: every file in the game's folder, subfolders included, is linked in place. */
  private File linkFolder(Uri uri) throws IOException {
    File content = freshFolder("content");
    linkTree(parentFolder(uri), content, 0);
    return new File(content, documentName(uri));
  }
  private void linkTree(Uri folder, File target, int depth) throws IOException {
    if (folder == null || depth > 6)
      return;
    for (Library.Item item : library.children(folder)) {
      File destination = new File(target, item.name);
      if (item.directory()) {
        destination.mkdirs();
        linkTree(item.uri, destination, depth + 1);
      } else {
        link(destination, item.uri);
      }
    }
  }
  /**
   * Some cores need libretro's system files (blueMSX machines, PPSSPP fonts, ScummVM themes).
   * They are downloaded once, like cores, and linked into the work folder.
   */
  private void prepareAssets() throws IOException {
    if (info == null || info.assets == null)
      return;
    File pack = new File(getFilesDir(), "assets/" + info.assets);
    File done = new File(pack, ".complete");
    if (!done.exists()) {
      deleteTree(pack);
      pack.mkdirs();
      URL url = new URL(
          "https://buildbot.libretro.com/assets/system/" + Uri.encode(info.assets) + ".zip");
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
      if (c.getResponseCode() != 200)
        throw new IOException(
            getString(R.string.core_download_failed_x, "HTTP " + c.getResponseCode()));
      String root = pack.getCanonicalPath() + File.separator;
      try (ZipInputStream zip = new ZipInputStream(c.getInputStream())) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          File file = new File(pack, entry.getName());
          if (!file.getCanonicalPath().startsWith(root))
            continue; // no paths outside the pack
          if (entry.isDirectory()) {
            file.mkdirs();
            continue;
          }
          file.getParentFile().mkdirs();
          try (OutputStream out = new FileOutputStream(file)) {
            copy(zip, out);
          }
        }
      }
      done.createNewFile();
    }
    File[] entries = pack.listFiles();
    if (entries == null)
      return;
    for (File entry : entries) {
      if (entry.getName().startsWith("."))
        continue;
      File destination = new File(work, entry.getName());
      if (java.nio.file.Files.isSymbolicLink(destination.toPath()))
        continue;
      try {
        android.system.Os.symlink(entry.getAbsolutePath(), destination.getPath());
      } catch (android.system.ErrnoException ignored) {
      }
    }
  }
  /** The built-in core could not be downloaded or loaded, or it rejected the game. */
  private static final class CoreFailure extends IOException {
    CoreFailure(String message) {
      super(message);
    }
  }
  private Library.Game currentGame() {
    Library.SystemInfo info = Library.system(system);
    String raw = getIntent().getStringExtra("uri");
    return info == null || raw == null
        ? null
        : new Library.Game(info, gameTitle, fileTitle, saveStem, Uri.parse(raw));
  }
  /**
   * Serves a SAF document under target: through the host's VFS, and as a link to the open file
   * (/proc/self/fd) for cores that open files themselves. The host keeps its own duplicate.
   */
  private void link(File target, Uri uri) throws IOException {
    try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
      if (descriptor == null)
        throw new IOException(getString(R.string.could_not_open_rom));
      target.delete();
      int fd = nativeMapFile(target.getPath(), descriptor.getFd());
      if (fd >= 0)
        try {
          android.system.Os.symlink("/proc/self/fd/" + fd, target.getPath());
        } catch (android.system.ErrnoException ignored) {
          // The VFS path still works for cores that use it.
        }
    }
  }
  private File downloadCore(String name) throws IOException {
    File dir = new File(getFilesDir(), "cores");
    dir.mkdirs();
    File so = new File(dir, name + "_libretro_android.so");
    if (so.exists() && so.length() > 10000)
      return so;
    String zipName = name + "_libretro_android.so.zip";
    File archive = new File(dir, zipName);
    Uri emugo = library.ensureEmugo();
    Uri coreDir = library.findChild(emugo, "cores");
    try {
      if (coreDir == null && emugo != null)
        coreDir = android.provider.DocumentsContract.createDocument(getContentResolver(), emugo,
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "cores");
      Uri synced = library.findChild(coreDir, zipName);
      if (synced != null)
        try (InputStream in = getContentResolver().openInputStream(synced);
            OutputStream out = new FileOutputStream(archive)) {
          copy(in, out);
        }
    } catch (Exception ignored) {
    }
    if (!archive.exists() || archive.length() == 0) {
      IOException last = null;
      for (String suffix : new String[] {"_libretro_android.so.zip", "_libretro.so.zip"}) {
        try {
          URL url = new URL(
              "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/" + name + suffix);
          HttpURLConnection c = (HttpURLConnection) url.openConnection();
          c.setConnectTimeout(15000);
          c.setReadTimeout(30000);
          if (c.getResponseCode() != 200)
            throw new IOException("HTTP " + c.getResponseCode());
          try (InputStream in = c.getInputStream();
              OutputStream out = new FileOutputStream(archive)) {
            copy(in, out);
          }
          last = null;
          break;
        } catch (IOException e) {
          last = e;
        }
      }
      if (last != null)
        throw new IOException(getString(R.string.core_download_failed_x, last.getMessage()));
      try {
        if (coreDir != null) {
          Uri target = library.findChild(coreDir, zipName);
          if (target == null)
            target = android.provider.DocumentsContract.createDocument(
                getContentResolver(), coreDir, "application/zip", zipName);
          try (InputStream in = new FileInputStream(archive);
              OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            copy(in, out);
          }
        }
      } catch (Exception ignored) {
      }
    }
    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().endsWith(".so")) {
          try (OutputStream out = new FileOutputStream(so)) {
            copy(zip, out);
          }
          so.setReadable(true, false);
          so.setWritable(false, false);
          return so;
        }
      }
    }
    throw new IOException(getString(R.string.no_core_library_in_archive));
  }
  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] b = new byte[65536];
    int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
  }
  private static String extension(Uri uri) {
    String s = uri.getLastPathSegment();
    if (s == null)
      return ".rom";
    int n = s.lastIndexOf('.');
    return n < 0 ? ".rom" : s.substring(n);
  }
  void onFrame(int[] pixels, int width, int height) {
    frame.update(pixels, width, height);
  }
  void onAudioFormat(int sampleRate) {
    audio = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(
                    AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT) * 2,
                    sampleRate * 4 / 12))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    if (!menuShown) audio.play();
  }
  /** Pauses the core and silences already-buffered audio at the same moment. */
  private void setPaused(boolean value) {
    nativePause(value);
    AudioTrack a = audio;
    if (a == null) return;
    try {
      if (value) {
        a.pause();
        a.flush();
      } else {
        a.play();
      }
    } catch (IllegalStateException ignored) {
    }
  }
  void onAudio(short[] pcm) {
    AudioTrack a = audio;
    if (a != null)
      a.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
  }
  private String safeTitle() {
    return fileTitle.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
  /** Called by the core thread with the core's display aspect ratio (0 when unknown). */
  void onGeometry(float aspect) {
    if (frame != null)
      frame.coreAspect = aspect;
  }
  /** Called by the core thread whenever save RAM was written to the work folder. */
  void onSaveWritten() {
    saveSync.execute(() -> syncSave(false));
  }
  /**
   * Copies the work folder's battery save (and clock) to ROMs/emugo/saves; on exit also the
   * automatic state. Battery files use the ROM's file name, like RetroArch.
   */
  private synchronized void syncSave(boolean includeState) {
    if (work == null || library.root == null)
      return;
    try {
      Uri dir = library.ensureEmugo();
      Uri saves = library.findChild(dir, "saves");
      if (saves == null)
        saves = android.provider.DocumentsContract.createDocument(getContentResolver(), dir,
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "saves");
      for (String[] pair : new String[][] {
               {"save.srm", ".srm"}, {"save.rtc", ".rtc"}, {"state.sav", ".state"}}) {
        boolean state = pair[0].equals("state.sav");
        if (state && (!autoStates || !includeState)) continue;
        File source = new File(work, pair[0]);
        if (!source.exists())
          continue;
        String stem = !state && saveStem != null ? saveStem : safeTitle();
        String name = stem + pair[1];
        Uri out = library.findChild(saves, name);
        if (out == null)
          out = android.provider.DocumentsContract.createDocument(
              getContentResolver(), saves, "application/octet-stream", name);
        try (InputStream in = new FileInputStream(source);
            OutputStream stream = getContentResolver().openOutputStream(out, "wt")) {
          copy(in, stream);
        }
      }
      if (info != null && info.ownSaves)
        syncTree(this.saves, folder(saves, saveStem != null ? saveStem : safeTitle()));
    } catch (Exception ignored) {
    }
  }
  // Files the core wrote itself, by path, with the modification time last copied to the ROMs
  // folder: ROMs/emugo/saves/<ROM name>/ mirrors work/saves.
  private final java.util.Map<String, Long> syncedSaves = new java.util.HashMap<>();
  private Uri folder(Uri parent, String name) throws IOException {
    Uri found = library.findChild(parent, name);
    return found != null
        ? found
        : android.provider.DocumentsContract.createDocument(getContentResolver(), parent,
              android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name);
  }
  private void syncTree(File source, Uri target) throws IOException {
    File[] files = source.listFiles();
    if (files == null || target == null)
      return;
    for (File file : files) {
      if (file.isDirectory()) {
        syncTree(file, folder(target, file.getName()));
        continue;
      }
      Long synced = syncedSaves.get(file.getPath());
      if (synced != null && synced == file.lastModified())
        continue;
      Uri out = library.findChild(target, file.getName());
      if (out == null)
        out = android.provider.DocumentsContract.createDocument(
            getContentResolver(), target, "application/octet-stream", file.getName());
      try (InputStream in = new FileInputStream(file);
           OutputStream stream = getContentResolver().openOutputStream(out, "wt")) {
        copy(in, stream);
      }
      syncedSaves.put(file.getPath(), file.lastModified());
    }
  }
  private void restoreTree(Uri source, File target) throws IOException {
    if (source == null)
      return;
    target.mkdirs();
    for (Library.Item item : library.children(source)) {
      File destination = new File(target, item.name);
      if (item.directory()) {
        restoreTree(item.uri, destination);
        continue;
      }
      try (InputStream in = getContentResolver().openInputStream(item.uri);
           OutputStream out = new FileOutputStream(destination)) {
        if (in != null)
          copy(in, out);
      }
      syncedSaves.put(destination.getPath(), destination.lastModified());
    }
  }
  private void restoreSave() {
    try {
      Uri dir = library.findChild(library.ensureEmugo(), "saves");
      String own = safeTitle();
      String rom = saveStem != null ? saveStem : own;
      // Most specific first: EmuGo!'s own automatic state wins over one imported from RetroArch.
      String[][] groups = {
          {"state.sav", own + ".state", rom + ".state", rom + ".state.auto", own + ".state.auto"},
          {"save.srm", rom + ".srm", rom + ".sav", own + ".srm", own + ".sav"},
          {"save.rtc", rom + ".rtc", own + ".rtc"}};
      for (String[] group : groups) {
        if (group[0].equals("state.sav") && !autoStates) continue;
        Uri source = null;
        for (int i = 1; i < group.length && source == null; i++)
          source = library.findChild(dir, group[i]);
        if (source != null)
          try (InputStream in = getContentResolver().openInputStream(source);
              OutputStream out = new FileOutputStream(new File(work, group[0]))) {
            copy(in, out);
          }
      }
      if (info != null && info.ownSaves)
        restoreTree(library.findChild(dir, saveStem != null ? saveStem : own), saves);
    } catch (Exception ignored) {
    }
  }
  private void menu() {
    if (menuShown)
      return;
    menuShown = true;
    setPaused(true);
    pauseMenu = new PocketDialog.Builder(this)
        .keepOpenOnItemClick()
        .setOnCancelListener(d -> {
          menuShown = false;
          pauseMenu = null;
          pauseDialog = null;
          pauseBack = null;
          setPaused(false);
        });
    showGameMenu();
    pauseMenu.setOnBack(() -> {
      if (pauseBack != null) pauseBack.run();
    });
    pauseDialog = pauseMenu.show();
  }
  private void closePauseMenu(boolean resume) {
    Dialog dialog = pauseDialog;
    pauseDialog = null;
    pauseMenu = null;
    pauseBack = null;
    menuShown = false;
    if (dialog != null) dialog.dismiss();
    if (resume) setPaused(false);
  }
  private void showGameMenu() {
    pauseBack = () -> closePauseMenu(true);
    int[] items = raActive
        ? new int[] {R.string.resume_game, R.string.take_screenshot, R.string.achievements,
              R.string.emulation, R.string.return_to_library}
        : new int[] {R.string.resume_game, R.string.take_screenshot, R.string.emulation,
              R.string.return_to_library};
    pauseMenu.replacePage(gameTitle, labels(items), -1, (d, choice) -> {
      int item = items[choice];
      if (item == R.string.take_screenshot) {
        Screenshots.save(this, library, frame.snapshot(), gameTitle);
        closePauseMenu(true);
      } else if (item == R.string.achievements) {
        showAchievementsMenu();
      } else if (item == R.string.emulation) {
        showEmulationMenu();
      } else if (item == R.string.return_to_library) {
        closePauseMenu(false);
        setPaused(true);
        nativeStop();
        finish();
      } else {
        closePauseMenu(true);
      }
    });
  }
  private String[] labels(int... ids) {
    String[] labels = new String[ids.length];
    for (int index = 0; index < ids.length; index++) labels[index] = getString(ids[index]);
    return labels;
  }
  private void showAchievementsMenu() {
    pauseBack = this::showGameMenu;
    String[] rows = RaBridge.nativeAchievements();
    String[] summary = RaBridge.nativeSummary().split("\t", -1);
    String header = null;
    if (summary.length >= 7) {
      header = getString(R.string.x_of_x_achievements_unlocked, summary[2], summary[3]) + "  ·  "
          + getString(R.string.x_of_x_points, summary[4], summary[5])
          + (summary[6].equals("1") ? "  ·  " + getString(R.string.hardcore) : "");
    }
    java.util.List<RichRow.Entry> entries = new ArrayList<>();
    for (String row : rows) {
      String[] part = row.split("\t", -1);
      if (part[0].equals("H") && !entries.isEmpty() && entries.get(entries.size() - 1).header)
        entries.remove(entries.size() - 1); // section left empty by filtered rows
      if (part[0].equals("H")) {
        entries.add(
            RichRow.Entry.header(getString(RaBridge.bucketLabel(Integer.parseInt(part[1])))));
      } else if (part.length >= 7) {
        boolean unlocked = part[1].equals("1");
        String detail = part[3] + (part[5].isEmpty() ? "" : "  ·  " + part[5]);
        entries.add(new RichRow.Entry(part[2], detail, part[4], part[6], !unlocked));
      }
    }
    if (!entries.isEmpty() && entries.get(entries.size() - 1).header)
      entries.remove(entries.size() - 1);
    if (entries.isEmpty()) {
      pauseMenu.replacePage(getString(R.string.achievements),
          labels(R.string.no_achievements_for_this_game), -1, (d, w) -> showGameMenu());
      return;
    }
    pauseMenu.replaceEntries(summary.length >= 7 && !summary[0].isEmpty()
            ? summary[0]
            : getString(R.string.achievements),
        header, entries.toArray(new RichRow.Entry[0]), index -> {});
  }
  void onRaEvent(int kind, int value, String title, String text, String imageUrl) {
    if (raPopup == null || isFinishing()) return;
    if (kind == RaBridge.LOADED) {
      // Bookkeeping that must happen even with RA notifications turned off.
      if (value > 0)
        getSharedPreferences("local", 0).edit()
            .putString("raGameRom:" + value, getIntent().getStringExtra("uri")).apply();
      raGameImage = imageUrl;
      updatePresence();
      main.removeCallbacks(presenceTicker);
      main.postDelayed(presenceTicker, 30000);
    }
    if (kind == RaBridge.ERROR && value != RaBridge.NO_GAME_LOADED) {
      // Server errors such as unsupported-emulator warnings stay out of the game.
      android.util.Log.w("EmuGo-RA", title + ": " + text);
      return;
    }
    if (!library.get("raNotifications", true)) return;
    if (kind == RaBridge.ERROR) {
      String key = "raUnknownShown:" + system + "/" + gameTitle;
      android.content.SharedPreferences local = getSharedPreferences("local", 0);
      if (local.getBoolean(key, false)) return;
      local.edit().putBoolean(key, true).apply();
    }
    switch (kind) {
      case RaBridge.UNLOCK:
        raPopup.show(
            title, text + "  ·  " + getString(R.string.x_points, String.valueOf(value)), imageUrl);
        break;
      case RaBridge.LOADED: {
        String[] counts = text.split("/");
        String summary = counts.length == 2 && !counts[1].equals("0")
            ? getString(R.string.x_of_x_achievements_unlocked, counts[0], counts[1])
            : getString(R.string.this_game_has_no_achievements_yet);
        raPopup.show(title, summary, imageUrl);
        break;
      }
      case RaBridge.LEADERBOARD:
        raPopup.show(getString(value == 0 ? R.string.leaderboard_attempt_started
                             : value == 1 ? R.string.leaderboard_attempt_failed
                                          : R.string.leaderboard_submitted),
            value == 2 ? title + "  ·  " + text : title, null);
        break;
      case RaBridge.COMPLETED:
        raPopup.show(getString(value != 0 ? R.string.mastered_x : R.string.completed_x, title),
            null, imageUrl);
        break;
      case RaBridge.CONNECTION:
        raPopup.show(getString(value == 0 ? R.string.retroachievements_offline
                                          : R.string.retroachievements_reconnected),
            value == 0 ? getString(R.string.ra_offline_detail) : null, null);
        break;
      case RaBridge.LOGIN_FAILED:
        raPopup.show(getString(R.string.ra_sign_in_failed), text, null);
        break;
      case RaBridge.ERROR:
        raPopup.show("RetroAchievements",
            value == RaBridge.NO_GAME_LOADED ? getString(R.string.ra_game_not_recognized) : text,
            null);
        break;
      default:
        break;
    }
  }
  /** Discord: game title, then RetroAchievements rich presence or the system name. */
  private void updatePresence() {
    String systemName = getIntent().getStringExtra("systemName");
    String state = raActive ? RaBridge.nativeRichPresence() : "";
    // RA scripts often append stat blocks like "[Normal] [◻◻◻] [⚪x04] [0Pts.]"; Discord gets
    // only the readable sentence.
    if (state != null)
      state = state.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s{2,}", " ")
                  .replaceAll("[\\s,;:|·-]+$", "").trim();
    if (state == null || state.isEmpty()) state = systemName;
    DiscordPresence.playing(this, gameTitle, state, raGameImage, systemName, playStarted);
  }
  private void showEmulationMenu() {
    pauseBack = this::showGameMenu;
    pauseMenu.replacePage(getString(R.string.emulation),
        labels(R.string.display_modes, R.string.automatic_states), -1, (d, w) -> {
          if (w == 0)
            showDisplayModesMenu();
          if (w == 1)
            showAutoStatesMenu();
        });
  }
  /** Default follows Settings > Emulation; On and Off apply to this game only. */
  private void showAutoStatesMenu() {
    pauseBack = this::showEmulationMenu;
    String key = Library.gameKey(system, saveStem);
    Boolean override = library.autoStatesOverride(key);
    String fallback = getString(library.get("autoStates", false) ? R.string.on : R.string.off);
    String[] options = {
        getString(R.string.default_x, fallback), getString(R.string.on), getString(R.string.off)};
    int selected = override == null ? 0 : override ? 1 : 2;
    pauseMenu.replacePage(getString(R.string.automatic_states), options, selected, (d, w) -> {
      library.setAutoStatesOverride(key, w == 0 ? null : Boolean.valueOf(w == 1));
      autoStates = library.autoStates(key);
      nativeAutoStates(autoStates);
      showEmulationMenu();
    });
  }
  private void showDisplayModesMenu() {
    pauseBack = this::showEmulationMenu;
    pauseMenu.replacePage(getString(R.string.display_modes),
        new String[] {"Game Boy", "Game Boy Color"}, -1, (d, w) -> {
          if (w == 0)
            showConsoleDisplayMenu(true);
          if (w == 1)
            showConsoleDisplayMenu(false);
        });
  }
  private void showConsoleDisplayMenu(boolean gameBoy) {
    pauseBack = this::showDisplayModesMenu;
    String[] labels = gameBoy
        ? labels(R.string.green_palette, R.string.dot_matrix, R.string.lcd_blending)
        : labels(R.string.screen_filter, R.string.lcd_blending);
    String[] keys = gameBoy
        ? new String[] {"gbGreen", "gbDotMatrix", "gbLcd"}
        : new String[] {"gbcScreenFilter", "gbcLcd"};
    pauseMenu.replacePage(gameBoy ? "Game Boy" : "Game Boy Color", labels, -1,
        (d, w) -> showBooleanMenu(labels[w], keys[w], true,
            () -> showConsoleDisplayMenu(gameBoy)));
  }
  private void showBooleanMenu(
      String label, String key, boolean defaultValue, Runnable parentMenu) {
    pauseBack = parentMenu;
    pauseMenu.replacePage(label, labels(R.string.off, R.string.on),
        library.get(key, defaultValue) ? 1 : 0, (d, w) -> {
          boolean enabled = w == 1;
          library.put(key, enabled);
          frame.refreshFilters();
          parentMenu.run();
        });
  }
  private int retroButton(int code) {
    boolean nintendo = library.get("layout", "Nintendo").equals("Nintendo");
    switch (code) {
      case KeyEvent.KEYCODE_BUTTON_A:
        return nintendo ? 8 : 0;
      case KeyEvent.KEYCODE_BUTTON_B:
        return nintendo ? 0 : 8;
      case KeyEvent.KEYCODE_BUTTON_X:
        return nintendo ? 9 : 1;
      case KeyEvent.KEYCODE_BUTTON_Y:
        return nintendo ? 1 : 9;
      case KeyEvent.KEYCODE_BUTTON_L1:
        return 10;
      case KeyEvent.KEYCODE_BUTTON_R1:
        return 11;
      case KeyEvent.KEYCODE_BUTTON_L2:
        return 12;
      case KeyEvent.KEYCODE_BUTTON_R2:
        return 13;
      case KeyEvent.KEYCODE_BUTTON_THUMBL:
        return 14;
      case KeyEvent.KEYCODE_BUTTON_THUMBR:
        return 15;
      case KeyEvent.KEYCODE_BUTTON_START:
        return 3;
      case KeyEvent.KEYCODE_BUTTON_SELECT:
        return 2;
      case KeyEvent.KEYCODE_DPAD_UP:
        return 4;
      case KeyEvent.KEYCODE_DPAD_DOWN:
        return 5;
      case KeyEvent.KEYCODE_DPAD_LEFT:
        return 6;
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        return 7;
      default:
        return -1;
    }
  }
  private void pulse(int id) {
    if (id < 0)
      return;
    bits |= 1 << id;
    nativeButtons(bits | axisBits);
    main.postDelayed(() -> {
      bits &= ~(1 << id);
      nativeButtons(bits | axisBits);
    }, 100);
  }
  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    int code = e.getKeyCode();
    boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
    if (code == firstKey || code == secondKey) {
      if (e.getRepeatCount() > 0)
        return true;
      if (code == firstKey)
        firstHeld = down;
      else
        secondHeld = down;
      if (firstHeld && secondHeld && !comboTriggered) {
        comboTriggered = true;
        main.post(this::menu);
      }
      if (!down) {
        if (!comboTriggered)
          pulse(retroButton(code));
        if (!firstHeld && !secondHeld)
          comboTriggered = false;
      }
      return true;
    }
    int id = retroButton(code);
    if (id >= 0) {
      if (down)
        bits |= 1 << id;
      else
        bits &= ~(1 << id);
      nativeButtons(bits | axisBits);
      return true;
    }
    return super.dispatchKeyEvent(e);
  }
  @Override
  public void onBackPressed() {
    menu();
  }
  @Override
  protected void onResume() {
    super.onResume();
    if (raPopup != null) raPopup.activate();
  }
  @Override
  protected void onPause() {
    super.onPause();
    if (raPopup != null) raPopup.deactivate();
    nativeFlush(); // write save RAM before Android may kill the backgrounded process
    // Cores with their own save files are copied out too.
    if (info != null && info.ownSaves && !saveSync.isShutdown())
      try {
        saveSync.execute(() -> syncSave(false));
      } catch (java.util.concurrent.RejectedExecutionException ignored) {
      }
  }
  private static int axis(float value) {
    if (Math.abs(value) < 0.12f) return 0;
    return Math.max(-32768, Math.min(32767, Math.round(value * 32767f)));
  }
  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    int source = event.getSource();
    if (event.getActionMasked() != MotionEvent.ACTION_MOVE
        || ((source & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
            && (source & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD))
      return super.dispatchGenericMotionEvent(event);
    float lx = event.getAxisValue(MotionEvent.AXIS_X), ly = event.getAxisValue(MotionEvent.AXIS_Y);
    float rx = event.getAxisValue(MotionEvent.AXIS_Z), ry = event.getAxisValue(MotionEvent.AXIS_RZ);
    float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
    float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
    float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
        event.getAxisValue(MotionEvent.AXIS_BRAKE));
    float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
        event.getAxisValue(MotionEvent.AXIS_GAS));
    nativeAxes(axis(lx), axis(ly), axis(rx), axis(ry));
    int next = 0;
    // D-pads that report as a hat, and the left stick on digital-only systems.
    float dx = hx != 0 ? hx : analogSystem ? 0 : lx;
    float dy = hy != 0 ? hy : analogSystem ? 0 : ly;
    if (dy < -0.5f) next |= 1 << 4;
    if (dy > 0.5f) next |= 1 << 5;
    if (dx < -0.5f) next |= 1 << 6;
    if (dx > 0.5f) next |= 1 << 7;
    if (lt > 0.5f) next |= 1 << 12;
    if (rt > 0.5f) next |= 1 << 13;
    if (next != axisBits) {
      axisBits = next;
      nativeButtons(bits | axisBits);
    }
    return true;
  }
  @Override
  protected void onDestroy() {
    nativeStop();
    main.removeCallbacks(presenceTicker);
    RaBridge.detach(raListener);
    if (raPopup != null) raPopup.release();
    if (audio != null) {
      audio.pause();
      audio.flush();
      audio.stop();
      audio.release();
      audio = null;
    }
    nativeClearFiles();
    super.onDestroy();
  }
  final class FrameView extends View {
    private Bitmap bitmap;
    private Bitmap screenMask;
    private int[] previousFrame;
    private int maskWidth, maskHeight, maskScale;
    private final Paint paint = new Paint();
    private final String system = getIntent().getStringExtra("system");
    private final boolean gameBoy = "gb".equals(system);
    private final boolean gameBoyColor = "gbc".equals(system);
    private final boolean superNintendo = "snes".equals(system);
    private final boolean fourByThree = "psx".equals(system) || "n64".equals(system);
    private boolean lcdBlend;
    private boolean filter;
    private boolean green;
    /** Aspect ratio the core reports; used for systems without a fixed one. */
    volatile float coreAspect;
    // Where the picture was last drawn, for the touch screen.
    private final RectF picture = new RectF();
    private static final int[] DMG_PALETTE = {
        0xff0f380f, 0xff306230, 0xff8bac0f, 0xff9bbc0f
    };
    FrameView() {
      super(CoreActivity.this);
      setBackgroundColor(0xff000000);
      paint.setFilterBitmap(false);
      paint.setAntiAlias(false);
      paint.setDither(false);
      refreshFilters();
    }
    /** Exactly what is on screen: scaling, palette, blending and LCD overlays included. */
    Bitmap snapshot() {
      if (bitmap == null || getWidth() == 0 || getHeight() == 0) return null;
      Bitmap shot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(shot);
      canvas.drawColor(0xff000000);
      draw(canvas);
      return shot;
    }
    /** Touches on the picture become the libretro pointer (the DS touch screen). */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (picture.isEmpty())
        return false;
      int action = event.getActionMasked();
      boolean pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
      float x = (event.getX() - picture.left) / picture.width() * 2f - 1f;
      float y = (event.getY() - picture.top) / picture.height() * 2f - 1f;
      boolean inside = x >= -1f && x <= 1f && y >= -1f && y <= 1f;
      nativePointer(Math.round(Math.max(-1f, Math.min(1f, x)) * 0x7fff),
          Math.round(Math.max(-1f, Math.min(1f, y)) * 0x7fff), pressed && inside);
      return true;
    }
    synchronized void refreshFilters() {
      lcdBlend = gameBoy
          ? library.get("gbLcd", true) : gameBoyColor && library.get("gbcLcd", true);
      filter = gameBoy
          ? library.get("gbDotMatrix", true)
          : gameBoyColor && library.get("gbcScreenFilter", true);
      green = gameBoy && library.get("gbGreen", true);
      previousFrame = null;
      postInvalidate();
    }
    synchronized void update(int[] p, int w, int h) {
      int[] unblended = green ? new int[p.length] : p;
      if (green) {
        for (int i = 0; i < p.length; i++) {
          int color = p[i];
          int luminance = ((color >> 16 & 255) * 54 + (color >> 8 & 255) * 183
              + (color & 255) * 19) >> 8;
          unblended[i] = DMG_PALETTE[Math.min(3, luminance >> 6)];
        }
      }
      int[] output = unblended;
      if (lcdBlend) {
        output = unblended.clone();
        if (previousFrame != null && previousFrame.length == output.length) {
          for (int i = 0; i < output.length; i++) {
            int color = unblended[i];
            int old = previousFrame[i];
            output[i] = 0xff000000
                | ((((color >> 16 & 255) + (old >> 16 & 255)) / 2) << 16)
                | ((((color >> 8 & 255) + (old >> 8 & 255)) / 2) << 8)
                | ((color & 255) + (old & 255)) / 2;
          }
        }
        previousFrame = unblended.clone();
      }
      bitmap = Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888);
      postInvalidate();
    }
    @Override
    protected synchronized void onDraw(Canvas c) {
      if (bitmap != null) {
        if (!gameBoy && !gameBoyColor) {
          float aspect = superNintendo || fourByThree ? 4f / 3f
              : coreAspect > 0                        ? coreAspect
                               : bitmap.getWidth() / (float) bitmap.getHeight();
          float width = Math.min(getWidth(), getHeight() * aspect);
          float height = width / aspect;
          int left = Math.round((getWidth() - width) / 2f);
          int top = Math.round((getHeight() - height) / 2f);
          c.drawBitmap(bitmap, null,
              new Rect(left, top, left + Math.round(width), top + Math.round(height)), paint);
          picture.set(left, top, left + width, top + height);
          return;
        }
        float fit = Math.min(
            getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float scale = fit >= 1f ? Math.max(1, (int) Math.floor(fit)) : fit;
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        c.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h), paint);
        picture.set(left, top, left + w, top + h);
        if (filter && scale >= 2f) {
          int integerScale = (int) scale;
          ensureScreenMask(bitmap.getWidth(), bitmap.getHeight(), integerScale);
          if (screenMask != null)
            c.drawBitmap(screenMask, left, top, paint);
        }
      }
    }
    private void ensureScreenMask(int sourceWidth, int sourceHeight, int scale) {
      if (screenMask != null && maskWidth == sourceWidth && maskHeight == sourceHeight
          && maskScale == scale) return;
      maskWidth = sourceWidth;
      maskHeight = sourceHeight;
      maskScale = scale;
      screenMask = null;
      long width = (long) sourceWidth * scale;
      long height = (long) sourceHeight * scale;
      if (width * height > 3000000L) return;
      int[] pixels = new int[(int) (width * height)];
      for (int y = 0; y < height; y++) {
        int cellY = y % scale;
        for (int x = 0; x < width; x++) {
          int cellX = x % scale;
          if (gameBoy) {
            if (cellX == 0 || cellY == 0) pixels[(int) (y * width + x)] = 0x48000000;
          } else if (gameBoyColor) {
            int shade = cellY == scale - 1 ? 0x18000000 : 0;
            int tint = cellX * 3 < scale ? 0x0aff0000
                : cellX * 3 < scale * 2 ? 0x0a00ff00 : 0x0a0000ff;
            pixels[(int) (y * width + x)] = shade | tint;
          }
        }
      }
      screenMask = Bitmap.createBitmap(pixels, (int) width, (int) height,
          Bitmap.Config.ARGB_8888);
    }
  }
}
