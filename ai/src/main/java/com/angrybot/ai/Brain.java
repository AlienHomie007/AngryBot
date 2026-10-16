package com.angrybot.ai;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what to do, frame by frame (30-60 times a second):
 *
 *  - Something still moving?                       wait until the screen settles (a quarter second)
 *  - A safe green button (no gem price)?            press it
 *  - Pigs visible and the scene still?              aim at one with slingshot physics and fire
 *  - Only a close (X) button?                       press it (closes offers and pop-ups)
 *  - Nothing recognised for a while?                tap the screen to nudge the game on
 *
 * Aiming uses the ballistic formula with one unknown, k = launchSpeed^2 / gravity (in screen pixels).
 * After each shot it looks at where the screen changed (the impact) and corrects k.
 */
final class Brain {

    interface Host {
        void tap(float x, float y, Runnable done);        // done may run on any thread
        void swipe(float x1, float y1, float x2, float y2, long durMs, Runnable done);
        void status(String s);
        void showDebug(Vision.Frame f, float[] aim);
        boolean gameInFront();
        int screenW();
        int screenH();
    }

    private static final float STILL = 0.015f;          // < 1.5% of pixels changed = still
    private static final long STILL_FOR_BUTTON_MS = 150;
    private static final long STILL_FOR_SHOT_MS = 250;
    private static final long SETTLED_AFTER_SHOT_MS = 400;
    private static final long SHOT_TIMEOUT_MS = 12000;
    private static final long STUCK_MS = 8000;
    private static final long COOLDOWN_MS = 300;        // after a tap, give the game time to react
    private static final long DRAG_MS = 350;            // how long the slingshot pull takes
    private static final long DEBUG_EVERY_MS = 100;

    private final Host host;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean acting;
    private boolean busy;                 // a gesture is in progress
    private long cooldownUntil;
    private Vision.Frame prev;
    private long stillSince;
    private long lastProgress;
    private int nudges;
    private long lastDebug;
    private float[] aim;
    private String lastStatus;

    // shot bookkeeping
    private boolean waitingForShot;
    private long shotTime;
    private Vision.Frame beforeShot;
    private int pigsBeforeShot;
    private float aimTargetDx;
    private int misses;
    private boolean highArc;
    private int targetIndex;

    Brain(Host host, SharedPreferences prefs) {
        this.host = host;
        this.prefs = prefs;
    }

    boolean isCalibrated() { return prefs.contains("slingX"); }

    boolean isActing() { return acting; }

    void saveCalibration(float slingX, float slingY, float pull) {
        prefs.edit().putFloat("slingX", slingX).putFloat("slingY", slingY)
                .putFloat("pull", Math.max(60f, pull)).apply();
    }

    synchronized void setActing(boolean on) {
        acting = on;
        busy = false;
        waitingForShot = false;
        misses = 0;
        long now = SystemClock.uptimeMillis();
        lastProgress = now;
        stillSince = now;
        cooldownUntil = 0;
        main.removeCallbacksAndMessages(null);
        lastStatus = null;
    }

    /** Called for every live frame, on the frame thread. */
    synchronized void onFrame(Vision.Frame f) {
        long now = SystemClock.uptimeMillis();
        float change = Vision.changed(prev, f, 0);
        prev = f;
        if (change >= STILL) stillSince = now;
        long still = now - stillSince;

        if (now - lastDebug >= DEBUG_EVERY_MS) {
            lastDebug = now;
            host.showDebug(f, aim);
        }
        if (!acting) {
            status("Watching · " + f.pigs.size() + " pigs, " + f.buttons.size() + " buttons, "
                    + f.closes.size() + " close");
            return;
        }
        if (busy || now < cooldownUntil) return;
        if (!host.gameInFront()) {
            status("AI: waiting for Angry Birds 2…");
            return;
        }

        if (waitingForShot) {
            if (still >= SETTLED_AFTER_SHOT_MS || now - shotTime > SHOT_TIMEOUT_MS) {
                waitingForShot = false;
                learn(f);
            }
            return;
        }

        if (!f.buttons.isEmpty() && still >= STILL_FOR_BUTTON_MS) {
            Vision.Blob b = biggest(f.buttons);
            status("AI: pressing a button");
            act(() -> host.tap(f.fx(b.cx()), f.fy(b.cy()), this::gestureDone));
            return;
        }
        if (!f.pigs.isEmpty() && still >= STILL_FOR_SHOT_MS) {
            shoot(f);
            return;
        }
        if (!f.closes.isEmpty() && still >= STILL_FOR_SHOT_MS) {
            Vision.Blob b = f.closes.get(0);
            status("AI: closing a pop-up");
            act(() -> host.tap(f.fx(b.cx()), f.fy(b.cy()), this::gestureDone));
            return;
        }
        if (now - lastProgress > STUCK_MS) {
            // nothing recognised for a while: nudge the game ("tap to continue" screens etc.)
            float x = host.screenW() * 0.5f;
            float y = host.screenH() * (nudges % 2 == 0 ? 0.5f : 0.8f);
            nudges++;
            status("AI: stuck - tapping to continue");
            act(() -> host.tap(x, y, this::gestureDone));
            return;
        }
        status("AI: looking… (" + f.pigs.size() + " pigs, " + f.buttons.size() + " buttons)");
    }

