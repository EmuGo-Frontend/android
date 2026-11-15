package org.emugo.frontend;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A menu row with an image, a title, a muted detail line and a trailing value. */
final class RichRow {
  static final class Entry {
    final String title, detail, trailing, imageUrl;
    final boolean dimmed, header;
    boolean round;

    Entry(String title, String detail, String trailing, String imageUrl, boolean dimmed) {
      this(title, detail, trailing, imageUrl, dimmed, false);
    }

    private Entry(String title, String detail, String trailing, String imageUrl, boolean dimmed,
        boolean header) {
      this.title = title;
      this.detail = detail;
      this.trailing = trailing;
      this.imageUrl = imageUrl;
      this.dimmed = dimmed;
      this.header = header;
    }

    /** Shows the image as a circle, e.g. for avatars. */
    Entry rounded() {
      round = true;
      return this;
    }

    static Entry header(String title) {
      return new Entry(title, null, null, null, false, true);
    }
  }

  private RichRow() {}

  private static int dp(Context context, float value) {
    return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
  }

  /** A section label; not focusable. */
  static View header(Context context, ThemePalette colors, Typeface bold, String text) {
    TextView label = new TextView(context);
    label.setText(L10n.text(context, text));
    label.setTextSize(14);
    label.setTypeface(bold);
    label.setTextColor(colors.muted);
    label.setPadding(dp(context, 14), dp(context, 16), dp(context, 14), dp(context, 5));
    return label;
  }

  static View create(Context context, ThemePalette colors, Typeface regular, Typeface bold,
      Entry entry, Runnable action) {
    if (entry.header) return header(context, colors, bold, entry.title);
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(context, 10), dp(context, 7), dp(context, 16), dp(context, 7));
    row.setMinimumHeight(dp(context, 56));
    row.setFocusable(true);
    row.setFocusableInTouchMode(true);
    row.setDefaultFocusHighlightEnabled(false);
    ImageView image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    GradientDrawable placeholder = new GradientDrawable();
    placeholder.setColor(Color.argb(40, Color.red(colors.muted), Color.green(colors.muted),
        Color.blue(colors.muted)));
    placeholder.setCornerRadius(dp(context, entry.round ? 21 : 8));
    int corner = dp(context, 8);
    image.setOutlineProvider(new android.view.ViewOutlineProvider() {
      @Override
      public void getOutline(View view, android.graphics.Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
      }
    });
    if (entry.round) {
      image.setOutlineProvider(new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
          outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
      });
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }
    image.setBackground(placeholder);
    image.setClipToOutline(true);
    LinearLayout.LayoutParams imageParams =
        new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42));
    imageParams.rightMargin = dp(context, 12);
    if (entry.imageUrl != null) {
      row.addView(image, imageParams);
      if (entry.dimmed) {
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0);
        image.setColorFilter(new ColorMatrixColorFilter(gray));
        image.setImageAlpha(150);
      }
      ImageCache.load(context, entry.imageUrl, bitmap -> {
        image.setImageBitmap(bitmap);
        image.setBackground(null);
      });
    }
    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView title = new TextView(context);
    title.setText(entry.title);
    title.setTextSize(16);
    title.setTypeface(bold);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(title);
    TextView detail = null;
    if (entry.detail != null && !entry.detail.isEmpty()) {
      detail = new TextView(context);
      detail.setText(entry.detail);
      detail.setTextSize(13);
      detail.setTypeface(regular);
      detail.setMaxLines(2);
      detail.setEllipsize(TextUtils.TruncateAt.END);
      texts.addView(detail);
    }
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView trailing = null;
    if (entry.trailing != null && !entry.trailing.isEmpty()) {
      trailing = new TextView(context);
      trailing.setText(entry.trailing);
      trailing.setTextSize(13);
      trailing.setTypeface(bold);
      trailing.setPadding(dp(context, 10), 0, 0, 0);
      row.addView(trailing);
    }
    int selectedText = Color.luminance(colors.accent) > 0.5 ? 0xff101112 : 0xfff7f7f5;
    TextView detailView = detail, trailingView = trailing;
    View.OnFocusChangeListener paint = (v, focused) -> {
      int main = focused ? selectedText : colors.foreground;
      int sub = focused ? selectedText : colors.muted;
      title.setTextColor(main);
      title.setAlpha(entry.dimmed && !focused ? 0.65f : 1f);
      if (detailView != null) detailView.setTextColor(sub);
      if (trailingView != null) trailingView.setTextColor(main);
      if (focused) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(colors.accent);
        pill.setCornerRadius(dp(context, 22));
        row.setBackground(pill);
      } else {
        row.setBackground(null);
      }
    };
    paint.onFocusChange(row, false);
    row.setOnFocusChangeListener(paint);
    row.setOnClickListener(v -> {
      if (action != null) action.run();
    });
    row.setOnKeyListener((v, key, e) -> {
      if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0
          && (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
              || key == KeyEvent.KEYCODE_ENTER)) {
        if (action != null) action.run();
        return true;
      }
      return false;
    });
    return row;
  }
}
