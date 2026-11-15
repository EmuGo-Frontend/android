package org.emugo.frontend;

import android.content.Context;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** German UI text. Keys are the existing English labels; setting values stay in English. */
final class L10n {
  private static final Map<String, String> DE = new HashMap<>();
  private static volatile String language = "System";

  static {
    // Navigation and common actions.
    add("Home", "Startseite");
    add("Library", "Bibliothek");
    add("Android", "Android");
    add("Settings", "Einstellungen");
    add("Notifications", "Benachrichtigungen");
    add("Back", "Zurück");
    add("Open", "Öffnen");
    add("Close", "Schließen");
    add("Cancel", "Abbrechen");
    add("Later", "Später");
    add("On", "Ein");
    add("Off", "Aus");
    add("selected", "ausgewählt");
    add("Yes", "Ja");
    add("No", "Nein");
    add("Move", "Bewegen");
    add("Select", "Auswählen");
    add("Jump", "Springen");
    add("Previous page", "Vorherige Seite");
    add("Next page", "Nächste Seite");

    // Launcher and library.
    add("Choose ROMs folder", "ROM-Ordner auswählen");
    add("EmulationStation folders such as gb, gba, nes, psx",
        "EmulationStation-Ordner wie gb, gba, nes, psx");
    add("Systems", "Systeme");
    add("Scanning ROMs", "ROMs werden durchsucht");
    add("No systems found", "Keine Systeme gefunden");
    add("ROMs folder", "ROM-Ordner");
    add("Rescan ROMs", "ROMs neu einlesen");
    add("Hide file extensions", "Dateiendungen ausblenden");
    add("Merge .cue and .bin", ".cue und .bin zusammenfassen");
    add("Remove brackets", "Klammerzusätze entfernen");
    add("Import RetroArch saves", "RetroArch-Spielstände importieren");
    add("Select your RetroArch folder", "RetroArch-Ordner auswählen");
    add("Choose a ROMs folder first", "Wähle zuerst einen ROM-Ordner aus");
    add("Save import failed: %s", "Import der Spielstände fehlgeschlagen: %s");
    add("Imported %d save files", "%d Spielstanddateien importiert");

    // Settings and themes.
    add("Appearance", "Darstellung");
    add("Emulation", "Emulation");
    add("Controls", "Steuerung");
    add("Integrations", "Integrationen");
    add("System", "System");
    add("About", "Über");
    add("Theme", "Design");
    add("Default", "Standard");
    add("Color mode", "Farbmodus");
    add("Dark", "Dunkel");
    add("Light", "Hell");
    add("Catppuccin flavor", "Catppuccin-Variante");
    add("Flavor", "Variante");
    add("TechDweeb color", "TechDweeb-Farbe");
    add("Wallpaper", "Hintergrundbild");
    add("Custom background", "Eigenes Hintergrundbild");
    add("orange", "Orange");
    add("purple", "Lila");
    add("black", "Schwarz");
    add("red", "Rot");
    add("pink", "Rosa");
    add("grey", "Grau");
    add("green", "Grün");
    add("cyan", "Cyan");
    add("blue", "Blau");
    add("Display modes", "Anzeigemodi");
    add("Automatic states", "Automatische Spielstände");
    add("Use RetroArch", "RetroArch verwenden");
    add("Green palette", "Grüne Farbpalette");
    add("Dot matrix", "Punktmatrix");
    add("LCD blending", "LCD-Überblendung");
    add("Screen filter", "Bildschirmfilter");
    add("Controller labels", "Tastenbelegung");
    add("Button layout", "Tastenlayout");
    add("Game menu shortcut", "Spielmenü-Kürzel");
    add("Shortcut", "Kürzel");
    add("Control hints", "Steuerungshinweise");
    add("Second screen", "Zweiter Bildschirm");
    add("Language", "Sprache");
    add("English", "Englisch");
    add("EmuGo! on second screen", "EmuGo! auf dem zweiten Bildschirm");
    add("Primary", "Primär");
    add("Secondary", "Sekundär");
    add("Both", "Beide");
    add("Set as default launcher", "Als Standard-Launcher festlegen");
    add("EmuGo! is already your default launcher",
        "EmuGo! ist bereits dein Standard-Launcher");
    add("Android system settings", "Android-Systemeinstellungen");
    add("Check for updates", "Nach Updates suchen");
    add("Second screen unavailable", "Zweiter Bildschirm nicht verfügbar");

    // Integrations, notifications, and about.
    add("Status", "Status");
    add("Not installed", "Nicht installiert");
    add("Notification access unavailable", "Benachrichtigungszugriff nicht verfügbar");
    add("No status notification", "Keine Statusbenachrichtigung");
    add("Android notification center", "Android-Benachrichtigungen");
    add("Enable notification access", "Benachrichtigungszugriff aktivieren");
    add("Allow EmuGo!OS in Android settings", "EmuGo!OS in Android-Einstellungen erlauben");
    add("All clear", "Keine Benachrichtigungen");
    add("No active notifications", "Keine aktiven Benachrichtigungen");
    add("This notification has no open action", "Diese Benachrichtigung kann nicht geöffnet werden");
    add("This notification can no longer be opened",
        "Diese Benachrichtigung kann nicht mehr geöffnet werden");
    add("Installed apps", "Installierte Apps");
    add("OSS licenses", "Open-Source-Lizenzen");
    add("Open source components", "Open-Source-Komponenten");
    add("Libretro cores", "Libretro-Cores");
    add("Each downloaded core has its own upstream license",
        "Jeder heruntergeladene Core hat eine eigene Lizenz");
    add("Read notice", "Lizenzhinweis lesen");
    add("Sync ROMs/emugo between devices", "ROMs/emugo zwischen Geräten synchronisieren");
    add("Rich Presence uses EmuGo!'s application ID. Account sign-in is not available in this debug build.",
        "Rich Presence verwendet die Anwendungs-ID von EmuGo!. Eine Kontoanmeldung ist in dieser Debug-Version nicht verfügbar.");

    // Setup, game menu, and errors.
    add("Welcome to EmuGo!", "Willkommen bei EmuGo!");
    add("Choose a ROMs folder. Your settings and saves will live in its emugo folder. Which button layout do you use?",
        "Wähle einen ROM-Ordner. Einstellungen und Spielstände werden im Unterordner emugo gespeichert. Welches Tastenlayout verwendest du?");
    add("Would you like EmuGo! as your default launcher?",
        "Möchtest du EmuGo! als Standard-Launcher verwenden?");
    add("Set launcher", "Launcher festlegen");
    add("Resume game", "Spiel fortsetzen");
    add("Return to library", "Zur Bibliothek zurückkehren");
    add("Could not start game", "Spiel konnte nicht gestartet werden");
    add("Preparing %s…", "%s wird vorbereitet…");
    add("Missing game", "Spiel fehlt");
    add("Could not open ROM", "ROM konnte nicht geöffnet werden");
    add("Archive has no game file", "Archiv enthält keine Spieldatei");
    add("No core library in archive", "Archiv enthält keinen Core");
    add("Core download failed: %s", "Core-Download fehlgeschlagen: %s");
    add("No compatible %s app was found.", "Keine kompatible %s-App gefunden.");
    add("Could not launch %s: %s", "%s konnte nicht gestartet werden: %s");
    add("Open the game inside %s. This build does not accept game intents.",
        "Öffne das Spiel direkt in %s. Diese Version unterstützt keine Spiel-Intents.");
    add("Install RetroArch to use this option.",
        "Installiere RetroArch, um diese Option zu verwenden.");
    add("RetroArch core missing", "RetroArch-Core fehlt");
    add("Install %s in RetroArch first.", "Installiere zuerst %s in RetroArch.");
    add("Open RetroArch", "RetroArch öffnen");
    add("RetroArch could not open this game: %s",
        "RetroArch konnte dieses Spiel nicht öffnen: %s");
    add("Username", "Benutzername");
    add("Password", "Passwort");
    add("Sign in", "Anmelden");
    add("Sign-in failed", "Anmeldung fehlgeschlagen");
    add("Signed in as %s", "Angemeldet als %s");
    add("RetroAchievements sign-in failed", "RetroAchievements-Anmeldung fehlgeschlagen");
    add("Sign in once to obtain an emulator token. The password is discarded; the token is saved in ROMs/emugo/settings.json for sync.",
        "Melde dich einmal an, um ein Emulator-Token zu erhalten. Das Passwort wird verworfen; das Token wird zur Synchronisierung in ROMs/emugo/settings.json gespeichert.");

    // Updates.
    add("No published release yet", "Noch keine veröffentlichte Version");
    add("EmuGo!OS update available", "EmuGo!OS-Update verfügbar");
    add("%s is available. Open its release page?",
        "%s ist verfügbar. Versionsseite öffnen?");
    add("Open release", "Versionsseite öffnen");
    add("EmuGo!OS is up to date (%s)", "EmuGo!OS ist aktuell (%s)");
    add("Could not check updates: %s", "Updateprüfung fehlgeschlagen: %s");
    add("GitHub API returned %d", "GitHub-API meldet %d");
    add("%d games", "%d Spiele");
    // Infocenter, screenshots, BIOS intros.
    add("Infocenter", "Infocenter");
    add("Wi-Fi", "WLAN");
    add("Bluetooth", "Bluetooth");
    add("Screenshots", "Screenshots");
    add("No screenshots yet", "Noch keine Screenshots");
    add("Take screenshot", "Screenshot aufnehmen");
    add("Screenshot saved", "Screenshot gespeichert");
    add("Screenshot failed: %s", "Screenshot fehlgeschlagen: %s");
    add("No frame to capture yet", "Noch kein Bild zum Aufnehmen");
    add("Delete", "Löschen");
    add("Share", "Teilen");
    add("Delete screenshot?", "Screenshot löschen?");
    add("Boot intros", "Boot-Intros");
    add("Sign out", "Abmelden");
    add("Signed out", "Abgemeldet");
    add("Sign out of RetroAchievements?", "Von RetroAchievements abmelden?");
    // RetroAchievements runtime.
    add("Hardcore mode", "Hardcore-Modus");
    add("Hardcore", "Hardcore");
    add("Achievements", "Erfolge");
    add("No achievements for this game", "Keine Erfolge für dieses Spiel");
    add("%s points", "%s Punkte");
    add("%s of %s achievements unlocked", "%s von %s Erfolgen freigeschaltet");
    add("This game has no achievements yet", "Für dieses Spiel gibt es noch keine Erfolge");
    add("Leaderboard attempt started", "Bestenlisten-Versuch gestartet");
    add("Leaderboard attempt failed", "Bestenlisten-Versuch fehlgeschlagen");
    add("Leaderboard submitted", "Bestenlisten-Ergebnis übermittelt");
    add("Mastered %s", "%s gemeistert");
    add("Completed %s", "%s abgeschlossen");
    add("RetroAchievements offline", "RetroAchievements offline");
    add("RetroAchievements reconnected", "RetroAchievements wieder verbunden");
    add("Unlocks will be sent when the connection returns",
        "Freischaltungen werden gesendet, sobald die Verbindung zurück ist");
    add("This game is not recognized by RetroAchievements",
        "Dieses Spiel wird von RetroAchievements nicht erkannt");
    add("Missing disc file: %s", "Disc-Datei fehlt: %s");

    // Infocenter.
    add("Not connected", "Nicht verbunden");
    add("Connected · strong signal", "Verbunden · starkes Signal");
    add("Connected · good signal", "Verbunden · gutes Signal");
    add("Connected · weak signal", "Verbunden · schwaches Signal");
    add("No devices connected", "Keine Geräte verbunden");
    add("Volume", "Lautstärke");
    add("Brightness", "Helligkeit");
    add("Volume is locked by Do Not Disturb", "Lautstärke ist durch „Bitte nicht stören“ gesperrt");
    add("Unknown track", "Unbekannter Titel");
    add("OK", "OK");
    // Achievement lists and RetroAchievements profile.
    add("Locked", "Gesperrt");
    add("Unlocked", "Freigeschaltet");
    add("Unsupported", "Nicht unterstützt");
    add("Unofficial", "Inoffiziell");
    add("Recently unlocked", "Kürzlich freigeschaltet");
    add("Active challenges", "Aktive Herausforderungen");
    add("Almost there", "Fast geschafft");
    add("Waiting to sync", "Wartet auf Synchronisierung");
    add("%s of %s points", "%s von %s Punkten");
    add("Loading…", "Wird geladen…");
    add("Could not load profile: %s", "Profil konnte nicht geladen werden: %s");
    add("Could not load achievements: %s", "Erfolge konnten nicht geladen werden: %s");
    add("%s hardcore points", "%s Hardcore-Punkte");
    add("%s softcore points", "%s Softcore-Punkte");
    add("%s messages", "%s Nachrichten");
    add("Games", "Spiele");
    add("No progress on supported systems yet",
        "Noch kein Fortschritt auf unterstützten Systemen");
    add("On · select to show devices", "Ein · auswählen, um Geräte anzuzeigen");
    add("Low", "Niedrig");
    add("Mid", "Mittel");
    add("High", "Hoch");
    add("Max", "Maximal");
    add("Reload", "Neu laden");
    add("Reloading", "Wird neu geladen");
    add("Fix title articles", "Artikel in Titeln korrigieren");
    add("Play", "Spielen");
    add("%s is not in your library", "%s ist nicht in deiner Bibliothek");
    // Discord.
    add("Sign in with Discord", "Mit Discord anmelden");
    add("Shows the game you are playing on your Discord profile",
        "Zeigt das Spiel, das du spielst, in deinem Discord-Profil an");
    add("Connected", "Verbunden");
    add("Connecting…", "Verbindung wird hergestellt…");
    add("Rich Presence", "Rich Presence");
    add("Sign out of Discord?", "Von Discord abmelden?");
    add("Discord sign-in failed: %s", "Discord-Anmeldung fehlgeschlagen: %s");
    add("Discord is not available in this build", "Discord ist in diesem Build nicht verfügbar");
    add("The Discord SDK is not included in this build",
        "Das Discord-SDK ist in diesem Build nicht enthalten");
    add("No browser available", "Kein Browser verfügbar");
    add("Browsing the library", "Stöbert in der Bibliothek");
    add("Take one from the pause menu of a built-in game",
        "Nimm einen über das Pausenmenü eines integrierten Spiels auf");
    // Updater.
    add("EmuGo! is up to date (%s)", "EmuGo! ist aktuell (%s)");
    add("EmuGo! %s is available", "EmuGo! %s ist verfügbar");
    add("Download and install", "Herunterladen und installieren");
    add("No changelog provided", "Keine Änderungen angegeben");
    add("Starting download…", "Download wird gestartet…");
    add("Downloading EmuGo! %s", "EmuGo! %s wird heruntergeladen");
    add("Download failed: %s", "Download fehlgeschlagen: %s");
    add("Install failed: %s", "Installation fehlgeschlagen: %s");
    add("Allow updates", "Updates erlauben");
    add("Android needs your permission before EmuGo! can install its own updates. Turn on \"Allow from this source\" and come back.",
        "Android braucht deine Erlaubnis, bevor EmuGo! eigene Updates installieren kann. Aktiviere „Dieser Quelle vertrauen“ und kehre zurück.");
    add("Open settings", "Einstellungen öffnen");
    add("Updates", "Updates");
    add("Tap to see what's new and install it", "Tippen, um die Neuerungen zu sehen und zu installieren");
    // Licenses.
    add("The launcher itself", "Der Launcher selbst");
    add("RetroAchievements runtime", "RetroAchievements-Laufzeit");
    add("Interface to the emulator cores", "Schnittstelle zu den Emulator-Cores");
    add("Discord Rich Presence", "Discord Rich Presence");
    add("Notices for its components", "Hinweise zu seinen Komponenten");
    add("Interface font", "Schriftart der Oberfläche");
    add("Status and button icons", "Status- und Tastensymbole");
    add("MIT License", "MIT-Lizenz");
    add("SIL Open Font License 1.1", "SIL Open Font License 1.1");
    add("D-pad", "Steuerkreuz");
    add("Scroll", "Scrollen");
    add("Page", "Seite");
    // About page.
    add("What's new", "Neuigkeiten");
    add("Device info", "Geräteinfo");
    add("Installed cores", "Installierte Cores");
    add("Storage", "Speicher");
    add("Source code", "Quellcode");
    add("Report a problem", "Problem melden");
    add("Credits", "Mitwirkende");
    add("Could not load release notes: %s", "Versionshinweise konnten nicht geladen werden: %s");
    add("What's new in %s", "Neu in %s");
    add("No release notes for this version", "Keine Versionshinweise für diese Version");
    add("Copy", "Kopieren");
    add("Copied", "Kopiert");
    add("No cores downloaded yet", "Noch keine Cores heruntergeladen");
    add("Remove unused cores (%s)", "Ungenutzte Cores entfernen (%s)");
    add("Freed %s", "%s freigegeben");
    add("In use", "In Verwendung");
    add("Not used by your library", "Von deiner Bibliothek nicht verwendet");
    add("Remove %s?", "%s entfernen?");
    add("It is downloaded again the next time a game needs it.", "Er wird erneut heruntergeladen, sobald ein Spiel ihn braucht.");
    add("Remove", "Entfernen");
    add("Calculating…", "Wird berechnet…");
    add("Cores", "Cores");
    add("Emulator cores, managed under Installed cores", "Emulator-Cores, verwaltet unter Installierte Cores");
    add("Images", "Bilder");
    add("Badges, game icons and avatars", "Abzeichen, Spielsymbole und Avatare");
    add("Game files", "Spieldateien");
    add("Temporary copies of games and BIOS files", "Temporäre Kopien von Spielen und BIOS-Dateien");
    add("Downloaded updates", "Heruntergeladene Updates");
    add("Clear cache (%s)", "Cache leeren (%s)");
    add("Saves, states and settings are never removed here.", "Spielstände und Einstellungen werden hier nie entfernt.");
    add("Achievements and rcheevos", "Erfolge und rcheevos");
    add("Emulator cores and the Libretro API", "Emulator-Cores und die Libretro-API");
    add("Font by Vernon Adams, Cyreal and Jacques Le Bailly", "Schrift von Vernon Adams, Cyreal und Jacques Le Bailly");
    add("Icons by Paweł Kuna", "Symbole von Paweł Kuna");
    // Notification banners and clearing.
    add("Clear all", "Alle löschen");
    add("Clear", "Löschen");
    add("Notification banners", "Benachrichtigungsbanner");
    add("GitHub is limiting update checks right now. Try again in %d minutes.",
        "GitHub begrenzt Update-Prüfungen gerade. Versuche es in %d Minuten erneut.");
    add("The changelog is on the release page: %s", "Die Änderungen stehen auf der Release-Seite: %s");
    // Favourites.
    add("Favourites", "Favoriten");
    add("Favourite", "Favorit");
    add("Press X on a game to add it to your favourites",
        "Drücke X bei einem Spiel, um es zu deinen Favoriten hinzuzufügen");
    add("Added %s to favourites", "%s zu den Favoriten hinzugefügt");
    add("Removed %s from favourites", "%s aus den Favoriten entfernt");
  }

  private L10n() {}

  static void setLanguage(String value) {
    language = value;
  }

  static boolean isGerman(Context context) {
    if ("Deutsch".equals(language)) return true;
    if ("English".equals(language)) return false;
    return "de".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
  }

  static String text(Context context, String source) {
    if (source == null || !isGerman(context)) return source;
    if (source.startsWith("← ")) return "← " + text(context, source.substring(2));
    String translated = DE.get(source);
    return translated == null ? source : translated;
  }

  static String format(Context context, String source, Object... args) {
    return String.format(isGerman(context) ? Locale.GERMAN : Locale.ENGLISH,
        text(context, source), args);
  }

  private static void add(String english, String german) {
    DE.put(english, german);
  }
}