    /** Marks the AI busy and records progress, then runs the gesture. */
    private void act(Runnable gesture) {
        busy = true;
        lastProgress = SystemClock.uptimeMillis();
        gesture.run();
    }

    private void gestureDone() {
        synchronized (this) {
            busy = false;
            cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS;
            stillSince = SystemClock.uptimeMillis();   // the screen is about to change
        }
    }

    // ---------------------------------------------------------------- shooting

    private void shoot(Vision.Frame f) {
        float sx = prefs.getFloat("slingX", 0), sy = prefs.getFloat("slingY", 0);
        float pull = prefs.getFloat("pull", 200);
        int sw = host.screenW(), sh = host.screenH();
        float k = prefs.getFloat("k", sw * 1.0f);

        // pigs to the right of the slingshot, nearest first
        List<Vision.Blob> targets = new ArrayList<>();
        for (Vision.Blob p : f.pigs) if (f.fx(p.cx()) > sx + sw * 0.05f) targets.add(p);
        if (targets.isEmpty()) {
            status("AI: pigs are behind the slingshot?");
            return;
        }
        targets.sort((a, b) -> Float.compare(a.cx(), b.cx()));
        Vision.Blob t = targets.get(targetIndex % targets.size());
        float tx = f.fx(t.cx()), ty = f.fy(t.cy());
        float dx = tx - sx, dy = sy - ty;   // dy > 0 means the target is higher than the sling

        double theta = launchAngle(dx, dy, k, highArc);
        float rx = clamp((float) (sx - pull * Math.cos(theta)), 2, sw - 2);
        float ry = clamp((float) (sy + pull * Math.sin(theta)), 2, sh - 2);

        aim = new float[]{sx, sy, rx, ry, tx, ty};
        host.showDebug(f, aim);
        status(String.format("AI: firing at pig %d/%d (%s, %d°)", targetIndex % targets.size() + 1,
                targets.size(), highArc ? "high" : "low", Math.round(Math.toDegrees(theta))));

        beforeShot = f;
        pigsBeforeShot = f.pigs.size();
        aimTargetDx = dx;
        act(() -> host.swipe(sx, sy, rx, ry, DRAG_MS, () -> {
            synchronized (Brain.this) {
                busy = false;
                shotTime = SystemClock.uptimeMillis();
                stillSince = shotTime;
                waitingForShot = true;
            }
            // tap mid-flight to use the bird's power; farther targets -> later tap
            long t1 = 300 + (long) (1000 * dx / sw);
            main.postDelayed(() -> {
                if (acting) host.tap(sw * 0.5f, sh * 0.3f, null);
            }, t1);
        }));
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
        aim = null;
        int sw = host.screenW();
        float k = prefs.getFloat("k", sw * 1.0f);
        float sx = prefs.getFloat("slingX", 0);
        int fromX = (int) ((sx + sw * 0.05f) / after.scale);

        float changedShare = Vision.changed(beforeShot, after, fromX);
        float impact = Vision.changeCentroidX(beforeShot, after, fromX);
        // ignore if the whole picture changed (camera moved / next room) - nothing to learn from
        if (impact >= 0 && changedShare > 0.002f && changedShare < 0.4f && aimTargetDx > 0) {
            float impactDx = after.fx(impact) - sx;
            float ratio = clamp(impactDx / aimTargetDx, 0.75f, 1.3f);
            // range grows in proportion to k: if we landed short, the real k is smaller
            k = clamp(k * ratio, sw * 0.3f, sw * 4f);
            prefs.edit().putFloat("k", k).apply();
        }

        lastProgress = SystemClock.uptimeMillis();
        if (after.pigs.size() < pigsBeforeShot) {
            misses = 0;
            nudges = 0;
            status("AI: hit! " + after.pigs.size() + " pigs left");
        } else {
            misses++;
            if (misses % 2 == 0) highArc = !highArc;      // try the other arc every second miss
            if (misses % 4 == 0) targetIndex++;          // and another pig every fourth
            status("AI: missed - adjusting aim");
        }
    }

    private void status(String s) {
        if (!s.equals(lastStatus)) {
            lastStatus = s;
            host.status(s);
        }
    }

    private static Vision.Blob biggest(List<Vision.Blob> list) {
        Vision.Blob best = list.get(0);
        for (Vision.Blob b : list) if (b.count > best.count) best = b;
        return best;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
