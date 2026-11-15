package org.emugo.frontend;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Now-playing controls via the notification listener EmuGo! already uses. */
final class MediaControls {
  private MediaControls() {}

  static MediaController active(Context context) {
    try {
      MediaSessionManager manager =
          (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
      List<MediaController> sessions = manager.getActiveSessions(
          new ComponentName(context, NotificationService.class));
      MediaController fallback = null;
      for (MediaController controller : sessions) {
        if (controller.getMetadata() == null) continue;
        if (playing(controller)) return controller;
        if (fallback == null) fallback = controller;
      }
      return fallback;
    } catch (SecurityException e) {
      return null;
    }
  }

  static boolean playing(MediaController controller) {
    PlaybackState state = controller.getPlaybackState();
    return state != null && state.getState() == PlaybackState.STATE_PLAYING;
  }

  static String label(Context context, MediaController controller) {
    MediaMetadata data = controller.getMetadata();
    String title = data == null ? null : data.getString(MediaMetadata.METADATA_KEY_TITLE);
    String artist = data == null ? null : data.getString(MediaMetadata.METADATA_KEY_ARTIST);
    String text = title == null ? L10n.text(context, "Unknown track") : title;
    if (artist != null && !artist.isEmpty()) text += " – " + artist;
    return (playing(controller) ? "⏸ " : "▶ ") + text;
  }

  static void toggle(MediaController controller) {
    if (playing(controller)) controller.getTransportControls().pause();
    else controller.getTransportControls().play();
  }

  static void next(MediaController controller) {
    controller.getTransportControls().skipToNext();
  }

  static void previous(MediaController controller) {
    controller.getTransportControls().skipToPrevious();
  }
}
