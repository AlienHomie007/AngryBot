package com.angrybot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/**
 * See-through layer that shows what the AI found: pigs (cyan), buttons (yellow), close X (orange)
 * and the planned shot (white). These colours are picked so the AI never mistakes its own
 * drawings for pigs or buttons in the next screenshot.
 */
final class DebugView extends View {

    private Vision.Frame frame;
    private float[] aim;

    DebugView(Context c) {
        super(c);
    }

    void show(Vision.Frame f, float[] aim) {
        this.frame = f;
        this.aim = aim;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        draw(c, frame, aim);
    }

    static void draw(Canvas c, Vision.Frame f, float[] aim) {
        if (f == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(0xFF00E5FF);
        for (Vision.Blob b : f.pigs) box(c, f, b, p);
        p.setColor(0xFFFFEB3B);
        for (Vision.Blob b : f.buttons) box(c, f, b, p);
        p.setColor(0xFFFF9800);
        for (Vision.Blob b : f.closes) box(c, f, b, p);
        if (aim != null) {
            // aim = {slingX, slingY, releaseX, releaseY, targetX, targetY}
            p.setColor(0xFFFFFFFF);
            c.drawLine(aim[0], aim[1], aim[2], aim[3], p);
            p.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0));
            c.drawLine(aim[0], aim[1], aim[4], aim[5], p);
            p.setPathEffect(null);
            c.drawCircle(aim[4], aim[5], 14, p);
        }
    }

    private static void box(Canvas c, Vision.Frame f, Vision.Blob b, Paint p) {
        c.drawRect(f.fx(b.minX), f.fy(b.minY), f.fx(b.maxX + 1), f.fy(b.maxY + 1), p);
    }
}
