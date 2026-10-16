package com.angrybot.ai;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible screen that asks Android for permission to watch the screen, then starts CaptureService. */
public class CaptureActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, CaptureService.class)
                    .putExtra("code", resultCode)
                    .putExtra("data", data));
        } else if (AiService.instance != null) {
            AiService.instance.onCaptureDenied();
        }
        finish();
    }
}
