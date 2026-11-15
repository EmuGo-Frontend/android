package org.emugo.frontend;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A static message overlay in place of Android Toast styling. */
final class PocketNotice {
  private static final String TAG = "emugo-notice";

  private PocketNotice() {}

  static void show(Activity activity, String message) {
    FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
    for (int index = decor.getChildCount() - 1; index >= 0; index--) {
      View child = decor.getChildAt(index);
      if (TAG.equals(child.getTag()))
        decor.removeView(child);
    }
    ThemePalette colors = ThemePalette.resolve(activity, new Library(activity));
    TextView notice = new TextView(activity);
    notice.setTag(TAG);
    notice.setText(L10n.text(activity, message));
    notice.setTextColor(colors.background);
    notice.setTextSize(14);
    try {
      notice.setTypeface(Typeface.createFromAsset(activity.getAssets(), "fonts/nunito.ttf"));
    } catch (Exception ignored) {
    }
    int density = (int) (activity.getResources().getDisplayMetrics().density * 12 + 0.5f);
    notice.setPadding(density, density, density, density);
    GradientDrawable background = new GradientDrawable();
    background.setColor(colors.foreground);
    background.setCornerRadius(density * 2);
    notice.setBackground(background);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    params.leftMargin = density;
    params.rightMargin = density;
    params.bottomMargin = density * 3;
    decor.addView(notice, params);
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      if (notice.getParent() == decor)
        decor.removeView(notice);
    }, 3000);
  }
}
