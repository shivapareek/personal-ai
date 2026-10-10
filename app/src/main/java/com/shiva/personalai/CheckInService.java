package com.shiva.personalai;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that owns the assistant. On schedule she phones the user (ringtone, full-screen
 * incoming call, Accept / Decline). Answered: a live voice conversation. Missed or declined: she calls
 * again after the interval. Also handles the in-app chat/mic and survives being swiped away.
 */
public class CheckInService extends Service {

    public static final String ACTION_START = "com.shiva.personalai.START";
    public static final String ACTION_STOP = "com.shiva.personalai.STOP";
    public static final String ACTION_CHECK_IN = "com.shiva.personalai.CHECK_IN";
    public static final String ACTION_ALARM = "com.shiva.personalai.ALARM";
    public static final String ACTION_TALK = "com.shiva.personalai.TALK";
    public static final String ACTION_RESCHEDULE = "com.shiva.personalai.RESCHEDULE";
    public static final String ACTION_RESTART = "com.shiva.personalai.RESTART";
    public static final String ACTION_ANSWER = "com.shiva.personalai.ANSWER";
    public static final String ACTION_DECLINE = "com.shiva.personalai.DECLINE";
    public static final String ACTION_SNOOZE = "com.shiva.personalai.SNOOZE";
    public static final String ACTION_HANGUP = "com.shiva.personalai.HANGUP";
    public static final String ACTION_CALL_NOW = "com.shiva.personalai.CALL_NOW";
    public static final String ACTION_MUTE = "com.shiva.personalai.MUTE";
    public static final String ACTION_SPEAKER = "com.shiva.personalai.SPEAKER";
    public static final String EXTRA_GREET = "greet";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_ON = "on";

    static final int REQ_CHECKIN = 0;
    static final int REQ_RESTART = 2;

    private static final String CH_STATUS = "status";
    private static final String CH_MESSAGES = "messages";
    private static final String CH_CALLS = "calls";
    private static final int NOTIF_STATUS = 1;
    private static final int NOTIF_MESSAGE = 2;
    private static final int NOTIF_CALL = 3;
    private static final int NOTIF_MISSED = 4;
    private static final int MAX_TURNS = 4;
    private static final long STUCK_MS = 150_000;
    private static final long RING_MS = 40_000;
    private static final long SNOOZE_MS = 5 * 60_000;

    /** True while the service object exists in this process (a killed process leaves stale prefs behind). */
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Speaker speaker;
    private AudioManager audio;
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

    // call state
    private boolean ringing;
    private boolean outgoing;
    private boolean inCall;
    private int silence;
    private String preparedOpening;
    private boolean waitingOpening;
    private long callStart;
    private Ringtone ringtone;
    private Vibrator vibrator;

