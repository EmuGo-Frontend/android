package org.emugo.frontend;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import java.io.InputStream;

final class Wallpaper {
  private Wallpaper() {}

  static void apply(Activity activity, Library library, View v) {
    String custom = library.get("wallpaper", "");
    String tech = library.get("techColor", "orange");
    try {
      InputStream in;
      if (!custom.isEmpty())
        in = activity.getContentResolver().openInputStream(Uri.parse(custom));
      else if (library.get("theme", "Default").equals("TechDweeb"))
        in = activity.getAssets().open("wallpapers/" + tech + ".png");
      else
        return;
      Bitmap bitmap = BitmapFactory.decodeStream(in);
      in.close();
      android.graphics.drawable.BitmapDrawable drawable =
          new android.graphics.drawable.BitmapDrawable(activity.getResources(), bitmap);
      drawable.setGravity(Gravity.FILL);
      boolean light = library.get("scheme", "System").equals("Light")
          || (library.get("scheme", "System").equals("System")
              && (activity.getResources().getConfiguration().uiMode
                     & Configuration.UI_MODE_NIGHT_MASK)
                  == Configuration.UI_MODE_NIGHT_NO);
      v.setBackground(new android.graphics.drawable.LayerDrawable(
          new android.graphics.drawable.Drawable[] {drawable,
              new android.graphics.drawable.ColorDrawable(light ? 0xbfffffff : 0xbf000000)}));
    } catch (Exception ignored) {
    }
  }
}
