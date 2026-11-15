package org.emugo.frontend;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Static, controller-friendly menus that share the launcher's visual language. */
final class PocketDialog {
  private PocketDialog() {}

  static final class Builder {
    private final Activity activity;
    private String title;
    private String message;
    private String[] items;
    private int selectedIndex = -1;
    private DialogInterface.OnClickListener itemClick;
    private String positiveLabel;
    private DialogInterface.OnClickListener positiveClick;
    private String negativeLabel;
    private DialogInterface.OnClickListener negativeClick;
    private DialogInterface.OnCancelListener cancelListener;
    private View customView;
    private boolean dismissOnItemClick = true;
    private Dialog activeDialog;
    private LinearLayout activeContent;
    private RowScrollView activeScroll;
    private Runnable backAction;
    private RichRow.Entry[] entries;
    private java.util.function.IntConsumer entryClick;
    private boolean leftKey, rightKey, leftAxis, rightAxis;
    private ThemePalette activeColors;
    private Typeface regularFont, boldFont;

    Builder(Activity activity) {
      this.activity = activity;
    }

    Builder setTitle(int value) {
      return setTitle(activity.getString(value));
    }

    Builder setTitle(String value) {
      title = value;
      return this;
    }

    Builder setMessage(int value) {
      return setMessage(activity.getString(value));
    }

    Builder setMessage(String value) {
      message = value;
      return this;
    }

    Builder setView(View value) {
      customView = value;
      return this;
    }

    Builder setItems(String[] values, DialogInterface.OnClickListener click) {
      items = values;
      itemClick = click;
      selectedIndex = -1;
      return this;
    }

    Builder setSingleChoiceItems(
        String[] values, int selected, DialogInterface.OnClickListener click) {
      items = values;
      itemClick = click;
      selectedIndex = selected;
      return this;
    }

    Builder setPositiveButton(int label, DialogInterface.OnClickListener click) {
      return setPositiveButton(activity.getString(label), click);
    }

    Builder setPositiveButton(String label, DialogInterface.OnClickListener click) {
      positiveLabel = label;
      positiveClick = click;
      return this;
    }

    Builder setNegativeButton(int label, DialogInterface.OnClickListener click) {
      return setNegativeButton(activity.getString(label), click);
    }

    Builder setNegativeButton(String label, DialogInterface.OnClickListener click) {
      negativeLabel = label;
      negativeClick = click;
      return this;
    }

    Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
      cancelListener = listener;
      return this;
    }

    /** Runs instead of cancelling when B or Android back is pressed. */
    Builder setOnBack(Runnable action) {
      backAction = action;
      return this;
    }

    Builder keepOpenOnItemClick() {
      dismissOnItemClick = false;
      return this;
    }

    void replacePage(String pageTitle, String[] values, int selected,
        DialogInterface.OnClickListener click) {
      title = pageTitle;
      message = null;
      customView = null;
      positiveLabel = null;
      negativeLabel = null;
      items = values;
      selectedIndex = selected;
      itemClick = click;
      entries = null;
      if (activeDialog != null) renderPage();
    }

    /** A page of rich rows (image, title, detail, trailing value) under an optional summary. */
    void replaceEntries(String pageTitle, String summary, RichRow.Entry[] values,
        java.util.function.IntConsumer click) {
      title = pageTitle;
      message = summary;
      customView = null;
      positiveLabel = null;
      negativeLabel = null;
      items = null;
      entries = values;
      entryClick = click;
      if (activeDialog != null) renderPage();
    }

