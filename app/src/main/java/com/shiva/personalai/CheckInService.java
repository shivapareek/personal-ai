package com.shiva.personalai;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Foreground service that owns the assistant: schedules check-ins with exact alarms, starts the
 * conversation itself, speaks, listens, thinks with the AI and keeps running after the app is
 * swiped away. Every entry point calls startForeground so the system never kills it for a missed
 * foreground promotion.
 */
public class CheckInService extends Service {

    public static final String ACTION_START = "com.shiva.personalai.START";           // user turned auto check-ins on
    public static final String ACTION_STOP = "com.shiva.personalai.STOP";
    public static final String ACTION_CHECK_IN = "com.shiva.personalai.CHECK_IN";     // "check in now"
    public static final String ACTION_ALARM = "com.shiva.personalai.ALARM";           // scheduled check-in
    public static final String ACTION_TALK = "com.shiva.personalai.TALK";             // mic / typed message
    public static final String ACTION_RESCHEDULE = "com.shiva.personalai.RESCHEDULE"; // settings changed
    public static final String ACTION_RESTART = "com.shiva.personalai.RESTART";       // revive after kill / boot / app open
    public static final String EXTRA_GREET = "greet";
    public static final String EXTRA_TEXT = "text";

    static final int REQ_CHECKIN = 0;
    static final int REQ_RESTART = 2;

    private static final String CH_STATUS = "status";
    private static final String CH_MESSAGES = "messages";
    private static final int NOTIF_STATUS = 1;
    private static final int NOTIF_MESSAGE = 2;
    private static final int MAX_TURNS = 4;
    private static final long STUCK_MS = 150_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Speaker speaker;
    private boolean micAllowed;
    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;

