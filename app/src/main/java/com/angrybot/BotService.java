package com.angrybot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Floating control bar over the game.
 * Rec   – a transparent layer catches each drag/tap you make, saves it, and passes it on to the game.
 * Play  – replays the saved gestures with the same timing.  Loop – replays forever until Stop.
 * Calib – you pull the slingshot all the way back once, so the AI knows where it is and how far it pulls.
 * AI    – plays by itself: looks at the screen, presses buttons, aims at pigs (see AiPlayer).
 * Debug – draws what the AI sees.  Snap – saves a screenshot with the AI's findings to Pictures/AngryBot.
 */
public class BotService extends AccessibilityService implements AiPlayer.Host {

    private static final String PREFS = "angrybot";
    private static final String KEY = "level11";
    private static final long FIRST_GAP_MS = 1000;   // wait before the first gesture on Play
    private static final long LOOP_PAUSE_MS = 3000;  // wait between loop runs
    private static final float MIN_STEP_PX = 4f;     // drop path points closer than this
    private static final long PASS_THROUGH_DELAY_MS = 150;  // let "not touchable" take effect before injecting
    private static final long SETTLE_MS = 150;              // ignore touches briefly after an injection ends

    /** One finger gesture: wait gapMs after the previous one finished, then follow the points over durMs. */
    static class Stroke {
        long gapMs, durMs;
        float[] xs, ys;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Stroke> strokes = new ArrayList<>();
    private final ExecutorService shots = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private LinearLayout bar;
    private WindowManager.LayoutParams barLp;
    private TextView status;
    private CaptureView capture;
    private WindowManager.LayoutParams captureLp;
    private DebugView debugView;
    private AiPlayer ai;