    Dialog show() {
      Library library = new Library(activity);
      activeColors = ThemePalette.resolve(activity, library);
      regularFont = font("fonts/nunito.ttf");
      boldFont = font("fonts/nunito-bold.ttf");
      Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
          int key = event.getKeyCode();
          boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
          if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (getCurrentFocus() instanceof EditText && key == KeyEvent.KEYCODE_BACK)
              return super.dispatchKeyEvent(event);
            if (down && event.getRepeatCount() == 0) {
              if (backAction != null) backAction.run();
              else cancel();
            }
            return true;
          }
          if (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
            trigger(key == KeyEvent.KEYCODE_BUTTON_L2, false, down);
            return true;
          }
          return super.dispatchKeyEvent(event);
        }
        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
          int source = event.getSource();
          if (event.getActionMasked() == MotionEvent.ACTION_MOVE
              && ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                  || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)) {
            axis(true, Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)));
            axis(false, Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)));
          }
          return super.dispatchGenericMotionEvent(event);
        }
      };
      if (cancelListener != null)
        dialog.setOnCancelListener(cancelListener);

      FrameLayout frame = new FrameLayout(activity);
      frame.setBackgroundColor(activeColors.background);
      RowScrollView scroll = new RowScrollView(activity);
      frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
      LinearLayout content = new LinearLayout(activity);
      content.setOrientation(LinearLayout.VERTICAL);
      content.setPadding(dp(8), dp(72), dp(8), dp(48));
      int width =
          Math.min(dp(390), activity.getResources().getDisplayMetrics().widthPixels - dp(48));
      scroll.addView(content,
          new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
      activeDialog = dialog;
      activeContent = content;
      activeScroll = scroll;
      renderPage();
      dialog.setContentView(frame);
      dialog.show();
      Window window = dialog.getWindow();
      if (window != null) {
        window.setLayout(-1, -1);
        window.setWindowAnimations(0);
        Brightness.apply(activity, window);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      }
      return dialog;
    }

    private void renderPage() {
      LinearLayout content = activeContent;
      Dialog dialog = activeDialog;
      ThemePalette colors = activeColors;
      Typeface regular = regularFont;
      Typeface bold = boldFont;
      content.removeAllViews();
      activeScroll.scrollTo(0, 0);
      if (title != null) {
        TextView heading = label(title, 22, colors.foreground, bold);
        content.addView(heading);
        gap(content, 22);
      }
      if (message != null) {
        TextView explanation = label(message, 14, colors.muted, regular);
        content.addView(explanation);
        gap(content, 20);
      }
      if (customView != null) {
        styleFields(customView, colors, regular);
        if (customView.getParent() instanceof ViewGroup)
          ((ViewGroup) customView.getParent()).removeView(customView);
        content.addView(customView);
        gap(content, 18);
      }

      View preferred = null;
      if (entries != null) {
        for (int index = 0; index < entries.length; index++) {
          final int choice = index;
          View row = RichRow.create(activity, colors, regular, bold, entries[index],
              () -> {
                if (entryClick != null) entryClick.accept(choice);
              });
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
          params.bottomMargin = dp(2);
          content.addView(row, params);
          if (preferred == null && row.isFocusable()) preferred = row;
        }
      }
      if (items != null) {
        for (int index = 0; index < items.length; index++) {
          final int choice = index;
          View row = option(content, items[index], colors, regular, index == selectedIndex, () -> {
            if (itemClick != null)
              itemClick.onClick(dialog, choice);
            if (dismissOnItemClick) dialog.dismiss();
          });
          if (preferred == null)
            preferred = row;
        }
      }
      if (positiveLabel != null) {
        View row = option(content, positiveLabel, colors, regular, false, () -> {
          if (positiveClick != null)
            positiveClick.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
          dialog.dismiss();
        });
        if (preferred == null && items == null)
          preferred = row;
      }
      if (negativeLabel != null)
        option(content, negativeLabel, colors, regular, false, () -> {
          if (negativeClick != null)
            negativeClick.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
          dialog.dismiss();
        });

      if (preferred != null) {
        View first = preferred;
        if (dialog.isShowing()) first.requestFocus();
        else first.post(() -> first.requestFocus());
      }
    }

    private void axis(boolean left, float value) {
      boolean pressed = left ? leftAxis : rightAxis;
      if (value >= 0.55f) pressed = true;
      else if (value <= 0.25f) pressed = false;
      trigger(left, true, pressed);
    }

    /** LT/RT jump three rows, like the launcher menus. */
    private void trigger(boolean left, boolean axis, boolean pressed) {
      boolean was = left ? leftKey || leftAxis : rightKey || rightAxis;
      if (left) {
        if (axis) leftAxis = pressed;
        else leftKey = pressed;
      } else {
        if (axis) rightAxis = pressed;
        else rightKey = pressed;
      }
      boolean is = left ? leftKey || leftAxis : rightKey || rightAxis;
      if (!was && is && activeScroll != null) activeScroll.jump(left ? -3 : 3);
    }

    private Typeface font(String path) {
      try {
        return Typeface.createFromAsset(activity.getAssets(), path);
      } catch (Exception ignored) {
        return Typeface.DEFAULT;
      }
    }

    private int dp(float value) {
      return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String value, int size, int color, Typeface face) {
      TextView text = new TextView(activity);
      text.setText(value);
      text.setTextSize(size);
      text.setTextColor(color);
      text.setTypeface(face);
      return text;
    }

    private void gap(LinearLayout content, int height) {
      content.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private View option(
        LinearLayout content, String name, ThemePalette colors, Typeface face,
        boolean selected, Runnable action) {
      TextView row = label(name, 17, colors.foreground, face);
      int checkId = activity.getResources().getIdentifier(
          "tabler_check", "drawable", activity.getPackageName());
      Drawable check = selected && checkId != 0 ? activity.getDrawable(checkId).mutate() : null;
      if (check != null) {
        check.setBounds(0, 0, dp(18), dp(18));
        check.setTint(colors.foreground);
        row.setCompoundDrawablesRelative(null, null, check, null);
        row.setContentDescription(name + ", " + activity.getString(R.string.selected));
      }
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(14), dp(6), dp(14), dp(6));
      row.setMinHeight(dp(38));
      row.setFocusable(true);
      row.setFocusableInTouchMode(true);
      row.setDefaultFocusHighlightEnabled(false);
      row.setStateListAnimator(null);
      row.setForeground(null);
      row.setOnClickListener(v -> action.run());
      row.setOnFocusChangeListener((v, focused) -> {
        int selectedText = Color.luminance(colors.accent) > 0.5
            ? 0xff101112 : 0xfff7f7f5;
        row.setTextColor(focused ? selectedText : colors.foreground);
        if (check != null) check.setTint(focused ? selectedText : colors.foreground);
        if (focused) {
          GradientDrawable pill = new GradientDrawable();
          pill.setColor(colors.accent);
          pill.setCornerRadius(dp(40));
          row.setBackground(pill);
        } else {
          row.setBackground(null);
        }
      });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.bottomMargin = dp(2);
      content.addView(row, params);
      return row;
    }

    private void styleFields(View view, ThemePalette colors, Typeface face) {
      if (view instanceof EditText) {
        EditText field = (EditText) view;
        field.setTypeface(face);
        field.setTextColor(colors.foreground);
        field.setHintTextColor(colors.muted);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(colors.background);
        outline.setStroke(dp(1), colors.muted);
        field.setBackground(outline);
      }
      if (view instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++)
          styleFields(group.getChildAt(index), colors, face);
      }
    }
  }
}
