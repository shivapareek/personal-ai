package com.shiva.personalai;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** iOS Settings-style screen: inset grouped rows with colored icon squares; changes apply instantly. */
class SettingsTab {

    private static final int[] INTERVALS = {1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120};
    private static final String[] PERSONALITIES = {"Girlfriend", "Best friend", "Career coach"};
    private static final String[] ADDRESS = {"Aap (neutral)", "Male", "Female"};
    private static final String[] SPEEDS = {"Normal", "A bit faster", "Fast", "Very fast"};
    private static final int[] SPEED_PCT = {0, 10, 20, 30};

    private static int speedIndex(int pct) {
        int best = 0;
        for (int i = 0; i < SPEED_PCT.length; i++) if (Math.abs(SPEED_PCT[i] - pct) < Math.abs(SPEED_PCT[best] - pct)) best = i;
        return best;
    }

    // iOS system colors for the icon squares
    private static final int BLUE = 0xFF007AFF, GREEN = 0xFF34C759, ORANGE = 0xFFFF9500, RED = 0xFFFF3B30,
            PINK = 0xFFFF2D55, PURPLE = 0xFFAF52DE, INDIGO = 0xFF5856D6, GRAY = 0xFF8E8E93, TEAL = 0xFF30B0C7;

    private static class Row {
        TextView value;
        Supplier<String> text;
        Switch sw;
        Supplier<Boolean> checked;
    }

    private final MainActivity a;
    private final Store s;
    private final LinearLayout container;
    private final List<Row> rows = new ArrayList<>();
    private TextView profileName, profileSub, profileAvatar;
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
        profileCard();

        LinearLayout c = section("Calls");
        toggle(c, R.drawable.ic_call, GREEN, "Automatic calls", s::serviceOn, on -> {
            a.setAuto(on);
            refresh();
        });
        row(c, R.drawable.ic_chat, BLUE, "Check-in style", () -> s.callStyle() ? "Phone call" : "Voice message", () ->
                Ios.sheet(a, "How should she check in?", new String[]{"Phone call", "Voice message"}, s.callStyle() ? 0 : 1, i -> {
                    s.setCallStyle(i == 0);
                    refresh();
                }));
        row(c, R.drawable.ic_clock, ORANGE, "Frequency", () -> intervalLabel(s.intervalMin()), this::pickInterval);
        row(c, R.drawable.ic_moon, INDIGO, "Active from", () -> Store.hourLabel(s.startHour()), () -> pickHour(true));
        row(c, R.drawable.ic_moon, INDIGO, "Active until", () -> Store.hourLabel(s.endHour()), () -> pickHour(false));
        row(c, R.drawable.ic_call, GREEN, "Test incoming call", () -> "", a::testIncomingCall);
        toggle(c, R.drawable.ic_volume, GRAY, "Start calls on speaker", s::speakerDefault, s::setSpeakerDefault);
        toggle(c, R.drawable.ic_mic, RED, "Listen for my reply", s::listen, s::setListen);
        footer("If you miss or decline her call, she calls again after this interval. "
                + "Every minute is meant for testing; Android may delay very frequent alarms while the phone is idle.");

