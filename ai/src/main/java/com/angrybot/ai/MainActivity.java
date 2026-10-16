package com.angrybot.ai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup screen: explains the steps and opens Accessibility settings to switch the AI on. */
public class MainActivity extends Activity {

    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("AngryBot AI");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_VERTICAL);
        Drawable logo = getApplicationInfo().loadIcon(getPackageManager());
        logo.setBounds(0, 0, 160, 160);
        title.setCompoundDrawablesRelative(logo, null, null, null);
        title.setCompoundDrawablePadding(24);
        root.addView(title);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setPadding(0, 24, 0, 24);
        help.setText(
                "1. Tap the button below and turn on AngryBot AI under Accessibility (Downloaded / Installed apps).\n\n"
                + "2. Open Angry Birds 2 and start a level. Tap 🎯 Calib on the purple bar, then pull the bird on the "
                + "slingshot ALL the way back and let go. This shows the AI where the slingshot is (once).\n\n"
                + "3. Tap 🧠 AI and tap Start on Android's screen-capture prompt. The AI watches a live feed of the "
                + "screen (nothing is saved) and plays: presses Play/Continue/Retry, closes pop-ups and fires at pigs, "
                + "getting more accurate after each shot. It never presses buttons that cost gems.\n\n"
                + "4. ■ Stop ends it. 👀 Watch shows what the AI sees without making moves (cyan = pigs, "
                + "yellow = buttons, orange = close X). 📸 Snap saves that view to Pictures/AngryBot.");
        root.addView(help);

        Button open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        state = new TextView(this);
        state.setPadding(0, 24, 0, 0);
        root.addView(state);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName() + "/");
        state.setText(on ? "✅ AngryBot AI is on. Open Angry Birds 2." : "AngryBot AI is off.");
    }
}
