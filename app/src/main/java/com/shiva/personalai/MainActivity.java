package com.shiva.personalai;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private Store store;
    private TextView textHerName, textStatus, textNext;
    private View dotStatus, micRing;
    private Switch switchAuto;
    private LinearLayout chatList;
    private ScrollView scrollChat;
    private ObjectAnimator pulse;
    private int renderedCount = -1;
    private boolean updatingSwitch;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (Store.KEY_MESSAGES.equals(key)) renderChat();
        else if (Store.KEY_STATE.equals(key) || Store.KEY_NEXT_AT.equals(key) || Store.KEY_SERVICE_ON.equals(key)) renderStatus();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        textHerName = findViewById(R.id.textHerName);
        textStatus = findViewById(R.id.textStatus);
        textNext = findViewById(R.id.textNext);
        dotStatus = findViewById(R.id.dotStatus);
        micRing = findViewById(R.id.micRing);
        switchAuto = findViewById(R.id.switchAuto);
        chatList = findViewById(R.id.chatList);
        scrollChat = findViewById(R.id.scrollChat);
        ImageButton btnMic = findViewById(R.id.btnMic);

        switchAuto.setOnCheckedChangeListener((v, on) -> {
            if (updatingSwitch) return;
            if (on) {
                if (!ensurePermissions()) {
                    updatingSwitch = true;
                    switchAuto.setChecked(false);
                    updatingSwitch = false;
                    return;
                }
                startForegroundService(serviceIntent(CheckInService.ACTION_START).putExtra(CheckInService.EXTRA_GREET, true));
                store.setServiceOn(true);
                askBatteryOptimization();
            } else {
                startService(serviceIntent(CheckInService.ACTION_STOP));
                store.setServiceOn(false);
            }
            renderStatus();
        });

        btnMic.setOnClickListener(v -> {
            if (!ensurePermissions()) return;
            startForegroundService(serviceIntent(CheckInService.ACTION_TALK));
        });

        findViewById(R.id.btnNow).setOnClickListener(v -> {
            if (!ensurePermissions()) return;
            startForegroundService(serviceIntent(CheckInService.ACTION_CHECK_IN));
        });

        findViewById(R.id.btnTodo).setOnClickListener(v -> startActivity(new Intent(this, TodoActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnLearnings).setOnClickListener(v -> showLearnings());

        textHerName.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setMessage("Chat history clear karein?")
                    .setPositiveButton("Clear", (d, w) -> store.clearMessages())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        if (!store.onboarded()) {
            store.setOnboarded();
            requestRuntimePermissions();
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        textHerName.setText(store.herName());
        renderedCount = -1;
        renderChat();
        renderStatus();
        // Re-sending START while the app is visible lets the service (re)gain microphone access.
        if (store.serviceOn() && hasMic()) startForegroundService(serviceIntent(CheckInService.ACTION_START));
    }

    @Override
    protected void onPause() {
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onPause();
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, CheckInService.class).setAction(action);
    }

    // ---------- rendering ----------

    private void renderStatus() {
        boolean on = store.serviceOn();
        String state = store.state();
        updatingSwitch = true;
        switchAuto.setChecked(on);
        updatingSwitch = false;

        String status;
        if ("listening".equals(state)) status = "Sun rahi hai...";
        else if ("thinking".equals(state)) status = "Soch rahi hai...";
        else if ("speaking".equals(state)) status = "Bol rahi hai...";
        else status = on ? "Online" : "Offline";
        textStatus.setText(status);
        dotStatus.setAlpha(on ? 1f : 0.3f);

        long nextAt = store.nextAt();
        if (!on) textNext.setText("Band hai. On karo, wo khud puchegi.");
        else if (nextAt > 0) textNext.setText("Har " + store.intervalMin() + " min. Agla: "
                + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(nextAt)));
        else textNext.setText("Har " + store.intervalMin() + " min");

        if ("listening".equals(state)) startPulse();
        else stopPulse("speaking".equals(state) || "thinking".equals(state) ? 0.5f : 0f);
    }

    private void startPulse() {
        if (pulse != null && pulse.isRunning()) return;
        micRing.setAlpha(1f);
        pulse = ObjectAnimator.ofPropertyValuesHolder(micRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.25f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.25f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.3f));
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse(float alpha) {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        micRing.setScaleX(1f);
        micRing.setScaleY(1f);
        micRing.setAlpha(alpha);
    }

    private void renderChat() {
        List<Store.Msg> msgs = store.messages();
        if (msgs.size() == renderedCount) return;
        renderedCount = msgs.size();
        chatList.removeAllViews();
        if (msgs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Auto check-in on karo, " + store.herName() + " khud aapse baat karegi.\nYa mic dabao aur kuch bhi bolo.");
            empty.setTextColor(getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            chatList.addView(empty);
            return;
        }
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        int from = Math.max(0, msgs.size() - 40);
        for (int i = from; i < msgs.size(); i++) {
            Store.Msg m = msgs.get(i);
            boolean ai = "ai".equals(m.role);
            TextView tv = new TextView(this);
            tv.setText(m.text);
            tv.setTextSize(15);
            tv.setTextColor(getColor(R.color.text));
            tv.setMaxWidth(maxWidth);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setBackgroundResource(ai ? R.drawable.bg_bubble_ai : R.drawable.bg_bubble_user);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(ai ? Gravity.START : Gravity.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.addView(tv);
            chatList.addView(row, lp);
        }
        scrollChat.post(() -> scrollChat.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showLearnings() {
        List<String> items = store.learnings();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Aapne kya seekha");
        if (items.isEmpty()) b.setMessage("Abhi kuch nahi. Jab wo puchegi \"aaj kya seekha?\" to aapka jawab yahan save hoga.");
        else b.setItems(items.toArray(new String[0]), null);
        b.setPositiveButton("OK", null).show();
    }

    // ---------- permissions ----------

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensurePermissions() {
        if (hasMic()) return true;
        requestRuntimePermissions();
        Toast.makeText(this, "Mic permission do, phir dobara dabao", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    private void askBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }
}
