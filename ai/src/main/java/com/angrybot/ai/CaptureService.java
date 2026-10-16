package com.angrybot.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Live screen feed. Android mirrors the screen into a small (FRAME_W wide) image in memory,
 * 30-60 times a second; every new frame goes straight to the listener on a background thread.
 * Nothing is written to storage.
 */
public class CaptureService extends Service {

    interface FrameListener {
        /** Called on the frame thread. px is ARGB, w x h, scaled down from the real screen. */
        void onFrame(int[] px, int w, int h);
    }

    static final int FRAME_W = 480;

    static volatile CaptureService instance;
    static volatile FrameListener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    volatile int screenW, screenH;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || projection != null) return START_NOT_STICKY;

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture", "Screen watching",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "capture")
                .setContentTitle("AngryBot AI is watching the screen")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = data == null ? null : mpm.getMediaProjection(code, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, main);

        Point p = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        screenW = p.x;
        screenH = p.y;
        int frameH = Math.max(1, Math.round(FRAME_W * (float) p.y / p.x));

        thread = new HandlerThread("angrybot-frames");
        thread.start();
        Handler frames = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(FRAME_W, frameH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, frames);
        display = projection.createVirtualDisplay("AngryBot", FRAME_W, frameH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, frames);

        instance = this;
        AiService ai = AiService.instance;
        if (ai != null) main.post(ai::onCaptureStarted);
        return START_NOT_STICKY;
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();   // skips frames we were too slow for
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        int[] px;
        int w, h;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int ps = plane.getPixelStride(), rs = plane.getRowStride();
            w = img.getWidth();
            h = img.getHeight();
            px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int row = y * rs;
                for (int x = 0; x < w; x++) {
                    int i = row + x * ps;
                    px[y * w + x] = 0xFF000000 | ((buf.get(i) & 0xFF) << 16)
                            | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF);
                }
            }
        } finally {
            img.close();
        }
        FrameListener l = listener;
        if (l != null) l.onFrame(px, w, h);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        if (thread != null) thread.quitSafely();
        AiService ai = AiService.instance;
        if (ai != null) main.post(ai::onCaptureEnded);
        super.onDestroy();
    }
}
