package org.emugo.frontend;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** In-game RetroAchievements banners, shown one at a time at the top of the screen. */
final class RaPopup {
  private final Activity activity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayDeque<String[]> queue = new ArrayDeque<>();
  private final LinearLayout panel;
  private final ImageView badge;
  private final TextView heading, detail;
  private boolean showing;

  RaPopup(Activity activity) {
    this.activity = activity;
    float density = activity.getResources().getDisplayMetrics().density;
    Typeface regular = Typeface.DEFAULT, bold = Typeface.DEFAULT_BOLD;
    try {
      regular = Typeface.createFromAsset(activity.getAssets(), "fonts/nunito.ttf");
      bold = Typeface.createFromAsset(activity.getAssets(), "fonts/nunito-bold.ttf");
    } catch (Exception ignored) {
    }
    panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.HORIZONTAL);
    panel.setGravity(Gravity.CENTER_VERTICAL);
    int pad = (int) (10 * density);
    panel.setPadding(pad, pad, (int) (16 * density), pad);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xe6141516);
    background.setCornerRadius(18 * density);
    panel.setBackground(background);
    panel.setVisibility(View.GONE);
    badge = new ImageView(activity);
    LinearLayout.LayoutParams badgeParams =
        new LinearLayout.LayoutParams((int) (44 * density), (int) (44 * density));
    badgeParams.rightMargin = (int) (12 * density);
    panel.addView(badge, badgeParams);
    LinearLayout texts = new LinearLayout(activity);
    texts.setOrientation(LinearLayout.VERTICAL);
    heading = new TextView(activity);
    heading.setTextColor(0xfff7f7f5);
    heading.setTextSize(15);
    heading.setTypeface(bold);
    detail = new TextView(activity);
    detail.setTextColor(0xffb8b8b4);
    detail.setTextSize(13);
    detail.setTypeface(regular);
    detail.setMaxLines(2);
    texts.addView(heading);
    texts.addView(detail);
    panel.addView(texts, new LinearLayout.LayoutParams(-2, -2));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    params.topMargin = (int) (16 * density);
    params.leftMargin = params.rightMargin = (int) (16 * density);
    FrameLayout host = new FrameLayout(activity);
    host.addView(panel, params);
    activity.addContentView(host, new FrameLayout.LayoutParams(-1, -1));
  }

  void show(String title, String text, String imageUrl) {
    queue.add(new String[] {title, text, imageUrl});
    if (!showing) next();
  }

  private void next() {
    String[] item = queue.poll();
    if (item == null) {
      showing = false;
      panel.setVisibility(View.GONE);
      return;
    }
    showing = true;
    heading.setText(item[0]);
    detail.setText(item[1]);
    detail.setVisibility(item[1] == null || item[1].isEmpty() ? View.GONE : View.VISIBLE);
    String url = item[2];
    badge.setImageBitmap(null);
    badge.setVisibility(url == null || url.isEmpty() ? View.GONE : View.VISIBLE);
    ImageCache.load(activity, url, image -> {
      if (heading.getText().toString().equals(item[0])) badge.setImageBitmap(image);
    });
    panel.setVisibility(View.VISIBLE);
    main.postDelayed(this::next, 4000);
  }

  void release() {
    main.removeCallbacksAndMessages(null);
    queue.clear();
  }
}
