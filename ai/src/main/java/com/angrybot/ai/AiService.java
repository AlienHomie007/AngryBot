package com.angrybot.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.OutputStream;

/**
 * AngryBot AI's floating bar and hands.
 *  🧠 AI    – start playing (asks once for the live screen feed)
 *  👀 Watch – live feed + boxes, but no moves: check what the AI sees
 *  ■ Stop   – stop playing and end the screen feed
 *  🎯 Calib – pull the slingshot all the way back once so the AI knows where it is
 *  👁 Boxes – show/hide the detection boxes
 *  📸 Snap  – save the current frame (plain + with boxes) to Pictures/AngryBot
 */
public class AiService extends AccessibilityService implements Brain.Host, CaptureService.FrameListener {

    static volatile AiService instance;

    private static final long PASS_THROUGH_DELAY_MS = 150;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout bar;
    private WindowManager.LayoutParams barLp;
    private TextView status;
    private DebugView debugView;
    private CalibView calib;
    private WindowManager.LayoutParams calibLp;
    private Brain brain;

    private volatile Rect barArea;
    private volatile String frontPackage;
    private volatile int screenW, screenH;
    private volatile Vision.Frame lastFrame;
    private boolean wantActing, showBoxes = true;
    private int barBlocks;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        brain = new Brain(this, getSharedPreferences("angrybot_ai", MODE_PRIVATE));
        updateScreenSize();
        showBar();
        setStatus(brain.isCalibrated() ? "Ready · open Angry Birds 2 and tap 🧠 AI" : "Ready · tap 🎯 Calib first");
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
        stopEverything();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        if (wm != null) {
            if (debugView != null) wm.removeView(debugView);
            if (calib != null) wm.removeView(calib);
            if (bar != null) wm.removeView(bar);
        }
        instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- bar

