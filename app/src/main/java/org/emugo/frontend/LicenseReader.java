package org.emugo.frontend;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A full-screen, controller-friendly reader for license texts. */
final class LicenseReader {
  private LicenseReader() {}

  static void open(Activity activity, Library library, String file, String name, String license) {
    String raw;
    try (InputStream in = activity.getAssets().open("notices/" + file)) {
      byte[] data = new byte[in.available()];
      int count = in.read(data);
      raw = new String(data, 0, Math.max(0, count), StandardCharsets.UTF_8);
    } catch (Exception e) {
      PocketNotice.show(activity, String.valueOf(e.getMessage()));
      return;
    }
    ThemePalette colors = ThemePalette.resolve(activity, library);
    float density = activity.getResources().getDisplayMetrics().density;
    Typeface regular = font(activity, "fonts/nunito.ttf"), bold = font(activity, "fonts/nunito-bold.ttf");

    ScrollView scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.setFillViewport(true);
    LinearLayout column = new LinearLayout(activity);
    column.setOrientation(LinearLayout.VERTICAL);
    int side = (int) (24 * density);
    column.setPadding(side, (int) (48 * density), side, (int) (32 * density));

    TextView title = new TextView(activity);
    title.setText(name);
    title.setTextSize(24);
    title.setTypeface(bold);
    title.setTextColor(colors.foreground);
    column.addView(title);
    TextView subtitle = new TextView(activity);
    subtitle.setText(license);
    subtitle.setTextSize(15);
    subtitle.setTypeface(regular);
    subtitle.setTextColor(colors.muted);
    subtitle.setPadding(0, (int) (4 * density), 0, (int) (24 * density));
    column.addView(subtitle);

    TextView body = new TextView(activity);
    body.setText(format(raw));
    body.setTextSize(15);
    body.setTypeface(regular);
    body.setTextColor(colors.foreground);
    body.setLineSpacing(0, 1.35f);
    column.addView(body);

    int width = Math.min((int) (720 * density),
        activity.getResources().getDisplayMetrics().widthPixels);
    scroll.addView(column, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL));

    TextView hint = new TextView(activity);
    hint.setText(hint(activity, library));
    hint.setTextSize(12);
    hint.setTypeface(regular);
    hint.setTextColor(colors.muted);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(0, (int) (8 * density), 0, (int) (10 * density));
    hint.setBackgroundColor(colors.background);

    FrameLayout frame = new FrameLayout(activity);
    frame.setBackgroundColor(colors.background);
    FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1);
    scrollParams.bottomMargin = (int) (36 * density);
    frame.addView(scroll, scrollParams);
    frame.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

    int line = (int) (body.getLineHeight() * 1.35f * 3);
    boolean[] triggers = new boolean[2];
    Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
      @Override
      public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int page = Math.max(line, scroll.getHeight() - line);
        switch (event.getKeyCode()) {
          case KeyEvent.KEYCODE_DPAD_UP:
            scroll.scrollBy(0, -line);
            return true;
          case KeyEvent.KEYCODE_DPAD_DOWN:
            scroll.scrollBy(0, line);
            return true;
          case KeyEvent.KEYCODE_BUTTON_L1:
          case KeyEvent.KEYCODE_BUTTON_L2:
          case KeyEvent.KEYCODE_PAGE_UP:
          case KeyEvent.KEYCODE_DPAD_LEFT:
            scroll.scrollBy(0, -page);
            return true;
          case KeyEvent.KEYCODE_BUTTON_R1:
          case KeyEvent.KEYCODE_BUTTON_R2:
          case KeyEvent.KEYCODE_PAGE_DOWN:
          case KeyEvent.KEYCODE_DPAD_RIGHT:
            scroll.scrollBy(0, page);
            return true;
          case KeyEvent.KEYCODE_BUTTON_B:
          case KeyEvent.KEYCODE_BACK:
          case KeyEvent.KEYCODE_BUTTON_A:
            if (event.getRepeatCount() == 0) dismiss();
            return true;
          default:
            return super.dispatchKeyEvent(event);
        }
      }
      @Override
      public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE
            && ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)) {
          float[] values = {
              Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                  event.getAxisValue(MotionEvent.AXIS_BRAKE)),
              Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                  event.getAxisValue(MotionEvent.AXIS_GAS))};
          int page = Math.max(line, scroll.getHeight() - line);
          for (int i = 0; i < 2; i++) {
            if (!triggers[i] && values[i] >= 0.55f) {
              triggers[i] = true;
              scroll.scrollBy(0, i == 0 ? -page : page);
            } else if (values[i] <= 0.25f) {
              triggers[i] = false;
            }
          }
        }
        // Not consumed: Android turns hat D-pads and the left stick into D-pad key presses
        // (with repeat), which dispatchKeyEvent scrolls.
        return super.dispatchGenericMotionEvent(event);
      }
    };
    dialog.setContentView(frame);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setWindowAnimations(0);
      window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
      Brightness.apply(activity, window);
    }
  }

  private static String hint(Activity activity, Library library) {
    String layout = library.get("layout", "Nintendo");
    String page = layout.equals("PlayStation") ? "L2/R2"
        : layout.equals("Nintendo") ? "ZL/ZR" : "LT/RT";
    String back = layout.equals("PlayStation") ? "○" : "B";
    return activity.getString(R.string.d_pad) + "  " + activity.getString(R.string.scroll) + "     "
        + page + "  " + activity.getString(R.string.page) + "     " + back + "  "
        + activity.getString(R.string.close);
  }

  /**
   * License files are hard-wrapped at ~70 columns. Re-flow wrapped lines into paragraphs so the
   * text fits any width, keep short lines (titles, list items, "Website:" lines) as they are, and
   * make a component's name bold when a "Website:" or "License:" line follows it.
   */
  static CharSequence format(String raw) {
    String[] lines = raw.replace("\r", "").split("\n", -1);
    SpannableStringBuilder out = new SpannableStringBuilder();
    StringBuilder paragraph = new StringBuilder();
    int previousLength = 0;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        flush(out, paragraph);
        previousLength = 0;
        continue;
      }
      String next = i + 1 < lines.length ? lines[i + 1].trim() : "";
      boolean heading = next.startsWith("Website:") || next.startsWith("License:")
          || (i == 0 && line.length() < 60);
      if (heading) {
        flush(out, paragraph);
        if (out.length() > 0) out.append("\n");
        int start = out.length();
        out.append(line);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.12f), start, out.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");
        previousLength = 0;
        continue;
      }
      boolean listItem = line.matches("^([-*•]|\\(?[0-9a-zA-Z]{1,3}[.)]) .*");
      boolean field = line.matches("^(Website|License|Copyright)\\b.*");
      if (paragraph.length() == 0) {
        paragraph.append(line);
      } else if (previousLength >= 55 && !listItem && !field) {
        paragraph.append(' ').append(line); // a hard-wrapped continuation
      } else {
        paragraph.append('\n').append(line);
      }
      previousLength = line.length();
    }
    flush(out, paragraph);
    return out;
  }

  private static void flush(SpannableStringBuilder out, StringBuilder paragraph) {
    if (paragraph.length() == 0) return;
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append("\n");
    if (out.length() > 0) out.append("\n");
    out.append(paragraph);
    paragraph.setLength(0);
  }

  private static Typeface font(Activity activity, String path) {
    try {
      return Typeface.createFromAsset(activity.getAssets(), path);
    } catch (Exception e) {
      return Typeface.DEFAULT;
    }
  }
}
