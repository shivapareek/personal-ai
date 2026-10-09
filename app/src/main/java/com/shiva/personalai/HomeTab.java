package com.shiva.personalai;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** Home: assistant status, auto check-in switch, quick stats and the conversation. */
class HomeTab {

    private final MainActivity a;
    private final Store s;
    private final TextView avatar, herName, status, statPending, statDone, statLearn;
    private final Switch sw;
    private final LinearLayout chat;
    private final ScrollView scroll;
    private final EditText edit;
    private final ImageButton btnMic;
    private ObjectAnimator pulse;
    private boolean updating;
    private String renderedKey = "";

    HomeTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        avatar = root.findViewById(R.id.avatar);
        herName = root.findViewById(R.id.textHerName);
        status = root.findViewById(R.id.textStatus);
        statPending = root.findViewById(R.id.statPending);
        statDone = root.findViewById(R.id.statDone);
        statLearn = root.findViewById(R.id.statLearn);
        sw = root.findViewById(R.id.switchAuto);
        chat = root.findViewById(R.id.chatList);
        scroll = root.findViewById(R.id.scrollChat);
        edit = root.findViewById(R.id.editMessage);
        btnMic = root.findViewById(R.id.btnMic);

        sw.setOnCheckedChangeListener((v, on) -> {
            if (updating) return;
            a.setAuto(on);
            refreshStatus();
        });
        root.findViewById(R.id.btnCheckIn).setOnClickListener(v -> a.checkInNow());
        root.findViewById(R.id.statPendingCell).setOnClickListener(v -> a.openTab(1));
        root.findViewById(R.id.statDoneCell).setOnClickListener(v -> a.openTab(1));
        root.findViewById(R.id.statLearnCell).setOnClickListener(v -> a.openTab(2));
        herName.setOnLongClickListener(v -> {
            confirmClear();
            return true;
        });

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int st, int co, int af) { }
            @Override public void onTextChanged(CharSequence c, int st, int b, int co) { }
            @Override public void afterTextChanged(Editable e) { updateSendIcon(); }
        });
        btnMic.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (!text.isEmpty()) {
                edit.setText("");
                a.talk(text);
            } else {
                a.talk(null);
            }
        });
        edit.setHint("Message " + s.herName());
    }

    void refresh() {
        String name = s.herName();
        herName.setText(name);
        avatar.setText(name.substring(0, 1).toUpperCase());
        edit.setHint("Message " + name);
        refreshStatus();
        refreshStats();
        renderedKey = "";
        renderChat();
    }

    void onPrefChanged(String key) {
        switch (key) {
            case Store.KEY_MESSAGES:
                renderChat();
                break;
            case Store.KEY_STATE:
                refreshStatus();
                renderChat();
                break;
            case Store.KEY_NEXT_AT:
            case Store.KEY_SERVICE_ON:
            case "interval":
                refreshStatus();
                break;
            case "todos":
            case "learnings":
                refreshStats();
                break;
            case "herName":
                refresh();
                break;
            default:
                break;
        }
    }

    private void refreshStatus() {
        boolean on = s.serviceOn();
        String state = s.state();
        updating = true;
        sw.setChecked(on);
        updating = false;

        String text;
        boolean active = true;
        switch (state) {
            case "listening": text = "Listening…"; break;
            case "thinking": text = "Thinking…"; break;
            case "speaking": text = "Speaking…"; break;
            default:
                active = false;
                long next = s.nextAt();
                if (!on) text = "Paused · turn on for automatic check-ins";
                else if (next > 0) text = "Active · next check-in " + Store.timeLabel(next);
                else text = "Active";
        }
        status.setText(text);
        status.setTextColor(a.getColor(active || on ? R.color.accent : R.color.muted));

        if ("listening".equals(state)) startPulse();
        else stopPulse();
    }

    private void refreshStats() {
        int pending = 0, done = 0;
        for (Store.Todo t : s.todos()) {
            if (t.done) done++;
            else pending++;
        }
        statPending.setText(String.valueOf(pending));
        statDone.setText(String.valueOf(done));
        statLearn.setText(String.valueOf(s.learningEntries().size()));
    }

    private void updateSendIcon() {
        boolean hasText = edit.getText().toString().trim().length() > 0;
        btnMic.setImageResource(hasText ? R.drawable.ic_send : R.drawable.ic_mic);
        btnMic.setContentDescription(hasText ? "Send" : "Talk");
    }

    private void startPulse() {
        if (pulse != null) return;
        pulse = ObjectAnimator.ofFloat(btnMic, View.ALPHA, 1f, 0.45f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        btnMic.setAlpha(1f);
    }

    private void renderChat() {
        List<Store.Msg> msgs = s.messages();
        boolean thinking = "thinking".equals(s.state());
        String key = msgs.size() + ":" + (msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).time) + ":" + thinking;
        if (key.equals(renderedKey)) return;
        renderedKey = key;
        chat.removeAllViews();

        if (msgs.isEmpty() && !thinking) {
            TextView empty = new TextView(a);
            empty.setText("No messages yet.\nTurn on automatic check-ins and " + s.herName()
                    + " will start the conversation, or tap the mic to talk.");
            empty.setTextColor(a.getColor(R.color.muted));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.2f);
            empty.setPadding(a.dp(24), a.dp(48), a.dp(24), 0);
            chat.addView(empty);
            return;
        }
        int maxWidth = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.78f);
        for (int i = Math.max(0, msgs.size() - 50); i < msgs.size(); i++) {
            Store.Msg m = msgs.get(i);
            addBubble(m.text, "ai".equals(m.role), maxWidth, false);
        }
        if (thinking) addBubble("…", true, maxWidth, true);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addBubble(String text, boolean ai, int maxWidth, boolean placeholder) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(a.getColor(placeholder ? R.color.muted : ai ? R.color.text : R.color.onAccent));
        tv.setMaxWidth(maxWidth);
        tv.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        tv.setBackgroundResource(ai ? R.drawable.bg_bubble_ai : R.drawable.bg_bubble_user);
        tv.setTextIsSelectable(!placeholder);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(ai ? Gravity.START : Gravity.END);
        row.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(8);
        chat.addView(row, lp);
    }

    private void confirmClear() {
        new AlertDialog.Builder(a)
                .setTitle("Clear conversation?")
                .setMessage("Messages will be removed. Tasks and journal stay.")
                .setPositiveButton("Clear", (d, w) -> s.clearMessages())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
