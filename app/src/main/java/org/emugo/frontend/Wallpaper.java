package org.emugo.frontend;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.InputStream;

final class Wallpaper {
  private Wallpaper() {}

  /**
   * Draws the wallpaper behind {@code content}, which must already sit inside {@code frame}.
   * Returns the playing video wallpaper, or null for an image or no wallpaper.
   */
  static Video apply(Activity activity, Library library, FrameLayout frame, View content) {
    String custom = library.get("wallpaper", "");
    String tech = library.get("techColor", "orange");
    boolean light = library.get("scheme", "System").equals("Light")
        || (library.get("scheme", "System").equals("System")
            && (activity.getResources().getConfiguration().uiMode
                   & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_NO);
    ColorDrawable tint = new ColorDrawable(light ? 0xbfffffff : 0xbf000000);
    if (!custom.isEmpty() && isVideo(activity, Uri.parse(custom))) {
      Video video = new Video(activity, Uri.parse(custom));
      frame.addView(video.view, 0,
          new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      content.setBackground(tint);
      return video;
    }
    try {
      if (custom.isEmpty() && !library.get("theme", "Default").equals("TechDweeb"))
        return null;
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inJustDecodeBounds = true;
      InputStream in = open(activity, custom, tech);
      BitmapFactory.decodeStream(in, null, opts);
      in.close();
      android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
      int target = Math.max(dm.widthPixels, dm.heightPixels);
      int sample = 1;
      while (Math.min(opts.outWidth, opts.outHeight) / (sample * 2) >= target)
        sample *= 2;
      opts = new BitmapFactory.Options();
      opts.inSampleSize = sample;
      in = open(activity, custom, tech);
      Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
      in.close();
      if (bitmap == null)
        return null;
      content.setBackground(new LayerDrawable(new Drawable[] {new CenterCrop(bitmap), tint}));
    } catch (Exception ignored) {
    }
    return null;
  }

  static boolean isVideo(Activity activity, Uri uri) {
    try {
      String type = activity.getContentResolver().getType(uri);
      return type != null && type.startsWith("video/");
    } catch (Exception e) {
      return false;
    }
  }

  private static InputStream open(Activity activity, String custom, String tech)
      throws java.io.IOException {
    if (!custom.isEmpty())
      return activity.getContentResolver().openInputStream(Uri.parse(custom));
    return activity.getAssets().open("wallpapers/" + tech + ".png");
  }

  private static final class CenterCrop extends Drawable {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();

    CenterCrop(Bitmap bitmap) {
      this.bitmap = bitmap;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
      int bw = bitmap.getWidth(), bh = bitmap.getHeight();
      int vw = bounds.width(), vh = bounds.height();
      if (vw <= 0 || vh <= 0) {
        src.set(0, 0, bw, bh);
        return;
      }
      if ((long) bw * vh > (long) bh * vw) {
        int w = (int) ((long) bh * vw / vh);
        int x = (bw - w) / 2;
        src.set(x, 0, x + w, bh);
      } else {
        int h = (int) ((long) bw * vh / vw);
        int y = (bh - h) / 2;
        src.set(0, y, bw, y + h);
      }
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.drawBitmap(bitmap, src, getBounds(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return bitmap.hasAlpha() ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }
  }

  /** A muted, looping video scaled to cover its view and cropped evenly on both sides. */
  static final class Video implements TextureView.SurfaceTextureListener {
    final TextureView view;
    private final Activity activity;
    private final Uri uri;
    private MediaPlayer player;
    private Surface surface;
    private boolean paused;
    private int videoWidth, videoHeight;

    Video(Activity activity, Uri uri) {
      this.activity = activity;
      this.uri = uri;
      view = new TextureView(activity);
      view.setSurfaceTextureListener(this);
      view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> crop());
    }

    void pause() {
      paused = true;
      try {
        if (player != null && player.isPlaying())
          player.pause();
      } catch (Exception ignored) {
      }
    }

    void resume() {
      paused = false;
      try {
        if (player != null && !player.isPlaying())
          player.start();
      } catch (Exception ignored) {
      }
    }

    void release() {
      if (player != null) {
        player.release();
        player = null;
      }
      if (surface != null) {
        surface.release();
        surface = null;
      }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
      release();
      surface = new Surface(texture);
      try {
        player = new MediaPlayer();
        player.setDataSource(activity, uri);
        player.setSurface(surface);
        player.setLooping(true);
        player.setVolume(0f, 0f);
        player.setOnVideoSizeChangedListener((mp, w, h) -> {
          videoWidth = w;
          videoHeight = h;
          crop();
        });
        player.setOnErrorListener((mp, what, extra) -> true);
        player.setOnPreparedListener(mp -> {
          if (!paused)
            mp.start();
        });
        player.prepareAsync();
      } catch (Exception e) {
        release();
      }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
      crop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
      release();
      return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    // TextureView stretches the video to its own size; undo that and scale it to cover instead.
    private void crop() {
      int vw = view.getWidth(), vh = view.getHeight();
      if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0)
        return;
      float scale = Math.max((float) vw / videoWidth, (float) vh / videoHeight);
      Matrix matrix = new Matrix();
      matrix.setScale(videoWidth * scale / vw, videoHeight * scale / vh, vw / 2f, vh / 2f);
      view.setTransform(matrix);
    }
  }
}
