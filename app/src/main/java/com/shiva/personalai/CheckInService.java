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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that periodically speaks to the user (female TTS voice), listens to the answer,
 * updates the todo list and schedules the next check-in with AlarmManager.
 */
public class CheckInService extends Service implements TextToSpeech.OnInitListener {

    public static final String ACTION_START = "com.shiva.personalai.START";
    public static final String ACTION_STOP = "com.shiva.personalai.STOP";
    public static final String ACTION_CHECK_IN = "com.shiva.personalai.CHECK_IN"; // "abhi puchho" button
    public static final String ACTION_ALARM = "com.shiva.personalai.ALARM";       // scheduled

    private static final String CHANNEL = "personalai";
    private static final int NOTIF_ID = 1;
    private static final String UTT_ASK = "ask";
    private static final String UTT_REPLY = "reply";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean micAllowed;
    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;
    private boolean busy;
    private int currentType;
    private String currentQuestion = "";
    private List<String> currentPending = new ArrayList<>();
    private long nextAt;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        createChannel();
        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> onSpoken(id)); }
            @Override public void onError(String id) { main.post(() -> onSpoken(id)); }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            store.setServiceOn(false);
            cancelAlarm();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        store.setServiceOn(true);
        if (ACTION_CHECK_IN.equals(action) || (ACTION_ALARM.equals(action) && withinActiveHours())) {
            runCheckIn();
        }
        scheduleNext();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        destroyRecognizer();
        if (tts != null) tts.shutdown();
        releaseWakeLock();
        super.onDestroy();
    }

    // ---------- TTS ----------

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        applyVoice();
        ttsReady = true;
    }

    private void applyVoice() {
        Locale locale = store.hindi() ? new Locale("hi", "IN") : new Locale("en", "IN");
        try {
            tts.setLanguage(locale);
            String wanted = store.voiceName();
            if (!wanted.isEmpty() && tts.getVoices() != null) {
                for (Voice v : tts.getVoices()) {
                    if (wanted.equals(v.getName())) { tts.setVoice(v); break; }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void speak(String text, String utteranceId) {
        speak(text, utteranceId, 0);
    }

    private void speak(String text, String utteranceId, int attempt) {
        if (!ttsReady) {
            if (attempt < 10) main.postDelayed(() -> speak(text, utteranceId, attempt + 1), 1000);
            else finishCheckIn();
            return;
        }
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (r != TextToSpeech.SUCCESS) finishCheckIn();
    }

    private void onSpoken(String utteranceId) {
        if (UTT_ASK.equals(utteranceId)) {
            if (store.listen() && micAllowed && hasMicPermission() && SpeechRecognizer.isRecognitionAvailable(this)) {
                startListening();
            } else {
                finishCheckIn();
            }
        } else if (UTT_REPLY.equals(utteranceId)) {
            finishCheckIn();
        }
    }

    // ---------- check-in flow ----------

    private void runCheckIn() {
        if (busy) return;
        busy = true;
        acquireWakeLock();
        applyVoice(); // settings may have changed
        currentType = store.nextTypeAndAdvance();
        currentPending = store.pendingTexts();
        currentQuestion = Messages.checkIn(currentType, store);
        speak(currentQuestion, UTT_ASK);
    }

    private void startListening() {
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    destroyRecognizer();
                    speak(Messages.noReply(store), UTT_REPLY);
                }

                @Override
                public void onResults(Bundle results) {
                    destroyRecognizer();
                    List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list == null || list.isEmpty()) ? "" : list.get(0);
                    handleReply(text);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, store.hindi() ? "hi-IN" : "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizer.startListening(i);
        } catch (Exception e) {
            destroyRecognizer();
            finishCheckIn();
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void handleReply(String text) {
        if (text == null || text.trim().isEmpty()) {
            speak(Messages.noReply(store), UTT_REPLY);
            return;
        }
        if (currentType == Messages.TYPE_CAREER) store.addLearning(text);

        if (store.aiEnabled()) {
            final String question = currentQuestion;
            final int type = currentType;
            final List<String> pending = new ArrayList<>(currentPending);
            new Thread(() -> {
                try {
                    AiClient.Result r = AiClient.ask(store, type, question, text, pending);
                    main.post(() -> {
                        markDone(r.done);
                        speak(r.reply, UTT_REPLY);
                    });
                } catch (Exception e) {
                    main.post(() -> localReply(text));
                }
            }).start();
        } else {
            localReply(text);
        }
    }

    /** Reply without AI: simple parsing + fixed sentences. */
    private void localReply(String text) {
        String reply;
        if (currentType == Messages.TYPE_CARE) {
            reply = Messages.careReply(store);
        } else if (currentType == Messages.TYPE_CAREER) {
            reply = Messages.careerReply(store);
        } else {
            List<String> doneNow = markDone(ReplyParser.doneIndices(text, currentPending));
            reply = Messages.todoReply(doneNow, store.pendingTexts(), store);
        }
        speak(reply, UTT_REPLY);
    }

    /** Marks the given indices of currentPending as done. Returns the texts marked. */
    private List<String> markDone(List<Integer> pendingIdx) {
        List<String> marked = new ArrayList<>();
        if (pendingIdx.isEmpty()) return marked;
        List<Store.Todo> todos = store.todos();
        for (int idx : pendingIdx) {
            if (idx < 0 || idx >= currentPending.size()) continue;
            String text = currentPending.get(idx);
            for (Store.Todo t : todos) {
                if (!t.done && t.text.equals(text)) {
                    t.done = true;
                    marked.add(text);
                    break;
                }
            }
        }
        store.saveTodos(todos);
        return marked;
    }

    private void finishCheckIn() {
        busy = false;
        destroyRecognizer();
        releaseWakeLock();
        updateNotification();
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
        nextAt = computeNext();
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
                    // Android 14+: microphone type cannot be started from the background.
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
        ch.setDescription("Check-in reminders");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        String next = nextAt > 0
                ? "Agla check-in: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(nextAt))
                : "Chal raha hai";
        if (!micAllowed && store.listen()) next += " (sunne ke liye app kholke Start dabao)";
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Personal AI")
                .setContentText(next)
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalai:checkin");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(3 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
