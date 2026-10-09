package com.shiva.personalai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Single-activity app with a bottom navigation bar: Home (conversation), Tasks, Journal, Settings. */
public class MainActivity extends Activity {

    public static volatile boolean visible;
    public static final String EXTRA_REPLY = "reply";

    private Store store;
    private Speaker speaker;
    private HomeTab home;
    private TasksTab tasks;
    private JournalTab journal;
    private SettingsTab settings;
    private final View[] tabs = new View[4];
    private final View[] pills = new View[4];
    private final ImageView[] icons = new ImageView[4];
    private final TextView[] labels = new TextView[4];
    private int current;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (key == null) return;
        home.onPrefChanged(key);
        if ("todos".equals(key)) tasks.refresh();
        if ("learnings".equals(key)) journal.refresh();
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
        int[] pill = {R.id.navHomePill, R.id.navTasksPill, R.id.navJournalPill, R.id.navSettingsPill};
        int[] icon = {R.id.navHomeIcon, R.id.navTasksIcon, R.id.navJournalIcon, R.id.navSettingsIcon};
        int[] label = {R.id.navHomeLabel, R.id.navTasksLabel, R.id.navJournalLabel, R.id.navSettingsLabel};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            pills[i] = findViewById(pill[i]);
            icons[i] = findViewById(icon[i]);
            labels[i] = findViewById(label[i]);
            findViewById(nav[i]).setOnClickListener(v -> openTab(idx));
        }

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
        visible = true;
        store.prefs().registerOnSharedPreferenceChangeListener(prefsListener);
        home.refresh();
        tasks.refresh();
        journal.refresh();
        settings.refresh();
        // While the app is on screen the service may (re)acquire microphone access for background replies.
        if (store.serviceOn()) CheckInService.send(this, CheckInService.ACTION_RESTART);
        handleIntent(getIntent());
    }

    @Override
    protected void onPause() {
        visible = false;
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
            pills[j].setBackgroundResource(sel ? R.drawable.bg_nav_pill : 0);
            icons[j].setImageTintList(ColorStateList.valueOf(sel ? accent : muted));
            labels[j].setTextColor(sel ? getColor(R.color.text) : muted);
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(tabs[i].getWindowToken(), 0);
    }

    // ---------- actions used by the tabs ----------

    Store store() { return store; }

    Speaker speaker() { return speaker; }

    void talk(String text) {
        if (text == null && !ensureMic()) return;
        Intent i = new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_TALK);
        if (text != null) i.putExtra(CheckInService.EXTRA_TEXT, text);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the assistant", Toast.LENGTH_SHORT).show();
        }
    }

    void checkInNow() {
        CheckInService.send(this, CheckInService.ACTION_CHECK_IN);
    }

    void setAuto(boolean on) {
        if (on) {
            requestRuntimePermissions();
            Intent i = new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_START)
                    .putExtra(CheckInService.EXTRA_GREET, true);
            store.setServiceOn(true);
            startForegroundService(i);
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
        EditText input = new EditText(this);
        input.setHint("Your name");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(store.name());
        FrameLayout box = new FrameLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Welcome")
                .setMessage("What should " + store.herName() + " call you?")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Continue", (d, w) -> {
                    store.setName(input.getText().toString());
                    store.setOnboarded();
                    requestRuntimePermissions();
                    home.refresh();
                    settings.refresh();
                })
                .show();
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
