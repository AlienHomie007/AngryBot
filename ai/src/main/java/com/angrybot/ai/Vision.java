package com.angrybot.ai;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads one live frame and finds what matters: pigs, safe buttons to press, close (X) buttons.
 * Pure Java on a small frame, a few milliseconds per frame. All colour rules are HSV ranges -
 * tune them here if the AI misreads the screen.
 */
final class Vision {

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
        int w, h;          // frame size
        float scale;       // real screen pixels per frame pixel
        int[] px;          // ARGB
        final List<Blob> pigs = new ArrayList<>();
        final List<Blob> buttons = new ArrayList<>();
        final List<Blob> closes = new ArrayList<>();

        float fx(float x) { return x * scale; }   // frame -> real screen
        float fy(float y) { return y * scale; }
    }

    /** @param ignore area of the real screen to skip (our own control bar), may be null */
    static Frame analyze(int[] px, int w, int h, float scale, Rect ignore) {
        Frame f = new Frame();
        f.w = w;
        f.h = h;
        f.scale = scale;
        f.px = px;

        int n = w * h;
        boolean[] pig = new boolean[n], btn = new boolean[n], red = new boolean[n],
                gem = new boolean[n], white = new boolean[n];
        int igL = -1, igT = -1, igR = -1, igB = -1;
        if (ignore != null) {
            igL = (int) (ignore.left / scale) - 2;
            igT = (int) (ignore.top / scale) - 2;
            igR = (int) (ignore.right / scale) + 2;
            igB = (int) (ignore.bottom / scale) + 2;
        }
        for (int y = 0; y < h; y++) {
            boolean rowIgnored = y >= igT && y <= igB;
            for (int x = 0; x < w; x++) {
                if (rowIgnored && x >= igL && x <= igR) continue;
                int i = y * w + x;
                int c = px[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                int d = max - min;
                float v = max / 255f;
                float s = max == 0 ? 0 : d / (float) max;
                white[i] = s < 0.2f && v > 0.85f;
                if (s < 0.4f) continue;   // every colour rule below needs some saturation
                float hue;
                if (max == r) hue = 60f * ((g - b) / (float) d);
                else if (max == g) hue = 60f * ((b - r) / (float) d + 2);
                else hue = 60f * ((r - g) / (float) d + 4);
                if (hue < 0) hue += 360;
                pig[i] = hue >= PIG_HUE_MIN && hue <= PIG_HUE_MAX && s > PIG_SAT && v > PIG_VAL;
                btn[i] = hue >= BTN_HUE_MIN && hue <= BTN_HUE_MAX && s > BTN_SAT && v > BTN_VAL;
                red[i] = (hue < 15 || hue > 345) && s > 0.55f && v > 0.5f;
                gem[i] = hue >= GEM_HUE_MIN && hue <= GEM_HUE_MAX && v > 0.45f;
            }
        }

        // buttons first, so pigs inside a button can be thrown out
        for (Blob b : blobs(btn, w, h)) {
            float aspect = b.w() / (float) b.h();
            if (b.w() >= w * 0.10f && b.h() >= h * 0.06f && aspect >= 1.5f && aspect <= 7f
                    && b.fill() > 0.7f && share(gem, f, b, 6) < 0.02f) {
                f.buttons.add(b);
            }
        }
        for (Blob b : blobs(pig, w, h)) {
            int dia = Math.max(b.w(), b.h());
            float aspect = b.w() / (float) b.h();
            if (dia >= h * 0.025f && dia <= h * 0.16f && aspect >= 0.6f && aspect <= 1.6f
                    && b.fill() > 0.45f && b.cy() < h * 0.94f && !insideAny(b, f.buttons)) {
                f.pigs.add(b);
            }
        }
        for (Blob b : blobs(red, w, h)) {
            int dia = Math.max(b.w(), b.h());
            float aspect = b.w() / (float) b.h();
            if (dia >= h * 0.035f && dia <= h * 0.12f && aspect >= 0.7f && aspect <= 1.4f
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

    /** Average x (frame pixels) of the pixels that changed between two frames, or -1. */
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
        return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
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
