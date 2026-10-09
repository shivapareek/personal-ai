package com.shiva.personalai;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Settings: grouped rows, every change is saved immediately. */
class SettingsTab {

    private static final int[] INTERVALS = {10, 15, 20, 30, 45, 60, 90, 120};

    private static class Row {
        TextView subtitle;
        Supplier<String> sub;
        Switch sw;
        Supplier<Boolean> checked;
    }

    private final MainActivity a;
    private final Store s;
    private final LinearLayout container;
    private final List<Row> rows = new ArrayList<>();
    private LinearLayout card;
    private boolean updating;
    private TextToSpeech tts;

    SettingsTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        container = root.findViewById(R.id.settingsList);
        build();
    }

    void destroy() {
        if (tts != null) tts.shutdown();
    }

    // ---------- content ----------

    private void build() {
        section("Profile");
        row("Your name", () -> s.name().isEmpty() ? "Not set" : s.name(),
                () -> edit("Your name", s.name(), InputType.TYPE_TEXT_FLAG_CAP_WORDS, s::setName));
        row("Assistant name", s::herName,
                () -> edit("Assistant name", s.herName(), InputType.TYPE_TEXT_FLAG_CAP_WORDS, s::setHerName));

        section("Check-ins");
        row("Frequency", () -> "Every " + s.intervalMin() + " minutes", this::pickInterval);
        row("Active from", () -> Store.hourLabel(s.startHour()), () -> pickHour(true));
        row("Active until", () -> Store.hourLabel(s.endHour()), () -> pickHour(false));
        toggle("Listen for my reply", () -> "Opens the microphone after she asks a question",
                s::listen, s::setListen);

        section("Voice");
        row("Language", () -> s.hindi() ? "Hindi" : "English", () ->
                choose("Language", new String[]{"Hindi", "English"}, s.hindi() ? 0 : 1, i -> {
                    s.setHindi(i == 0);
                    s.setVoiceName("");
                }));
        toggle("Natural voice", () -> "Neural voice (Swara / Neerja). Needs internet, falls back to the phone voice offline.",
                s::onlineVoice, s::setOnlineVoice);
        row("Offline voice", () -> s.voiceName().isEmpty() ? "Phone default" : s.voiceName(), this::pickDeviceVoice);
        row("Test voice", () -> "Hear how she sounds", () -> {
            Toast.makeText(a, "Playing…", Toast.LENGTH_SHORT).show();
            a.speaker().speak(Messages.testVoice(s, s.hindi()), s.onlineVoice(), s.hindi(), s.voiceName(), () -> { });
        });

        section("AI");
        row("Groq API key", () -> s.aiKey().isEmpty() ? "Not set · using the free built-in AI" : "Set · using Groq",
                () -> edit("Groq API key", s.aiKey(), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, s::setAiKey));
        row("Model", s::aiModel, () -> edit("Model", s.aiModel(), InputType.TYPE_TEXT_VARIATION_URI, s::setAiModel));
        row("API endpoint", s::aiUrl, () -> edit("API endpoint", s.aiUrl(), InputType.TYPE_TEXT_VARIATION_URI, s::setAiUrl));
        note("Optional. Get a free key at console.groq.com for faster, more reliable replies.");

        section("Background reliability");
        row("Notifications", () -> notificationsOk() ? "Allowed" : "Off · tap to allow", this::fixNotifications);
        row("Microphone", () -> a.hasMic() ? "Allowed" : "Not allowed · tap to allow", () -> {
            if (a.hasMic()) a.openAppDetails();
            else a.requestRuntimePermissions();
        });
        row("Battery usage", () -> a.ignoringBatteryOptimizations() ? "Unrestricted" : "Restricted · tap to fix", () -> {
            if (a.ignoringBatteryOptimizations()) a.openAppDetails();
            else a.askBatteryOptimization();
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            row("Alarms & reminders", () -> exactAlarmsOk() ? "Allowed" : "Not allowed · tap to fix", () -> {
                try {
                    a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + a.getPackageName())));
                } catch (Exception e) {
                    a.openAppDetails();
                }
            });
        }
        row("Autostart", () -> "Needed on Xiaomi, Redmi, Oppo, Realme, Vivo, OnePlus, Huawei", this::openAutostart);
        note("Also lock the app in Recent apps (long-press its card, then Lock) so the phone does not close it.");

        section("About");
        row("Clear conversation", () -> "Remove all messages", () -> new AlertDialog.Builder(a)
                .setTitle("Clear conversation?")
                .setMessage("Messages will be removed. Tasks and journal stay.")
                .setPositiveButton("Clear", (d, w) -> s.clearMessages())
                .setNegativeButton("Cancel", null)
                .show());
        row("Version", this::version, null);
    }

    void refresh() {
        updating = true;
        for (Row r : rows) {
            if (r.sub != null) {
                String t = r.sub.get();
                r.subtitle.setText(t);
                r.subtitle.setVisibility(t == null || t.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (r.sw != null) r.sw.setChecked(r.checked.get());
        }
        updating = false;
    }

    // ---------- building blocks ----------

    private void section(String title) {
        TextView label = new TextView(a);
        label.setText(title);
        label.setTextAppearance(R.style.SectionLabel);
        label.setPadding(a.dp(4), a.dp(20), 0, a.dp(8));
        container.addView(label);
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        card.setDividerDrawable(a.getDrawable(R.drawable.divider));
        card.setClipToOutline(true);
        container.addView(card);
    }

    private Row row(String title, Supplier<String> sub, Runnable onClick) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_setting_row, card, false);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        Row r = new Row();
        r.subtitle = v.findViewById(R.id.subtitle);
        r.sub = sub;
        if (onClick != null) {
            v.setOnClickListener(x -> onClick.run());
        } else {
            v.findViewById(R.id.chevron).setVisibility(View.GONE);
            v.setBackground(null);
        }
        card.addView(v);
        rows.add(r);
        return r;
    }

    private void toggle(String title, Supplier<String> sub, Supplier<Boolean> get, Consumer<Boolean> set) {
        Row r = row(title, sub, null);
        View v = card.getChildAt(card.getChildCount() - 1);
        r.sw = v.findViewById(R.id.rowSwitch);
        r.sw.setVisibility(View.VISIBLE);
        r.checked = get;
        r.sw.setOnCheckedChangeListener((b, on) -> {
            if (!updating) set.accept(on);
        });
        v.setOnClickListener(x -> r.sw.toggle());
        v.setBackgroundResource(resolveSelectable());
    }

    private int resolveSelectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private void note(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(a.getColor(R.color.muted));
        t.setTextSize(12);
        t.setPadding(a.dp(4), a.dp(8), a.dp(4), 0);
        container.addView(t);
    }

    // ---------- dialogs ----------

    private void edit(String title, String current, int extraType, Consumer<String> save) {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | extraType);
        input.setText(current);
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(a);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    save.accept(input.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void choose(String title, String[] items, int checked, IntConsumer pick) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    pick.accept(which);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickInterval() {
        String[] labels = new String[INTERVALS.length];
        int checked = -1;
        for (int i = 0; i < INTERVALS.length; i++) {
            labels[i] = "Every " + INTERVALS[i] + " minutes";
            if (INTERVALS[i] == s.intervalMin()) checked = i;
        }
        choose("Check-in frequency", labels, checked, i -> {
            s.setIntervalMin(INTERVALS[i]);
            a.reschedule();
        });
    }

    private void pickHour(boolean start) {
        int from = start ? 0 : 1, to = start ? 23 : 24;
        String[] labels = new String[to - from + 1];
        for (int h = from; h <= to; h++) labels[h - from] = Store.hourLabel(h);
        int current = (start ? s.startHour() : s.endHour()) - from;
        choose(start ? "Active from" : "Active until", labels, current, i -> {
            if (start) s.setStartHour(i + from);
            else s.setEndHour(i + from);
            a.reschedule();
        });
    }

    private void pickDeviceVoice() {
        if (tts == null) {
            Toast.makeText(a, "Loading voices…", Toast.LENGTH_SHORT).show();
            tts = new TextToSpeech(a, st -> a.runOnUiThread(this::showVoiceDialog));
        } else {
            showVoiceDialog();
        }
    }

    private void showVoiceDialog() {
        List<Voice> voices = new ArrayList<>();
        try {
            String lang = s.hindi() ? "hi" : "en";
            for (Voice v : tts.getVoices()) {
                if (!lang.equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                voices.add(v);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(voices, (x, y) -> x.getName().compareTo(y.getName()));
        String[] labels = new String[voices.size() + 1];
        labels[0] = "Phone default";
        int checked = 0;
        for (int i = 0; i < voices.size(); i++) {
            labels[i + 1] = voices.get(i).getName();
            if (voices.get(i).getName().equals(s.voiceName())) checked = i + 1;
        }
        choose("Offline voice", labels, checked, i -> {
            String name = i == 0 ? "" : voices.get(i - 1).getName();
            s.setVoiceName(name);
            a.speaker().speak(Messages.testVoice(s, s.hindi()), false, s.hindi(), name, () -> { });
        });
    }

    // ---------- system checks ----------

    private boolean notificationsOk() {
        NotificationManager nm = a.getSystemService(NotificationManager.class);
        return a.hasNotificationPermission() && nm != null && nm.areNotificationsEnabled();
    }

    private void fixNotifications() {
        if (!a.hasNotificationPermission()) {
            a.requestRuntimePermissions();
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
        } catch (Exception e) {
            a.openAppDetails();
        }
    }

    private boolean exactAlarmsOk() {
        AlarmManager am = a.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
    }

    private void openAutostart() {
        String[][] screens = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] c : screens) {
            try {
                a.startActivity(new Intent().setComponent(new ComponentName(c[0], c[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(a, "Turn on Autostart for " + a.getString(R.string.app_name), Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(a, "Open Battery and allow background activity", Toast.LENGTH_LONG).show();
        a.openAppDetails();
    }

    private String version() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
