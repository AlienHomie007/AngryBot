package com.angrybot;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AI loop: screenshot -> understand -> act -> check the result, about once a second.
 *
 *  - Something still moving?           wait until the screen settles
 *  - A safe green button (Play/Continue/Retry, no gem price)?  press it
 *  - Pigs visible and the scene still?  aim at one with slingshot physics and fire
 *  - Only a close (X) button?           press it (closes offers and pop-ups)
 *  - Nothing has happened for a while?  tap the middle of the screen to nudge the game on
 *
 * Aiming uses the ballistic formula with one unknown, k = launchSpeed^2 / gravity (in screen pixels).
 * After each shot it sees where the screen changed (the impact) and corrects k, so it gets more accurate.
 */
final class AiPlayer {

    interface Host {
        void screenshot(java.util.function.Consumer<Bitmap> cb);   // cb runs on a background thread
        void tap(float x, float y, Runnable done);
        void swipe(float x1, float y1, float x2, float y2, long durMs, Runnable done);
        void status(String s);
        void showDebug(Vision.Frame f, float[] aim);
        Rect barRect();
        boolean gameInFront();
        int screenW();
        int screenH();
    }

    private static final long TICK_MS = 700;
    private static final long SHOT_TIMEOUT_MS = 12000;
    private static final long STUCK_MS = 15000;
    private static final float STILL = 0.01f;   // < 1% of pixels changed = screen is still

    private final Host host;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private boolean running;
    boolean useAbilities = true;

    // what the AI has seen
    private Vision.Frame prev;
    private int stillCount;
    private long lastProgress;
    private int nudges;

    // shot bookkeeping
    private boolean waitingForShot;
    private long shotTime;
    private Vision.Frame beforeShot;
    private int pigsBeforeShot;
    private float aimTargetDx;
    private int misses;
    private boolean highArc;
    private int targetIndex;
    private Vision.Frame lastFrame;
    private Bitmap lastBitmap;

    AiPlayer(Host host, SharedPreferences prefs) {
        this.host = host;
        this.prefs = prefs;
    }

    boolean isRunning() { return running; }

    boolean isCalibrated() { return prefs.contains("slingX"); }

    void saveCalibration(float slingX, float slingY, float pull) {
        prefs.edit().putFloat("slingX", slingX).putFloat("slingY", slingY)
                .putFloat("pull", Math.max(60f, pull)).apply();
    }

