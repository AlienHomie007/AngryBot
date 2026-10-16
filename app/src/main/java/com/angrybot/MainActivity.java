package com.angrybot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup screen: explains the steps and opens Accessibility settings to switch the bot on. */
public class MainActivity extends Activity {

    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("AngryBot");
        title.setTextSize(28);
        root.addView(title);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setPadding(0, 24, 0, 24);
        help.setText(
                "1. Tap the button below and turn on AngryBot under Accessibility (Downloaded / Installed apps).\n\n"
                + "2. A small control bar appears. Drag it by its top label so it's out of the way.\n\n"
                + "3. Open Angry Birds 2 and go to level 11's start screen.\n\n"
                + "4. Tap ● Rec and play the level. Each drag or tap is sent to the game once you lift your finger, "
                + "so let go and wait for each move to happen.\n\n"
                + "5. Tap ■ Stop when you've won. Go back to the same start screen and tap ▶ Play, "
                + "or ⟳ Loop to repeat it until you tap ■ Stop.");
        root.addView(help);

        Button open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        state = new TextView(this);
        state.setPadding(0, 24, 0, 0);
        root.addView(state);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName() + "/");
        state.setText(on ? "✅ AngryBot is on. Open Angry Birds 2." : "AngryBot is off.");
    }
}
