package org.emugo.frontend;

import android.app.*;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.display.DisplayManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.LinkedHashSet;

public class MainActivity extends Activity {
  private enum Screen {
    HOME,
    SYSTEMS,
    GAMES,
    FAVOURITES,
    SETTINGS,
    APPEARANCE,
    EMULATION,
    BUILT_IN_SYSTEMS,
    DISPLAY_MODES,
    GAME_BOY_DISPLAY,
    GAME_BOY_COLOR_DISPLAY,
    LIBRARY_SETTINGS,
    CONTROLS,
    INTEGRATIONS,
    SYNCTHING,
    SYSTEM_SETTINGS,
    ABOUT,
    LICENSES,
    CORES,
    STORAGE,
    CREDITS,
    INFOCENTER,
    RETROACHIEVEMENTS,
    RA_PROFILE,
    RA_GAME,
    DISCORD,
    SCREENSHOTS,
    APPS
  }

  private Screen screen = Screen.HOME;
  private Screen appsOrigin = Screen.HOME;
  private Screen infocenterOrigin = Screen.HOME;
  private Screen syncthingOrigin = Screen.INTEGRATIONS;
  private Screen raSettingsOrigin = Screen.INTEGRATIONS;
  private RaProfile.Profile raProfile;
  private List<RaProfile.Game> raGames;
  private RaProfile.Game raGame;
  private List<RaProfile.Achievement> raAchievements;
  // Profile data is cached for the session and refreshed quietly once it is older than this.
  private static final long RA_CACHE_MILLIS = 15 * 60 * 1000;
  private long raProfileLoaded;
  private String raProfileUser;
  private boolean raRefreshing;
  private final Map<Integer, List<RaProfile.Achievement>> raAchievementCache = new HashMap<>();
  private final Map<View, RaProfile.Game> raRowGames = new HashMap<>();
  private int infocenterOriginScroll;
  private final Map<Screen, Integer> focusedPositions = new EnumMap<>(Screen.class);
  private int menuGeneration;
  private Library library;
  private LinearLayout root, body, top, heading;
  private LinearLayout controlHints;
  private HorizontalScrollView hintBar;
  private RowScrollView scroll;
  private TextView syncthingStatus;
  private Library.SystemInfo selected;
  private int gamePage;
  private static final int GAMES_PER_PAGE = 60;
  private int fg, bg, muted, accent;
  private android.graphics.Typeface font;
  private android.graphics.Typeface boldFont;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Presentation second;
  private Banner banner;
  private static final int PICK_ROMS = 41;
  private static final int PICK_WALLPAPER = 42;
  private static final int PICK_RETROARCH_SAVES = 43;
  private static final int REQUEST_HOME_ROLE = 48;
  private static final int REQUEST_BLUETOOTH = 49;
  private static final int FULLSCREEN_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN
      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
  private boolean firstHeld, secondHeld, comboTriggered;
  private boolean leftTriggerKeyDown, rightTriggerKeyDown;
  private boolean leftTriggerAxisDown, rightTriggerAxisDown;

