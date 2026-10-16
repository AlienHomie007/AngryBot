package com.angrybot;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks at a screenshot and finds what matters: pigs, safe buttons to press, close (X) buttons.
 * Works on a small copy of the screen (ANALYSIS_W wide) so it runs fast on the phone.
 * All colour rules are simple HSV ranges - tune them here if the AI misreads the screen.
 */
final class Vision {

    static final int ANALYSIS_W = 320;

    // pigs: bright lime green, roundish
    static final float PIG_HUE_MIN = 70, PIG_HUE_MAX = 115, PIG_SAT = 0.45f, PIG_VAL = 0.45f;
    // buttons (Play / Continue / Retry): bright green, wide rounded rectangles
    static final float BTN_HUE_MIN = 75, BTN_HUE_MAX = 140, BTN_SAT = 0.40f, BTN_VAL = 0.55f;
    // gem prices: purple/pink - a button showing these costs gems, so the AI never presses it
    static final float GEM_HUE_MIN = 275, GEM_HUE_MAX = 335;

    static final class Blob {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, count;
        long sumX, sumY;

        int w() { return maxX - minX + 1; }
        int h() { return maxY - minY + 1; }
        float fill() { return count / (float) (w() * h()); }
        float cx() { return sumX / (float) count; }
        float cy() { return sumY / (float) count; }
    }

    static final class Frame {
        int w, h;          // analysis size
        float scale;       // full-screen pixels per analysis pixel
        int[] px;          // analysis pixels (ARGB)
        final List<Blob> pigs = new ArrayList<>();
        final List<Blob> buttons = new ArrayList<>();
        final List<Blob> closes = new ArrayList<>();

        float fx(float x) { return x * scale; }   // analysis -> full screen
        float fy(float y) { return y * scale; }
    }

    /** @param ignore area of the full screen to skip (our own control bar), may be null */
    static Frame analyze(Bitmap full, Rect ignore) {
        Frame f = new Frame();
        f.scale = full.getWidth() / (float) ANALYSIS_W;
        f.w = ANALYSIS_W;
        f.h = Math.max(1, Math.round(full.getHeight() / f.scale));
        Bitmap small = Bitmap.createScaledBitmap(full, f.w, f.h, true);
        f.px = new int[f.w * f.h];
        small.getPixels(f.px, 0, f.w, 0, 0, f.w, f.h);
        if (small != full) small.recycle();

        int n = f.w * f.h;
        boolean[] pig = new boolean[n], btn = new boolean[n], red = new boolean[n],
                gem = new boolean[n], white = new boolean[n];
        Rect ig = ignore == null ? null : new Rect(
                (int) (ignore.left / f.scale) - 2, (int) (ignore.top / f.scale) - 2,
                (int) (ignore.right / f.scale) + 2, (int) (ignore.bottom / f.scale) + 2);
        float[] hsv = new float[3];
        for (int y = 0; y < f.h; y++) {
            for (int x = 0; x < f.w; x++) {
                int i = y * f.w + x;
                if (ig != null && ig.contains(x, y)) continue;
                Color.colorToHSV(f.px[i], hsv);
                float hue = hsv[0], s = hsv[1], v = hsv[2];
                pig[i] = hue >= PIG_HUE_MIN && hue <= PIG_HUE_MAX && s > PIG_SAT && v > PIG_VAL;
                btn[i] = hue >= BTN_HUE_MIN && hue <= BTN_HUE_MAX && s > BTN_SAT && v > BTN_VAL;
                red[i] = (hue < 15 || hue > 345) && s > 0.55f && v > 0.5f;
                gem[i] = hue >= GEM_HUE_MIN && hue <= GEM_HUE_MAX && s > 0.4f && v > 0.45f;
                white[i] = s < 0.2f && v > 0.85f;
            }
        }

        // buttons first, so pigs inside a button can be thrown out
        for (Blob b : blobs(btn, f.w, f.h)) {
            float aspect = b.w() / (float) b.h();
            if (b.w() >= f.w * 0.10f && b.h() >= f.h * 0.06f && aspect >= 1.5f && aspect <= 7f
                    && b.fill() > 0.7f && share(gem, f, b, 6) < 0.02f) {
                f.buttons.add(b);
            }
        }
        for (Blob b : blobs(pig, f.w, f.h)) {
            int d = Math.max(b.w(), b.h());
            float aspect = b.w() / (float) b.h();
            if (d >= f.h * 0.025f && d <= f.h * 0.16f && aspect >= 0.6f && aspect <= 1.6f
                    && b.fill() > 0.45f && b.cy() < f.h * 0.94f && !insideAny(b, f.buttons)) {
                f.pigs.add(b);
            }
        }
        for (Blob b : blobs(red, f.w, f.h)) {
            int d = Math.max(b.w(), b.h());
            float aspect = b.w() / (float) b.h();
            if (d >= f.h * 0.035f && d <= f.h * 0.12f && aspect >= 0.7f && aspect <= 1.4f
                    && b.fill() > 0.45f && share(white, f, b, 0) > 0.05f) {
                f.closes.add(b);
            }
        }
        return f;
    }

