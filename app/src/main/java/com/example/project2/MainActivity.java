package com.example.project2;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PREMIUM MOBILE UI: Cyberpunk / Glassmorphism Dashboard.
 * Designed to mirror the high-aesthetic web interface.
 */
public class MainActivity extends Activity {
    private static final String TAG = "spotifybot";

    // Cyberpunk Color Palette
    private static final int COLOR_BG = 0xFF0A0A0F;
    private static final int COLOR_ACCENT = 0xFF00BFFF; // Neon Blue
    private static final int COLOR_SUCCESS = 0xFF00FF94; // Cyber Green
    private static final int COLOR_GLASS = 0x1AFFFFFF; // Translucent White

    private TextView statusLabel;
    private View pulseDot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String shortId = deviceId.length() > 8 ? deviceId.substring(deviceId.length() - 8) : deviceId;

        // 1. Root Layout (Deep Space Background)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 60, 60, 60);

        // 2. Header: Logo & Title
        TextView logo = new TextView(this);
        logo.setText("SPOTIFY AUTOMATION");
        logo.setTextColor(COLOR_ACCENT);
        logo.setTextSize(26);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setLetterSpacing(0.2f);
        logo.setGravity(Gravity.CENTER);

        // 3. Status Card (Glassmorphism)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(50, 50, 50, 50);
        card.setGravity(Gravity.CENTER);

        GradientDrawable glassBg = new GradientDrawable();
        glassBg.setColor(COLOR_GLASS);
        glassBg.setCornerRadius(30);
        glassBg.setStroke(2, 0x33FFFFFF);
        card.setBackground(glassBg);

        // Pulse Icon
        pulseDot = new View(this);
        int dotSize = 40;
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotParams.setMargins(0, 0, 0, 30);
        pulseDot.setLayoutParams(dotParams);

        // Animation for Pulse Dot
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(pulseDot,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.4f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.4f),
                PropertyValuesHolder.ofFloat("alpha", 1.0f, 0.4f));
        pulse.setDuration(1500);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();

        statusLabel = new TextView(this);
        statusLabel.setText("SERVICE: CHECKING...");
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(14);
        statusLabel.setTypeface(Typeface.MONOSPACE);

        card.addView(pulseDot);
        card.addView(statusLabel);

        // 4. Action Button (Cyber Style)
        Button actionBtn = new Button(this);
        actionBtn.setText("OPEN SETTINGS");
        actionBtn.setBackgroundColor(Color.TRANSPARENT);
        actionBtn.setTextColor(COLOR_ACCENT);
        actionBtn.setPadding(40, 30, 40, 30);
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(15);
        btnBg.setStroke(3, COLOR_ACCENT);
        actionBtn.setBackground(btnBg);
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 60, 0, 0);
        actionBtn.setLayoutParams(btnParams);

        actionBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        // 5. Device Info
        TextView deviceTxt = new TextView(this);
        deviceTxt.setText("DEVICE ID: " + shortId);
        deviceTxt.setTextColor(0x88FFFFFF);
        deviceTxt.setTextSize(12);
        deviceTxt.setPadding(0, 40, 0, 0);

        root.addView(logo);
        root.addView(card);
        root.addView(actionBtn);
        root.addView(deviceTxt);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        statusLabel.setText(enabled ? "SERVICE: ACTIVE" : "SERVICE: OFF");
        statusLabel.setTextColor(enabled ? COLOR_SUCCESS : Color.RED);
        
        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(enabled ? COLOR_SUCCESS : Color.RED);
        pulseDot.setBackground(dotDrawable);
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + MyAccessibilityService.class.getName();
        try {
            int accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return settingValue != null && settingValue.toLowerCase().contains(service.toLowerCase());
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
