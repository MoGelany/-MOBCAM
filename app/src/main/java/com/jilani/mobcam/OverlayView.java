package com.jilani.mobcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Grid, center cross and reel-crop guides drawn over the video area. */
public class OverlayView extends View {
    public final RectF video = new RectF();
    public boolean grid = false, hidden = false;
    public float guide = 0f; // aspect ratio, 0 = off
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Path path = new Path();

    public OverlayView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        dim.setColor(Color.argb(140, 0, 0, 0));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (hidden || video.width() <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        RectF area = new RectF(video);
        if (guide > 0) {
            float gw = video.width(), gh = gw / guide;
            if (gh > video.height()) { gh = video.height(); gw = gh * guide; }
            float gx = video.centerX() - gw / 2, gy = video.centerY() - gh / 2;
            area.set(gx, gy, gx + gw, gy + gh);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(video, Path.Direction.CW);
            path.addRect(area, Path.Direction.CW);
            c.drawPath(path, dim);
            line.setColor(Color.argb(220, 255, 214, 10));
            line.setStrokeWidth(1.5f * d);
            c.drawRect(area, line);
        }
        if (grid) {
            line.setColor(Color.argb(120, 255, 255, 255));
            line.setStrokeWidth(1f * d);
            for (int i = 1; i < 3; i++) {
                float x = area.left + area.width() * i / 3f, y = area.top + area.height() * i / 3f;
                c.drawLine(x, area.top, x, area.bottom, line);
                c.drawLine(area.left, y, area.right, y, line);
            }
            float cx = video.centerX(), cy = video.centerY(), s = 10 * d;
            line.setColor(Color.argb(210, 255, 255, 255));
            line.setStrokeWidth(1.5f * d);
            c.drawLine(cx - s, cy, cx + s, cy, line);
            c.drawLine(cx, cy - s, cx, cy + s, line);
        }
    }
}
