package com.angrybot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Floating control bar over the game.
 * Rec  – a transparent layer catches each drag/tap you make, saves it, and passes it on to the game.
 * Play – replays the saved gestures with the same timing.  Loop – replays forever until Stop.
 */
public class BotService extends AccessibilityService {

    private static final String PREFS = "angrybot";
    private static final String KEY = "level11";
    private static final long FIRST_GAP_MS = 1000;   // wait before the first gesture on Play
    private static final long LOOP_PAUSE_MS = 3000;  // wait between loop runs
    private static final float MIN_STEP_PX = 4f;     // drop path points closer than this

    /** One finger gesture: wait gapMs after the previous one finished, then follow the points over durMs. */
    static class Stroke {
        long gapMs, durMs;
        float[] xs, ys;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Stroke> strokes = new ArrayList<>();

    private WindowManager wm;
    private LinearLayout bar;
    private WindowManager.LayoutParams barLp;
    private TextView status;
    private CaptureView capture;
    private WindowManager.LayoutParams captureLp;

    private boolean playing, looping;
    private int run;
    private long lastInjectEnd;  // uptime when the previous recorded gesture finished replaying

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        load();
        showBar();
        setStatus("Ready · " + strokes.size() + " moves");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        stopAll();
    }

    @Override
    public void onDestroy() {
        if (wm != null) {
            stopAll();
            if (bar != null) wm.removeView(bar);
        }
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

        addButton("● Rec", v -> startRecording());
        addButton("▶ Play", v -> startPlaying(false));
        addButton("⟳ Loop", v -> startPlaying(true));
        addButton("■ Stop", v -> stopAll());
        addButton("↶ Undo", v -> undo());

        status = new TextView(this);
        status.setTextColor(0xFFFFD54F);
        status.setTextSize(10);
        status.setPadding(8, 4, 8, 4);
        status.setMaxWidth(260);
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

    private void addButton(String label, View.OnClickListener l) {
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
        bar.addView(b);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    // ---------------------------------------------------------------- recording

    private void startRecording() {
        stopAll();
        strokes.clear();
        lastInjectEnd = 0;

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
        // keep the control bar above the capture layer
        wm.removeView(bar);
        wm.addView(bar, barLp);
        setStatus("REC · 0 moves");
    }

    /** Called by CaptureView when a finger is lifted. */
    void onGestureCaptured(List<float[]> pts, long durMs) {
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
        strokes.add(s);
        lastInjectEnd = now + s.durMs;
        save();
        setStatus("REC · " + strokes.size() + " moves");

        // let the gesture through to the game: make our layer see-through for touches while it plays
        setCaptureTouchable(false);
        inject(s, ok -> setCaptureTouchable(true));
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
        handler.removeCallbacksAndMessages(null);
        if (capture != null) {
            wm.removeView(capture);
            capture = null;
            save();
        }
        setStatus("Ready · " + strokes.size() + " moves");
    }

    // ---------------------------------------------------------------- gesture injection

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
