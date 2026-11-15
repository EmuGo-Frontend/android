package org.emugo.frontend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small controller button drawings used by the launcher footer. */
final class ControllerGlyph extends View {
  private final String button;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  ControllerGlyph(Context context, String button, int color) {
    super(context);
    this.button = button;
    paint.setColor(color);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    setContentDescription(button);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.scale(getHeight() / 24f, getHeight() / 24f);
    paint.setStyle(Paint.Style.STROKE);
    if (button.equals("DPAD")) {
      Path cross = new Path();
      cross.moveTo(9, 2);
      cross.lineTo(15, 2);
      cross.lineTo(15, 9);
      cross.lineTo(22, 9);
      cross.lineTo(22, 15);
      cross.lineTo(15, 15);
      cross.lineTo(15, 22);
      cross.lineTo(9, 22);
      cross.lineTo(9, 15);
      cross.lineTo(2, 15);
      cross.lineTo(2, 9);
      cross.lineTo(9, 9);
      cross.close();
      canvas.drawPath(cross, paint);
    } else if (button.equals("PS_SQUARE")) {
      canvas.drawCircle(12, 12, 10, paint);
      canvas.drawRect(8, 8, 16, 16, paint);
    } else if (button.equals("PS_CROSS") || button.equals("PS_CIRCLE")) {
      canvas.drawCircle(12, 12, 10, paint);
      if (button.equals("PS_CROSS")) {
        canvas.drawLine(8, 8, 16, 16, paint);
        canvas.drawLine(16, 8, 8, 16, paint);
      } else {
        canvas.drawCircle(12, 12, 4.5f, paint);
      }
    } else if (button.equals("A") || button.equals("B") || button.equals("X")) {
      canvas.drawCircle(12, 12, 10, paint);
      drawLabel(canvas, button, 12, 15.8f, 12);
    } else if (button.contains("/")) {
      String[] pair = button.split("/");
      canvas.drawRoundRect(new RectF(1, 4, 24, 20), 4, 4, paint);
      canvas.drawRoundRect(new RectF(28, 4, 51, 20), 4, 4, paint);
      drawLabel(canvas, pair[0], 12.5f, 15, 8);
      drawLabel(canvas, pair[1], 39.5f, 15, 8);
    } else {
      float width = getWidth() * 24f / Math.max(1, getHeight());
      canvas.drawRoundRect(new RectF(1, 4, width - 1, 20), 4, 4, paint);
      drawLabel(canvas, button, width / 2, 15, button.length() > 2 ? 7 : button.length() > 1 ? 8 : 10);
    }
    canvas.restore();
  }

  private void drawLabel(Canvas canvas, String label, float x, float baseline, float size) {
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(size);
    paint.setTextAlign(Paint.Align.CENTER);
    canvas.drawText(label, x, baseline, paint);
    paint.setStyle(Paint.Style.STROKE);
  }
}
