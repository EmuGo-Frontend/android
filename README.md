# EmuGo!OS

EmuGo!OS is an Android 10+ emulation frontend and optional home launcher for dual-screen handhelds. Its default theme uses a compact, centered Nunito menu on black with a white selection pill, single-line items, and Tabler status icons at the top right, inspired by the Analogue Pocket. The UI has no sounds or view animations. Systems appear only when matching EmulationStation folders exist in the ROMs folder selected with Android's document picker.
The interface supports English and German. It follows the Android language by default; System → Language can select either language on Android 10+, and Android 13+ also offers the standard per-app language setting.

The source is organized by responsibility: `MainActivity` handles launcher navigation, `ThemePalette` defines theme colors, `Wallpaper` applies backgrounds, `Library` handles ROM folders and synced settings, and `CoreActivity` hosts in-app emulation. Request codes and fullscreen flags are named constants. Java and C++ follow the repository's `.clang-format` rules.

## Current build

- Select a ROMs folder; the app creates `emugo/settings.json`, `emugo/index.json`, `emugo/saves/`, and `emugo/cores/` there. The Home menu has an Android entry for installed apps. A local copy of the ROM index makes menus available immediately while a background scan refreshes it. The scan includes nested folders, and Library settings has a manual Rescan ROMs action.
- GB, GBC, GBA, NES, SNES, N64, Genesis, Master System, Game Gear, and PlayStation use automatically selected Libretro cores downloaded from the official arm64 Android buildbot. The app runs them through its own Libretro host, without launching RetroArch. N64 uses parallel_n64 with the angrylion software renderer; PlayStation uses pcsx_rearmed, reading discs (CUE/BIN, CHD, PBP) in place through the host's Libretro VFS.
- Optional BIOS files go in `ROMs/bios/`: `gb.bin`, `gbc.bin`, `gba.bin`, and `ps1.bin`. With a BIOS present, the cores play the real boot intro; Emulation → Boot intros turns that off. Without `ps1.bin`, pcsx_rearmed uses its built-in open-source HLE BIOS. No BIOS is included with EmuGo!.
- PS2, PSP, Nintendo DS, GameCube, Wii, 3DS, and Switch use installed standalone emulators, including NetherSX2 and melonDS. Launch compatibility depends on the emulator build and its intent support.
- In-app games use sharp nearest-neighbor rendering. All in-app systems except Game Boy and Game Boy Color fill the largest viewport that preserves their aspect ratio; SNES uses 4:3. Game Boy and Game Boy Color retain integer scaling so their display effects align with source pixels. Game Boy has a green palette, dot matrix overlay, and frame blending for LCD flicker transparency. Game Boy Color has a subtle RGB LCD screen overlay and frame blending. Settings → Emulation → Display modes has a submenu for each console and an On/Off choice for each effect. The in-game pause menu has the same hierarchy. External emulators control their own rendering.
- Automatic save-state restore and write is off by default. Settings → Emulation → Automatic states opens an On/Off submenu, also available from the in-game pause menu. Ordinary in-game SRAM saves continue to sync. Turning automatic states on during a game saves a state when that game exits; automatic restore starts with the next launch.
- Page headings remain visible when a menu scrolls. The full launcher UI stays visible on the primary screen when a second display is enabled, so a disconnected or unavailable second display cannot blank the primary screen.
- Controller layout, game menu shortcut, light and dark mode, second-screen use, themes, and the supplied TechDweeb wallpapers are configurable.
- Launcher menus show bottom-left controller button glyphs and action text, using A/B or PlayStation Cross/Circle according to the selected layout; Controls → Control hints can hide them. RB toggles the notification center and returns to the previous screen, selection, and scroll position. Opening a notification sends its own Android content intent and closes the center; notifications with no open action say so. LT and RT jump 10 alphabetically sorted games or three rows in other menus. Game-list jumps cross page boundaries; page controls remain available. Android notification access is requested through system settings when needed.
- Emulation → Use RetroArch is off by default. When enabled, systems normally run by EmuGo's in-app Libretro host launch through an installed RetroArch app with the matching core selected automatically. That core must already be installed in RetroArch. Systems assigned to standalone emulators keep their existing launch path.
- RetroAchievements runs through rcheevos in softcore mode for every built-in system: unlock, leaderboard, and mastery popups (Settings → Integrations → RetroAchievements → Notifications turns them off), an Achievements page in the pause menu, and rich presence on the RA profile. Home → RetroAchievements shows the signed-in profile and per-game progress inside EmuGo!; X reloads it and Start launches the matching game from the library.
- Discord Rich Presence uses the Discord Social SDK with OAuth2 PKCE as a public client, so no client secret exists in the app. Sign-in opens a Custom Tab and a temporary server on `http://127.0.0.1:53134/callback` receives the redirect; that URI must be whitelisted in the Discord developer portal with Public Client enabled. Presence shows the game, its RetroAchievements status, the RA game icon, and play time. Tokens stay on the device. The SDK binary is supplied locally and omitted from the source tree.
- Built-in games can be paused for screenshots, saved to `ROMs/emugo/screenshots/` and shown in Home → Screenshots as a thumbnail album.
- The Infocenter (RB) shows Wi-Fi and Bluetooth status, volume, EmuGo!'s own brightness (System, Low, Mid, High, Max), media controls, and notifications.
- Select the RetroArch folder once in Library settings. EmuGo! finds saves and states in its save subfolders and imports them on later starts. Automatic imports leave existing EmuGo! saves alone; manually running the import again refreshes them.
- The launcher checks the latest release through GitHub's Releases API once per day; Settings also has a manual check. A repository without a release is handled quietly.