    private boolean busy;
    private long lastActivity;
    private int convo;
    private int mode;
    private int turn;
    private boolean typed;
    private List<String> pending = new ArrayList<>();

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            if (SystemClock.elapsedRealtime() - lastActivity >= STUCK_MS) endConversation();
            else main.postDelayed(this, 15_000);
        }
    };

    // ---------- static scheduling helpers (also used by receivers and the keep-alive job) ----------

    static PendingIntent alarmIntent(Context c, String action, int req) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void armAlarm(Context c, long at, String action, int req) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(c, action, req);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAlarms(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(alarmIntent(c, ACTION_ALARM, REQ_CHECKIN));
        am.cancel(alarmIntent(c, ACTION_RESTART, REQ_RESTART));
    }

    static void send(Context c, String action) {
        Intent i = new Intent(c, CheckInService.class).setAction(action);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            try {
                c.startService(i);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        speaker = new Speaker(this, store);
        createChannels();
        store.setState("idle");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_RESTART : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            store.setServiceOn(false);
            store.setNextAt(0);
            cancelAlarms(this);
            KeepAliveJob.cancel(this);
            endConversation();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        goForeground();

        switch (action) {
            case ACTION_START:
                store.setServiceOn(true);
                KeepAliveJob.schedule(this);
                ensureAlarm();
                if (intent.getBooleanExtra(EXTRA_GREET, false)) beginCheckIn(Messages.TYPE_CARE);
                break;
            case ACTION_ALARM:
                if (!store.serviceOn()) break;
                if (busy && SystemClock.elapsedRealtime() - lastActivity >= STUCK_MS) endConversation();
                if (!busy && withinActiveHours()) beginCheckIn(store.nextTypeAndAdvance());
                scheduleNext();
                break;
            case ACTION_CHECK_IN:
                if (busy) endConversation();
                beginCheckIn(store.nextTypeAndAdvance());
                if (store.serviceOn()) scheduleNext();
                break;
            case ACTION_TALK:
                beginTalk(intent.getStringExtra(EXTRA_TEXT));
                break;
            case ACTION_RESCHEDULE:
                if (store.serviceOn()) scheduleNext();
                break;
            default: // ACTION_RESTART
                if (store.serviceOn()) {
                    KeepAliveJob.schedule(this);
                    ensureAlarm();
                }
                break;
        }
        stopIfIdle();
        return store.serviceOn() ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** App swiped away from recents: make sure we come back even on phones that kill the process. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (store.serviceOn()) {
            armAlarm(this, System.currentTimeMillis() + 3000, ACTION_RESTART, REQ_RESTART);
            ensureAlarm();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (store.serviceOn()) armAlarm(this, System.currentTimeMillis() + 5000, ACTION_RESTART, REQ_RESTART);
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
        speaker.shutdown();
        releaseWakeLock();
        store.setState("idle");
        super.onDestroy();
    }

    private void stopIfIdle() {
        if (!busy && !store.serviceOn()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ---------- conversation ----------

    private void startConversation(int m, boolean typedInput) {
        convo++;
        busy = true;
        mode = m;
        turn = 0;
        typed = typedInput;
        pending = store.pendingTexts();
        acquireWakeLock();
        touch();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 15_000);
    }

    private void touch() {
        lastActivity = SystemClock.elapsedRealtime();
    }

    private void beginCheckIn(int type) {
        if (busy) return;
        startConversation(type, false);
        final int my = convo;
        final List<Store.Msg> history = store.messages();
        final List<String> pend = new ArrayList<>(pending);
        final boolean listenAfter = !(type == Messages.TYPE_TODO && pend.isEmpty());
        store.setState("thinking");
        new Thread(() -> {
            String opening;
            try {
                opening = AiClient.chat(store, type, pend, history, true).reply;
            } catch (Exception e) {
                opening = null;
            }
            if (opening == null || opening.trim().isEmpty()) opening = Messages.checkIn(type, store);
            final String text = opening;
            main.post(() -> {
                if (my == convo) say(text, listenAfter);
            });
        }).start();
    }

    private void beginTalk(String text) {
        if (busy) {
            speaker.stop();
            destroyRecognizer();
        }
        startConversation(Messages.TYPE_TALK, text != null && !text.trim().isEmpty());
        cancelMessageNotification();
        if (typed) {
            onUserSaid(text.trim());
        } else if (canListen()) {
            listen();
        } else if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(Messages.noRecognizer(store), false);
        } else {
            finishConversation();
        }
    }

    private void say(String text, boolean thenListen) {
        touch();
        store.addMessage("ai", text);
        if (!MainActivity.visible && mode != Messages.TYPE_TALK) postMessageNotification(text, thenListen);
        store.setState("speaking");
        final int my = convo;
        speaker.speak(text, () -> {
            if (my != convo) return;
            if (thenListen && !typed && turn < MAX_TURNS && canListen()) listen();
            else finishConversation();
        });
    }

    private boolean canListen() {
        return store.listen() && hasMicPermission() && (micAllowed || MainActivity.visible)
                && SpeechRecognizer.isRecognitionAvailable(this);
    }

    private void listen() {
        turn++;
        touch();
        destroyRecognizer();
        store.setState("listening");
        final int my = convo;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { touch(); }
                @Override public void onBeginningOfSpeech() { touch(); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { store.setState("thinking"); }
                @Override public void onPartialResults(Bundle partialResults) { touch(); }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    destroyRecognizer();
                    if (my != convo) return;
                    if (mode != Messages.TYPE_TALK && turn == 1) say(Messages.noReply(store), false);
                    else finishConversation();
                }

                @Override
                public void onResults(Bundle results) {
                    destroyRecognizer();
                    if (my != convo) return;
                    List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list == null || list.isEmpty()) ? "" : list.get(0).trim();
                    if (text.isEmpty()) finishConversation();
                    else onUserSaid(text);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, store.hindi() ? "hi-IN" : "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
            recognizer.startListening(i);
        } catch (Exception e) {
            destroyRecognizer();
            finishConversation();
        }
    }

    private void onUserSaid(String text) {
        touch();
        cancelMessageNotification();
        store.addMessage("user", text);
        if (mode == Messages.TYPE_CAREER && turn <= 1) store.addLearning(text);
        store.setState("thinking");
        final int my = convo;
        final int myMode = mode;
        final List<Store.Msg> history = store.messages();
        final List<String> pend = new ArrayList<>(pending);
        new Thread(() -> {
            try {
                AiClient.Result r = AiClient.chat(store, myMode, pend, history, false);
                main.post(() -> {
                    if (my != convo) return;
                    markDone(r.done);
                    say(r.reply, r.askMore);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (my == convo) localReply(text);
                });
            }
        }).start();
    }

    /** Reply without AI: simple parsing + fixed sentences. */
    private void localReply(String text) {
        String reply;
        if (mode == Messages.TYPE_CARE) {
            reply = Messages.careReply(store);
        } else if (mode == Messages.TYPE_CAREER) {
            reply = Messages.careerReply(store);
        } else {
            List<String> doneNow = markDone(ReplyParser.doneIndices(text, pending));
            if (mode == Messages.TYPE_TALK && doneNow.isEmpty()) reply = Messages.talkOffline(store);
            else reply = Messages.todoReply(doneNow, store.pendingTexts(), store);
        }
        say(reply, false);
    }

    /** Marks the given indices of `pending` as done. Returns the texts marked. */
    private List<String> markDone(List<Integer> pendingIdx) {
        List<String> marked = new ArrayList<>();
        if (pendingIdx.isEmpty()) return marked;
        List<Store.Todo> todos = store.todos();
        for (int idx : pendingIdx) {
            if (idx < 0 || idx >= pending.size()) continue;
            String text = pending.get(idx);
            for (Store.Todo t : todos) {
                if (!t.done && t.text.equals(text)) {
                    t.done = true;
                    marked.add(text);
                    break;
                }
            }
        }
        store.saveTodos(todos);
        pending = store.pendingTexts();
        return marked;
    }

    private void finishConversation() {
        busy = false;
        main.removeCallbacks(watchdog);
        destroyRecognizer();
        store.setState("idle");
        releaseWakeLock();
        updateStatusNotification();
        stopIfIdle();
    }

    private void endConversation() {
        convo++;
        speaker.stop();
        finishConversation();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    // ---------- scheduling ----------

    private boolean withinActiveHours() {
        int start = store.startHour(), end = store.endHour();
        if (start >= end) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= start && h < end;
    }

    private long computeNext() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, store.intervalMin());
        int start = store.startHour(), end = store.endHour();
        if (start < end) {
            int h = c.get(Calendar.HOUR_OF_DAY);
            if (h < start || h >= end) {
                Calendar s = (Calendar) c.clone();
                s.set(Calendar.HOUR_OF_DAY, start);
                s.set(Calendar.MINUTE, 0);
                s.set(Calendar.SECOND, 0);
                if (!s.after(c)) s.add(Calendar.DAY_OF_YEAR, 1);
                c = s;
            }
        }
        return c.getTimeInMillis();
    }

    private void scheduleNext() {
        long next = computeNext();
        store.setNextAt(next);
        armAlarm(this, next, ACTION_ALARM, REQ_CHECKIN);
        updateStatusNotification();
    }

    /** Re-arms the stored check-in (alarms are lost on reboot / force-stop), or schedules a new one. */
    private void ensureAlarm() {
        long next = store.nextAt();
        if (next <= System.currentTimeMillis() + 5000) scheduleNext();
        else {
            armAlarm(this, next, ACTION_ALARM, REQ_CHECKIN);
            updateStatusNotification();
        }
    }

    // ---------- foreground + notifications ----------

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called on every start command. The microphone type is only requested while the app is on screen
     * (or was already granted), because Android only allows background microphone use for a service
     * that was promoted while the user was in the app.
     */
    private void goForeground() {
        Notification n = buildStatusNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (hasMicPermission() && (MainActivity.visible || micAllowed)) {
                try {
                    startForeground(NOTIF_STATUS, n, media | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    micAllowed = true;
                    return;
                } catch (Exception ignored) {
                }
            }
            try {
                startForeground(NOTIF_STATUS, n, media);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startForeground(NOTIF_STATUS, n);
        } catch (Exception ignored) {
        }
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("personalai"); // v1/v2 channel
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Background status", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        NotificationChannel msgs = new NotificationChannel(CH_MESSAGES, "Check-in messages", NotificationManager.IMPORTANCE_HIGH);
        msgs.setSound(null, null);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(msgs);
    }

    private Notification buildStatusNotification() {
        String title = store.herName() + (store.serviceOn() ? " is active" : "");
        long next = store.nextAt();
        String text = !store.serviceOn() ? "In conversation"
                : next > 0 ? "Next check-in " + Store.timeLabel(next) : "Automatic check-ins on";
        if (store.serviceOn() && store.listen() && !micAllowed) text += " · open the app once to enable voice replies";
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true);
        if (store.serviceOn()) {
            PendingIntent pause = PendingIntent.getService(this, 5,
                    new Intent(this, CheckInService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), "Pause", pause).build());
        }
        return b.build();
    }

    private void updateStatusNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_STATUS, buildStatusNotification());
        } catch (Exception ignored) {
        }
    }

    /** Shows what she said, so check-ins are visible even when the phone is in a pocket or the mic is blocked. */
    private void postMessageNotification(String text, boolean wantsReply) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent reply = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_REPLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand))
                .setContentTitle(store.herName())
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(this, 3, open, flags))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        if (wantsReply) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Reply",
                    PendingIntent.getActivity(this, 4, reply, flags)).build());
        }
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_MESSAGE, b.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelMessageNotification() {
        getSystemService(NotificationManager.class).cancel(NOTIF_MESSAGE);
    }

    // ---------- wake lock ----------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalai:conversation");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