    void start() {
        if (running) return;
        if (!isCalibrated()) {
            host.status("AI: tap 🎯 Calib first");
            return;
        }
        running = true;
        prev = null;
        stillCount = 0;
        waitingForShot = false;
        misses = 0;
        lastProgress = SystemClock.uptimeMillis();
        host.status("AI: watching…");
        schedule(200);
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    /** Last screenshot and what was found in it, for the 📸 Snap button. */
    Bitmap lastBitmap() { return lastBitmap; }
    Vision.Frame lastFrame() { return lastFrame; }

    private void schedule(long delay) {
        if (running) main.postDelayed(this::tick, delay);
    }

    private void tick() {
        if (!running) return;
        if (!host.gameInFront()) {
            host.status("AI: waiting for Angry Birds 2…");
            schedule(1500);
            return;
        }
        Rect bar = host.barRect();
        host.screenshot(bmp -> {
            if (bmp == null) {
                main.post(() -> schedule(800));
                return;
            }
            Vision.Frame f = Vision.analyze(bmp, bar);
            main.post(() -> {
                if (lastBitmap != null && lastBitmap != bmp) lastBitmap.recycle();
                lastBitmap = bmp;
                lastFrame = f;
                onFrame(f);
            });
        });
    }

    private void onFrame(Vision.Frame f) {
        if (!running) return;
        float change = Vision.changed(prev, f, 0);
        prev = f;
        stillCount = change < STILL ? stillCount + 1 : 0;
        host.showDebug(f, null);
        long now = SystemClock.uptimeMillis();

        if (waitingForShot) {
            if (stillCount >= 2 || now - shotTime > SHOT_TIMEOUT_MS) {
                waitingForShot = false;
                learn(f);
            }
            schedule(400);
            return;
        }
        if (change >= STILL) {          // something is moving - let it finish
            host.status("AI: waiting for the screen to settle");
            schedule(TICK_MS);
            return;
        }

        if (!f.buttons.isEmpty()) {
            Vision.Blob b = biggest(f.buttons);
            host.status("AI: pressing a button");
            progress();
            host.tap(f.fx(b.cx()), f.fy(b.cy()), () -> schedule(1500));
            return;
        }
        if (!f.pigs.isEmpty() && stillCount >= 2) {
            shoot(f);
            return;
        }
        if (!f.closes.isEmpty() && stillCount >= 2) {
            Vision.Blob b = f.closes.get(0);
            host.status("AI: closing a pop-up");
            progress();
            host.tap(f.fx(b.cx()), f.fy(b.cy()), () -> schedule(1200));
            return;
        }

        if (now - lastProgress > STUCK_MS) {
            // nothing recognised for a while: nudge the game (dismiss "tap to continue" screens etc.)
            float x = host.screenW() * 0.5f;
            float y = host.screenH() * (nudges % 2 == 0 ? 0.5f : 0.8f);
            nudges++;
            lastProgress = now;
            host.status("AI: stuck - tapping to continue");
            host.tap(x, y, () -> schedule(1500));
            return;
        }
        host.status("AI: looking… (" + f.pigs.size() + " pigs, " + f.buttons.size() + " buttons)");
        schedule(TICK_MS);
    }

    // ---------------------------------------------------------------- shooting

    private void shoot(Vision.Frame f) {
        float sx = prefs.getFloat("slingX", 0), sy = prefs.getFloat("slingY", 0);
        float pull = prefs.getFloat("pull", 200);
        float k = prefs.getFloat("k", host.screenW() * 1.0f);

        // pigs to the right of the slingshot, nearest first
        java.util.List<Vision.Blob> targets = new java.util.ArrayList<>();
        for (Vision.Blob p : f.pigs) if (f.fx(p.cx()) > sx + host.screenW() * 0.05f) targets.add(p);
        if (targets.isEmpty()) {
            host.status("AI: pigs are behind the slingshot?");
            schedule(TICK_MS);
            return;
        }
        targets.sort((a, b) -> Float.compare(a.cx(), b.cx()));
        Vision.Blob t = targets.get(targetIndex % targets.size());
        float tx = f.fx(t.cx()), ty = f.fy(t.cy());
        float dx = tx - sx, dy = sy - ty;   // dy > 0 means the target is higher than the sling

        double theta = launchAngle(dx, dy, k, highArc);
        float rx = (float) (sx - pull * Math.cos(theta));
        float ry = (float) (sy + pull * Math.sin(theta));
        rx = clamp(rx, 2, host.screenW() - 2);
        ry = clamp(ry, 2, host.screenH() - 2);

        host.showDebug(f, new float[]{sx, sy, rx, ry, tx, ty});
        host.status(String.format("AI: firing at pig %d/%d (%s, %d°)", targetIndex % targets.size() + 1,
                targets.size(), highArc ? "high" : "low", Math.round(Math.toDegrees(theta))));

        beforeShot = f;
        pigsBeforeShot = f.pigs.size();
        aimTargetDx = dx;
        progress();
        host.swipe(sx, sy, rx, ry, 700, () -> {
            shotTime = SystemClock.uptimeMillis();
            waitingForShot = true;
            stillCount = 0;
            if (useAbilities) {
                // tap mid-flight to use the bird's power; farther targets -> later tap
                long t1 = 400 + (long) (1100 * dx / host.screenW());
                main.postDelayed(() -> {
                    if (running) host.tap(host.screenW() * 0.5f, host.screenH() * 0.3f, null);
                }, t1);
            }
            schedule(900);
        });
    }

    /** Angle (radians, up from horizontal) that lands at (dx, dy) for launch strength k; 45° if out of reach. */
    static double launchAngle(float dx, float dy, float k, boolean high) {
        double a = dx * (double) dx / (2 * k);
        double b = -dx;
        double c = dy + a;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return Math.toRadians(45);
        double u = high ? (-b + Math.sqrt(disc)) / (2 * a) : (-b - Math.sqrt(disc)) / (2 * a);
        return Math.atan(u);
    }

    /** After the dust settles: did we hit? Where did the bird land? Adjust the aim for next time. */
    private void learn(Vision.Frame after) {
        int pigsNow = after.pigs.size();
        float k = prefs.getFloat("k", host.screenW() * 1.0f);
        float sx = prefs.getFloat("slingX", 0);
        int fromX = (int) ((sx + host.screenW() * 0.05f) / after.scale);

        float changedShare = Vision.changed(beforeShot, after, fromX);
        float impact = Vision.changeCentroidX(beforeShot, after, fromX);
        // ignore if the whole picture changed (camera moved / next room) - nothing to learn from
        if (impact >= 0 && changedShare > 0.002f && changedShare < 0.4f && aimTargetDx > 0) {
            float impactDx = after.fx(impact) - sx;
            float ratio = clamp(impactDx / aimTargetDx, 0.75f, 1.3f);
            // range grows in proportion to k: if we landed short, the real k is smaller
            k = clamp(k * ratio, host.screenW() * 0.3f, host.screenW() * 4f);
            prefs.edit().putFloat("k", k).apply();
        }

        if (pigsNow < pigsBeforeShot) {
            misses = 0;
            host.status("AI: hit! " + pigsNow + " pigs left");
        } else {
            misses++;
            if (misses % 2 == 0) highArc = !highArc;      // try the other arc every second miss
            if (misses % 4 == 0) targetIndex++;          // and another pig every fourth
            host.status("AI: missed - adjusting aim");
        }
    }

    private void progress() {
        lastProgress = SystemClock.uptimeMillis();
        nudges = 0;
    }

    private static Vision.Blob biggest(java.util.List<Vision.Blob> list) {
        Vision.Blob best = list.get(0);
        for (Vision.Blob b : list) if (b.count > best.count) best = b;
        return best;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
