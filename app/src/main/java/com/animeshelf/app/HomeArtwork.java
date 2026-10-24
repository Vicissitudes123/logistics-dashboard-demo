package com.animeshelf.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small density-independent line icons for the library, without bundled image assets. */
final class HomeArtwork extends Drawable {
    enum Symbol { LIBRARY, DOWNLOAD, SETTINGS, SEARCH, CLOSE, MORE, PLAY }
    private final Symbol symbol;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;

    HomeArtwork(Symbol symbol, int color) { this.symbol = symbol; this.color = color; }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE);
        Path path = new Path();
        switch (symbol) {
            case LIBRARY:
                canvas.drawRoundRect(new RectF(4, 5, 20, 21), 2.3f, 2.3f, paint);
                canvas.drawLine(8, 2.5f, 16, 2.5f, paint);
                path.moveTo(10, 10); path.lineTo(15, 13); path.lineTo(10, 16); path.close(); canvas.drawPath(path, paint);
                break;
            case DOWNLOAD:
                canvas.drawLine(12, 3, 12, 15, paint);
                path.moveTo(7.5f, 10.5f); path.lineTo(12, 15); path.lineTo(16.5f, 10.5f); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(4, 16); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 16); canvas.drawPath(path, paint);
                break;
            case SETTINGS:
                canvas.drawCircle(12, 12, 7.2f, paint); canvas.drawCircle(12, 12, 2.5f, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    canvas.drawLine(12 + (float) Math.cos(angle) * 7.5f, 12 + (float) Math.sin(angle) * 7.5f,
                        12 + (float) Math.cos(angle) * 9.6f, 12 + (float) Math.sin(angle) * 9.6f, paint);
                }
                break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); canvas.drawLine(15.5f, 15.5f, 21, 21, paint); break;
            case CLOSE:
                canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint); break;
            case MORE:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) canvas.drawCircle(5.5f + i * 6.5f, 12, 1.5f, paint);
                break;
            case PLAY:
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20); path.close(); canvas.drawPath(path, paint); break;
        }
        canvas.restore();
    }

    @Override public void setAlpha(int value) { paint.setAlpha(value); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