    /** Fraction of pixels whose brightness changed noticeably, looking only at x >= fromX. */
    static float changed(Frame a, Frame b, int fromX) {
        if (a == null || b == null || a.w != b.w || a.h != b.h) return 1f;
        int count = 0, total = 0;
        for (int y = 0; y < a.h; y++) {
            for (int x = Math.max(0, fromX); x < a.w; x++) {
                int i = y * a.w + x;
                total++;
                if (Math.abs(luma(a.px[i]) - luma(b.px[i])) > 30) count++;
            }
        }
        return total == 0 ? 0 : count / (float) total;
    }

    /** Average x (analysis pixels) of the pixels that changed between two frames, or -1. */
    static float changeCentroidX(Frame a, Frame b, int fromX) {
        if (a == null || b == null || a.w != b.w || a.h != b.h) return -1;
        long sum = 0;
        int count = 0;
        for (int y = 0; y < a.h; y++) {
            for (int x = Math.max(0, fromX); x < a.w; x++) {
                int i = y * a.w + x;
                if (Math.abs(luma(a.px[i]) - luma(b.px[i])) > 30) { sum += x; count++; }
            }
        }
        return count < 10 ? -1 : sum / (float) count;
    }

    private static int luma(int c) {
        return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
    }

    private static boolean insideAny(Blob b, List<Blob> boxes) {
        float x = b.cx(), y = b.cy();
        for (Blob o : boxes) {
            if (x >= o.minX && x <= o.maxX && y >= o.minY && y <= o.maxY) return true;
        }
        return false;
    }

    /** Share of pixels set in mask inside the blob's box grown by pad. */
    private static float share(boolean[] mask, Frame f, Blob b, int pad) {
        int x0 = Math.max(0, b.minX - pad), x1 = Math.min(f.w - 1, b.maxX + pad);
        int y0 = Math.max(0, b.minY - pad), y1 = Math.min(f.h - 1, b.maxY + pad);
        int hit = 0, total = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                total++;
                if (mask[y * f.w + x]) hit++;
            }
        }
        return total == 0 ? 0 : hit / (float) total;
    }

    /** 4-connected components of a mask; tiny specks are dropped. */
    private static List<Blob> blobs(boolean[] mask, int w, int h) {
        List<Blob> out = new ArrayList<>();
        boolean[] seen = new boolean[mask.length];
        int[] stack = new int[mask.length];
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            Blob b = new Blob();
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % w, y = i / w;
                b.count++;
                b.sumX += x;
                b.sumY += y;
                if (x < b.minX) b.minX = x;
                if (x > b.maxX) b.maxX = x;
                if (y < b.minY) b.minY = y;
                if (y > b.maxY) b.maxY = y;
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w; }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w; }
            }
            if (b.count >= 6) out.add(b);
        }
        return out;
    }
}