    private final Runnable ringTimeout = () -> missed(false, false);

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            boolean waitingOnUser = inCall && store.muted();
            if (!waitingOnUser && !ringing && SystemClock.elapsedRealtime() - lastActivity >= STUCK_MS) endConversation();
            else main.postDelayed(this, 15_000);
        }
    };

    // ---------- static helpers (receivers, job, activities) ----------

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
        send(c, new Intent(c, CheckInService.class).setAction(action));
    }

    static void send(Context c, Intent i) {
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
        running = true;
        store = new Store(this);
        speaker = new Speaker(this, store);
        audio = getSystemService(AudioManager.class);
        createChannels();
        store.setState("idle");
        store.setCallState("none");
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
                if (intent.getBooleanExtra(EXTRA_GREET, false) && !busy) startCheckIn();
                break;
            case ACTION_ALARM:
                if (!store.serviceOn()) break;
                if (busy && !ringing && !inCall && SystemClock.elapsedRealtime() - lastActivity >= STUCK_MS) endConversation();
                if (!busy && withinActiveHours()) startCheckIn();
                if (!ringing && !inCall) scheduleNext();
                break;
            case ACTION_CHECK_IN:
                if (ringing || inCall || outgoing) break;
                if (busy) endConversation();
                startCheckIn();
                break;
            case ACTION_TALK:
                if (ringing || inCall || outgoing) break;
                beginTalk(intent.getStringExtra(EXTRA_TEXT));
                break;
            case ACTION_RESCHEDULE:
                if (store.serviceOn() && !ringing && !inCall) scheduleNext();
                break;
            case ACTION_ANSWER:
                answer();
                break;
            case ACTION_DECLINE:
                missed(true, false);
                break;
            case ACTION_SNOOZE:
                missed(true, true);
                break;
            case ACTION_HANGUP:
                if (ringing) missed(true, false);
                else hangUp();
                break;
            case ACTION_CALL_NOW:
                callNow();
                break;
            case ACTION_MUTE:
                setMuted(intent.getBooleanExtra(EXTRA_ON, !store.muted()));
                break;
            case ACTION_SPEAKER:
                setSpeaker(intent.getBooleanExtra(EXTRA_ON, !store.speakerOn()));
                break;
            default: // ACTION_RESTART
                if (store.serviceOn()) {
                    KeepAliveJob.schedule(this);
                    if (!ringing && !inCall) ensureAlarm();
                }
                break;
        }
        stopIfIdle();
        return store.serviceOn() ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (store.serviceOn()) {
            armAlarm(this, System.currentTimeMillis() + 3000, ACTION_RESTART, REQ_RESTART);
            if (!ringing && !inCall) ensureAlarm();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (store.serviceOn()) armAlarm(this, System.currentTimeMillis() + 5000, ACTION_RESTART, REQ_RESTART);
        main.removeCallbacksAndMessages(null);
        stopRinging();
        if (inCall) stopCallAudio();
        destroyRecognizer();
        speaker.shutdown();
        releaseWakeLock();
        store.setState("idle");
        store.setCallState("none");
        running = false;
        super.onDestroy();
    }

    private void stopIfIdle() {
        if (!busy && !store.serviceOn()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ---------- check-in entry ----------

    private void startCheckIn() {
        if (store.callStyle()) {
            ring(store.peekType());
        } else {
            int type = store.peekType();
            store.advanceType();
            beginCheckIn(type);
        }
    }

    // ---------- phone call ----------

    private void ring(int type) {
        if (busy) return;
        startConversation(type, false);
        ringing = true;
        silence = 0;
        preparedOpening = null;
        waitingOpening = false;
        store.setCallState("ringing");
        final int my = convo;
        prepareOpening(my, type, false);
        startRinging();
        postIncomingCallNotification();
        if (Foreground.visible()) openCallScreen(null);
        main.postDelayed(ringTimeout, RING_MS);
        updateStatusNotification();
    }

    /** Writes and pre-synthesizes her first sentence while the phone rings, so she talks the moment you pick up. */
    private void prepareOpening(int my, int type, boolean userCalled) {
        final List<Store.Msg> history = store.messages();
        final List<String> pend = new ArrayList<>(pending);
        new Thread(() -> {
            String text = null;
            try {
                text = AiClient.chat(store, userCalled ? Messages.TYPE_TALK : type, pend, history, true, true).reply;
            } catch (Exception ignored) {
            }
            if (text == null || text.trim().isEmpty()) {
                text = userCalled ? Messages.userCalledOpening(store) : Messages.callOpening(type, store);
            }
            speaker.warm(text);
            final String t = text;
            main.post(() -> {
                if (my != convo) return;
                preparedOpening = t;
                if (outgoing) {
                    outgoing = false;
                    beginActiveCall();
                    say(t, true, false);
                } else if (inCall && waitingOpening) {
                    waitingOpening = false;
                    say(t, true, false);
                }
            });
        }).start();
    }

    private void answer() {
        if (!ringing) {
            if (!inCall && !outgoing) store.setCallState("none");
            return;
        }
        main.removeCallbacks(ringTimeout);
        stopRinging();
        cancelNotification(NOTIF_CALL);
        cancelNotification(NOTIF_MISSED);
        ringing = false;
        store.advanceType();
        store.setMissedCalls(0);
        beginActiveCall();
        if (preparedOpening != null) {
            say(preparedOpening, true, false);
        } else {
            waitingOpening = true;
            store.setState("thinking");
        }
    }

    /** The user phones her from the app or from a missed-call notification. */
    private void callNow() {
        if (ringing) {
            answer();
            return;
        }
        if (inCall || outgoing) return;
        if (busy) endConversation();
        startConversation(Messages.TYPE_TALK, false);
        cancelNotification(NOTIF_MISSED);
        outgoing = true;
        silence = 0;
        store.setCallState("outgoing");
        store.setMissedCalls(0);
        prepareOpening(convo, Messages.TYPE_TALK, true);
        updateStatusNotification();
    }

    private void beginActiveCall() {
        inCall = true;
        callStart = System.currentTimeMillis();
        store.setCallStart(callStart);
        store.setMuted(false);
        store.setSpeakerOn(store.speakerDefault());
        store.setCallState("active");
        startCallAudio();
        speaker.setCallAudio(true);
        touch();
        updateStatusNotification();
    }

    private void hangUp() {
        if (!inCall && !outgoing) return;
        boolean wasActive = inCall;
        long duration = System.currentTimeMillis() - callStart;
        convo++;
        speaker.stop();
        destroyRecognizer();
        resetCall();
        if (wasActive) store.addMessage("system", "Call · " + formatDuration(duration));
        if (store.serviceOn()) scheduleNext();
        finishConversation();
    }

    /** Ring timeout, Decline, or Remind Me. She will call again after the interval (or in 5 min for Remind Me). */
    private void missed(boolean declined, boolean snooze) {
        if (!ringing) return;
        convo++;
        resetCall();
        if (snooze) {
            store.addMessage("system", "Call snoozed · she will call back in 5 min");
            long at = System.currentTimeMillis() + SNOOZE_MS;
            store.setNextAt(at);
            armAlarm(this, at, ACTION_ALARM, REQ_CHECKIN);
        } else {
            store.incMissedCalls();
            store.addMessage("system", declined ? "Declined call" : "Missed call");
            if (!declined) postMissedCallNotification();
            if (store.serviceOn()) scheduleNext();
        }
        finishConversation();
    }

    private void resetCall() {
        main.removeCallbacks(ringTimeout);
        stopRinging();
        cancelNotification(NOTIF_CALL);
        if (inCall) {
            speaker.setCallAudio(false);
            stopCallAudio();
        }
        ringing = false;
        outgoing = false;
        inCall = false;
        waitingOpening = false;
        preparedOpening = null;
        store.setMuted(false);
        store.setCallState("none");
    }

    private void setMuted(boolean on) {
        store.setMuted(on);
        if (!inCall) return;
        touch();
        if (on) {
            destroyRecognizer();
            if ("listening".equals(store.state())) store.setState("idle");
        } else if ("idle".equals(store.state()) && canListen()) {
            listen();
        }
    }

    private void setSpeaker(boolean on) {
        store.setSpeakerOn(on);
        if (inCall) applySpeaker(on);
    }

    private void startCallAudio() {
        try {
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            applySpeaker(store.speakerOn());
        } catch (Exception ignored) {
        }
    }

    private void applySpeaker(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            audio.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else {
                    audio.clearCommunicationDevice();
                }
            } else {
                audio.setSpeakerphoneOn(on);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopCallAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice();
            else audio.setSpeakerphoneOn(false);
            audio.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }

    private void startRinging() {
        AudioAttributes ringAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        try {
            ringtone = RingtoneManager.getRingtone(this, Settings.System.DEFAULT_RINGTONE_URI);
            if (ringtone != null) {
                ringtone.setAudioAttributes(ringAttrs);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) {
        }
        try {
            if (audio.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
                vibrator = getSystemService(Vibrator.class);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 900, 1100}, 0), ringAttrs);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopRinging() {
        try {
            if (ringtone != null) ringtone.stop();
        } catch (Exception ignored) {
        }
        ringtone = null;
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {
        }
        vibrator = null;
    }

    private void openCallScreen(String action) {
        try {
            Intent i = new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (action != null) i.setAction(action);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
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

    /** Non-call check-in: she just speaks (the "Speak directly" style). */
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
                if (my == convo) say(text, listenAfter, false);
            });
        }).start();
    }

    private void beginTalk(String text) {
        if (busy) {
            speaker.stop();
            destroyRecognizer();
        }
        startConversation(Messages.TYPE_TALK, text != null && !text.trim().isEmpty());
        cancelNotification(NOTIF_MESSAGE);
        if (typed) {
            onUserSaid(text.trim());
        } else if (canListen()) {
            listen();
        } else if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(Messages.noRecognizer(store), false, false);
        } else {
            finishConversation();
        }
    }

    private void say(String text, boolean thenListen, boolean endCallAfter) {
        touch();
        store.addMessage("ai", text);
        if (!inCall && !Foreground.visible() && mode != Messages.TYPE_TALK) postMessageNotification(text, thenListen);
        store.setState("speaking");
        final int my = convo;
        speaker.speak(text, () -> {
            if (my != convo) return;
            if (inCall) {
                if (endCallAfter) {
                    hangUp();
                } else if (store.muted() || !canListen()) {
                    store.setState("idle");
                } else {
                    listen();
                }
                return;
            }
            if (thenListen && !typed && turn < MAX_TURNS && canListen()) listen();
            else finishConversation();
        });
    }

    private boolean canListen() {
        return store.listen() && hasMicPermission() && (micAllowed || Foreground.visible())
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
                    if (inCall) {
                        if (store.muted()) return;
                        silence++;
                        if (silence == 1) say(Messages.callHello(store), true, false);
                        else say(Messages.callGoodbye(store), false, true);
                        return;
                    }
                    if (mode != Messages.TYPE_TALK && turn == 1) say(Messages.noReply(store), false, false);
                    else finishConversation();
                }

                @Override
                public void onResults(Bundle results) {
                    destroyRecognizer();
                    if (my != convo) return;
                    List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list == null || list.isEmpty()) ? "" : list.get(0).trim();
                    if (text.isEmpty()) {
                        onError(SpeechRecognizer.ERROR_NO_MATCH);
                        return;
                    }
                    silence = 0;
                    onUserSaid(text);
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
            if (inCall) store.setState("idle");
            else finishConversation();
        }
    }

    private void onUserSaid(String text) {
        touch();
        cancelNotification(NOTIF_MESSAGE);
        store.addMessage("user", text);
        if (mode == Messages.TYPE_CAREER && turn <= 1) store.addLearning(text);
        store.setState("thinking");
        final int my = convo;
        final int myMode = mode;
        final boolean call = inCall;
        final List<Store.Msg> history = store.messages();
        final List<String> pend = new ArrayList<>(pending);
        new Thread(() -> {
            try {
                AiClient.Result r = AiClient.chat(store, myMode, pend, history, false, call);
                main.post(() -> {
                    if (my != convo) return;
                    markDone(r.done);
                    say(r.reply, r.askMore, inCall && r.endCall);
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
        if (inCall && Messages.soundsLikeBye(text)) {
            say(Messages.callGoodbye(store), false, true);
            return;
        }
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
        say(reply, false, false);
    }

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
        destroyRecognizer();
        if (ringing || inCall || outgoing) resetCall();
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

    private void goForeground() {
        Notification n = buildStatusNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (hasMicPermission() && (Foreground.visible() || micAllowed)) {
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
        nm.deleteNotificationChannel("personalai");
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Background status", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        NotificationChannel msgs = new NotificationChannel(CH_MESSAGES, "Messages and missed calls", NotificationManager.IMPORTANCE_HIGH);
        msgs.setSound(null, null);
        NotificationChannel calls = new NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(null, null); // the service plays the phone ringtone itself
        calls.enableVibration(false);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(msgs);
        nm.createNotificationChannel(calls);
    }

    private PendingIntent activity(int req, Class<?> cls, String action, boolean reply) {
        Intent i = new Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) i.setAction(action);
        if (reply) i.putExtra(MainActivity.EXTRA_REPLY, true);
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent service(int req, String action) {
        return PendingIntent.getService(this, req, new Intent(this, CheckInService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildStatusNotification() {
        String title;
        String text;
        PendingIntent open;
        if (inCall || outgoing) {
            title = "On call with " + store.herName();
            text = "Tap to return to the call";
            open = activity(1, CallActivity.class, null, false);
        } else {
            title = store.herName() + (store.serviceOn() ? " is with you" : "");
            long next = store.nextAt();
            text = !store.serviceOn() ? "In conversation"
                    : next > 0 ? "Next call " + Store.timeLabel(next) : "Automatic calls on";
            if (store.serviceOn() && store.listen() && !micAllowed) text += " · open the app once to enable voice replies";
            open = activity(1, MainActivity.class, null, false);
        }
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
        if (inCall) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_call_end), "Hang up",
                    service(6, ACTION_HANGUP)).build());
        } else if (store.serviceOn()) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), "Pause",
                    service(5, ACTION_STOP)).build());
        }
        return b.build();
    }

    private void updateStatusNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_STATUS, buildStatusNotification());
        } catch (Exception ignored) {
        }
    }

    private void postIncomingCallNotification() {
        PendingIntent full = activity(10, CallActivity.class, null, false);
        PendingIntent answer = activity(11, CallActivity.class, CallActivity.ACTION_ANSWER, false);
        PendingIntent decline = service(12, ACTION_DECLINE);
        Notification.Builder b = new Notification.Builder(this, CH_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand))
                .setContentTitle(store.herName())
                .setContentText("Incoming call")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person caller = new Person.Builder().setName(store.herName()).setImportant(true).build();
            b.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer));
        } else {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_call_end), "Decline", decline).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_call), "Answer", answer).build());
        }
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_CALL, b.build());
        } catch (Exception ignored) {
        }
    }

    private void postMissedCallNotification() {
        int n = store.missedCalls();
        String text = n > 1 ? n + " missed calls" : "Missed call";
        Notification.Builder b = new Notification.Builder(this, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand))
                .setContentTitle(store.herName())
                .setContentText(text)
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentIntent(activity(13, MainActivity.class, null, false))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_call), "Call back",
                        activity(14, CallActivity.class, CallActivity.ACTION_OUTGOING, false)).build());
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_MISSED, b.build());
        } catch (Exception ignored) {
        }
    }

    private void postMessageNotification(String text, boolean wantsReply) {
        Notification.Builder b = new Notification.Builder(this, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand))
                .setContentTitle(store.herName())
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(activity(3, MainActivity.class, null, false))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        if (wantsReply) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Reply",
                    activity(4, MainActivity.class, null, true)).build());
        }
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_MESSAGE, b.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelNotification(int id) {
        try {
            getSystemService(NotificationManager.class).cancel(id);
        } catch (Exception ignored) {
        }
    }

    // ---------- wake lock ----------

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalai:conversation");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
