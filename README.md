<div align="center">
  <img src="logo.png" alt="EmuGo! logo" width="150">

  # EmuGo!

  A retro emulation frontend and home launcher for Android handhelds, inspired by the Analogue Pocket. Get it from [Releases](https://github.com/EmuGo-Frontend/android/releases/latest).

  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/EmuGo-Frontend/android"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="56"></a>
</div>

<br>

EmuGo! is a quiet, controller-first launcher: a centered list on black, one white selection pill, no sounds and no animations. Point it at your ROMs folder and it shows the systems you actually have. Older handhelds and consoles run inside the app through its own Libretro host. Newer ones hand off to the standalone emulators you already use.

It runs on Android 10 and newer, on arm64 devices. The interface is available in English and German.

## Getting started

1. Install the APK from the latest release.
2. Pick your ROMs folder when asked. EmuGo! looks for EmulationStation-style system folders such as `gb`, `gba`, `snes` or `psx`, including nested ones.
3. Choose your controller layout (Nintendo, Xbox or PlayStation) and, if you like, make EmuGo! your home launcher.

Cores are downloaded the first time you start a game for a system.

## Systems

Every game starts in the first of these that is available:

1. the built-in emulator, when the system has one and it is turned on,
2. the first installed standalone app,
3. RetroArch, with the matching core.

Settings > Emulation > Built-in emulators turns the built-in emulators off for every system, and Built-in emulator per system overrides that for single systems. When a built-in core cannot start, the game moves on to the standalone app or RetroArch by itself. If nothing installed can play a system, EmuGo! names the app to install.

| System | Folder | Built-in core | Standalone app |
| --- | --- | --- | --- |
| Game Boy, Game Boy Color | `gb`, `gbc` | gambatte | |
| Game Boy Advance | `gba` | mGBA | |
| NES | `nes` | Nestopia | |
| Super Nintendo | `snes` | Snes9x | |
| Nintendo 64 | `n64` | Mupen64Plus-Next (OpenGL ES) | Mupen64Plus FZ |
| Nintendo DS | `nds` | melonDS DS (touch screen works) | melonDS |
| Virtual Boy | `virtualboy` | Beetle VB | |
| Pokémon Mini | `pokemini` | PokeMini | |
| Game & Watch | `gameandwatch` | Handheld Electronic (GW) | |
| Genesis, Master System, Game Gear, SG-1000 | `genesis`, `mastersystem`, `gamegear`, `sg-1000` | Genesis Plus GX | |
| Sega CD | `segacd` | Genesis Plus GX | |
| Sega 32X | `sega32x` | PicoDrive | |
| Saturn | `saturn` | Yaba Sanshiro (OpenGL ES) | Yaba Sanshiro 2 |
| Dreamcast | `dreamcast` | Flycast (OpenGL ES) | Redream, Flycast |
| PC Engine, PC Engine CD | `pcengine`, `pcenginecd` | Beetle PCE Fast | |
| Neo Geo Pocket (Color) | `ngp`, `ngpc` | Beetle NeoPop | |
| WonderSwan (Color) | `wonderswan`, `wonderswancolor` | Beetle Cygne | |
| Atari 2600, 7800, Lynx | `atari2600`, `atari7800`, `atarilynx` | Stella 2014, ProSystem, Handy | |
| ColecoVision | `colecovision` | Gearcoleco | |
| MSX | `msx` | blueMSX | |
| Arcade, Neo Geo, CPS1 to CPS3 | `arcade`, `fbneo`, `neogeo`, `cps1`, `cps2`, `cps3` | FinalBurn Neo | |
| DOS | `dos` | DOSBox Pure | |
| ScummVM | `scummvm` | ScummVM | ScummVM |
| PlayStation | `psx` | PCSX ReARMed | DuckStation |
| PSP | `psp` | PPSSPP (OpenGL ES) | PPSSPP |
| PS2 | `ps2` | | NetherSX2 |
| PS3 | `ps3` | | aPS3e, RPCSX |
| PS Vita | `psvita` | | Vita3K |
| GameCube, Wii | `gc`, `wii` | | Dolphin |
| Wii U | `wiiu` | | Cemu |
| 3DS | `3ds` | | Azahar |
| Switch | `switch` | | Eden |
| Xbox 360 | `xbox360` | | aX360e |
| Xbox | `xbox` | | none for Android yet |

Discs, PSP and DS games, arcade sets and DOS archives are read in place, without copying them into the app. A .cue or .gdi brings its tracks along. Arcade clones find their parent set when it sits in the same folder. ScummVM games keep their own folder with a `.scummvm` file inside. MSX, PSP and ScummVM download libretro's system files once, like cores.

## Your ROMs folder

Everything EmuGo! keeps lives next to your games, so syncing one folder carries your setup to another device.

```
ROMs/
├── bios/             optional BIOS files, see below
├── gb/ gba/ psx/ ... your games
└── emugo/
    ├── settings.json
    ├── saves/        battery saves, clocks, automatic states and per-game save folders
    ├── screenshots/
    └── cores/
```

Battery saves are written as soon as a game changes them, and again when EmuGo! goes to the background. Game Boy Color cartridge clocks are kept in a matching `.rtc` file. Automatic save states are off by default. The pause menu can turn them on or off for a single game, and Settings > Emulation resets all of those per-game choices at once.

Saves and states from RetroArch can be imported from Settings > Library.

### BIOS files

No BIOS is included. Put your own in `ROMs/bios/`:

| File | System |
| --- | --- |
| `gb.bin`, `gbc.bin`, `gba.bin` | Game Boy, Game Boy Color, Game Boy Advance boot intros |
| `ps1.bin` | PlayStation |
| `bios7.bin`, `bios9.bin`, `firmware.bin` | Nintendo DS (optional) |
| `bios_CD_U.bin`, `bios_CD_E.bin`, `bios_CD_J.bin` | Sega CD |
| `saturn_bios.bin` | Saturn (optional) |
| `dc_boot.bin`, `dc_flash.bin` | Dreamcast (optional) |
| `syscard3.pce` | PC Engine CD |
| `lynxboot.img` | Atari Lynx |
| `7800 BIOS (U).rom` | Atari 7800 (optional) |
| `colecovision.rom` | ColecoVision |
| `bios.min` | Pokémon Mini (optional) |
| `neogeo.zip`, `pgm.zip` | Neo Geo and PGM arcade sets (or next to the games) |

Settings > Emulation > Boot intros skips the Game Boy and PlayStation intros again. Without `ps1.bin`, PlayStation games use the core's built-in open-source BIOS.

## Around the launcher

| Button | Action |
| --- | --- |
| D-pad | Move |
| A | Select |
| B | Back |
| R / RB | Infocenter |
| ZL/ZR, LT/RT | Jump through long lists |
| Start + Select | Game menu (configurable) |

The **Infocenter** shows Wi-Fi and Bluetooth status, volume, brightness, now-playing controls and your notifications.

**Screenshots** are taken from the pause menu and collected in an album on the Home screen. From the viewer you can delete or share them.

The Game Boy and Game Boy Color can use a green palette, a dot-matrix or LCD overlay, and frame blending, all under Settings > Emulation > Display modes. Every other system scales to the largest sharp picture that keeps its aspect ratio.

Themes include Nord, Dracula, Catppuccin, Tokyo Night and others, plus TechDweeb wallpapers and custom backgrounds.

## RetroAchievements

Sign in under Settings > Integrations > RetroAchievements. Achievements work for every built-in system in softcore mode:

- unlock, leaderboard and mastery notifications, which can be turned off
- an Achievements page in the pause menu
- rich presence on your RetroAchievements profile

Home > RetroAchievements opens your profile inside EmuGo!, with your points and per-game progress. Press X to reload it. Press Start on a game to launch it from your library.

## Discord

Settings > Integrations > Discord shows what you are playing on your Discord profile, including the RetroAchievements status line and the game's icon.

Sign-in uses OAuth2 with PKCE, so the app holds no client secret. If you build EmuGo! under your own Discord application, enable Public Client and add this redirect URI in the developer portal:

```
http://127.0.0.1:53134/callback
```

## Building

You need the Android SDK and Java 17 or newer. On the first build Gradle installs the matching SDK platform, NDK and CMake by itself.

```sh
./gradlew :app:assembleDebug
```

Discord support needs the Discord Social SDK, which is not part of this repository. Unpack it once with `sh scripts/prepare-discord-sdk.sh /path/to/DiscordSocialSdk.zip`. Builds without it work, just without Discord.

Release builds are signed with credentials from the environment, never from files in the repository:

```sh
SIGNING_KEYSTORE=/path/to/keystore.jks \
SIGNING_STORE_PASSWORD=... \
./gradlew :app:assembleRelease
```

`SIGNING_KEY_ALIAS` defaults to `upload`, and `SIGNING_KEY_PASSWORD` to the store password. The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

Interface text lives in `app/src/main/res/values/strings.xml`, with the German translation in `values-de/strings.xml`.

## Syncing

EmuGo! works well with [Syncthing](https://play.google.com/store/apps/details?id=com.github.catfriend1.syncthingandroid). Share the whole ROMs folder, `emugo/` included, and your library, saves and settings follow you between devices. Settings > Integrations > Syncthing shows its status.

`settings.json` holds your RetroAchievements login token, so only share the folder with devices you trust.

## Known limits

- OpenGL ES cores (N64, Saturn, Dreamcast, PSP) draw offscreen and each frame is copied to the screen, which costs a little speed compared with RetroArch.
- Only the first controller is connected to the built-in host.
- Standalone emulators decide for themselves how they handle launch requests, so some versions may not open games directly.

## License

EmuGo! is released under the MIT License. It includes rcheevos and Tabler Icons (MIT) and the Nunito font (SIL OFL 1.1). The TechDweeb wallpapers are included with permission. Libretro cores are downloaded separately and keep their own licenses. Third-party notices are in [`notices/`](notices/) and on the OSS licenses page inside the app.