  private Screen languageReturn;
  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(L10n.wrap(base));
  }
  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (languageReturn != null)
      state.putString("languageReturn", languageReturn.name());
  }
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    library = new Library(this);
    // settings.json may carry a different language from another device.
    if (L10n.remember(this, library.get("language", "System")))
      handler.post(this::recreate);
    try {
      font = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/nunito.ttf");
      boldFont = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/nunito-bold.ttf");
    } catch (Exception ignored) {
    }
    immersive();
    render();
    String returnTo = b == null ? null : b.getString("languageReturn");
    if (returnTo != null)
      showScreen(Screen.valueOf(returnTo));
    banner = new Banner(this);
    Foreground.register(getApplication());
    Foreground.addListener(new Foreground.Listener() {
      @Override
      public void onReturned() {
        UpdateChecker.remind(MainActivity.this);
      }
      @Override
      public void onLeft() {}
    });
    DiscordPresence.init(this);
    DiscordPresence.setListener(() -> {
      if (screen == Screen.DISCORD) {
        View focused = body.findFocus();
        if (focused != null) rememberSelection(focused);
        discordSettings();
        restoreSelection(Screen.DISCORD, false);
      }
    });
    UpdateChecker.schedule(this);
    handler.post(updateTimer);
    requestNotificationPermission();
    handleUpdateIntent(getIntent());
    handler.postDelayed(this::tick, 1000);
    String retro = library.local.getString("retroSavesRoot", null);
    if (retro != null && library.root != null)
      new Thread(() -> importSavesFrom(Uri.parse(retro), false)).start();
    if (!library.local.getBoolean("onboarded", false))
      handler.postDelayed(this::onboarding, 400);
  }
  // While EmuGo! is open, check every 15 minutes too; new versions only ever produce one
  // Android notification each (shared with the background job).
  private final Runnable updateTimer = new Runnable() {
    public void run() {
      UpdateChecker.check(MainActivity.this, false);
      handler.postDelayed(this, UpdateChecker.INTERVAL_MILLIS);
    }
  };
  private static final int REQUEST_NOTIFICATIONS = 50;
  private void requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33
        || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        || library.local.getBoolean("notificationPermissionAsked", false))
      return;
    library.local.edit().putBoolean("notificationPermissionAsked", true).apply();
    requestPermissions(
        new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
  }
  @Override
  protected void onPause() {
    super.onPause();
    banner.deactivate();
  }
  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleUpdateIntent(intent);
  }
  /**
   * Tapping the update notification opens the changelog and installer; the Discord one opens
   * its sign-in page.
   */
  private void handleUpdateIntent(Intent intent) {
    if (intent != null && intent.getBooleanExtra(UpdateChecker.EXTRA_SHOW_UPDATE, false)) {
      intent.removeExtra(UpdateChecker.EXTRA_SHOW_UPDATE);
      UpdateChecker.check(this, true);
    }
    // The Discord sign-in expired: show the page with "Sign in with Discord".
    if (intent != null && intent.getBooleanExtra(DiscordPresence.EXTRA_SHOW_DISCORD, false)) {
      intent.removeExtra(DiscordPresence.EXTRA_SHOW_DISCORD);
      focusedPositions.remove(Screen.DISCORD);
      discordSettings();
    }
  }
  @Override
  protected void onResume() {
    super.onResume();
    banner.activate();
    UpdateChecker.resumePendingInstall(this);
    immersive();
    Brightness.apply(this, getWindow());
    updateSecond();
    DiscordPresence.browsing(this);
    library.scanAsync(this::refreshIndexedScreen);
    if (screen == Screen.INFOCENTER) {
      View focused = body.findFocus();
      if (focused != null) rememberSelection(focused);
      infocenter();
      restoreSelection(Screen.INFOCENTER, false);
    }
  }
  private void refreshIndexedScreen() {
    if (isFinishing() || isDestroyed())
      return;
    if (screen == Screen.HOME || screen == Screen.SYSTEMS || screen == Screen.GAMES) {
      Screen destination = screen;
      View focused = body.findFocus();
      if (focused != null)
        rememberSelection(focused);
      showScreen(destination);
      restoreSelection(destination);
    }
    updateSecond();
  }
  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (second != null)
      second.dismiss();
    super.onDestroy();
  }
  private void immersive() {
    getWindow().getDecorView().setSystemUiVisibility(FULLSCREEN_FLAGS);
  }
  private void tick() {
    if (top != null && top.getChildCount() > 0)
      drawStatus();
    if (screen == Screen.SYNCTHING && syncthingStatus != null)
      syncthingStatus.setText(syncthingStatusText());
    handler.postDelayed(this::tick, 1000);
  }
  private int dp(float v) {
    return (int) (v * getResources().getDisplayMetrics().density + .5f);
  }
  private TextView text(int value, int size, boolean bold) {
    return text(getString(value), size, bold);
  }
  private TextView text(String value, int size, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(fg);
    t.setTextSize(size);
    t.setTypeface(bold ? boldFont : font);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }
  private void colors() {
    ThemePalette palette = ThemePalette.resolve(this, library);
    bg = palette.background;
    fg = palette.foreground;
    muted = palette.muted;
    accent = palette.accent;
  }
  private GradientDrawable panel() {
    GradientDrawable d = new GradientDrawable();
    d.setColor(accent);
    d.setCornerRadius(dp(40));
    return d;
  }
  private void render() {
    colors();
    root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(bg);
    setContentView(root);
    Wallpaper.apply(this, library, root);
    top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.setPadding(dp(18), 0, dp(18), 0);
    root.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));
    drawStatus();
    heading = new LinearLayout(this);
    heading.setOrientation(LinearLayout.VERTICAL);
    heading.setPadding(0, dp(22), 0, dp(12));
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    scroll = new RowScrollView(this);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(dp(8), dp(10), dp(8), dp(28));
    int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
    android.widget.FrameLayout.LayoutParams menuParams =
        new android.widget.FrameLayout.LayoutParams(
            Math.min(dp(390), availableWidth), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    scroll.addView(body, menuParams);
    hintBar = new HorizontalScrollView(this);
    hintBar.setHorizontalScrollBarEnabled(false);
    hintBar.setFocusable(false);
    hintBar.setFocusableInTouchMode(false);
    hintBar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    root.addView(hintBar, new LinearLayout.LayoutParams(-1, dp(42)));
    controlHints = new LinearLayout(this);
    controlHints.setGravity(Gravity.CENTER_VERTICAL);
    controlHints.setPadding(dp(18), 0, dp(18), 0);
    hintBar.addView(controlHints);
    drawControlHints();
    home();
  }
  private void drawControlHints() {
    if (hintBar == null) return;
    hintBar.setVisibility(library.get("controlHints", true) ? View.VISIBLE : View.GONE);
    controlHints.removeAllViews();
    String layout = library.get("layout", "Nintendo");
    controlHint("DPAD", R.string.move);
    controlHint(layout.equals("PlayStation") ? "PS_CROSS" : "A", R.string.select);
    controlHint(layout.equals("PlayStation") ? "PS_CIRCLE" : "B", R.string.back);
    controlHint(layout.equals("PlayStation") ? "R1"
            : layout.equals("Nintendo")      ? "R"
                                             : "RB",
        R.string.infocenter);
    controlHint(layout.equals("PlayStation") ? "L2/R2"
            : layout.equals("Nintendo")      ? "ZL/ZR"
                                             : "LT/RT",
        R.string.jump);
    if (screen == Screen.INFOCENTER)
      controlHint(layout.equals("PlayStation") ? "PS_SQUARE" : "X", R.string.clear);
    if (screen == Screen.GAMES || screen == Screen.FAVOURITES)
      controlHint(layout.equals("PlayStation") ? "PS_SQUARE" : "X", R.string.favourite);
    if (screen == Screen.RA_PROFILE || screen == Screen.RA_GAME) {
      controlHint(layout.equals("PlayStation") ? "PS_SQUARE" : "X", R.string.reload);
      controlHint(layout.equals("PlayStation") ? "OPTIONS" : "START", R.string.play);
    }
  }
  private void controlHint(String button, int label) {
    LinearLayout item = new LinearLayout(this);
    item.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-2, -1);
    itemParams.rightMargin = dp(18);
    controlHints.addView(item, itemParams);
    ControllerGlyph icon = new ControllerGlyph(this, button, muted);
    LinearLayout.LayoutParams iconParams =
        new LinearLayout.LayoutParams(dp(button.contains("/") ? 52
            : button.length() > 4 && !button.startsWith("PS_") ? 42 : 24), dp(24));
    iconParams.rightMargin = dp(5);
    item.addView(icon, iconParams);
    TextView caption = text(label, 11, false);
    caption.setTextColor(muted);
    caption.setSingleLine(true);
    item.addView(caption);
  }
  private void drawStatus() {
    top.removeAllViews();
    top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    boolean wifi = false;
    int wifiStrength = NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED;
    boolean bluetooth = false;
    try {
      ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
      wifi = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
      if (wifi)
        wifiStrength = cap.getSignalStrength();
    } catch (Exception ignored) {
    }
    try {
      BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
      bluetooth = adapter != null && adapter.isEnabled();
    } catch (Exception ignored) {
    }
    String wifiIcon = "tabler_wifi_off";
    if (wifi)
      wifiIcon =
          wifiStrength == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED || wifiStrength >= -55
          ? "tabler_wifi"
          : wifiStrength >= -67 ? "tabler_wifi_2"
          : wifiStrength >= -80 ? "tabler_wifi_1"
                                : "tabler_wifi_0";
    statusIcon(wifiIcon, true);
    statusIcon("tabler_bluetooth", bluetooth);
    statusIcon("tabler_clock", true);
    TextView time =
        text(android.text.format.DateFormat.getTimeFormat(this).format(new Date()), 12, false);
    time.setTextColor(muted);
    LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-2, -1);
    timeParams.rightMargin = dp(10);
    top.addView(time, timeParams);
    try {
      BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
      int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
      Intent batteryState = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      boolean charging =
          batteryState != null && batteryState.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
      String batteryIcon = charging ? "tabler_battery_charging"
          : level <= 25             ? "tabler_battery_1"
          : level <= 50             ? "tabler_battery_2"
          : level <= 75             ? "tabler_battery_3"
                                    : "tabler_battery_4";
      boolean light = Color.luminance(bg) > 0.5;
      int batteryColor = charging ? muted
          : level <= 5            ? (light ? 0xffd32f2f : 0xffff5c5c)
          : level <= 20           ? (light ? 0xffb88600 : 0xfff5c542)
                                  : muted;
      statusIcon(batteryIcon, true, batteryColor);
      TextView percent = text(level + "%", 12, false);
      percent.setTextColor(batteryColor);
      top.addView(percent);
    } catch (Exception ignored) {
    }
  }
  private void statusIcon(String name, boolean active) {
    statusIcon(name, active, muted);
  }
  private void statusIcon(String name, boolean active, int color) {
    int drawable = getResources().getIdentifier(name, "drawable", getPackageName());
    if (drawable == 0)
      return;
    ImageView icon = new ImageView(this);
    icon.setImageResource(drawable);
    icon.setColorFilter(color);
    icon.setAlpha(active ? 1f : 0.3f);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(16), dp(16));
    params.rightMargin = dp(5);
    top.addView(icon, params);
  }
  private void title(int name) {
    title(getString(name));
  }
  private void title(String name) {
    menuGeneration++;
    heading.removeAllViews();
    heading.setVisibility(View.VISIBLE);
    drawControlHints();
    setBodyWide(false);
    TextView h = text(name, 22, true);
    int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(Math.min(dp(374), availableWidth), -2);
    params.gravity = Gravity.CENTER_HORIZONTAL;
    heading.addView(h, params);
    body.setPadding(dp(8), dp(10), dp(8), dp(28));
    scroll.scrollTo(0, 0);
  }
  private void space(int height) {
    View v = new View(this);
    body.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }
  private LinearLayout row(int name, Runnable action) {
    return row(getString(name), "", action);
  }
  private LinearLayout row(int name, String detail, Runnable action) {
    return row(getString(name), detail, action);
  }
  private LinearLayout row(int name, int detail, Runnable action) {
    return row(getString(name), getString(detail), action);
  }
  private LinearLayout row(String name, String detail, Runnable action) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(dp(14), dp(6), dp(14), dp(6));
    card.setMinimumHeight(dp(38));
    card.setFocusable(true);
    card.setFocusableInTouchMode(true);
    card.setDefaultFocusHighlightEnabled(false);
    card.setStateListAnimator(null);
    card.setForeground(null);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(2);
    body.addView(card, lp);
    ImageView backIcon = null;
    if (name.startsWith("← ")) {
      backIcon = new ImageView(this);
      int drawable =
          getResources().getIdentifier("tabler_arrow_left", "drawable", getPackageName());
      backIcon.setImageResource(drawable);
      backIcon.setColorFilter(fg);
      LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
      iconParams.rightMargin = dp(9);
      card.addView(backIcon, iconParams);
    }
    boolean heart = name.startsWith(HEART);
    if (heart) {
      ImageView heartIcon = new ImageView(this);
      heartIcon.setImageResource(
          getResources().getIdentifier("tabler_heart_filled", "drawable", getPackageName()));
      heartIcon.setContentDescription(getString(R.string.favourite));
      LinearLayout.LayoutParams heartParams = new LinearLayout.LayoutParams(dp(16), dp(16));
      heartParams.rightMargin = dp(9);
      card.addView(heartIcon, heartParams); // stays pink, also on the selection pill
    }
    TextView n = text(name.startsWith("← ") || heart ? name.substring(2) : name, 17, false);
    n.setSingleLine(true);
    n.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(n);
    card.setContentDescription(
        detail == null || detail.isEmpty() ? n.getText() : n.getText() + ". " + detail);
    int selectedText = Color.luminance(accent) > 0.5 ? 0xff101112 : 0xfff7f7f5;
    ImageView icon = backIcon;
    card.setOnClickListener(v -> {
      rememberSelection(card);
      action.run();
    });
    card.setOnKeyListener((v, key, e) -> {
      if (e.getAction() == KeyEvent.ACTION_DOWN
          && (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
              || key == KeyEvent.KEYCODE_ENTER)) {
        rememberSelection(card);
        action.run();
        return true;
      }
      return false;
    });
    card.setOnFocusChangeListener((v, focus) -> {
      card.setBackground(focus ? panel() : null);
      n.setTextColor(focus ? selectedText : fg);
      if (icon != null)
        icon.setColorFilter(focus ? selectedText : fg);
    });
    autoFocus(card);
    return card;
  }
  /** Focuses a freshly added row if it is the remembered (or first) one on this screen. */
  private void autoFocus(View card) {
    if (body.findFocus() == null) {
      Integer remembered = focusedPositions.get(screen);
      int position = body.indexOfChild(card);
      if (remembered == null || remembered == position) {
        card.requestFocus();
      } else if (position == 0) {
        int generation = menuGeneration;
        body.post(() -> {
          if (menuGeneration == generation && body.findFocus() == null)
            restoreSelection(screen);
        });
      }
    }
  }
  private View richRow(RichRow.Entry entry, Runnable action) {
    View row = RichRow.create(this, ThemePalette.resolve(this, library), font, boldFont, entry,
        action == null ? null : () -> {
          rememberSelection(body.findFocus() != null ? body.findFocus() : body);
          action.run();
        });
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(2);
    body.addView(row, params);
    if (row.isFocusable()) autoFocus(row);
    return row;
  }
  private void message(int value) {
    message(getString(value));
  }
  private void message(String value) {
    TextView text = text(value, 15, false);
    text.setTextColor(muted);
    text.setPadding(dp(14), dp(6), dp(14), dp(6));
    body.addView(text);
  }
  private void rememberSelection(View item) {
    int position = body.indexOfChild(item);
    if (position >= 0)
      focusedPositions.put(screen, position);
  }
  private void restoreSelection(Screen destination) {
    restoreSelection(destination, true);
  }
  private void restoreSelection(Screen destination, boolean alignScroll) {
    if (screen != destination) return;
    Integer position = focusedPositions.get(destination);
    View item = position != null && position >= 0 && position < body.getChildCount()
        && body.getChildAt(position).isFocusable() ? body.getChildAt(position) : null;
    if (item == null)
      for (int index = 0; index < body.getChildCount(); index++)
        if (body.getChildAt(index).isFocusable()) {
          item = body.getChildAt(index);
          break;
        }
    if (item == null) return;
    if (body.findFocus() != item) item.requestFocus();
    if (!alignScroll) return;
    View target = item;
    int generation = menuGeneration;
    scroll.post(() -> {
      if (menuGeneration == generation && body.indexOfChild(target) >= 0)
        scroll.showRow(target, 3);
    });
  }
  private void restoreScroll(int position) {
    int generation = menuGeneration;
    scroll.post(() -> {
      if (menuGeneration == generation) scroll.scrollTo(0, position);
    });
  }
  private void home() {
    menuGeneration++;
    screen = Screen.HOME;
    body.removeAllViews();
    heading.removeAllViews();
    heading.setVisibility(View.GONE);
    drawControlHints();
    body.setPadding(dp(8), dp(44), dp(8), dp(28));
    setBodyWide(false);
    scroll.scrollTo(0, 0);
    if (library.root == null)
      row(R.string.choose_roms_folder, R.string.choose_roms_folder_hint, this::pickRoms);
    else
      row(R.string.library, "", this::systems);
    if (library.root != null)
      row(R.string.screenshots, "", () -> {
        screenshotFocus = 0;
        screenshots();
      });
    row("RetroAchievements", "", this::openRetroAchievements);
    row(R.string.android, "", this::openApps);
    row(R.string.settings, "", this::settings);
  }
  private void systems() {
    screen = Screen.SYSTEMS;
    body.removeAllViews();
    title(R.string.library);
    List<Library.SystemInfo> systems = library.visibleSystems();
    if (systems.isEmpty())
      row(library.isScanning() && !library.hasIndex() ? R.string.scanning_roms
                                                      : R.string.no_systems_found,
          this::pickRoms);
    if (!library.favouriteGames().isEmpty())
      row(R.string.favourites, "", this::favourites);
    for (Library.SystemInfo s : systems) row(s.name, "", () -> showSystem(s));
  }
  private final Map<View, Library.Game> gameRows = new HashMap<>();
  // Row prefix that row() turns into a pink heart icon.
  private static final String HEART = "\u2665 ";
  private void favourites() {
    screen = Screen.FAVOURITES;
    body.removeAllViews();
    gameRows.clear();
    title(R.string.favourites);
    List<Library.Game> games = library.favouriteGames();
    if (games.isEmpty()) {
      message(R.string.favourites_empty_hint);
      return;
    }
    for (Library.Game game : games)
      gameRows.put(richRow(new RichRow.Entry(game.title, game.system.name, null, null, false),
                       () -> launch(game)),
          game);
  }
  /** X / Square: adds or removes the focused game; the list keeps its place. */
  private void toggleFocusedFavourite() {
    View focused = body.findFocus();
    Library.Game game = focused == null ? null : gameRows.get(focused);
    if (game == null) return;
    rememberSelection(focused);
    int previousScroll = scroll.getScrollY();
    boolean added = library.toggleFavourite(game);
    PocketNotice.show(this,
        getString(added ? R.string.added_x_to_favourites : R.string.removed_x_from_favourites,
            game.title));
    if (screen == Screen.FAVOURITES) {
      favourites();
      restoreSelection(Screen.FAVOURITES);
    } else if (selected != null) {
      renderGamePage(selected);
      restoreSelection(Screen.GAMES, false);
      restoreScroll(previousScroll);
    }
  }
  private void showSystem(Library.SystemInfo s) {
    gamePage = 0;
    renderGamePage(s);
  }
  private void renderGamePage(Library.SystemInfo s) {
    screen = Screen.GAMES;
    selected = s;
    body.removeAllViews();
    List<Library.Game> games = library.games(s);
    int pages = Math.max(1, (games.size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE);
    gamePage = Math.min(gamePage, pages - 1);
    title(s.name);
    gameRows.clear();
    java.util.Set<String> favourites = library.favourites();
    int end = Math.min(games.size(), (gamePage + 1) * GAMES_PER_PAGE);
    for (int index = gamePage * GAMES_PER_PAGE; index < end; index++) {
      Library.Game game = games.get(index);
      boolean favourite = favourites.contains(Library.favouriteKey(game));
      gameRows.put(row((favourite ? HEART : "") + game.title, "", () -> launch(game)), game);
    }
    if (pages > 1) {
      if (gamePage > 0)
        row(R.string.previous_page, "", () -> turnGamePage(-1));
      if (gamePage + 1 < pages)
        row(R.string.next_page, "", () -> turnGamePage(1));
    }
  }
  private void turnGamePage(int direction) {
    if (selected == null)
      return;
    int pages = Math.max(1, (library.games(selected).size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE);
    int next = Math.max(0, Math.min(pages - 1, gamePage + direction));
    if (next != gamePage) {
      gamePage = next;
      renderGamePage(selected);
      scroll.scrollTo(0, 0);
    }
  }
  private void jumpGames(int direction) {
    if (selected == null) return;
    List<Library.Game> games = library.games(selected);
    if (games.isEmpty()) return;
    View focused = body.findFocus();
    int row = focused == null ? 0 : body.indexOfChild(focused);
    int current = Math.max(0, Math.min(games.size() - 1, gamePage * GAMES_PER_PAGE + row));
    int target = Math.max(0, Math.min(games.size() - 1, current + direction * 10));
    int targetPage = target / GAMES_PER_PAGE;
    if (targetPage != gamePage) {
      gamePage = targetPage;
      renderGamePage(selected);
    }
    focusRow(target % GAMES_PER_PAGE);
  }
  private void jumpMenuRows(int direction) {
    View target = scroll.jump(direction * 3);
    if (target != null) rememberSelection(target);
  }
  private void setTriggerState(boolean left, boolean axis, boolean pressed) {
    boolean wasPressed = left
        ? leftTriggerKeyDown || leftTriggerAxisDown
        : rightTriggerKeyDown || rightTriggerAxisDown;
    if (left) {
      if (axis) leftTriggerAxisDown = pressed;
      else leftTriggerKeyDown = pressed;
    } else {
      if (axis) rightTriggerAxisDown = pressed;
      else rightTriggerKeyDown = pressed;
    }
    boolean isPressed = left
        ? leftTriggerKeyDown || leftTriggerAxisDown
        : rightTriggerKeyDown || rightTriggerAxisDown;
    if (!wasPressed && isPressed) {
      if (screen == Screen.GAMES) jumpGames(left ? -1 : 1);
      else jumpMenuRows(left ? -1 : 1);
    }
  }
  private void updateTriggerAxis(boolean left, float value) {
    boolean pressed = left ? leftTriggerAxisDown : rightTriggerAxisDown;
    if (value >= 0.55f) pressed = true;
    else if (value <= 0.25f) pressed = false;
    setTriggerState(left, true, pressed);
  }
  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE
        && (((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
            || ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD))) {
      updateTriggerAxis(true, Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
          event.getAxisValue(MotionEvent.AXIS_BRAKE)));
      updateTriggerAxis(false, Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
          event.getAxisValue(MotionEvent.AXIS_GAS)));
    }
    return super.dispatchGenericMotionEvent(event);
  }
  private void focusRow(int index) {
    int generation = menuGeneration;
    body.post(() -> {
      if (menuGeneration != generation) return;
      if (index < 0 || index >= body.getChildCount()) return;
      View target = body.getChildAt(index);
      if (!target.isFocusable()) return;
      target.requestFocus();
      rememberSelection(target);
      scroll.showRow(target, 3);
    });
  }
  private void settings() {
    screen = Screen.SETTINGS;
    body.removeAllViews();
    title(R.string.settings);
    row(R.string.appearance, "", this::appearanceSettings);
    row(R.string.emulation, "", this::emulationSettings);
    row(R.string.library, "", this::librarySettings);
    row(R.string.controls, "", this::controlSettings);
    row(R.string.integrations, "", this::integrationSettings);
    row(R.string.system, "", this::systemSettings);
    row(R.string.about, "", this::about);
  }
  private void appearanceSettings() {
    screen = Screen.APPEARANCE;
    body.removeAllViews();
    title(R.string.appearance);
    row(R.string.theme, L10n.option(this, library.get("theme", "Default")),
        ()
            -> choose(R.string.theme,
                new String[] {"Default", "Nord", "Dracula", "Catppuccin", "Monaspace",
                    "Tokyo Night", "Ayu", "Dainty", "Houston", "Matcha", "TechDweeb"},
                "theme"));
    row(R.string.color_mode, L10n.option(this, library.get("scheme", "System")),
        () -> choose(R.string.color_mode, new String[] {"System", "Dark", "Light"}, "scheme"));
    if (library.get("theme", "Default").equals("Catppuccin"))
      row(R.string.catppuccin_flavor, L10n.option(this, library.get("catppuccin", "Mocha")),
          ()
              -> choose(R.string.flavor, new String[] {"Latte", "Frappe", "Macchiato", "Mocha"},
                  "catppuccin"));
    if (library.get("theme", "Default").equals("TechDweeb"))
      row(R.string.techdweeb_color, L10n.option(this, library.get("techColor", "orange")),
          ()
              -> choose(R.string.wallpaper,
                  new String[] {"orange", "purple", "black", "red", "pink", "grey", "green", "cyan",
                      "blue", "dmg"},
                  "techColor"));
    row(R.string.custom_background, "", () -> {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("image/*")
                          .addCategory(Intent.CATEGORY_OPENABLE);
      startActivityForResult(intent, PICK_WALLPAPER);
    });
  }
  private void librarySettings() {
    screen = Screen.LIBRARY_SETTINGS;
    body.removeAllViews();
    title(R.string.library);
    row(R.string.roms_folder, "", this::pickRoms);
    row(R.string.rescan_roms, "", () -> {
      library.scanAsync(this::refreshIndexedScreen);
      PocketNotice.show(this, R.string.scanning_roms);
    });
    toggle(R.string.hide_file_extensions, "hideExtensions", true);
    toggle(R.string.merge_cue_and_bin, "mergeCue", true);
    toggle(R.string.remove_brackets, "removeBrackets", true);
    toggle(R.string.fix_title_articles, "fixArticles", true);
    row(R.string.import_retroarch_saves, "", this::importSaves);
  }
  private void emulationSettings() {
    screen = Screen.EMULATION;
    body.removeAllViews();
    title(R.string.emulation);
    row(R.string.display_modes, "", this::displayModes);
    booleanOption(R.string.automatic_states, "autoStates", false);
    int overrides = library.overrideCount(Library.STATE_OVERRIDES);
    if (overrides > 0)
      row(getResources().getQuantityString(R.plurals.reset_game_overrides_n, overrides, overrides),
          "", this::resetGameOverrides);
    booleanOption(R.string.boot_intros, "bootIntros", true);
    // Off: every game goes to its standalone app, then RetroArch.
    row(R.string.built_in_emulators, onOff(library.builtInDefault()), () -> {
      int previousScroll = scroll.getScrollY();
      new PocketDialog.Builder(this)
          .setTitle(R.string.built_in_emulators)
          .setSingleChoiceItems(new String[] {onOff(false), onOff(true)},
              library.builtInDefault() ? 1 : 0,
              (dialog, choice) -> {
                library.put("builtIn", choice == 1);
                emulationSettings();
                restoreSelection(Screen.EMULATION, false);
                restoreScroll(previousScroll);
              })
          .show();
    });
    row(R.string.built_in_per_system, "", this::builtInSystems);
  }
  /** Default follows "Built-in emulators"; On and Off apply to one system. */
  private void builtInSystems() {
    screen = Screen.BUILT_IN_SYSTEMS;
    body.removeAllViews();
    title(R.string.built_in_per_system);
    int overrides = library.overrideCount(Library.BUILT_IN_OVERRIDES);
    if (overrides > 0)
      row(getResources().getQuantityString(
              R.plurals.reset_system_overrides_n, overrides, overrides),
          "", this::resetSystemOverrides);
    for (Library.SystemInfo system : Library.SYSTEMS) {
      if (system.core == null)
        continue;
      Boolean override = library.override(Library.BUILT_IN_OVERRIDES, system.folder);
      String state = override == null
          ? getString(R.string.default_x, onOff(library.builtInDefault()))
          : onOff(override);
      row(system.name, state, () -> {
        int previousScroll = scroll.getScrollY();
        Boolean current = library.override(Library.BUILT_IN_OVERRIDES, system.folder);
        new PocketDialog.Builder(this)
            .setTitle(system.name)
            .setSingleChoiceItems(
                new String[] {getString(R.string.default_x, onOff(library.builtInDefault())),
                    onOff(true), onOff(false)},
                current == null ? 0
                    : current   ? 1
                                : 2,
                (dialog, choice) -> {
                  library.setOverride(Library.BUILT_IN_OVERRIDES, system.folder,
                      choice == 0 ? null : Boolean.valueOf(choice == 1));
                  builtInSystems();
                  restoreSelection(Screen.BUILT_IN_SYSTEMS, false);
                  restoreScroll(previousScroll);
                })
            .show();
      });
    }
  }
  private void resetSystemOverrides() {
    new PocketDialog.Builder(this)
        .setTitle(R.string.reset_system_overrides_question)
        .setMessage(
            getString(R.string.reset_system_overrides_message, onOff(library.builtInDefault())))
        .setPositiveButton(R.string.reset,
            (d, w) -> {
              library.clearOverrides(Library.BUILT_IN_OVERRIDES);
              PocketNotice.show(this, R.string.system_overrides_reset);
              focusedPositions.remove(Screen.BUILT_IN_SYSTEMS);
              builtInSystems();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
  /** Games set from the pause menu go back to the global automatic states setting. */
  private void resetGameOverrides() {
    new PocketDialog.Builder(this)
        .setTitle(R.string.reset_game_overrides_question)
        .setMessage(getString(
            R.string.reset_game_overrides_message, onOff(library.get("autoStates", false))))
        .setPositiveButton(R.string.reset,
            (d, w) -> {
              library.clearOverrides(Library.STATE_OVERRIDES);
              PocketNotice.show(this, R.string.game_overrides_reset);
              focusedPositions.remove(Screen.EMULATION);
              emulationSettings();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
  private void displayModes() {
    screen = Screen.DISPLAY_MODES;
    body.removeAllViews();
    title(R.string.display_modes);
    row("Game Boy", "", this::gameBoyDisplay);
    row("Game Boy Color", "", this::gameBoyColorDisplay);
  }
  private void gameBoyDisplay() {
    screen = Screen.GAME_BOY_DISPLAY;
    body.removeAllViews();
    title("Game Boy");
    booleanOption(R.string.green_palette, "gbGreen", true);
    booleanOption(R.string.dot_matrix, "gbDotMatrix", true);
    booleanOption(R.string.lcd_blending, "gbLcd", true);
  }
  private void gameBoyColorDisplay() {
    screen = Screen.GAME_BOY_COLOR_DISPLAY;
    body.removeAllViews();
    title("Game Boy Color");
    booleanOption(R.string.screen_filter, "gbcScreenFilter", true);
    booleanOption(R.string.lcd_blending, "gbcLcd", true);
  }
  private void controlSettings() {
    screen = Screen.CONTROLS;
    body.removeAllViews();
    title(R.string.controls);
    row(R.string.controller_labels, L10n.option(this, library.get("layout", "Nintendo")),
        ()
            -> choose(R.string.button_layout, new String[] {"Nintendo", "Xbox", "PlayStation"},
                "layout"));
    row(R.string.game_menu_shortcut, L10n.option(this, library.get("menuCombo", "Start + Select")),
        ()
            -> choose(R.string.shortcut, new String[] {"Start + Select", "L3 + R3", "Select + X"},
                "menuCombo"));
    booleanOption(R.string.control_hints, "controlHints", true);
  }
  private void integrationSettings() {
    screen = Screen.INTEGRATIONS;
    body.removeAllViews();
    title(R.string.integrations);
    row("RetroAchievements", "", this::openRetroAchievementsSettings);
    row("Discord", "", this::discordSettings);
    row("Syncthing", "", this::openSyncthingSettings);
  }
  private void openRetroAchievements() {
    if (RaBridge.signedIn(library)) {
      String user = library.get("raUser", "");
      if (!user.equals(raProfileUser)) {
        raProfile = null;
        raGames = null;
        raAchievementCache.clear();
        raProfileUser = user;
        focusedPositions.remove(Screen.RA_PROFILE);
      }
      retroAchievementsProfile();
      if (raProfile != null
          && System.currentTimeMillis() - raProfileLoaded > RA_CACHE_MILLIS)
        loadRetroAchievementsProfile(true);
    } else {
      openRetroAchievementsSettings();
    }
  }
  private void openRetroAchievementsSettings() {
    raSettingsOrigin = screen;
    retroAchievementsSettings();
  }
  /** The signed-in user's RetroAchievements profile, shown inside EmuGo!. */
  private void retroAchievementsProfile() {
    screen = Screen.RA_PROFILE;
    body.removeAllViews();
    title("RetroAchievements");
    if (raProfile == null || raGames == null) {
      message(R.string.loading);
      loadRetroAchievementsProfile(false);
      return;
    }
    raRowGames.clear();
    RaProfile.Profile p = raProfile;
    String points = getString(R.string.x_hardcore_points, String.valueOf(p.hardcorePoints))
        + "  ·  " + getString(R.string.x_softcore_points, String.valueOf(p.softcorePoints));
    richRow(new RichRow
                .Entry(p.user, points,
                    p.unreadMessages > 0
                        ? getString(R.string.x_messages, String.valueOf(p.unreadMessages))
                        : null,
                    p.avatarUrl, false)
                .rounded(),
        () -> {});
    row(R.string.settings, "", this::openRetroAchievementsSettings);
    body.addView(
        RichRow.header(this, ThemePalette.resolve(this, library), boldFont, R.string.games));
    if (raGames.isEmpty())
      message(R.string.ra_no_progress);
    for (RaProfile.Game game : raGames) {
      int unlocked = Math.max(game.unlocked, game.unlockedHardcore);
      String detail = RaProfile.consoleName(game.console) + "  ·  "
          + getString(R.string.x_of_x_achievements_unlocked, String.valueOf(unlocked),
              String.valueOf(game.total));
      String percent = game.total == 0 ? "" : Math.round(unlocked * 100f / game.total) + "%";
      View gameRow = richRow(new RichRow.Entry(game.title, detail, percent, game.iconUrl, false), () -> {
        raGame = game;
        raAchievements = raAchievementCache.get(game.id);
        focusedPositions.remove(Screen.RA_GAME);
        retroAchievementsGame();
      });
      raRowGames.put(gameRow, game);
    }
  }
  /** Fetches profile and game list; a quiet refresh keeps showing the cached data meanwhile. */
  private void loadRetroAchievementsProfile(boolean quiet) {
    if (raRefreshing) return;
    raRefreshing = true;
    String user = library.get("raUser", ""), token = library.get("raToken", "");
    new Thread(() -> {
      try {
        RaProfile.Profile profile = RaProfile.profile(this, user, token);
        List<RaProfile.Game> games = RaProfile.games(this, user, token);
        runOnUiThread(() -> {
          raRefreshing = false;
          if (!user.equals(library.get("raUser", ""))) return;
          raProfile = profile;
          raGames = games;
          raProfileLoaded = System.currentTimeMillis();
          raAchievementCache.clear();
          if (screen == Screen.RA_PROFILE) {
            View focused = body.findFocus();
            if (focused != null) rememberSelection(focused);
            int previousScroll = scroll.getScrollY();
            retroAchievementsProfile();
            restoreSelection(Screen.RA_PROFILE, !quiet);
            if (quiet) restoreScroll(previousScroll);
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          raRefreshing = false;
          if (quiet || screen != Screen.RA_PROFILE || raProfile != null) return;
          body.removeAllViews();
          message(getString(R.string.could_not_load_profile_x, String.valueOf(e.getMessage())));
          row(R.string.settings, "", this::openRetroAchievementsSettings);
        });
      }
    }, "EmuGo-ra-profile").start();
  }
  /** Game Boy games may sit in either Game Boy folder; everything else by console id. */
  private static boolean raConsoleMatches(Library.SystemInfo system, int console) {
    boolean gameBoy = console == 4 || console == 6;
    return system.achievements == console
        || (gameBoy && (system.achievements == 4 || system.achievements == 6));
  }
  /** Finds the local ROM for a RetroAchievements game, preferring the one last played. */
  private Library.Game localGame(RaProfile.Game game) {
    String remembered = library.local.getString("raGameRom:" + game.id, null);
    String key = Library.matchKey(game.title);
    Library.Game byTitle = null, byPrefix = null;
    for (Library.SystemInfo system : library.visibleSystems()) {
      if (!raConsoleMatches(system, game.console))
        continue;
      for (Library.Game candidate : library.games(system)) {
        if (remembered != null && remembered.equals(candidate.uri.toString())) return candidate;
        String candidateKey = Library.matchKey(candidate.fileTitle);
        if (byTitle == null && candidateKey.equals(key)) byTitle = candidate;
        if (byPrefix == null && !key.isEmpty() && !candidateKey.isEmpty()
            && (candidateKey.startsWith(key) || key.startsWith(candidateKey)))
          byPrefix = candidate;
      }
    }
    return byTitle != null ? byTitle : byPrefix;
  }
  private void playRetroAchievementsGame() {
    RaProfile.Game game = screen == Screen.RA_GAME ? raGame : raRowGames.get(body.findFocus());
    if (game == null) return;
    Library.Game local = localGame(game);
    if (local == null) {
      PocketNotice.show(this, getString(R.string.x_is_not_in_your_library, game.title));
      return;
    }
    launch(local);
  }
  private void reloadRetroAchievements() {
    PocketNotice.show(this, R.string.reloading);
    if (screen == Screen.RA_GAME && raGame != null) {
      raAchievementCache.remove(raGame.id);
      raAchievements = null;
      retroAchievementsGame();
    } else {
      loadRetroAchievementsProfile(true);
    }
  }
  private void retroAchievementsGame() {
    if (raGame == null) {
      retroAchievementsProfile();
      return;
    }
    screen = Screen.RA_GAME;
    body.removeAllViews();
    title(raGame.title);
    if (raAchievements == null) {
      message(R.string.loading);
      int generation = menuGeneration;
      RaProfile.Game game = raGame;
      String user = library.get("raUser", ""), token = library.get("raToken", "");
      new Thread(() -> {
        try {
          List<RaProfile.Achievement> list = RaProfile.achievements(this, user, token, game.id);
          runOnUiThread(() -> {
            raAchievementCache.put(game.id, list);
            if (raGame != game) return;
            raAchievements = list;
            if (menuGeneration == generation && screen == Screen.RA_GAME) {
              retroAchievementsGame();
              restoreSelection(Screen.RA_GAME);
            }
          });
        } catch (Exception e) {
          runOnUiThread(() -> {
            if (menuGeneration != generation || screen != Screen.RA_GAME) return;
            body.removeAllViews();
            message(
                getString(R.string.could_not_load_achievements_x, String.valueOf(e.getMessage())));
          });
        }
      }, "EmuGo-ra-game").start();
      return;
    }
    boolean lockedHeader = false;
    ThemePalette colors = ThemePalette.resolve(this, library);
    if (!raAchievements.isEmpty() && raAchievements.get(0).unlocked)
      body.addView(RichRow.header(this, colors, boldFont, R.string.unlocked));
    for (RaProfile.Achievement a : raAchievements) {
      if (!a.unlocked && !lockedHeader) {
        lockedHeader = true;
        body.addView(RichRow.header(this, colors, boldFont, R.string.locked));
      }
      String detail = a.description + (a.hardcore ? "  ·  " + getString(R.string.hardcore) : "");
      richRow(
          new RichRow.Entry(a.title, detail, getString(R.string.x_points, String.valueOf(a.points)),
              a.unlocked ? a.badgeUrl : a.lockedBadgeUrl, !a.unlocked),
          () -> {});
    }
  }
  private void retroAchievementsSettings() {
    screen = Screen.RETROACHIEVEMENTS;
    body.removeAllViews();
    title("RetroAchievements");
    String user = library.get("raUser", "");
    boolean signedIn = !library.get("raToken", "").isEmpty();
    if (!signedIn) {
      row(R.string.sign_in, "", () -> RetroAchievements.signIn(this, library, () -> {
        retroAchievementsSettings();
        restoreSelection(Screen.RETROACHIEVEMENTS);
      }));
      return;
    }
    row(getString(R.string.signed_in_as_x, user), "", this::openRetroAchievements);
    booleanOption(R.string.notifications, "raNotifications", true);
    row(R.string.sign_out, "",
        ()
            -> new PocketDialog.Builder(this)
                   .setTitle(R.string.sign_out_of_retroachievements)
                   .setPositiveButton(R.string.sign_out,
                       (d, w) -> {
                         library.put("raToken", "");
                         RaBridge.signOut();
                         raProfile = null;
                         raGames = null;
                         raProfileUser = null;
                         raAchievementCache.clear();
                         PocketNotice.show(this, R.string.signed_out);
                         focusedPositions.remove(Screen.RETROACHIEVEMENTS);
                         retroAchievementsSettings();
                       })
                   .setNegativeButton(R.string.cancel, null)
                   .show());
  }
  private int screenshotFocus;
  /** Switch-style album: a full-width thumbnail grid, newest first. */
  private void screenshots() {
    screen = Screen.SCREENSHOTS;
    body.removeAllViews();
    title(R.string.screenshots);
    setBodyWide(true);
    int generation = menuGeneration;
    new Thread(() -> {
      List<Library.Item> items = Screenshots.list(library);
      runOnUiThread(() -> {
        if (menuGeneration != generation || screen != Screen.SCREENSHOTS) return;
        if (items.isEmpty()) {
          TextView empty = text(R.string.no_screenshots_yet, 17, false);
          empty.setTextColor(muted);
          empty.setGravity(Gravity.CENTER);
          empty.setPadding(0, dp(80), 0, 0);
          body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
          TextView hint = text(R.string.screenshots_empty_hint, 14, false);
          hint.setTextColor(muted);
          hint.setGravity(Gravity.CENTER);
          hint.setPadding(0, dp(6), 0, 0);
          body.addView(hint, new LinearLayout.LayoutParams(-1, -2));
          return;
        }
        TextView caption = text("", 15, true);
        caption.setPadding(dp(3), 0, dp(3), dp(10));
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(caption);
        int columns = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 4 : 2;
        int gap = dp(8);
        int width = getResources().getDisplayMetrics().widthPixels - dp(48);
        int tileWidth = (width - gap * (columns - 1)) / columns;
        int tileHeight = tileWidth * 9 / 16;
        LinearLayout line = null;
        View focusTarget = null;
        for (int index = 0; index < items.size(); index++) {
          if (index % columns == 0) {
            line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, -2);
            lineParams.bottomMargin = gap;
            body.addView(line, lineParams);
          }
          int position = index;
          Library.Item item = items.get(index);
          View tile = screenshotTile(item, tileWidth, tileHeight, () -> {
            screenshotFocus = position;
            Screenshots.view(this, library, items, position, () -> {
              screenshotFocus = Math.min(screenshotFocus, items.size() - 1);
              screenshots();
            });
          }, focused -> {
            if (focused) {
              screenshotFocus = position;
              caption.setText(Screenshots.label(item));
            }
          });
          LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(tileWidth, tileHeight);
          if (index % columns != 0) tileParams.leftMargin = gap;
          line.addView(tile, tileParams);
          if (index == Math.min(screenshotFocus, items.size() - 1)) focusTarget = tile;
        }
        if (focusTarget != null) {
          View target = focusTarget;
          target.requestFocus();
          scroll.post(() -> {
            if (menuGeneration == generation) scroll.showRow((View) target.getParent(), 1);
          });
        }
      });
    }, "EmuGo-screenshots").start();
  }
  private View screenshotTile(Library.Item item, int width, int height, Runnable open,
      java.util.function.Consumer<Boolean> onFocus) {
    android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
    int border = dp(3);
    tile.setPadding(border, border, border, border);
    tile.setFocusable(true);
    tile.setFocusableInTouchMode(true);
    tile.setDefaultFocusHighlightEnabled(false);
    tile.setContentDescription(Screenshots.label(item));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    GradientDrawable placeholder = new GradientDrawable();
    placeholder.setColor(Color.argb(40, Color.red(muted), Color.green(muted), Color.blue(muted)));
    placeholder.setCornerRadius(dp(6));
    image.setBackground(placeholder);
    image.setClipToOutline(true);
    tile.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
    ImageCache.loadThumbnail(this, item.uri, width, bitmap -> image.setImageBitmap(bitmap));
    tile.setOnFocusChangeListener((v, focused) -> {
      if (focused) {
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(border, accent);
        outline.setCornerRadius(dp(9));
        tile.setBackground(outline);
      } else {
        tile.setBackground(null);
      }
      onFocus.accept(focused);
    });
    tile.setOnClickListener(v -> open.run());
    tile.setOnKeyListener((v, key, e) -> {
      if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0
          && (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
              || key == KeyEvent.KEYCODE_ENTER)) {
        open.run();
        return true;
      }
      return false;
    });
    return tile;
  }
  /** The menu column is narrow; the screenshot grid uses the full width. */
  private void setBodyWide(boolean wide) {
    int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
    android.widget.FrameLayout.LayoutParams params =
        (android.widget.FrameLayout.LayoutParams) body.getLayoutParams();
    int width = wide ? availableWidth : Math.min(dp(390), availableWidth);
    if (params.width != width) {
      params.width = width;
      body.setLayoutParams(params);
    }
    body.setPadding(wide ? 0 : dp(8), body.getPaddingTop(), wide ? 0 : dp(8),
        body.getPaddingBottom());
    // Keep the page heading aligned with the content's left edge.
    int headingWidth = wide ? availableWidth : Math.min(dp(374), availableWidth);
    for (int index = 0; index < heading.getChildCount(); index++) {
      View child = heading.getChildAt(index);
      LinearLayout.LayoutParams childParams = (LinearLayout.LayoutParams) child.getLayoutParams();
      if (childParams.width != headingWidth) {
        childParams.width = headingWidth;
        child.setLayoutParams(childParams);
      }
    }
  }
  private void discordSettings() {
    screen = Screen.DISCORD;
    body.removeAllViews();
    title("Discord");
    if (!DiscordPresence.available()) {
      message(R.string.discord_sdk_missing);
      return;
    }
    if (!DiscordPresence.signedIn()) {
      row(R.string.sign_in_with_discord, "", () -> DiscordPresence.signIn(this));
      message(R.string.discord_sign_in_hint);
      return;
    }
    String name = DiscordPresence.userName();
    row(DiscordPresence.connected() ? (name.isEmpty() ? getString(R.string.connected)
                                                      : getString(R.string.signed_in_as_x, name))
                                    : getString(R.string.connecting),
        "", () -> {});
    boolean presence = library.get("discordPresence", true);
    row(getString(R.string.rich_presence) + ": " + getString(presence ? R.string.on : R.string.off),
        "", () -> {
          library.put("discordPresence", !presence);
          DiscordPresence.apply(this);
          discordSettings();
          restoreSelection(Screen.DISCORD, false);
        });
    row(R.string.sign_out, "",
        ()
            -> new PocketDialog.Builder(this)
                   .setTitle(R.string.sign_out_of_discord)
                   .setPositiveButton(R.string.sign_out,
                       (d, w) -> {
                         DiscordPresence.signOut();
                         focusedPositions.remove(Screen.DISCORD);
                         discordSettings();
                       })
                   .setNegativeButton(R.string.cancel, null)
                   .show());
  }
  private void openSyncthingSettings() {
    syncthingOrigin = screen;
    syncthingSettings();
  }
  private void leaveSyncthingSettings() {
    showScreen(syncthingOrigin);
    restoreSelection(syncthingOrigin);
  }
  private void syncthingSettings() {
    screen = Screen.SYNCTHING;
    body.removeAllViews();
    title("Syncthing");
    space(18);
    TextView label = text(R.string.status, 14, true);
    label.setTextColor(muted);
    label.setPadding(dp(14), 0, dp(14), dp(5));
    body.addView(label);
    syncthingStatus = text(syncthingStatusText(), 17, false);
    syncthingStatus.setPadding(dp(14), 0, dp(14), dp(22));
    body.addView(syncthingStatus);
    row(R.string.open, "", this::openSyncthing);
  }
  private String syncthingStatusText() {
    if (getPackageManager().getLaunchIntentForPackage(NotificationService.SYNCTHING_PACKAGE) == null)
      return getString(R.string.not_installed);
    android.service.notification.StatusBarNotification[] notices = NotificationService.current();
    if (notices == null)
      return getString(R.string.notification_access_unavailable);
    android.service.notification.StatusBarNotification latest = null;
    for (android.service.notification.StatusBarNotification item : notices) {
      if (!NotificationService.SYNCTHING_PACKAGE.equals(item.getPackageName())) continue;
      boolean ongoing = (item.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
      boolean previousOngoing = latest != null
          && (latest.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
      if (latest == null || (ongoing && !previousOngoing)
          || (ongoing == previousOngoing && item.getPostTime() > latest.getPostTime()))
        latest = item;
    }
    if (latest == null)
      return getString(R.string.no_status_notification);
    android.os.Bundle extras = latest.getNotification().extras;
    String title = String.valueOf(extras.getCharSequence(android.app.Notification.EXTRA_TITLE, ""));
    String detail = String.valueOf(extras.getCharSequence(android.app.Notification.EXTRA_TEXT, ""));
    if (detail.isEmpty())
      return title.isEmpty() ? getString(R.string.no_status_notification) : title;
    if (title.isEmpty() || title.equals(detail)) return detail;
    return title + "\n" + detail;
  }
  private void systemSettings() {
    screen = Screen.SYSTEM_SETTINGS;
    body.removeAllViews();
    title(R.string.system);
    row(R.string.language, L10n.option(this, library.get("language", "System")),
        () -> choose(R.string.language, new String[] {"System", "English", "Deutsch"}, "language"));
    row(R.string.second_screen, L10n.option(this, library.get("secondScreen", "Off")),
        ()
            -> choose(R.string.emugo_on_second_screen,
                new String[] {"Off", "Primary", "Secondary", "Both"}, "secondScreen"));
    booleanOption(R.string.notification_banners, "notificationBanners", true);
    row(R.string.set_as_default_launcher, "", this::requestHome);
    row(R.string.android_system_settings, "",
        () -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
    row(R.string.check_for_updates, "", () -> UpdateChecker.check(this, true));
  }
  private void about() {
    screen = Screen.ABOUT;
    body.removeAllViews();
    title(R.string.about);
    richRow(new RichRow.Entry("EmuGo! " + Version.name(this), getString(R.string.check_for_updates),
                null, null, false),
        () -> UpdateChecker.check(this, true));
    row(R.string.whats_new, "", this::whatsNew);
    row(R.string.device_info, "", this::deviceInfo);
    row(R.string.installed_cores, "", this::installedCores);
    row(R.string.storage, "", this::storage);
    row(R.string.source_code, "", () -> openUrl(AboutInfo.REPOSITORY));
    row(R.string.report_a_problem, "", this::reportProblem);
    row(R.string.credits, "", this::credits);
    row(R.string.oss_licenses, "", this::licenses);
  }
  private void whatsNew() {
    PocketNotice.show(this, R.string.loading);
    new Thread(() -> {
      String text;
      try {
        text = AboutInfo.changelog(this);
      } catch (Exception e) {
        text = e instanceof UpdateChecker.RateLimitException
            ? e.getMessage()
            : getString(R.string.could_not_load_release_notes_x, String.valueOf(e.getMessage()));
      }
      String notes = text;
      runOnUiThread(()
                        -> new PocketDialog.Builder(this)
                               .setTitle(getString(R.string.whats_new_in_x, Version.name(this)))
                               .setMessage(notes)
                               .setPositiveButton(R.string.close, null)
                               .show());
    }, "EmuGo-whats-new").start();
  }
  private void deviceInfo() {
    String info = AboutInfo.deviceInfo(this);
    new PocketDialog.Builder(this)
        .setTitle(R.string.device_info)
        .setMessage(info)
        .setPositiveButton(R.string.copy,
            (d, w) -> {
              android.content.ClipboardManager clipboard =
                  (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              clipboard.setPrimaryClip(
                  android.content.ClipData.newPlainText("EmuGo! device info", info));
              PocketNotice.show(this, R.string.copied);
            })
        .setNegativeButton(R.string.close, null)
        .show();
  }
  /** Opens a new GitHub issue with the device details already filled in. */
  private void reportProblem() {
    String body = "**What happened?**\n\n\n**Steps to reproduce**\n\n\n**Device**\n```\n"
        + AboutInfo.deviceInfo(this) + "\n```\n";
    openUrl(Uri.parse(AboutInfo.REPOSITORY + "/issues/new").buildUpon()
                .appendQueryParameter("body", body)
                .build()
                .toString());
  }
  private void installedCores() {
    screen = Screen.CORES;
    body.removeAllViews();
    title(R.string.installed_cores);
    List<AboutInfo.Core> cores = AboutInfo.cores(this, library);
    if (cores.isEmpty()) {
      message(R.string.no_cores_downloaded_yet);
      return;
    }
    long unusedBytes = 0;
    for (AboutInfo.Core core : cores) if (!core.used) unusedBytes += core.bytes;
    if (unusedBytes > 0) {
      long freed = unusedBytes;
      row(getString(R.string.remove_unused_cores_x, AboutInfo.formatBytes(freed)), "", () -> {
        for (AboutInfo.Core core : cores)
          if (!core.used)
            AboutInfo.removeCore(this, core.name);
        PocketNotice.show(this, getString(R.string.freed_x, AboutInfo.formatBytes(freed)));
        focusedPositions.remove(Screen.CORES);
        installedCores();
      });
    }
    for (AboutInfo.Core core : cores) {
      String detail = (core.systems.isEmpty() ? "" : core.systems + "  ·  ")
          + getString(core.used ? R.string.in_use : R.string.not_used_by_your_library);
      richRow(
          new RichRow.Entry(core.name, detail, AboutInfo.formatBytes(core.bytes), null, !core.used),
          ()
              -> new PocketDialog.Builder(this)
                     .setTitle(getString(R.string.remove_x, core.name))
                     .setMessage(R.string.remove_core_message)
                     .setPositiveButton(R.string.remove,
                         (d, w) -> {
                           AboutInfo.removeCore(this, core.name);
                           installedCores();
                           restoreSelection(Screen.CORES);
                         })
                     .setNegativeButton(R.string.cancel, null)
                     .show());
    }
  }
  private void storage() {
    screen = Screen.STORAGE;
    body.removeAllViews();
    title(R.string.storage);
    message(R.string.calculating);
    int generation = menuGeneration;
    new Thread(() -> {
      long cores = AboutInfo.size(AboutInfo.coresDir(this));
      long images = AboutInfo.size(AboutInfo.imageCache(this));
      long updates = AboutInfo.size(AboutInfo.updateCache(this));
      long games = AboutInfo.temporaryGameFiles(this);
      runOnUiThread(() -> {
        if (menuGeneration != generation || screen != Screen.STORAGE) return;
        body.removeAllViews();
        richRow(
            new RichRow.Entry(getString(R.string.cores), getString(R.string.storage_cores_detail),
                AboutInfo.formatBytes(cores), null, false),
            this::installedCores);
        richRow(
            new RichRow.Entry(getString(R.string.images), getString(R.string.storage_images_detail),
                AboutInfo.formatBytes(images), null, false),
            null);
        richRow(new RichRow.Entry(getString(R.string.game_files),
                    getString(R.string.storage_game_files_detail), AboutInfo.formatBytes(games),
                    null, false),
            null);
        richRow(new RichRow.Entry(getString(R.string.downloaded_updates), null,
                    AboutInfo.formatBytes(updates), null, false),
            null);
        long clearable = images + games + updates;
        row(getString(R.string.clear_cache_x, AboutInfo.formatBytes(clearable)), "", () -> {
          new Thread(() -> {
            AboutInfo.clearCache(this);
            runOnUiThread(() -> {
              PocketNotice.show(
                  this, getString(R.string.freed_x, AboutInfo.formatBytes(clearable)));
              if (screen == Screen.STORAGE)
                storage();
            });
          }, "EmuGo-clear-cache").start();
        });
        message(R.string.storage_note);
        restoreSelection(Screen.STORAGE);
      });
    }, "EmuGo-storage").start();
  }
  private void credits() {
    screen = Screen.CREDITS;
    body.removeAllViews();
    title(R.string.credits);
    // Name, website; descriptions are in CREDIT_DETAILS.
    String[][] credits = {{"RetroAchievements", "https://retroachievements.org"},
        {"Libretro", "https://www.libretro.com"},
        {"Nunito", "https://fonts.google.com/specimen/Nunito"},
        {"Tabler Icons", "https://tabler.io/icons"}};
    for (int index = 0; index < credits.length; index++) {
      String[] credit = credits[index];
      richRow(new RichRow.Entry(credit[0], getString(CREDIT_DETAILS[index]), null, null, false),
          () -> openUrl(credit[1]));
    }
  }
  private static final int[] CREDIT_DETAILS = {R.string.credit_retroachievements,
      R.string.credit_libretro, R.string.credit_nunito, R.string.credit_tabler};
  // File and name; what each is used for and its license are in NOTICE_DETAILS.
  private static final String[][] NOTICES = {{"EmuGo-MIT.txt", "EmuGo!"},
      {"rcheevos-MIT.txt", "rcheevos"}, {"Libretro-API.txt", "Libretro API"},
      {"Discord-SDK-third-party.txt", "Discord Social SDK"}, {"Nunito-OFL.txt", "Nunito"},
      {"Tabler-Icons-MIT.txt", "Tabler Icons"}};
  private static final int[][] NOTICE_DETAILS = {{R.string.notice_emugo, R.string.license_mit},
      {R.string.notice_rcheevos, R.string.license_mit},
      {R.string.notice_libretro_api, R.string.license_mit},
      {R.string.notice_discord, R.string.license_discord_notices},
      {R.string.notice_nunito, R.string.license_ofl},
      {R.string.notice_tabler, R.string.license_mit}};
  private void licenses() {
    screen = Screen.LICENSES;
    body.removeAllViews();
    title(R.string.oss_licenses);
    for (int index = 0; index < NOTICES.length; index++) {
      String[] notice = NOTICES[index];
      String license = getString(NOTICE_DETAILS[index][1]);
      richRow(new RichRow.Entry(notice[1], getString(NOTICE_DETAILS[index][0]) + "  ·  " + license,
                  null, null, false),
          () -> LicenseReader.open(this, library, notice[0], notice[1], license));
    }
  }
  private String onOff(boolean on) {
    return getString(on ? R.string.on : R.string.off);
  }
  private void toggle(int label, String key, boolean defaultValue) {
    row(label, onOff(library.get(key, defaultValue)), () -> {
      Screen destination = screen;
      int previousScroll = scroll.getScrollY();
      library.put(key, !library.get(key, defaultValue));
      showScreen(destination);
      restoreSelection(destination, false);
      restoreScroll(previousScroll);
    });
  }
  private void booleanOption(int label, String key, boolean defaultValue) {
    row(label, onOff(library.get(key, defaultValue)), () -> {
      Screen destination = screen;
      int previousScroll = scroll.getScrollY();
      new PocketDialog.Builder(this)
          .setTitle(label)
          .setSingleChoiceItems(new String[] {onOff(false), onOff(true)},
              library.get(key, defaultValue) ? 1 : 0,
              (dialog, choice) -> {
                library.put(key, choice == 1);
                if (key.equals("controlHints"))
                  drawControlHints();
                showScreen(destination);
                restoreSelection(destination, false);
                restoreScroll(previousScroll);
              })
          .show();
    });
  }
  private void choose(int title, String[] options, String key) {
    Screen returnTo = screen;
    new PocketDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(L10n.options(this, options),
            Arrays.asList(options).indexOf(library.get(key, options[0])),
            (d, w) -> {
              library.put(key, options[w]);
              d.dismiss();
              if (key.equals("language") && L10n.remember(this, options[w])) {
                // New resources need a new activity; come back to the same settings page.
                languageReturn = returnTo;
                recreate();
                return;
              }
              render();
              showScreen(returnTo);
              updateSecond();
            })
        .show();
  }
  private void connectivityToggles() {
    int wifi = R.string.off;
    try {
      android.net.wifi.WifiManager manager =
          (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      if (manager != null && manager.isWifiEnabled()) {
        wifi = R.string.not_connected;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
          int strength = cap.getSignalStrength();
          wifi = strength == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED || strength >= -60
              ? R.string.connected_strong_signal
              : strength >= -75 ? R.string.connected_good_signal
                                : R.string.connected_weak_signal;
        }
      }
    } catch (Exception ignored) {
    }
    row(getString(R.string.wi_fi) + ": " + getString(wifi), "",
        () -> startActivity(new Intent(Settings.Panel.ACTION_WIFI)));
    boolean bluetooth = false;
    try {
      BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
      bluetooth = adapter != null && adapter.isEnabled();
    } catch (Exception ignored) {
    }
    boolean bluetoothOn = bluetooth;
    boolean permitted = BluetoothDevices.permitted(this);
    LinearLayout bluetoothRow = row(getString(R.string.bluetooth) + ": "
            + getString(!bluetoothOn ? R.string.off
                    : permitted      ? R.string.on
                                     : R.string.on_select_to_show_devices),
        "", () -> {
          if (bluetoothOn && !BluetoothDevices.permitted(this))
            requestPermissions(
                new String[] {android.Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
          else
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        });
    if (bluetoothOn && permitted) {
      TextView bluetoothText = (TextView) bluetoothRow.getChildAt(bluetoothRow.getChildCount() - 1);
      int generation = menuGeneration;
      BluetoothDevices.query(this, devices -> {
        if (menuGeneration != generation) return;
        bluetoothText.setText(getString(R.string.bluetooth) + ": "
            + (devices.isEmpty() ? getString(R.string.no_devices_connected)
                                 : String.join(", ", devices)));
      });
    }
    adjustRow(this::volumeLabel, this::stepVolume);
    adjustRow(this::brightnessLabel, this::stepBrightness);
    nowPlaying();
    space(14);
    TextView label = text(R.string.notifications, 14, true);
    label.setTextColor(muted);
    label.setPadding(dp(14), 0, dp(14), dp(5));
    body.addView(label);
  }
  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == REQUEST_BLUETOOTH && screen == Screen.INFOCENTER) {
      View focused = body.findFocus();
      if (focused != null) rememberSelection(focused);
      infocenter();
      restoreSelection(Screen.INFOCENTER, false);
    }
  }
  /** A row whose value changes with left/right; A also steps up. */
  private void adjustRow(java.util.function.Supplier<String> label,
      java.util.function.IntConsumer step) {
    LinearLayout card = row(label.get(), "", () -> {});
    TextView text = (TextView) card.getChildAt(card.getChildCount() - 1);
    card.setOnClickListener(v -> {
      step.accept(1);
      text.setText(label.get());
    });
    card.setOnKeyListener((v, key, e) -> {
      if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
      int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1
          : key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_BUTTON_A
              || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
          ? 1 : 0;
      if (direction == 0) return false;
      rememberSelection(card);
      step.accept(direction);
      text.setText(label.get());
      return true;
    });
  }
  private String volumeLabel() {
    android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
    int value = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
    return getString(R.string.volume) + ": " + Math.round(value * 100f / Math.max(1, max)) + "%";
  }
  private void stepVolume(int direction) {
    try {
      android.media.AudioManager audio =
          (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
      audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
          direction > 0 ? android.media.AudioManager.ADJUST_RAISE
                        : android.media.AudioManager.ADJUST_LOWER, 0);
    } catch (SecurityException e) {
      PocketNotice.show(this, R.string.volume_locked);
    }
  }
  private String brightnessLabel() {
    return getString(R.string.brightness) + ": " + Brightness.label(this);
  }
  private void stepBrightness(int direction) {
    Brightness.step(this, direction);
    Brightness.apply(this, getWindow());
  }
  private void nowPlaying() {
    android.media.session.MediaController controller = MediaControls.active(this);
    if (controller == null) return;
    String label = MediaControls.label(this, controller);
    LinearLayout card = row(label, "", () -> {});
    TextView text = (TextView) card.getChildAt(card.getChildCount() - 1);
    Runnable refresh = () -> handler.postDelayed(() -> {
      android.media.session.MediaController now = MediaControls.active(this);
      if (now != null && screen == Screen.INFOCENTER) text.setText(MediaControls.label(this, now));
    }, 400);
    card.setOnClickListener(v -> {
      MediaControls.toggle(controller);
      refresh.run();
    });
    card.setOnKeyListener((v, key, e) -> {
      if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return false;
      rememberSelection(card);
      if (key == KeyEvent.KEYCODE_DPAD_LEFT) MediaControls.previous(controller);
      else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) MediaControls.next(controller);
      else if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
          || key == KeyEvent.KEYCODE_ENTER) MediaControls.toggle(controller);
      else return false;
      refresh.run();
      return true;
    });
  }
  private void infocenter() {
    screen = Screen.INFOCENTER;
    body.removeAllViews();
    title(R.string.infocenter);
    connectivityToggles();
    android.service.notification.StatusBarNotification[] notices = NotificationService.current();
    if (notices == null) {
      row(R.string.enable_notification_access, R.string.allow_emugo_os_in_android_settings,
          () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
      return;
    }
    ArrayList<android.service.notification.StatusBarNotification> visible = new ArrayList<>();
    for (android.service.notification.StatusBarNotification item : notices)
      if (!NotificationService.SYNCTHING_PACKAGE.equals(item.getPackageName()))
        visible.add(item);
    if (visible.isEmpty()) {
      TextView empty = text(R.string.all_clear, 17, false);
      empty.setPadding(dp(14), dp(6), dp(14), dp(6));
      body.addView(empty);
      return;
    }
    visible.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
    infocenterKeys.clear();
    boolean anyClearable = false;
    for (android.service.notification.StatusBarNotification item : visible)
      anyClearable |= item.isClearable();
    if (anyClearable)
      row(R.string.clear_all, "", () -> {
        NotificationService.dismissAll();
        focusedPositions.remove(Screen.INFOCENTER);
        refreshInfocenterSoon();
      });
    for (android.service.notification.StatusBarNotification item : visible) {
      android.app.Notification notification = item.getNotification();
      String app = item.getPackageName();
      try {
        app = getPackageManager()
                  .getApplicationLabel(getPackageManager().getApplicationInfo(app, 0))
                  .toString();
      } catch (Exception ignored) {
      }
      String title = String.valueOf(
          notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE, app));
      String detail = String.valueOf(
          notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT, ""));
      View notificationRow = richRow(new RichRow.Entry(title,
              detail.isEmpty() || detail.equals("null") ? app : app + "  ·  " + detail, null, null,
              false), () -> {
        PendingIntent open = notification.contentIntent;
        if (open == null) {
          PocketNotice.show(this, R.string.notification_no_open_action);
          return;
        }
        try {
          open.send(this, 0, null);
          if ((notification.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0)
            NotificationService.dismiss(item.getKey());
          toggleInfocenter();
        } catch (PendingIntent.CanceledException e) {
          PocketNotice.show(this, R.string.notification_no_longer_opens);
        }
      });
      if (item.isClearable()) infocenterKeys.put(notificationRow, item.getKey());
    }
  }
  private final Map<View, String> infocenterKeys = new HashMap<>();
  /** X / Square on a focused notification dismisses it. */
  private void dismissFocusedNotification() {
    View focused = body.findFocus();
    String key = focused == null ? null : infocenterKeys.get(focused);
    if (key == null) return;
    rememberSelection(focused);
    NotificationService.dismiss(key);
    refreshInfocenterSoon();
  }
  // Cancelling is asynchronous; rebuild once the listener has the new state.
  private void refreshInfocenterSoon() {
    handler.postDelayed(() -> {
      if (screen != Screen.INFOCENTER) return;
      infocenter();
      restoreSelection(Screen.INFOCENTER, false);
    }, 300);
  }
  private void toggleInfocenter() {
    if (screen == Screen.INFOCENTER) {
      Screen destination = infocenterOrigin;
      showScreen(destination);
      restoreSelection(destination, false);
      restoreScroll(infocenterOriginScroll);
    } else {
      View focused = body.findFocus();
      if (focused != null)
        rememberSelection(focused);
      infocenterOrigin = screen;
      infocenterOriginScroll = scroll.getScrollY();
      infocenter();
    }
  }
  private void openApps() {
    if (screen != Screen.APPS) appsOrigin = screen;
    apps();
  }
  private void leaveApps() {
    showScreen(appsOrigin);
    restoreSelection(appsOrigin);
  }
  private void apps() {
    screen = Screen.APPS;
    body.removeAllViews();
    title(R.string.android);
    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> entries = getPackageManager().queryIntentActivities(query, 0);
    entries.sort(Comparator.comparing(
        x -> x.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER));
    for (ResolveInfo info : entries) {
      if (info.activityInfo.packageName.equals(getPackageName()))
        continue;
      row(info.loadLabel(getPackageManager()).toString(), info.activityInfo.packageName, () -> {
        Intent launch =
            getPackageManager().getLaunchIntentForPackage(info.activityInfo.packageName);
        if (launch != null)
          startActivity(launch);
      });
    }
  }
  private void pickRoms() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, PICK_ROMS);
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null)
      return;
    Uri uri = data.getData();
    try {
      library.keepPermission(uri, data.getFlags());
    } catch (Exception ignored) {
    }
    if (request == PICK_ROMS) {
      library.setRoot(uri);
      render();
      library.scanAsync(this::refreshIndexedScreen);
    } else if (request == PICK_WALLPAPER) {
      library.put("wallpaper", uri.toString());
      render();
      appearanceSettings();
    } else if (request == PICK_RETROARCH_SAVES) {
      library.local.edit().putString("retroSavesRoot", uri.toString()).apply();
      new Thread(() -> importSavesFrom(uri, true)).start();
    }
  }
  private void onboarding() {
    if (isFinishing())
      return;
    new PocketDialog.Builder(this)
        .setTitle(R.string.welcome_to_emugo)
        .setMessage(R.string.welcome_message)
        .setItems(new String[] {"Nintendo", "Xbox", "PlayStation"},
            (d, w) -> {
              library.put("layout", new String[] {"Nintendo", "Xbox", "PlayStation"}[w]);
              drawControlHints();
              library.local.edit().putBoolean("onboarded", true).apply();
              new PocketDialog.Builder(this)
                  .setMessage(R.string.default_launcher_question)
                  .setPositiveButton(R.string.set_launcher, (a, b) -> requestHome())
                  .setNegativeButton(R.string.later, null)
                  .show();
            })
        .show();
  }
  private void requestHome() {
    if (Build.VERSION.SDK_INT >= 29) {
      RoleManager rm = getSystemService(RoleManager.class);
      if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
        if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
          PocketNotice.show(this, R.string.emugo_is_already_your_default_launcher);
        } else {
          startActivityForResult(
              rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
        }
      }
    }
  }
  private void openUrl(String url) {
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
  }
  private void openSyncthing() {
    String packageName = NotificationService.SYNCTHING_PACKAGE;
    Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
    if (launch != null) {
      try {
        startActivity(launch);
        return;
      } catch (ActivityNotFoundException ignored) {
      }
    }
    openUrl("https://play.google.com/store/apps/details?id=" + packageName);
  }
  private void importSaves() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, PICK_RETROARCH_SAVES);
    PocketNotice.show(this, R.string.select_your_retroarch_folder);
  }
  private void importSavesFrom(Uri source, boolean notify) {
    try {
      Uri emugo = library.ensureEmugo();
      if (emugo == null)
        throw new IOException(getString(R.string.choose_a_roms_folder_first));
      Uri target = library.findChild(emugo, "saves");
      if (target == null)
        target = android.provider.DocumentsContract.createDocument(getContentResolver(), emugo,
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "saves");
      String treeId = android.provider.DocumentsContract.getTreeDocumentId(source);
      Uri sourceDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(source, treeId);
      Map<String, Uri> existing = new HashMap<>();
      for (Library.Item item : library.children(target))
        existing.put(item.name.toLowerCase(Locale.ROOT), item.uri);
      Set<String> imported = new HashSet<>();
      ArrayDeque<Uri> pending = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      pending.add(sourceDoc);
      int count = 0;
      while (!pending.isEmpty()) {
        Uri folder = pending.removeFirst();
        if (!visited.add(folder.toString())) continue;
        List<Library.Item> entries = library.childrenInTree(source, folder);
        boolean isRoot = folder.equals(sourceDoc);
        boolean hasSaveFolders = isRoot && entries.stream()
            .anyMatch(item -> item.directory() && isRetroArchSaveFolder(item.name));
        for (Library.Item item : entries) {
          if (item.directory()) {
            if (!hasSaveFolders || isRetroArchSaveFolder(item.name)) pending.addLast(item.uri);
            continue;
          }
          String name = item.name.toLowerCase(Locale.ROOT);
          if (!isRetroArchSaveFile(name) || !imported.add(name)) continue;
          Uri dest = existing.get(name);
          if (dest != null && !notify) continue;
          if (dest == null) {
            dest = android.provider.DocumentsContract.createDocument(
                getContentResolver(), target, "application/octet-stream", item.name);
            existing.put(name, dest);
          }
          try (InputStream in = getContentResolver().openInputStream(item.uri);
              OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            count++;
          }
        }
      }
      int total = count;
      if (notify)
        runOnUiThread(
            () -> PocketNotice.show(this, getString(R.string.imported_n_save_files, total)));
    } catch (Exception e) {
      if (notify)
        runOnUiThread(()
                          -> PocketNotice.show(
                              this, getString(R.string.save_import_failed_x, e.getMessage())));
    }
  }
  private boolean isRetroArchSaveFolder(String name) {
    String value = name.toLowerCase(Locale.ROOT);
    return value.equals("saves") || value.equals("savefiles") || value.equals("states")
        || value.equals("savestates") || value.equals("save states")
        || value.equals("memcards") || value.equals("memory cards");
  }
  private boolean isRetroArchSaveFile(String name) {
    return name.endsWith(".srm") || name.endsWith(".sav") || name.endsWith(".state")
        || name.endsWith(".state.auto") || name.endsWith(".mcr")
        || name.endsWith(".rtc") || name.matches(".*\\.state[0-9]+");
  }
  private void launch(Library.Game game) {
    GameLauncher.launch(this, library, game);
  }
  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        navigateBack();
      return true;
    }
    String combo = library.get("menuCombo", "Start + Select");
    int first =
        combo.equals("L3 + R3") ? KeyEvent.KEYCODE_BUTTON_THUMBL : KeyEvent.KEYCODE_BUTTON_SELECT;
    int last = combo.equals("L3 + R3") ? KeyEvent.KEYCODE_BUTTON_THUMBR
        : combo.equals("Select + X")   ? KeyEvent.KEYCODE_BUTTON_X
                                       : KeyEvent.KEYCODE_BUTTON_START;
    if (key == KeyEvent.KEYCODE_BUTTON_START && !firstHeld
        && (screen == Screen.RA_PROFILE || screen == Screen.RA_GAME)) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        playRetroAchievementsGame();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_X && !firstHeld
        && (screen == Screen.GAMES || screen == Screen.FAVOURITES)) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        toggleFocusedFavourite();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_X && !firstHeld && screen == Screen.INFOCENTER) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        dismissFocusedNotification();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_X && !firstHeld
        && (screen == Screen.RA_PROFILE || screen == Screen.RA_GAME)) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        reloadRetroAchievements();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_R1) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
        toggleInfocenter();
      return true;
    }
    if (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
      setTriggerState(key == KeyEvent.KEYCODE_BUTTON_L2, false,
          event.getAction() == KeyEvent.ACTION_DOWN);
      return true;
    }
    if (key == first || key == last) {
      boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
      if (key == first)
        firstHeld = down;
      else
        secondHeld = down;
      if (firstHeld && secondHeld && !comboTriggered) {
        comboTriggered = true;
        new PocketDialog.Builder(this)
            .setItems(new String[] {getString(R.string.library), getString(R.string.settings),
                          getString(R.string.android), getString(R.string.infocenter)},
                (d, w) -> {
                  if (w == 0)
                    home();
                  if (w == 1)
                    settings();
                  if (w == 2)
                    openApps();
                  if (w == 3)
                    toggleInfocenter();
                })
            .show();
      }
      if (!firstHeld && !secondHeld)
        comboTriggered = false;
      return true;
    }
    return super.dispatchKeyEvent(event);
  }
  @Override
  public void onBackPressed() {
    navigateBack();
  }
  private void navigateBack() {
    Screen previous = screen;
    switch (screen) {
      case GAMES:
      case FAVOURITES:
        systems();
        break;
      case SYSTEMS:
      case SETTINGS:
        home();
        break;
      case APPS:
        leaveApps();
        break;
      case INFOCENTER:
        toggleInfocenter();
        return;
      case SCREENSHOTS:
        home();
        break;
      case RETROACHIEVEMENTS:
        if (raSettingsOrigin == Screen.RA_PROFILE) retroAchievementsProfile();
        else if (raSettingsOrigin == Screen.HOME) home();
        else integrationSettings();
        break;
      case RA_PROFILE:
        home();
        break;
      case DISCORD:
        integrationSettings();
        break;
      case BUILT_IN_SYSTEMS:
        emulationSettings();
        break;
      case RA_GAME:
        retroAchievementsProfile();
        break;
      case APPEARANCE:
      case EMULATION:
      case LIBRARY_SETTINGS:
      case CONTROLS:
      case INTEGRATIONS:
      case SYSTEM_SETTINGS:
        settings();
        break;
      case SYNCTHING:
        leaveSyncthingSettings();
        break;
      case DISPLAY_MODES:
        emulationSettings();
        break;
      case GAME_BOY_DISPLAY:
      case GAME_BOY_COLOR_DISPLAY:
        displayModes();
        break;
      case ABOUT:
        settings();
        break;
      case LICENSES:
      case CORES:
      case STORAGE:
      case CREDITS:
        about();
        break;
      case HOME:
        break;
    }
    if (screen != previous)
      restoreSelection(screen);
  }
  private void showScreen(Screen destination) {
    switch (destination) {
      case HOME:
        home();
        break;
      case SYSTEMS:
        systems();
        break;
      case FAVOURITES:
        favourites();
        break;
      case GAMES:
        if (selected != null)
          renderGamePage(selected);
        else
          systems();
        break;
      case SETTINGS:
        settings();
        break;
      case APPEARANCE:
        appearanceSettings();
        break;
      case EMULATION:
        emulationSettings();
        break;
      case BUILT_IN_SYSTEMS:
        builtInSystems();
        break;
      case DISPLAY_MODES:
        displayModes();
        break;
      case GAME_BOY_DISPLAY:
        gameBoyDisplay();
        break;
      case GAME_BOY_COLOR_DISPLAY:
        gameBoyColorDisplay();
        break;
      case LIBRARY_SETTINGS:
        librarySettings();
        break;
      case CONTROLS:
        controlSettings();
        break;
      case INTEGRATIONS:
        integrationSettings();
        break;
      case SYNCTHING:
        syncthingSettings();
        break;
      case SYSTEM_SETTINGS:
        systemSettings();
        break;
      case ABOUT:
        about();
        break;
      case LICENSES:
        licenses();
        break;
      case CORES:
        installedCores();
        break;
      case STORAGE:
        storage();
        break;
      case CREDITS:
        credits();
        break;
      case INFOCENTER:
        infocenter();
        break;
      case RETROACHIEVEMENTS:
        retroAchievementsSettings();
        break;
      case RA_PROFILE:
        retroAchievementsProfile();
        break;
      case DISCORD:
        discordSettings();
        break;
      case RA_GAME:
        retroAchievementsGame();
        break;
      case SCREENSHOTS:
        screenshots();
        break;
      case APPS:
        apps();
        break;
    }
  }
  private void updateSecond() {
    if (second != null) {
      second.dismiss();
      second = null;
    }
    String mode = library.get("secondScreen", "Off");
    if (mode.equals("Off") || mode.equals("Primary"))
      return;
    DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    Display[] displays = dm.getDisplays();
    if (displays.length < 2)
      return;
    second = new Presentation(this, displays[1]);
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(1);
    container.setPadding(dp(28), dp(24), dp(28), dp(24));
    container.setBackgroundColor(bg);
    TextView heading = text("EmuGo!", 28, true);
    container.addView(heading);
    ScrollView scroll = new ScrollView(this);
    container.addView(scroll);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(1);
    scroll.addView(list);
    for (Library.SystemInfo s : library.visibleSystems()) {
      TextView item =
          text(s.name + "  ·  " + getString(R.string.n_games, library.games(s).size()), 22, false);
      item.setPadding(0, dp(10), 0, dp(10));
      list.addView(item);
      item.setOnClickListener(v -> {
        list.removeAllViews();
        for (Library.Game g : library.games(s)) {
          TextView game = text(g.title, 19, false);
          game.setPadding(0, dp(10), 0, dp(10));
          list.addView(game);
          game.setOnClickListener(x -> launch(g));
        }
      });
    }
    second.setContentView(container);
    try {
      second.show();
    } catch (android.view.WindowManager.InvalidDisplayException e) {
      second = null;
      PocketNotice.show(this, R.string.second_screen_unavailable);
    }
  }
}
