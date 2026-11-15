package org.emugo.frontend;

import android.app.Notification;
import android.app.NotificationManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Map;

public final class NotificationService extends NotificationListenerService {
  static final String SYNCTHING_PACKAGE = "com.github.catfriend1.syncthingandroid";
  private static volatile NotificationService connected;
  @Override
  public void onListenerConnected() {
    super.onListenerConnected();
    connected = this;
  }
  @Override
  public void onListenerDisconnected() {
    connected = null;
    super.onListenerDisconnected();
  }
  static StatusBarNotification[] current() {
    NotificationService service = connected;
    if (service == null)
      return null;
    try {
      return service.getActiveNotifications();
    } catch (Exception e) {
      return null;
    }
  }
  private static final Handler main = new Handler(Looper.getMainLooper());
  // What each notification key last showed, so updates of the same notification stay quiet.
  private final Map<String, String> shown = new HashMap<>();

  /** New notifications appear as a banner on the EmuGo! screen in front. */
  @Override
  public void onNotificationPosted(StatusBarNotification item) {
    Notification n = item.getNotification();
    if (n == null || SYNCTHING_PACKAGE.equals(item.getPackageName())) return;
    if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
             | Notification.FLAG_GROUP_SUMMARY)) != 0)
      return;
    if (Notification.CATEGORY_PROGRESS.equals(n.category)
        || Notification.CATEGORY_TRANSPORT.equals(n.category)
        || Notification.CATEGORY_SERVICE.equals(n.category))
      return;
    try {
      Ranking ranking = new Ranking();
      if (getCurrentRanking().getRanking(item.getKey(), ranking)
          && ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT)
        return; // silent and minimized notifications
    } catch (Exception ignored) {
    }
    CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
    CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
    if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) return;
    String content = title + "\n" + text;
    String previous = shown.put(item.getKey(), content);
    if (content.equals(previous)) return;
    if (previous != null && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) return;
    String app = item.getPackageName();
    Drawable icon = null;
    try {
      app = getPackageManager()
                .getApplicationLabel(getPackageManager().getApplicationInfo(app, 0))
                .toString();
      icon = n.getLargeIcon() != null ? n.getLargeIcon().loadDrawable(this)
                                      : getPackageManager().getApplicationIcon(item.getPackageName());
    } catch (Exception ignored) {
    }
    String heading = title == null || title.length() == 0 ? app : title.toString();
    String detail = (text == null ? "" : text.toString());
    if (!heading.equals(app)) detail = app + (detail.isEmpty() ? "" : "  ·  " + detail);
    Drawable picture = icon;
    String body = detail;
    main.post(() -> {
      Banner banner = Banner.active();
      if (banner == null || !new Library(this).get("notificationBanners", true)) return;
      banner.show(heading, body, picture, n.contentIntent);
    });
  }

  @Override
  public void onNotificationRemoved(StatusBarNotification item) {
    shown.remove(item.getKey());
  }

  /** Removes every notification the user could also swipe away in the system shade. */
  static void dismissAll() {
    NotificationService service = connected;
    if (service != null) service.cancelAllNotifications();
  }

  static boolean clearable(StatusBarNotification item) {
    return item.isClearable();
  }

  static void dismiss(String key) {
    NotificationService service = connected;
    if (service != null)
      service.cancelNotification(key);
  }
}
