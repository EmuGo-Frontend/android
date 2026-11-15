package org.emugo.frontend;

import android.app.Activity;
import android.app.PendingIntent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayDeque;

/**
 * Banners at the top center of EmuGo!'s screens, one at a time: RetroAchievements events and
 * Android notifications (including EmuGo!'s own update notice). The banner of the activity in
 * front is {@link #active()}.
 */
final class Banner {
  private static final class Item {
    String title, text, imageUrl;
    Drawable icon;
    PendingIntent action;
  }

  private static Banner active;
  private final Activity activity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayDeque<Item> queue = new ArrayDeque<>();
  private final FrameLayout host;
  private final LinearLayout panel;
  private final ImageView badge;
  private final TextView heading, detail;
  private Item current;
  private boolean showing;

  Banner(Activity activity) {
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
    panel.setOnClickListener(v -> {
      Item item = current;
      if (item == null || item.action == null) return;
      try {
        item.action.send();
      } catch (PendingIntent.CanceledException ignored) {
      }
      dismissCurrent();
    });
    badge = new ImageView(activity);
    badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int corner = (int) (8 * density);
    badge.setOutlineProvider(new android.view.ViewOutlineProvider() {
      @Override
      public void getOutline(View view, android.graphics.Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
      }
    });
    badge.setClipToOutline(true);
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
    heading.setSingleLine(true);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    detail = new TextView(activity);
    detail.setTextColor(0xffb8b8b4);
    detail.setTextSize(13);
    detail.setTypeface(regular);
    detail.setMaxLines(2);
    detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
    texts.addView(heading);
    texts.addView(detail);
    panel.addView(texts, new LinearLayout.LayoutParams(-2, -2));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    params.topMargin = (int) (16 * density);
    params.leftMargin = params.rightMargin = (int) (16 * density);
    heading.setMaxWidth((int) (480 * density));
    detail.setMaxWidth((int) (480 * density));
    host = new FrameLayout(activity);
    host.addView(panel, params);
  }

  /** The banner of the EmuGo! screen currently in front, or null. */
  static Banner active() {
    return active;
  }

  /** Call from onResume / onPause of the owning activity. */
  void activate() {
    active = this;
  }

  void deactivate() {
    if (active == this) active = null;
  }

  /** A RetroAchievements-style banner with an optional image URL. */
  void show(String title, String text, String imageUrl) {
    Item item = new Item();
    item.title = title;
    item.text = text;
    item.imageUrl = imageUrl;
    enqueue(item);
  }

  /** An Android notification: its icon, title, text and tap action. */
  void show(String title, String text, Drawable icon, PendingIntent action) {
    Item item = new Item();
    item.title = title;
    item.text = text;
    item.icon = icon;
    item.action = action;
    enqueue(item);
  }

  private void enqueue(Item item) {
    queue.add(item);
    if (!showing) next();
  }

  // setContentView (e.g. after a theme change) drops overlays, so re-attach when needed.
  private void attach() {
    if (host.getParent() != null && host.isAttachedToWindow()) return;
    if (host.getParent() instanceof ViewGroup) ((ViewGroup) host.getParent()).removeView(host);
    activity.addContentView(host, new FrameLayout.LayoutParams(-1, -1));
  }

  private void dismissCurrent() {
    main.removeCallbacksAndMessages(null);
    next();
  }

  private void next() {
    Item item = queue.poll();
    current = item;
    if (item == null) {
      showing = false;
      panel.setVisibility(View.GONE);
      return;
    }
    showing = true;
    attach();
    heading.setText(item.title);
    detail.setText(item.text);
    detail.setVisibility(item.text == null || item.text.isEmpty() ? View.GONE : View.VISIBLE);
    panel.setClickable(item.action != null);
    if (item.icon != null) {
      badge.setImageDrawable(item.icon);
      badge.setVisibility(View.VISIBLE);
    } else {
      badge.setImageBitmap(null);
      String url = item.imageUrl;
      badge.setVisibility(url == null || url.isEmpty() ? View.GONE : View.VISIBLE);
      ImageCache.load(activity, url, image -> {
        if (current == item) badge.setImageBitmap(image);
      });
    }
    panel.setVisibility(View.VISIBLE);
    panel.bringToFront();
    main.postDelayed(this::next, 4500);
  }

  void release() {
    deactivate();
    main.removeCallbacksAndMessages(null);
    queue.clear();
  }
}
