package com.shiva.personalai;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.Locale;

/** iPhone-style incoming / in-call screen. Shows over the lock screen and turns the screen on. */
public class CallActivity extends Activity {

    public static final String ACTION_ANSWER = "com.shiva.personalai.call.ANSWER";
    public static final String ACTION_OUTGOING = "com.shiva.personalai.call.OUTGOING";

    private Store store;
    private TextView name, status, avatar, activity;
    private View pulseRing, incoming, active;
    private ImageButton btnMute, btnSpeaker;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator ringPulse, talkPulse;
    private PowerManager.WakeLock proximity;
    private String pendingAction;
    private boolean sawCall;
    private boolean finishing;
    private long createdAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (Store.KEY_CALL.equals(key) || Store.KEY_STATE.equals(key) || Store.KEY_MUTED.equals(key)
                || Store.KEY_SPEAKER.equals(key)) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);
        store = new Store(this);
        createdAt = System.currentTimeMillis();

        name = findViewById(R.id.callName);
        status = findViewById(R.id.callStatus);
        avatar = findViewById(R.id.callAvatar);
        activity = findViewById(R.id.callActivity);
        pulseRing = findViewById(R.id.callPulse);
        incoming = findViewById(R.id.incomingControls);
        active = findViewById(R.id.activeControls);
        btnMute = findViewById(R.id.btnMute);
        btnSpeaker = findViewById(R.id.btnSpeaker);

        findViewById(R.id.btnAccept).setOnClickListener(v -> send(CheckInService.ACTION_ANSWER));
        findViewById(R.id.btnDecline).setOnClickListener(v -> send(CheckInService.ACTION_DECLINE));
        findViewById(R.id.btnRemind).setOnClickListener(v -> send(CheckInService.ACTION_SNOOZE));
        findViewById(R.id.btnMessage).setOnClickListener(v -> {
            send(CheckInService.ACTION_DECLINE);
            openChat();
        });
        findViewById(R.id.btnEnd).setOnClickListener(v -> send(CheckInService.ACTION_HANGUP));
        btnMute.setOnClickListener(v -> send(new Intent(this, CheckInService.class)
                .setAction(CheckInService.ACTION_MUTE).putExtra(CheckInService.EXTRA_ON, !store.muted())));
        btnSpeaker.setOnClickListener(v -> send(new Intent(this, CheckInService.class)
                .setAction(CheckInService.ACTION_SPEAKER).putExtra(CheckInService.EXTRA_ON, !store.speakerOn())));
        findViewById(R.id.btnChat).setOnClickListener(v -> openChat());

        pendingAction = getIntent().getAction();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingAction = intent.getAction();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Foreground.resumed();
        store.prefs().registerOnSharedPreferenceChangeListener(listener);
        // Sent after we are visible so the service may use the microphone.
        if (ACTION_ANSWER.equals(pendingAction)) send(CheckInService.ACTION_ANSWER);
        else if (ACTION_OUTGOING.equals(pendingAction)) send(CheckInService.ACTION_CALL_NOW);
        pendingAction = null;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        Foreground.paused();
        store.prefs().unregisterOnSharedPreferenceChangeListener(listener);
        handler.removeCallbacks(tick);
        setProximity(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAnim();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if ("ringing".equals(store.callState())) return;
        moveTaskToBack(true);
    }

    private void send(String action) {
        send(new Intent(this, CheckInService.class).setAction(action));
    }

    private void send(Intent i) {
        CheckInService.send(this, i);
    }

    private void openChat() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ---------- rendering ----------

    private void render() {
        if (finishing) return;
        String her = store.herName();
        name.setText(her);
        avatar.setText(her.substring(0, 1).toUpperCase(Locale.ROOT));
        String call = CheckInService.running ? store.callState() : "none";

        if ("none".equals(call)) {
            boolean grace = System.currentTimeMillis() - createdAt < 4000 && !sawCall;
            if (grace) {
                status.setText("calling…");
                return;
            }
            finishing = true;
            status.setText(sawCall ? "Call ended" : "");
            incoming.setVisibility(View.INVISIBLE);
            active.setVisibility(View.INVISIBLE);
            activity.setText("");
            stopAnim();
            setProximity(false);
            handler.postDelayed(this::finishAndRemoveTask, sawCall ? 900 : 0);
            return;
        }
        sawCall = true;

        if ("ringing".equals(call)) {
            incoming.setVisibility(View.VISIBLE);
            active.setVisibility(View.GONE);
            status.setText("Personal AI audio…");
            activity.setText("");
            startRingPulse();
            setProximity(false);
            return;
        }

        incoming.setVisibility(View.GONE);
        active.setVisibility(View.VISIBLE);
        stopRingPulse();

        if ("outgoing".equals(call)) {
            status.setText("calling…");
            activity.setText("");
        } else {
            long secs = Math.max(0, (System.currentTimeMillis() - store.callStart()) / 1000);
            status.setText(String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60));
            String state = store.state();
            String label;
            if (store.muted()) label = "Muted";
            else if ("listening".equals(state)) label = "Listening…";
            else if ("speaking".equals(state)) label = her + " is speaking";
            else if ("thinking".equals(state)) label = "…";
            else label = "";
            activity.setText(label);
            if ("speaking".equals(state)) startTalkPulse();
            else stopTalkPulse();
        }

        styleToggle(btnMute, store.muted());
        styleToggle(btnSpeaker, store.speakerOn());
        setProximity("active".equals(call) && !store.speakerOn());
    }

    private void styleToggle(ImageButton b, boolean on) {
        b.setBackgroundResource(on ? R.drawable.bg_call_btn_on : R.drawable.bg_call_btn);
        b.setImageTintList(ColorStateList.valueOf(on ? 0xFF000000 : 0xFFFFFFFF));
    }

    private void startRingPulse() {
        if (ringPulse != null) return;
        pulseRing.setAlpha(1f);
        ringPulse = ObjectAnimator.ofPropertyValuesHolder(pulseRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.0f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f));
        ringPulse.setDuration(1400);
        ringPulse.setRepeatCount(ValueAnimator.INFINITE);
        ringPulse.start();
    }

    private void stopRingPulse() {
        if (ringPulse != null) {
            ringPulse.cancel();
            ringPulse = null;
        }
        pulseRing.setAlpha(0f);
    }

    private void startTalkPulse() {
        if (talkPulse != null) return;
        talkPulse = ObjectAnimator.ofPropertyValuesHolder(avatar,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
        talkPulse.setDuration(450);
        talkPulse.setRepeatMode(ValueAnimator.REVERSE);
        talkPulse.setRepeatCount(ValueAnimator.INFINITE);
        talkPulse.start();
    }

    private void stopTalkPulse() {
        if (talkPulse != null) {
            talkPulse.cancel();
            talkPulse = null;
        }
        avatar.setScaleX(1f);
        avatar.setScaleY(1f);
    }

    private void stopAnim() {
        stopRingPulse();
        stopTalkPulse();
    }

    /** Screen turns off when the phone is held to the ear, like a real call (earpiece mode only). */
    private void setProximity(boolean on) {
        try {
            if (on) {
                if (proximity == null) {
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm == null || !pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
                    proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "personalai:proximity");
                }
                if (!proximity.isHeld()) proximity.acquire(30 * 60 * 1000L);
            } else if (proximity != null && proximity.isHeld()) {
                proximity.release();
            }
        } catch (Exception ignored) {
        }
    }
}
