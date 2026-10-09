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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service: starts conversations on a schedule (care / career / todo), speaks with the
 * neural voice, listens to the answer, thinks with the AI (or fixed rules when offline), updates the
 * todo list and keeps a chat history the UI shows.
 */
public class CheckInService extends Service {

    public static final String ACTION_START = "com.shiva.personalai.START";          // turn on (+ greet)
    public static final String ACTION_STOP = "com.shiva.personalai.STOP";
    public static final String ACTION_CHECK_IN = "com.shiva.personalai.CHECK_IN";    // "check-in now" button
    public static final String ACTION_ALARM = "com.shiva.personalai.ALARM";          // scheduled
    public static final String ACTION_TALK = "com.shiva.personalai.TALK";            // mic button
    public static final String ACTION_RESCHEDULE = "com.shiva.personalai.RESCHEDULE";// settings saved
    public static final String EXTRA_GREET = "greet";

    private static final String CHANNEL = "personalai";
    private static final int NOTIF_ID = 1;
    private static final int MAX_TURNS = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Speaker speaker;
    private boolean micAllowed;
    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;

    private boolean busy;
    private int mode;
    private int turn;
    private int convo;
    private List<String> pending = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        speaker = new Speaker(this, store);
        createChannel();
        store.setState("idle");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            store.setServiceOn(false);
            store.setNextAt(0);
            cancelAlarm();
            endConversation();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        store.setServiceOn(true);

        if (ACTION_TALK.equals(action)) {
            beginTalk();
        } else if (ACTION_CHECK_IN.equals(action)) {
            beginCheckIn(store.nextTypeAndAdvance());
            scheduleNext();
        } else if (ACTION_ALARM.equals(action)) {
            if (!busy && withinActiveHours()) beginCheckIn(store.nextTypeAndAdvance());
            scheduleNext();
        } else if (ACTION_RESCHEDULE.equals(action)) {
            scheduleNext();
        } else { // ACTION_START
            if (intent != null && intent.getBooleanExtra(EXTRA_GREET, false)) beginCheckIn(Messages.TYPE_CARE);
            if (store.nextAt() < System.currentTimeMillis()) scheduleNext();
            else updateNotification();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        endConversation();
        speaker.shutdown();
        store.setState("idle");
        super.onDestroy();
    }

    // ---------- conversation flow ----------

    private void beginCheckIn(int type) {
        if (busy) return;
        busy = true;
        acquireWakeLock();
        mode = type;
        turn = 0;
        pending = store.pendingTexts();
        store.setState("thinking");
        final int myConvo = ++convo;
        final List<Store.Msg> history = store.messages();
        final boolean listenAfter = !(type == Messages.TYPE_TODO && pending.isEmpty());
        new Thread(() -> {
            String opening;
            try {
                opening = AiClient.chat(store, type, pending, history, true).reply;
            } catch (Exception e) {
                opening = Messages.checkIn(type, store);
            }
            final String text = opening;
            main.post(() -> {
                if (myConvo == convo) say(text, listenAfter);
            });
        }).start();
    }

    private void beginTalk() {
        if (busy) {
            // user tapped mic while she was talking: stop and listen right away
            speaker.stop();
            destroyRecognizer();
        }
        busy = true;
        convo++;
        acquireWakeLock();
        mode = Messages.TYPE_TALK;
        turn = 0;
        pending = store.pendingTexts();
        if (canListen()) listen();
        else say(Messages.noReply(store), false);
    }

    private void say(String text, boolean thenListen) {
        store.addMessage("ai", text);
        store.setState("speaking");
        speaker.speak(text, () -> {
            if (thenListen && turn < MAX_TURNS && canListen()) listen();
            else finishConversation();
        });
    }

    private boolean canListen() {
        return store.listen() && micAllowed && hasMicPermission() && SpeechRecognizer.isRecognitionAvailable(this);
    }

    private void listen() {
        turn++;
        destroyRecognizer();
        store.setState("listening");
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { store.setState("thinking"); }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    destroyRecognizer();
                    if (mode == Messages.TYPE_TALK || turn > 1) finishConversation();
                    else say(Messages.noReply(store), false);
                }

                @Override
                public void onResults(Bundle results) {
                    destroyRecognizer();
                    List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list == null || list.isEmpty()) ? "" : list.get(0);
                    if (text.trim().isEmpty()) finishConversation();
                    else onUserSaid(text.trim());
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
        store.addMessage("user", text);
        if (mode == Messages.TYPE_CAREER && turn == 1) store.addLearning(text);
        store.setState("thinking");
        final int myConvo = convo;
        final List<Store.Msg> history = store.messages();
        final int myMode = mode;
        new Thread(() -> {
            try {
                AiClient.Result r = AiClient.chat(store, myMode, pending, history, false);
                main.post(() -> {
                    if (myConvo != convo) return;
                    markDone(r.done);
                    say(r.reply, r.askMore);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (myConvo == convo) localReply(text);
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
        } else if (mode == Messages.TYPE_TODO) {
            List<String> doneNow = markDone(ReplyParser.doneIndices(text, pending));
            reply = Messages.todoReply(doneNow, store.pendingTexts(), store);
        } else {
            List<String> doneNow = markDone(ReplyParser.doneIndices(text, pending));
            reply = doneNow.isEmpty() ? Messages.talkOffline(store) : Messages.todoReply(doneNow, store.pendingTexts(), store);
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
        destroyRecognizer();
        store.setState("idle");
        releaseWakeLock();
        updateNotification();
    }

    private void endConversation() {
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

    private PendingIntent alarmIntent() {
        Intent i = new Intent(this, AlarmReceiver.class);
        return PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleNext() {
        long nextAt = computeNext();
        store.setNextAt(nextAt);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent();
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi);
        updateNotification();
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.cancel(alarmIntent());
    }

    // ---------- foreground / notification ----------

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (micAllowed) {
            updateNotification();
            return;
        }
        int media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        int mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasMicPermission()) {
                try {
                    startForeground(NOTIF_ID, n, mic | media);
                    micAllowed = true;
                    return;
                } catch (Exception ignored) {
                    // Android 14+: microphone type cannot be started while the app is in the background.
                }
            }
            try {
                startForeground(NOTIF_ID, n, media);
            } catch (Exception ignored) {
            }
        } else {
            startForeground(NOTIF_ID, n);
            micAllowed = hasMicPermission();
        }
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Personal AI", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Companion check-ins");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        long nextAt = store.nextAt();
        String text = nextAt > 0
                ? "Agla check-in " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(nextAt))
                : "Online";
        if (!micAllowed && store.listen()) text += " (sunne ke liye app ek baar kholo)";
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(store.herName() + " online hai")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    // ---------- wake lock ----------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalai:conversation");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(5 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