    @SuppressLint("ClickableViewAccessibility")
    private void showBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xCC2D1B4E);
        bar.setPadding(6, 6, 6, 6);

        TextView handle = new TextView(this);
        handle.setText("⠿ AngryBot AI");
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
        addButton(row, "🧠 AI", v -> start(true));
        addButton(row, "👀 Watch", v -> start(false));
        addButton(row, "■ Stop", v -> stopEverything());
        row = addRow();
        addButton(row, "🎯 Calib", v -> startCalibration());
        addButton(row, "👁 Boxes", v -> toggleBoxes());
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
        // remember where the bar is so the AI ignores it and gestures can pass under it
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            barArea = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        });
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
        if (touchable) barLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else barLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(bar, barLp);
    }

    private void raiseBar() {
        wm.removeView(bar);
        wm.addView(bar, barLp);
    }

    private void updateScreenSize() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        screenW = p.x;
        screenH = p.y;
    }

    // ---------------------------------------------------------------- start / stop

    private void start(boolean act) {
        removeCalib();
        if (act && !brain.isCalibrated()) {
            setStatus("Tap 🎯 Calib first");
            return;
        }
        wantActing = act;
        updateScreenSize();
        showDebugLayer();
        if (CaptureService.instance != null) {
            CaptureService.listener = this;
            brain.setActing(act);
            setStatus(act ? "AI playing" : "Watching (no moves)");
            return;
        }
        brain.setActing(false);
        setStatus("Tap Start on the screen-capture prompt");
        startActivity(new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** CaptureService is up: frames start flowing. */
    void onCaptureStarted() {
        CaptureService.listener = this;
        brain.setActing(wantActing);
        setStatus(wantActing ? "AI playing" : "Watching (no moves)");
    }

    void onCaptureDenied() {
        setStatus("Screen capture was not allowed - the AI can't see the game");
    }

    void onCaptureEnded() {
        CaptureService.listener = null;
        brain.setActing(false);
        setStatus("Screen feed ended");
    }

    private void stopEverything() {
        if (brain != null) brain.setActing(false);
        CaptureService.listener = null;
        stopService(new Intent(this, CaptureService.class));
        removeCalib();
        handler.removeCallbacksAndMessages(null);
        if (bar != null) {
            barBlocks = 0;
            setBarTouchable(true);
        }
        setStatus("Stopped");
    }

    // ---------------------------------------------------------------- frames (frame thread)

    @Override
    public void onFrame(int[] px, int w, int h) {
        float scale = screenW / (float) w;
        Vision.Frame f = Vision.analyze(px, w, h, scale, barArea);
        lastFrame = f;
        brain.onFrame(f);
    }

    // ---------------------------------------------------------------- calibration

    private void startCalibration() {
        if (brain.isActing()) brain.setActing(false);
        removeCalib();
        calib = new CalibView(this);
        calibLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        calibLp.gravity = Gravity.TOP | Gravity.START;
        calibLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(calib, calibLp);
        raiseBar();
        setStatus("CALIB: pull the bird on the slingshot ALL the way back, then let go");
    }

    private void removeCalib() {
        if (calib == null) return;
        wm.removeView(calib);
        calib = null;
    }

    /** Finger lifted on the calibration layer: save it, then let the same pull through to fire the bird. */
    void onCalibrated(float x1, float y1, float x2, float y2, long durMs) {
        float pull = (float) Math.hypot(x2 - x1, y2 - y1);
        if (pull < 60) {
            setStatus("CALIB: too short - pull further back");
            return;
        }
        brain.saveCalibration(x1, y1, pull);
        removeCalib();
        setStatus("Calibrated ✓ Now tap 🧠 AI");
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        handler.postDelayed(() -> dispatch(p, Math.max(200, durMs), null), PASS_THROUGH_DELAY_MS);
    }

    /** Full-screen layer that catches the one calibration pull and draws it. */
    static final class CalibView extends View {
        private final AiService service;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x1, y1, x2, y2;
        private long down;
        private boolean dragging;

        CalibView(AiService service) {
            super(service);
            this.service = service;
            paint.setColor(0xCCFFEB3B);
            paint.setStrokeWidth(8f);
            setBackgroundColor(0x14FFEB3B);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x1 = x2 = e.getRawX();
                    y1 = y2 = e.getRawY();
                    down = e.getEventTime();
                    dragging = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    x2 = e.getRawX();
                    y2 = e.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    dragging = false;
                    service.onCalibrated(x1, y1, e.getRawX(), e.getRawY(), e.getEventTime() - down);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
            }
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (dragging) c.drawLine(x1, y1, x2, y2, paint);
        }
    }

    // ---------------------------------------------------------------- debug boxes & snapshots

    private void showDebugLayer() {
        if (debugView != null || !showBoxes) return;
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
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(debugView, lp);
        raiseBar();
    }

    private void toggleBoxes() {
        showBoxes = !showBoxes;
        if (showBoxes) {
            showDebugLayer();
            setStatus("Boxes on: cyan = pigs, yellow = buttons, orange = close X");
        } else {
            if (debugView != null) wm.removeView(debugView);
            debugView = null;
            setStatus("Boxes off");
        }
    }

    /** Saves the latest live frame (plain + with boxes) to Pictures/AngryBot. */
    private void snap() {
        Vision.Frame f = lastFrame;
        if (f == null) {
            setStatus("Snap: tap 👀 Watch or 🧠 AI first");
            return;
        }
        Bitmap plain = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888);
        Bitmap marked = plain.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(marked);
        c.scale(1f / f.scale, 1f / f.scale);   // boxes are in screen pixels, the frame is smaller
        DebugView.draw(c, f, null);
        boolean ok = savePng(plain, "plain") & savePng(marked, "ai");
        plain.recycle();
        marked.recycle();
        setStatus(ok ? "Saved to Pictures/AngryBot · " + f.pigs.size() + " pigs, " + f.buttons.size() + " buttons"
                : "Snap: saving failed");
    }

    private boolean savePng(Bitmap b, String tag) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "angrybot_ai_" + System.currentTimeMillis() + "_" + tag + ".png");
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

    // ---------------------------------------------------------------- Brain.Host

    @Override
    public void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        handler.post(() -> dispatch(p, 50, done));
    }

    @Override
    public void swipe(float x1, float y1, float x2, float y2, long durMs, Runnable done) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        handler.post(() -> dispatch(p, durMs, done));
    }

    /** Sends a gesture right away; only if it touches our bar does it first wait for the bar to step aside. */
    private void dispatch(Path p, long durMs, Runnable done) {
        Rect area = barArea;
        android.graphics.RectF bounds = new android.graphics.RectF();
        p.computeBounds(bounds, true);
        boolean underBar = area != null && android.graphics.RectF.intersects(bounds,
                new android.graphics.RectF(area.left - 20, area.top - 20, area.right + 20, area.bottom + 20));
        if (!underBar) {
            send(p, durMs, done);
            return;
        }
        barBlocks++;
        setBarTouchable(false);
        handler.postDelayed(() -> send(p, durMs, () -> {
            if (--barBlocks <= 0) {
                barBlocks = 0;
                setBarTouchable(true);
            }
            if (done != null) done.run();
        }), PASS_THROUGH_DELAY_MS);
    }

    private void send(Path p, long durMs, Runnable done) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, durMs)))
                .build();
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { if (done != null) done.run(); }
            @Override public void onCancelled(GestureDescription d) { if (done != null) done.run(); }
        }, null);
        if (!sent && done != null) done.run();
    }

    @Override
    public void status(String s) {
        handler.post(() -> setStatus(s));
    }

    @Override
    public void showDebug(Vision.Frame f, float[] aim) {
        handler.post(() -> {
            if (debugView != null) debugView.show(f, aim);
        });
    }

    @Override
    public boolean gameInFront() {
        // unknown (service just started with the game already open) counts as "yes"
        String p = frontPackage;
        return p == null || p.contains("rovio");
    }

    @Override
    public int screenW() { return screenW; }

    @Override
    public int screenH() { return screenH; }
}