    private boolean playing, looping, calibrating;
    private int run;
    private long lastInjectEnd;  // uptime when the previous recorded gesture finished replaying
    boolean passingThrough;      // true while a recorded gesture is being forwarded to the game
    private int barBlocks;       // AI gestures in flight that must pass under the control bar
    private String frontPackage; // app currently on screen

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        load();
        ai = new AiPlayer(this, prefs());
        showBar();
        setStatus("Ready · " + strokes.size() + " moves");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String p = event.getPackageName().toString();
            if (!p.equals(getPackageName()) && !p.equals("com.android.systemui")) frontPackage = p;
        }
    }

    @Override
    public void onInterrupt() {
        stopAll();
    }

    @Override
    public void onDestroy() {
        if (wm != null) {
            stopAll();
            if (debugView != null) wm.removeView(debugView);
            if (bar != null) wm.removeView(bar);
        }
        shots.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- control bar

    @SuppressLint("ClickableViewAccessibility")
    private void showBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xCC202020);
        bar.setPadding(6, 6, 6, 6);

        TextView handle = new TextView(this);
        handle.setText("⠿ AngryBot");
        handle.setTextColor(Color.WHITE);
        handle.setTextSize(11);
        handle.setPadding(8, 8, 8, 8);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = e.getRawX(); sy = e.getRawY(); ox = barLp.x; oy = barLp.y;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    barLp.x = ox + (int) (e.getRawX() - sx);
                    barLp.y = oy + (int) (e.getRawY() - sy);
                    wm.updateViewLayout(bar, barLp);
                }
                return true;
            }
        });
        bar.addView(handle);

        LinearLayout row = addRow();
        addButton(row, "● Rec", v -> startRecording());
        addButton(row, "▶ Play", v -> startPlaying(false));
        addButton(row, "⟳ Loop", v -> startPlaying(true));
        row = addRow();
        addButton(row, "■ Stop", v -> stopAll());
        addButton(row, "↶ Undo", v -> undo());
        addButton(row, "🎯 Calib", v -> startCalibration());
        row = addRow();
        addButton(row, "🧠 AI", v -> toggleAi());
        addButton(row, "👁 Debug", v -> toggleDebug());
        addButton(row, "📸 Snap", v -> snap());

        status = new TextView(this);
        status.setTextColor(0xFFFFD54F);
        status.setTextSize(10);
        status.setPadding(8, 4, 8, 4);
        status.setMaxWidth(420);
        bar.addView(status);

        barLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        barLp.gravity = Gravity.TOP | Gravity.START;
        barLp.x = 0;
        barLp.y = 120;
        wm.addView(bar, barLp);
    }

    private LinearLayout addRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(row);
        return row;
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(16, 8, 16, 8);
        b.setOnClickListener(l);
        row.addView(b);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void setBarTouchable(boolean touchable) {
        if (bar == null) return;
        if (touchable) barLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else barLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(bar, barLp);
    }

    /** Re-adds the bar so it sits above a layer that was just added. */
    private void raiseBar() {
        wm.removeView(bar);
        wm.addView(bar, barLp);
    }

    // ---------------------------------------------------------------- recording & calibration

    private void startRecording() {
        stopAll();
        strokes.clear();
        lastInjectEnd = 0;
        showCapture();
        setStatus("REC · 0 moves");
    }

    private void startCalibration() {
        stopAll();
        calibrating = true;
        showCapture();
        setStatus("CALIB: put a bird on the slingshot, pull it ALL the way back, let go");
    }

    private void showCapture() {
        capture = new CaptureView(this);
        captureLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        captureLp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            captureLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(capture, captureLp);
        raiseBar();  // keep the control bar above the capture layer
    }

    private void removeCapture() {
        if (capture == null) return;
        wm.removeView(capture);
        capture = null;
    }

    /** Called by CaptureView when a finger is lifted. */
    void onGestureCaptured(List<float[]> pts, long durMs) {
        // Our own forwarded gesture can land back on this layer if Android hasn't applied
        // "not touchable" yet; recording it again caused every move to happen twice.
        if (passingThrough) return;
        long now = SystemClock.uptimeMillis();
        Stroke s = new Stroke();
        s.durMs = Math.max(1, Math.min(durMs, GestureDescription.getMaxGestureDuration()));
        // game-visible gap: from the end of the previous injected gesture to the start of this one
        s.gapMs = strokes.isEmpty() ? FIRST_GAP_MS : Math.max(0, now - lastInjectEnd);
        s.xs = new float[pts.size()];
        s.ys = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            s.xs[i] = pts.get(i)[0];
            s.ys[i] = pts.get(i)[1];
        }

        if (calibrating) {
            float[] a = pts.get(0), b = pts.get(pts.size() - 1);
            float pull = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            calibrating = false;
            if (pull < 60) {
                setStatus("CALIB: that was too short - tap 🎯 Calib and pull further");
                removeCapture();
                return;
            }
            ai.saveCalibration(a[0], a[1], pull);
            setStatus("Calibrated ✓ Now tap 🧠 AI");
            passThrough(s, this::removeCapture);
            return;
        }

        strokes.add(s);
        lastInjectEnd = now + s.durMs;
        save();
        setStatus("REC · " + strokes.size() + " moves");
        passThrough(s, null);
    }

    /**
     * Let a captured gesture through to the game: make our layer see-through for touches,
     * wait for that to apply, then forward it; ignore everything until it has finished.
     */
    private void passThrough(Stroke s, Runnable after) {
        passingThrough = true;
        setCaptureTouchable(false);
        handler.postDelayed(() -> inject(s, ok -> {
            setCaptureTouchable(true);
            if (after != null) after.run();
            handler.postDelayed(() -> passingThrough = false, SETTLE_MS);
        }), PASS_THROUGH_DELAY_MS);
    }

    private void setCaptureTouchable(boolean touchable) {
        if (capture == null) return;
        if (touchable) captureLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else captureLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(capture, captureLp);
    }

    private void undo() {
        if (strokes.isEmpty()) return;
        strokes.remove(strokes.size() - 1);
        save();
        setStatus((capture != null ? "REC · " : "Ready · ") + strokes.size() + " moves");
    }

    // ---------------------------------------------------------------- playback

    private void startPlaying(boolean loop) {
        stopAll();
        if (strokes.isEmpty()) {
            setStatus("Nothing recorded yet");
            return;
        }
        playing = true;
        looping = loop;
        run = 1;
        step(0);
    }

    private void step(int i) {
        if (!playing) return;
        if (i >= strokes.size()) {
            if (looping) {
                run++;
                setStatus("Loop run " + run + " in " + LOOP_PAUSE_MS / 1000 + "s");
                handler.postDelayed(() -> step(0), LOOP_PAUSE_MS);
            } else {
                playing = false;
                setStatus("Done · " + strokes.size() + " moves");
            }
            return;
        }
        Stroke s = strokes.get(i);
        handler.postDelayed(() -> {
            if (!playing) return;
            setStatus((looping ? "Run " + run + " · " : "") + "move " + (i + 1) + "/" + strokes.size());
            inject(s, ok -> {
                if (ok) step(i + 1);
                else {
                    playing = false;
                    setStatus("Stopped: gesture interrupted (screen touched?)");
                }
            });
        }, s.gapMs);
    }

    private void stopAll() {
        playing = false;
        passingThrough = false;
        calibrating = false;
        if (ai != null) ai.stop();
        handler.removeCallbacksAndMessages(null);
        barBlocks = 0;
        setBarTouchable(true);
        if (capture != null) {
            removeCapture();
            save();
        }
        setStatus("Ready · " + strokes.size() + " moves");
    }

    // ---------------------------------------------------------------- AI, debug view, snapshots

    private void toggleAi() {
        if (ai.isRunning()) {
            stopAll();
            setStatus("AI stopped");
            return;
        }
        stopAll();
        if (Build.VERSION.SDK_INT < 30) {
            setStatus("The AI needs Android 11 or newer");
            return;
        }
        ai.start();
    }

    private void toggleDebug() {
        if (debugView != null) {
            wm.removeView(debugView);
            debugView = null;
            setStatus("Debug off");
            return;
        }
        debugView = new DebugView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(debugView, lp);
        raiseBar();
        debugView.show(ai.lastFrame(), null);
        setStatus("Debug on: cyan = pigs, yellow = buttons, orange = close X");
    }

    /** Saves the current screen (plain + with the AI's findings drawn on) to Pictures/AngryBot. */
    private void snap() {
        setStatus("Snap: taking screenshot…");
        Rect barArea = barRect();
        screenshot(bmp -> {
            if (bmp == null) {
                handler.post(() -> setStatus("Snap failed (needs Android 11+)"));
                return;
            }
            Vision.Frame f = Vision.analyze(bmp, barArea);
            boolean ok = savePng(bmp, "plain");
            Bitmap marked = bmp.copy(Bitmap.Config.ARGB_8888, true);
            DebugView.draw(new Canvas(marked), f, null);
            ok &= savePng(marked, "ai");
            marked.recycle();
            bmp.recycle();
            boolean saved = ok;
            handler.post(() -> {
                if (debugView != null) debugView.show(f, null);
                setStatus(saved
                        ? "Saved to Pictures/AngryBot · AI sees " + f.pigs.size() + " pigs, " + f.buttons.size() + " buttons"
                        : "Snap: saving failed");
            });
        });
    }

    private boolean savePng(Bitmap b, String tag) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "angrybot_" + System.currentTimeMillis() + "_" + tag + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AngryBot");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                return os != null && b.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
        } catch (Exception e) {
            return false;
        }
    }

    // AiPlayer.Host ------------------------------------------------

    @Override
    public void screenshot(Consumer<Bitmap> cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.accept(null);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, shots, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                Bitmap sw = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                if (hw != null) hw.recycle();
                hb.close();
                cb.accept(sw);
            }

            @Override
            public void onFailure(int errorCode) {
                cb.accept(null);
            }
        });
    }

    @Override
    public void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        dispatchAi(p, 60, done);
    }

    @Override
    public void swipe(float x1, float y1, float x2, float y2, long durMs, Runnable done) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        dispatchAi(p, durMs, done);
    }

    /** Sends an AI gesture to the game, letting it pass under the control bar. */
    private void dispatchAi(Path p, long durMs, Runnable done) {
        barBlocks++;
        setBarTouchable(false);
        handler.postDelayed(() -> {
            Runnable finish = () -> {
                if (--barBlocks <= 0) {
                    barBlocks = 0;
                    setBarTouchable(true);
                }
                if (done != null) done.run();
            };
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, durMs)))
                    .build();
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { finish.run(); }
                @Override public void onCancelled(GestureDescription d) { finish.run(); }
            }, null);
            if (!sent) finish.run();
        }, PASS_THROUGH_DELAY_MS);
    }

    @Override
    public void status(String s) {
        setStatus(s);
    }

    @Override
    public void showDebug(Vision.Frame f, float[] aim) {
        if (debugView != null) debugView.show(f, aim);
    }

    @Override
    public Rect barRect() {
        int[] loc = new int[2];
        bar.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + bar.getWidth(), loc[1] + bar.getHeight());
    }

    @Override
    public boolean gameInFront() {
        // unknown (service just started with the game already open) counts as "yes"
        return frontPackage == null || frontPackage.contains("rovio");
    }

    @Override
    public int screenW() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p.x;
    }

    @Override
    public int screenH() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p.y;
    }

    // ---------------------------------------------------------------- gesture injection (recordings)

    interface Done { void run(boolean ok); }

    private void inject(Stroke s, Done done) {
        Path path = new Path();
        path.moveTo(Math.max(0, s.xs[0]), Math.max(0, s.ys[0]));
        for (int i = 1; i < s.xs.length; i++) {
            path.lineTo(Math.max(0, s.xs[i]), Math.max(0, s.ys[i]));
        }
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, s.durMs))
                .build();
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done.run(true); }
            @Override public void onCancelled(GestureDescription d) { done.run(false); }
        }, null);
        if (!sent) done.run(false);
    }

    // ---------------------------------------------------------------- storage

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Stroke s : strokes) {
                JSONObject o = new JSONObject();
                o.put("gap", s.gapMs);
                o.put("dur", s.durMs);
                JSONArray xs = new JSONArray(), ys = new JSONArray();
                for (int i = 0; i < s.xs.length; i++) {
                    xs.put((double) s.xs[i]);
                    ys.put((double) s.ys[i]);
                }
                o.put("xs", xs);
                o.put("ys", ys);
                arr.put(o);
            }
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
        }
    }

    private void load() {
        strokes.clear();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Stroke s = new Stroke();
                s.gapMs = o.getLong("gap");
                s.durMs = o.getLong("dur");
                JSONArray xs = o.getJSONArray("xs"), ys = o.getJSONArray("ys");
                s.xs = new float[xs.length()];
                s.ys = new float[ys.length()];
                for (int j = 0; j < xs.length(); j++) {
                    s.xs[j] = (float) xs.getDouble(j);
                    s.ys[j] = (float) ys.getDouble(j);
                }
                strokes.add(s);
            }
        } catch (Exception e) {
            strokes.clear();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- capture layer

    /** Full-screen see-through layer that records one finger and draws the drag so you can aim. */
    static class CaptureView extends View {
        private final BotService service;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path drawPath = new Path();
        private final List<float[]> pts = new ArrayList<>();
        private long downTime;

        CaptureView(BotService service) {
            super(service);
            this.service = service;
            paint.setColor(0xCCFF5252);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8f);
            setBackgroundColor(0x14FF0000);  // faint red tint = recording
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            if (service.passingThrough) {  // our own forwarded gesture echoing back - don't record or draw it
                pts.clear();
                drawPath.reset();
                invalidate();
                return true;
            }
            if (pts.isEmpty() && e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pts.clear();
                    pts.add(new float[]{x, y});
                    downTime = e.getEventTime();
                    drawPath.reset();
                    drawPath.moveTo(x, y);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float[] last = pts.get(pts.size() - 1);
                    if (Math.hypot(x - last[0], y - last[1]) >= MIN_STEP_PX) {
                        pts.add(new float[]{x, y});
                        drawPath.lineTo(x, y);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    float[] end = pts.get(pts.size() - 1);
                    if (end[0] != x || end[1] != y) pts.add(new float[]{x, y});  // exact release point matters
                    service.onGestureCaptured(new ArrayList<>(pts), e.getEventTime() - downTime);
                    pts.clear();
                    drawPath.reset();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pts.clear();
                    drawPath.reset();
                    break;
            }
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawPath(drawPath, paint);
        }
    }
}
