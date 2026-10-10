package com.shiva.personalai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Single-activity app with an iOS-style tab bar: Chat, Tasks, Journal, Settings. */
public class MainActivity extends Activity {

    public static final String EXTRA_REPLY = "reply";

    private Store store;
    private Speaker speaker;
    private HomeTab home;
    private TasksTab tasks;
    private JournalTab journal;
    private SettingsTab settings;
    private final View[] tabs = new View[4];
    private final ImageView[] icons = new ImageView[4];
    private final TextView[] labels = new TextView[4];
    private TextView badge;
    private int current;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (key == null) return;
        home.onPrefChanged(key);
        if ("todos".equals(key)) {
            tasks.refresh();
            updateBadge();
        }
        if ("learnings".equals(key)) journal.refresh();
        if (Store.KEY_SERVICE_ON.equals(key) || Store.KEY_NEXT_AT.equals(key)) settings.refresh();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);
        speaker = new Speaker(this, store);

        tabs[0] = findViewById(R.id.tabHome);
        tabs[1] = findViewById(R.id.tabTasks);
        tabs[2] = findViewById(R.id.tabJournal);
        tabs[3] = findViewById(R.id.tabSettings);
        int[] nav = {R.id.navHome, R.id.navTasks, R.id.navJournal, R.id.navSettings};
        int[] icon = {R.id.navHomeIcon, R.id.navTasksIcon, R.id.navJournalIcon, R.id.navSettingsIcon};
        int[] label = {R.id.navHomeLabel, R.id.navTasksLabel, R.id.navJournalLabel, R.id.navSettingsLabel};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            icons[i] = findViewById(icon[i]);
            labels[i] = findViewById(label[i]);
            findViewById(nav[i]).setOnClickListener(v -> openTab(idx));
        }
        badge = findViewById(R.id.navBadge);

        home = new HomeTab(this, tabs[0]);
        tasks = new TasksTab(this, tabs[1]);
        journal = new JournalTab(this, tabs[2]);
        settings = new SettingsTab(this, tabs[3]);

        openTab(savedInstanceState == null ? 0 : savedInstanceState.getInt("tab", 0));
        if (!store.onboarded()) showOnboarding();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", current);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Foreground.resumed();
        store.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        home.refresh();
        tasks.refresh();
        journal.refresh();
        settings.refresh();
        updateBadge();
        if (store.serviceOn()) CheckInService.send(this, CheckInService.ACTION_RESTART);
        if (!CheckInService.running) {
            store.setCallState("none");
            store.setState("idle");
        } else if (!"none".equals(store.callState())) {
            openCallScreen(null);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onPause() {
        Foreground.paused();
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        settings.destroy();
        super.onDestroy();
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_REPLY, false)) {
            i.removeExtra(EXTRA_REPLY);
            openTab(0);
            talk(null);
        }
    }

    // ---------- navigation ----------

    void openTab(int i) {
        current = i;
        int accent = getColor(R.color.accent);
        int muted = getColor(R.color.muted);
        for (int j = 0; j < 4; j++) {
            boolean sel = j == i;
            tabs[j].setVisibility(sel ? View.VISIBLE : View.GONE);
            icons[j].setImageTintList(ColorStateList.valueOf(sel ? accent : muted));
            labels[j].setTextColor(sel ? accent : muted);
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(tabs[i].getWindowToken(), 0);
    }

    private void updateBadge() {
        int n = store.pendingTexts().size();
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        badge.setText(n > 99 ? "99+" : String.valueOf(n));
    }

    // ---------- actions used by the tabs ----------

    Store store() { return store; }

    Speaker speaker() { return speaker; }

    void talk(String text) {
        if (text == null && !ensureMic()) return;
        Intent i = new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_TALK);
        if (text != null) i.putExtra(CheckInService.EXTRA_TEXT, text);
        CheckInService.send(this, i);
    }

    /** User phones her. */
    void callHer() {
        if (!ensureMic()) return;
        openCallScreen(CallActivity.ACTION_OUTGOING);
    }

    /** She phones the user right now (test the incoming call). */
    void testIncomingCall() {
        requestRuntimePermissions();
        CheckInService.send(this, CheckInService.ACTION_CHECK_IN);
    }

    void openCallScreen(String action) {
        Intent i = new Intent(this, CallActivity.class);
        if (action != null) i.setAction(action);
        startActivity(i);
    }

    void setAuto(boolean on) {
        if (on) {
            requestRuntimePermissions();
            store.setServiceOn(true);
            CheckInService.send(this, new Intent(this, CheckInService.class)
                    .setAction(CheckInService.ACTION_START).putExtra(CheckInService.EXTRA_GREET, true));
            askBatteryOptimization();
        } else {
            store.setServiceOn(false);
            startService(new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_STOP));
        }
    }

    void reschedule() {
        if (store.serviceOn()) CheckInService.send(this, CheckInService.ACTION_RESCHEDULE);
    }

    // ---------- permissions ----------

    boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureMic() {
        if (hasMic()) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
        Toast.makeText(this, "Allow microphone access to talk", Toast.LENGTH_SHORT).show();
        return false;
    }

    void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO);
        if (!hasNotificationPermission()) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (store.serviceOn() && hasMic()) CheckInService.send(this, CheckInService.ACTION_RESTART);
        settings.refresh();
    }

    boolean ignoringBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    void askBatteryOptimization() {
        if (ignoringBatteryOptimizations()) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    // ---------- onboarding ----------

    private void showOnboarding() {
        Ios.input(this, "Welcome", "What should " + store.herName() + " call you?", store.name(), "Your name",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, false, name -> {
                    store.setName(name);
                    store.setOnboarded();
                    requestRuntimePermissions();
                    home.refresh();
                    settings.refresh();
                });
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
