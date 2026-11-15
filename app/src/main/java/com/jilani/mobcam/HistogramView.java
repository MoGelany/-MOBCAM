package com.jilani.mobcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** RGB + luma histogram with clipping markers. */
public class HistogramView extends View {
    private float[] y, r, g, b;
    private boolean hi, lo;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public HistogramView(Context c) {
        super(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(140, 0, 0, 0));
        bg.setCornerRadius(6 * c.getResources().getDisplayMetrics().density);
        setBackground(bg);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void set(float[] y, float[] r, float[] g, float[] b, boolean hi, boolean lo) {
        this.y = y; this.r = r; this.g = g; this.b = b; this.hi = hi; this.lo = lo;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (y == null) return;
        float W = getWidth(), H = getHeight(), mx = 1;
        for (int i = 1; i < 63; i++) mx = Math.max(mx, Math.max(Math.max(y[i], r[i]), Math.max(g[i], b[i])));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        draw(c, r, Color.argb(110, 255, 60, 60), W, H, mx);
        draw(c, g, Color.argb(110, 60, 255, 90), W, H, mx);
        draw(c, b, Color.argb(110, 60, 140, 255), W, H, mx);
        draw(c, y, Color.argb(140, 255, 255, 255), W, H, mx);
        p.setXfermode(null);
        float d = getResources().getDisplayMetrics().density;
        if (hi) { p.setColor(Color.RED); c.drawRect(W - 3 * d, 0, W, H, p); }
        if (lo) { p.setColor(Color.rgb(48, 96, 255)); c.drawRect(0, 0, 3 * d, H, p); }
    }

    private void draw(Canvas c, float[] a, int color, float W, float H, float mx) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(0, H);
        for (int i = 0; i < 64; i++) path.lineTo(i * W / 63f, H - Math.min(1f, a[i] / mx) * (H - 4));
        path.lineTo(W, H);
        path.close();
        c.drawPath(path, p);
    }
}
