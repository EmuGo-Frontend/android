package org.emugo.frontend;

import android.content.Context;
import android.content.res.Configuration;

/** Resolves the selected theme into colors used by the launcher. */
final class ThemePalette {
  final int background;
  final int foreground;
  final int muted;
  final int accent;

  private ThemePalette(int background, int foreground, int muted, int accent) {
    this.background = background;
    this.foreground = foreground;
    this.muted = muted;
    this.accent = accent;
  }

  static ThemePalette resolve(Context context, Library library) {
    String theme = library.get("theme", "Default");
    String scheme = library.get("scheme", "System");
    boolean light = scheme.equals("Light")
        || (scheme.equals("System")
            && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_NO);
    int bg = light ? 0xfff7f7f5 : 0xff060606;
    int fg = light ? 0xff141414 : 0xfff5f5f4;
    int muted = light ? 0xff62646b : 0xffa5a7ae;
    int accent = light ? 0xff111111 : 0xfff7f7f5;
    switch (theme) {
      case "Nord":
        bg = light ? 0xffeceff4 : 0xff2e3440;
        fg = light ? 0xff2e3440 : 0xffeceff4;
        accent = 0xff88c0d0;
        break;
      case "Dracula":
        bg = light ? 0xfff8f8f2 : 0xff282a36;
        fg = light ? 0xff282a36 : 0xfff8f8f2;
        accent = 0xffbd93f9;
        break;
      case "Catppuccin":
        String flavor = library.get("catppuccin", light ? "Latte" : "Mocha");
        bg = flavor.equals("Latte")      ? 0xffeff1f5
            : flavor.equals("Frappe")    ? 0xff303446
            : flavor.equals("Macchiato") ? 0xff24273a
                                         : 0xff1e1e2e;
        fg = flavor.equals("Latte") ? 0xff4c4f69 : 0xffcdd6f4;
        accent = flavor.equals("Latte")  ? 0xff8839ef
            : flavor.equals("Frappe")    ? 0xffca9ee6
            : flavor.equals("Macchiato") ? 0xffc6a0f6
                                         : 0xffcba6f7;
        break;
      case "Tokyo Night":
        bg = light ? 0xffd5d6db : 0xff1a1b26;
        fg = light ? 0xff343b58 : 0xffc0caf5;
        accent = 0xff7aa2f7;
        break;
      case "Ayu":
        bg = light ? 0xfffafafa : 0xff0b0e14;
        fg = light ? 0xff5c6773 : 0xffbfbdb6;
        accent = 0xffffb454;
        break;
      case "Matcha":
        bg = light ? 0xffeef4e9 : 0xff17241c;
        fg = light ? 0xff1f3425 : 0xffe4f2da;
        accent = 0xff9bc581;
        break;
      case "Houston":
        bg = light ? 0xffeaf0f6 : 0xff101b2a;
        fg = light ? 0xff14273b : 0xffedf3f9;
        accent = 0xff47b7e7;
        break;
      case "Monaspace":
        bg = light ? 0xfff6f4f1 : 0xff1c1b1a;
        fg = light ? 0xff282522 : 0xffe7e2dc;
        accent = 0xffd6a6e8;
        break;
      case "Dainty":
        bg = light ? 0xfffff7f2 : 0xff262027;
        fg = light ? 0xff4c3444 : 0xfff9e4ed;
        accent = 0xffe6a8c3;
        break;
      case "TechDweeb":
        bg = light ? 0xfff3f3f3 : 0xff171717;
        fg = light ? 0xff171717 : 0xfff7f7f7;
        switch (library.get("techColor", "orange")) {
          case "purple":
            accent = 0xffb48aff;
            break;
          case "black":
            accent = 0xfff2f2f2;
            break;
          case "red":
            accent = 0xffff6b6b;
            break;
          case "pink":
            accent = 0xffff9ad5;
            break;
          case "grey":
            accent = 0xffb9bbc5;
            break;
          case "green":
            accent = 0xff83c994;
            break;
          case "cyan":
            accent = 0xff6ed4e3;
            break;
          case "blue":
            accent = 0xff80adff;
            break;
          case "dmg":
            accent = 0xff9aa67a;
            break;
          default:
            accent = 0xffff8b2b;
            break;
        }
        break;
    }
    muted = (fg & 0x00ffffff) | 0x99000000;
    return new ThemePalette(bg, fg, muted, accent);
  }
}
