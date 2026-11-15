package org.emugo.frontend;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

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
  static void dismiss(String key) {
    NotificationService service = connected;
    if (service != null)
      service.cancelNotification(key);
  }
}