        c = section("Companion");
        row(c, R.drawable.ic_heart, PINK, "Personality", () -> PERSONALITIES[s.personality()], () ->
                Ios.sheet(a, "Personality", PERSONALITIES, s.personality(), i -> {
                    s.setPersonality(i);
                    refresh();
                }));
        row(c, R.drawable.ic_person, GRAY, "Your name", () -> s.name().isEmpty() ? "Not set" : s.name(), () ->
                Ios.input(a, "Your name", null, s.name(), "Name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, true, v -> {
                    s.setName(v);
                    refresh();
                }));
        row(c, R.drawable.ic_person, GRAY, "Address me as", () -> ADDRESS[s.address()], () ->
                Ios.sheet(a, "Used for Hindi grammar", ADDRESS, s.address(), i -> {
                    s.setAddress(i);
                    refresh();
                }));
        row(c, R.drawable.ic_translate, BLUE, "Language", () -> s.hindi() ? "Hindi" : "English", () ->
                Ios.sheet(a, "Language", new String[]{"Hindi", "English"}, s.hindi() ? 0 : 1, i -> {
                    s.setHindi(i == 0);
                    s.setVoiceName("");
                    refresh();
                }));

        c = section("Voice");
        toggle(c, R.drawable.ic_sparkle, PURPLE, "Natural voice", s::onlineVoice, s::setOnlineVoice);
        row(c, R.drawable.ic_clock, PURPLE, "Speaking speed", () -> SPEEDS[speedIndex(s.speechRate())], () ->
                Ios.sheet(a, "How fast should she talk?", SPEEDS, speedIndex(s.speechRate()), i -> {
                    s.setSpeechRate(SPEED_PCT[i]);
                    refresh();
                    a.speaker().speak(Messages.testVoice(s, s.hindi()), s.onlineVoice(), s.hindi(), s.voiceName(), () -> { });
                }));
        row(c, R.drawable.ic_volume, PURPLE, "Offline voice", () -> s.voiceName().isEmpty() ? "Default" : s.voiceName(), this::pickDeviceVoice);
        row(c, R.drawable.ic_volume, PURPLE, "Play sample", () -> "", () ->
                a.speaker().speak(Messages.testVoice(s, s.hindi()), s.onlineVoice(), s.hindi(), s.voiceName(), () -> { }));
        footer("Natural voice uses a free neural voice (Swara for Hindi, Neerja for English) over the internet and falls back to the phone voice offline.");

        c = section("AI");
        row(c, R.drawable.ic_sparkle, TEAL, "AI", () -> {
            String name = AiClient.providerName(s);
            return AiClient.usingFreeAi(s) ? "!" + name + " (slow)" : name;
        }, a::setupAiKey);
        row(c, R.drawable.ic_clock, TEAL, "Test AI speed", () -> s.lastAi(), a::testAi);
        row(c, R.drawable.ic_key, GRAY, "API key", () -> s.aiKey().isEmpty() ? "None"
                : s.aiKey().substring(0, Math.min(6, s.aiKey().length())) + "…", () ->
                Ios.input(a, "API key", "Groq (gsk_…) or Gemini (AIza…). Leave empty for the free AI.", s.aiKey(), "gsk_…",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, true, val -> {
                            s.setAiKey(val);
                            refresh();
                        }));
        row(c, R.drawable.ic_sparkle, GRAY, "Model", s::aiModel, () ->
                Ios.input(a, "Model", "Leave as is unless you know the exact model name.", s.aiModel(), "Model",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true, val -> {
                            s.setAiModel(val);
                            refresh();
                        }));
        footer("The free AI needs no key but takes 5 to 15 seconds per reply. A free Groq key makes her answer in under a second "
                + "and much smarter. Groq or Gemini is picked automatically from the key.");

        c = section("Background");
        row(c, R.drawable.ic_fullscreen, GREEN, "Display over other apps", () -> a.canDrawOverlays() ? "Allowed" : "!Not allowed",
                a::openOverlaySettings);
        if (Build.VERSION.SDK_INT >= 34) {
            row(c, R.drawable.ic_fullscreen, GREEN, "Full-screen calls", () -> fullScreenOk() ? "Allowed" : "!Not allowed",
                    a::openFullScreenSettings);
        }
        if (MainActivity.isMiui()) {
            row(c, R.drawable.ic_fullscreen, GREEN, "Lock screen & pop-ups", () -> "Xiaomi", a::openMiuiPermissions);
        }
        row(c, R.drawable.ic_bell, RED, "Notifications", () -> notificationsOk() ? "On" : "!Off", this::fixNotifications);
        row(c, R.drawable.ic_mic, ORANGE, "Microphone", () -> a.hasMic() ? "Allowed" : "!Not allowed", () -> {
            if (a.hasMic()) a.openAppDetails();
            else a.requestRuntimePermissions();
        });
        row(c, R.drawable.ic_battery, GREEN, "Battery", () -> a.ignoringBatteryOptimizations() ? "Unrestricted" : "!Restricted", () -> {
            if (a.ignoringBatteryOptimizations()) a.openAppDetails();
            else a.askBatteryOptimization();
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            row(c, R.drawable.ic_alarm, ORANGE, "Alarms & reminders", () -> exactAlarmsOk() ? "Allowed" : "!Not allowed", () -> {
                try {
                    a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + a.getPackageName())));
                } catch (Exception e) {
                    a.openAppDetails();
                }
            });
        }
        row(c, R.drawable.ic_power, GRAY, "Autostart", () -> "", this::openAutostart);
        footer("\"Display over other apps\" lets her call turn on your screen. On Xiaomi, Redmi, Oppo, Realme, Vivo and OnePlus "
                + "also turn on Autostart and lock the app in Recent apps so calls keep coming.");

        c = section("About");
        row(c, R.drawable.ic_delete, RED, "Clear conversation", () -> "", () ->
                Ios.confirm(a, "Clear conversation?", "Messages will be removed. Tasks and journal stay.", "Clear", s::clearMessages));
        Row v = row(c, R.drawable.ic_info, GRAY, "Version", this::version, null);
        v.value.setVisibility(View.VISIBLE);
    }

    void refresh() {
        updating = true;
        profileName.setText(s.herName());
        profileAvatar.setText(s.herName().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        long next = s.nextAt();
        profileSub.setText(s.serviceOn() && next > 0
                ? (s.callStyle() ? "Next call " : "Next check-in ") + Store.timeLabel(next)
                : "Automatic calls are off");
        for (Row r : rows) {
            if (r.text != null) {
                String t = r.text.get();
                boolean problem = t != null && t.startsWith("!");
                if (problem) t = t.substring(1);
                r.value.setText(t);
                r.value.setTextColor(a.getColor(problem ? R.color.red : R.color.muted));
                r.value.setVisibility(t == null || t.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (r.sw != null) r.sw.setChecked(r.checked.get());
        }
        updating = false;
    }

    // ---------- building blocks ----------

    private void profileCard() {
        LinearLayout card = Ios.groupCard(a, 16);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.topMargin = a.dp(14);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(12));
        row.setBackgroundResource(selectable());
        profileAvatar = new TextView(a);
        profileAvatar.setBackgroundResource(R.drawable.bg_avatar);
        profileAvatar.setGravity(Gravity.CENTER);
        profileAvatar.setTextColor(0xFFFFFFFF);
        profileAvatar.setTextSize(26);
        row.addView(profileAvatar, new LinearLayout.LayoutParams(a.dp(60), a.dp(60)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(a.dp(14), 0, 0, 0);
        profileName = new TextView(a);
        profileName.setTextSize(22);
        profileName.setTextColor(a.getColor(R.color.text));
        profileSub = new TextView(a);
        profileSub.setTextSize(13);
        profileSub.setTextColor(a.getColor(R.color.muted));
        texts.addView(profileName);
        texts.addView(profileSub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView chev = new ImageView(a);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.tertiary)));
        row.addView(chev, new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        row.setOnClickListener(v -> Ios.input(a, "Her name", null, s.herName(), "Name",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, true, n -> {
                    if (!n.trim().isEmpty()) s.setHerName(n);
                    refresh();
                }));
        card.addView(row);
        container.addView(card);
    }

    private LinearLayout section(String title) {
        container.addView(Ios.sectionHeader(a, title));
        LinearLayout card = Ios.groupCard(a, 59);
        container.addView(card);
        return card;
    }

    private void footer(String text) {
        container.addView(Ios.sectionFooter(a, text));
    }

    private Row row(LinearLayout card, int icon, int color, String title, Supplier<String> value, Runnable onClick) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_row, card, false);
        GradientDrawable sq = new GradientDrawable();
        sq.setColor(color);
        sq.setCornerRadius(a.dp(7));
        v.findViewById(R.id.iconBox).setBackground(sq);
        ((ImageView) v.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.title)).setText(title);
        Row r = new Row();
        r.value = v.findViewById(R.id.value);
        r.text = value;
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

    private void toggle(LinearLayout card, int icon, int color, String title, Supplier<Boolean> get, Consumer<Boolean> set) {
        Row r = row(card, icon, color, title, null, null);
        View v = card.getChildAt(card.getChildCount() - 1);
        r.value.setVisibility(View.GONE);
        r.sw = v.findViewById(R.id.rowSwitch);
        r.sw.setVisibility(View.VISIBLE);
        r.checked = get;
        r.sw.setOnCheckedChangeListener((b, on) -> {
            if (!updating) set.accept(on);
        });
    }

    private int selectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---------- pickers ----------

    private static String intervalLabel(int m) {
        if (m == 1) return "Every minute";
        if (m < 60) return "Every " + m + " min";
        if (m == 60) return "Every hour";
        return "Every " + (m % 60 == 0 ? (m / 60) + " hours" : m + " min");
    }

    private void pickInterval() {
        String[] labels = new String[INTERVALS.length];
        int checked = -1;
        for (int i = 0; i < INTERVALS.length; i++) {
            labels[i] = intervalLabel(INTERVALS[i]) + (INTERVALS[i] <= 2 ? " (testing)" : "");
            if (INTERVALS[i] == s.intervalMin()) checked = i;
        }
        Ios.sheet(a, "How often should she call?", labels, checked, i -> {
            s.setIntervalMin(INTERVALS[i]);
            a.reschedule();
            refresh();
        });
    }

    private void pickHour(boolean start) {
        int from = start ? 0 : 1, to = start ? 23 : 24;
        String[] labels = new String[to - from + 1];
        for (int h = from; h <= to; h++) labels[h - from] = Store.hourLabel(h);
        int current = (start ? s.startHour() : s.endHour()) - from;
        Ios.sheet(a, start ? "Calls start at" : "Calls stop at", labels, current, i -> {
            if (start) s.setStartHour(i + from);
            else s.setEndHour(i + from);
            a.reschedule();
            refresh();
        });
    }

    private void pickDeviceVoice() {
        if (tts == null) {
            Toast.makeText(a, "Loading voices…", Toast.LENGTH_SHORT).show();
            tts = new TextToSpeech(a, st -> a.runOnUiThread(this::showVoiceSheet));
        } else {
            showVoiceSheet();
        }
    }

    private void showVoiceSheet() {
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
        labels[0] = "Default";
        int checked = 0;
        for (int i = 0; i < voices.size(); i++) {
            labels[i + 1] = voices.get(i).getName();
            if (voices.get(i).getName().equals(s.voiceName())) checked = i + 1;
        }
        Ios.sheet(a, "Offline voice", labels, checked, i -> {
            String name = i == 0 ? "" : voices.get(i - 1).getName();
            s.setVoiceName(name);
            refresh();
            a.speaker().speak(Messages.testVoice(s, s.hindi()), false, s.hindi(), name, () -> { });
        });
    }

    // ---------- system checks ----------

    private boolean notificationsOk() {
        NotificationManager nm = a.getSystemService(NotificationManager.class);
        return a.hasNotificationPermission() && nm != null && nm.areNotificationsEnabled();
    }

    private boolean fullScreenOk() {
        NotificationManager nm = a.getSystemService(NotificationManager.class);
        return Build.VERSION.SDK_INT < 34 || (nm != null && nm.canUseFullScreenIntent());
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