## Build

Set up an Android SDK with API 36, NDK 30, Java 17, and Gradle. Run `gradle :app:assembleDebug`.

If Gradle is unavailable, set `ANDROID_HOME` and `JAVA_HOME`, then run `scripts/build-debug-offline.sh`. It creates `EmuGo-debug.apk` with the installed Android build tools.

For a signed release, run `scripts/build-release-offline.sh` with `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, and optionally `SIGNING_KEY_ALIAS` (default `upload`) and `SIGNING_KEY_PASSWORD` in the environment. It creates `EmuGo-1.0.0.apk`. Keystores and credentials are never stored in the repository; `.gitignore` excludes them.

For Discord Rich Presence, first run `sh scripts/prepare-discord-sdk.sh /path/to/DiscordSocialSdk-1.10.19337.zip`. This puts the AAR in `.local-sdk/`, which is ignored by Git. Builds without it retain the rest of the app.

APKs are arm64 only. The debug APK is signed with a development key and should not be published.

## Sync

Sync the selected ROMs folder, including `emugo/`, between devices. The Syncthing submenu shows its ongoing notification as status, while EmuGo!'s notification center hides Syncthing notifications. The submenu's Open item launches Syncthing-Fork when installed, or its [Google Play page](https://play.google.com/store/apps/details?id=com.github.catfriend1.syncthingandroid) otherwise. `settings.json` contains the RetroAchievements emulator token in plain text, so share that folder only with devices you trust.

## Licenses

EmuGo!OS source, rcheevos, and Tabler Icons are MIT licensed. The Nunito font is under SIL OFL 1.1. The Libretro API header includes its own MIT notice. Discord SDK third-party notices and the in-app OSS licenses page are in `notices/`. Downloaded Libretro cores retain their individual upstream licenses. The TechDweeb wallpaper images were supplied for this project with permission to redistribute, per the user's statement about the creator's Patreon terms.

## Known limits

The current Libretro host supports software-rendered cores. Hardware-rendered cores and some systems that need firmware or special content handling may fail to start. Standalone emulator intents, dual-screen behavior, and controller mappings still need validation on the Ayn Thor and Mangmi Air X. The connected device is not visible inside the current build sandbox, so no device test has been run yet.
