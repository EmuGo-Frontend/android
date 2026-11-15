package org.emugo.frontend;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saves in-app emulator screenshots to ROMs/emugo/screenshots and shows them full screen. */
final class Screenshots {
  static final String FOLDER = "screenshots";

  private Screenshots() {}

  /** Writes a PNG on a background thread. The frame is enlarged by an integer factor. */
  static void save(Activity activity, Library library, Bitmap frame, String title) {
    if (frame == null) {
      PocketNotice.show(activity, "No frame to capture yet");
      return;
    }
    Bitmap source = frame.copy(Bitmap.Config.ARGB_8888, false);
    new Thread(() -> {
      String message;
      try {
        Uri folder = library.ensureFolder(FOLDER);
        if (folder == null) throw new java.io.IOException("Choose a ROMs folder first");
        int scale = Math.max(1, 720 / Math.max(1, source.getHeight()));
        Bitmap scaled = scale == 1 ? source
            : Bitmap.createScaledBitmap(
                source, source.getWidth() * scale, source.getHeight() * scale, false);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date());
        String name = title.replaceAll("[\\\\/:*?\"<>|]", "_") + " - " + stamp + ".png";
        Uri target = DocumentsContract.createDocument(
            activity.getContentResolver(), folder, "image/png", name);
        if (target == null) throw new java.io.IOException("Could not create file");
        try (OutputStream out = activity.getContentResolver().openOutputStream(target, "wt")) {
          scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        message = "Screenshot saved";
      } catch (Exception e) {
        message = L10n.format(activity, "Screenshot failed: %s", String.valueOf(e.getMessage()));
      }
      String text = message;
      activity.runOnUiThread(() -> PocketNotice.show(activity, text));
    }, "EmuGo-screenshot").start();
  }

  /** Newest first. Must be called off the main thread for large folders. */
  static List<Library.Item> list(Library library) {
    ArrayList<Library.Item> out = new ArrayList<>();
    Uri emugo = library.ensureEmugo();
    Uri folder = library.findChild(emugo, FOLDER);
    for (Library.Item item : library.children(folder))
      if (!item.directory() && item.name.toLowerCase(Locale.ROOT).endsWith(".png")) out.add(item);
    out.sort((a, b) -> b.name.substring(Math.max(0, b.name.length() - 23))
        .compareTo(a.name.substring(Math.max(0, a.name.length() - 23))));
    return out;
  }

  static String label(Library.Item item) {
    String name = item.name;
    return name.toLowerCase(Locale.ROOT).endsWith(".png")
        ? name.substring(0, name.length() - 4) : name;
  }

  /** Full-screen viewer. Left/right browse, X deletes, Y shares, B closes. */
  static void view(Activity activity, Library library, List<Library.Item> items, int start,
      Runnable changed) {
    ThemePalette colors = ThemePalette.resolve(activity, library);
    Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
    FrameLayout frame = new FrameLayout(activity);
    frame.setBackgroundColor(0xff000000);
    int[] index = {Math.max(0, Math.min(items.size() - 1, start))};
    Bitmap[] current = {null};
    View image = new View(activity) {
      final Paint paint = new Paint();
      {
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
      }
      @Override
      protected void onDraw(Canvas canvas) {
        Bitmap bitmap = current[0];
        if (bitmap == null) return;
        float fit = Math.min(getWidth() / (float) bitmap.getWidth(),
            getHeight() / (float) bitmap.getHeight());
        float scale = fit >= 1f ? (float) Math.floor(fit) : fit;
        int w = Math.round(bitmap.getWidth() * scale), h = Math.round(bitmap.getHeight() * scale);
        int left = (getWidth() - w) / 2, top = (getHeight() - h) / 2;
        canvas.drawBitmap(bitmap, null, new Rect(left, top, left + w, top + h), paint);
      }
    };
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    TextView caption = new TextView(activity);
    caption.setTextColor(0xffe8e8e8);
    caption.setTextSize(13);
    caption.setShadowLayer(6, 0, 0, 0xff000000);
    try {
      caption.setTypeface(Typeface.createFromAsset(activity.getAssets(), "fonts/nunito.ttf"));
    } catch (Exception ignored) {
    }
    int pad = (int) (14 * activity.getResources().getDisplayMetrics().density);
    caption.setPadding(pad, pad, pad, pad);
    frame.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      if (items.isEmpty()) {
        dialog.dismiss();
        return;
      }
      Library.Item item = items.get(index[0]);
      caption.setText(label(item) + "   " + (index[0] + 1) + "/" + items.size() + "   ·   "
          + buttonName(library, "X") + " " + L10n.text(activity, "Delete") + "   "
          + buttonName(library, "Y") + " " + L10n.text(activity, "Share"));
      new Thread(() -> {
        Bitmap bitmap = null;
        try (InputStream in = activity.getContentResolver().openInputStream(item.uri)) {
          bitmap = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        Bitmap loaded = bitmap;
        activity.runOnUiThread(() -> {
          current[0] = loaded;
          image.invalidate();
        });
      }, "EmuGo-screenshot-load").start();
    };
    dialog.setOnKeyListener((d, key, event) -> {
      if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
      switch (key) {
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_BUTTON_L1:
          if (index[0] > 0) {
            index[0]--;
            load[0].run();
          }
          return true;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
        case KeyEvent.KEYCODE_BUTTON_R1:
          if (index[0] < items.size() - 1) {
            index[0]++;
            load[0].run();
          }
          return true;
        case KeyEvent.KEYCODE_BUTTON_X:
          if (event.getRepeatCount() == 0) confirmDelete(activity, items, index, load[0], changed);
          return true;
        case KeyEvent.KEYCODE_BUTTON_Y:
          if (event.getRepeatCount() == 0) share(activity, items.get(index[0]));
          return true;
        case KeyEvent.KEYCODE_BUTTON_B:
          dialog.dismiss();
          return true;
        default:
          return false;
      }
    });
    image.setOnClickListener(v -> {
      if (index[0] < items.size() - 1) {
        index[0]++;
        load[0].run();
      }
    });
    dialog.setContentView(frame);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setWindowAnimations(0);
      Brightness.apply(activity, window);
      window.getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
    }
    load[0].run();
  }

  private static void confirmDelete(Activity activity, List<Library.Item> items, int[] index,
      Runnable reload, Runnable changed) {
    Library.Item item = items.get(index[0]);
    new PocketDialog.Builder(activity)
        .setTitle("Delete screenshot?")
        .setMessage(label(item))
        .setPositiveButton("Delete", (d, w) -> {
          try {
            DocumentsContract.deleteDocument(activity.getContentResolver(), item.uri);
            items.remove(index[0]);
            index[0] = Math.max(0, Math.min(index[0], items.size() - 1));
            changed.run();
            reload.run();
          } catch (Exception e) {
            PocketNotice.show(activity, String.valueOf(e.getMessage()));
          }
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private static String buttonName(Library library, String button) {
    if (library.get("layout", "Nintendo").equals("PlayStation"))
      return button.equals("X") ? "\u25a1" : "\u25b3";
    return button;
  }

  private static void share(Activity activity, Library.Item item) {
    Intent send = new Intent(Intent.ACTION_SEND)
                      .setType("image/png")
                      .putExtra(Intent.EXTRA_STREAM, item.uri)
                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      activity.startActivity(Intent.createChooser(send, L10n.text(activity, "Share")));
    } catch (Exception e) {
      PocketNotice.show(activity, String.valueOf(e.getMessage()));
    }
  }
}
