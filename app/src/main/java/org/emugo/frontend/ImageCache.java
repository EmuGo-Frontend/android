package org.emugo.frontend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Badges, game icons and avatars: memory cache plus a small disk cache in cacheDir. */
final class ImageCache {
  private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(16 << 20) {
    @Override
    protected int sizeOf(String key, Bitmap value) {
      return value.getByteCount();
    }
  };
  private static final ExecutorService workers = Executors.newFixedThreadPool(3);
  private static final Handler main = new Handler(Looper.getMainLooper());

  private ImageCache() {}

  /** Delivers on the main thread; delivers nothing when the image cannot be loaded. */
  static void load(Context context, String url, Consumer<Bitmap> done) {
    if (url == null || url.isEmpty()) return;
    Bitmap cached = memory.get(url);
    if (cached != null) {
      done.accept(cached);
      return;
    }
    File dir = new File(context.getCacheDir(), "images");
    workers.execute(() -> {
      Bitmap image = null;
      File file = new File(dir, key(url));
      try {
        if (file.exists()) image = BitmapFactory.decodeFile(file.getPath());
        if (image == null) {
          dir.mkdirs();
          HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
          c.setConnectTimeout(10000);
          c.setReadTimeout(15000);
          c.setRequestProperty("User-Agent", Version.userAgent(context));
          File part = new File(dir, file.getName() + ".part");
          try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          }
          part.renameTo(file);
          image = BitmapFactory.decodeFile(file.getPath());
        }
      } catch (Exception ignored) {
      }
      if (image == null) return;
      memory.put(url, image);
      Bitmap result = image;
      main.post(() -> done.accept(result));
    });
  }

  /** A downscaled local image (e.g. a screenshot thumbnail), cached in memory only. */
  static void loadThumbnail(Context context, android.net.Uri uri, int width,
      Consumer<Bitmap> done) {
    String key = uri + "#" + width;
    Bitmap cached = memory.get(key);
    if (cached != null) {
      done.accept(cached);
      return;
    }
    workers.execute(() -> {
      Bitmap image = null;
      try {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
          BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
          image = BitmapFactory.decodeStream(in, null, options);
        }
      } catch (Exception ignored) {
      }
      if (image == null) return;
      memory.put(key, image);
      Bitmap result = image;
      main.post(() -> done.accept(result));
    });
  }

  private static String key(String url) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
      StringBuilder out = new StringBuilder();
      for (byte b : digest) out.append(String.format("%02x", b));
      return out.toString();
    } catch (Exception e) {
      return Integer.toHexString(url.hashCode());
    }
  }
}
