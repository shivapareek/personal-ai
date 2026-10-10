package com.shiva.personalai;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Chat: iMessage-style thread with her, call button, and the auto-call banner. */
class HomeTab {

    private static final long GROUP_GAP_MS = 15 * 60_000;

    private final MainActivity a;
    private final Store s;
    private final TextView avatar, herName, status, bannerTitle, bannerText, bannerButton;
    private final View banner, dot;
    private final LinearLayout chat;
    private final ScrollView scroll;
    private final EditText edit;
    private final ImageButton btnMic;
    private String renderedKey = "";

    HomeTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        avatar = root.findViewById(R.id.avatar);
        herName = root.findViewById(R.id.textHerName);
        status = root.findViewById(R.id.textStatus);
        banner = root.findViewById(R.id.bannerAuto);
        dot = root.findViewById(R.id.dotStatus);
        bannerTitle = root.findViewById(R.id.bannerTitle);
        bannerText = root.findViewById(R.id.bannerText);
        bannerButton = root.findViewById(R.id.bannerButton);
        chat = root.findViewById(R.id.chatList);
        scroll = root.findViewById(R.id.scrollChat);
        edit = root.findViewById(R.id.editMessage);
        btnMic = root.findViewById(R.id.btnMic);

        root.findViewById(R.id.btnCallNow).setOnClickListener(v -> a.callHer());
        bannerButton.setOnClickListener(v -> {
            if (!s.serviceOn()) a.setAuto(true);
            else a.setupAiKey();
        });
        root.findViewById(R.id.headerCenter).setOnClickListener(v -> a.openTab(3));
        root.findViewById(R.id.headerCenter).setOnLongClickListener(v -> {
            Ui.confirm(a, "Clear conversation?", "Messages will be removed. Tasks and journal stay.", "Clear", s::clearMessages);
            return true;
        });

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int st, int co, int af) { }
            @Override public void onTextChanged(CharSequence c, int st, int b, int co) { }
            @Override public void afterTextChanged(Editable e) { updateSendButton(); }
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
        updateSendButton();
    }

    void refresh() {
        String name = s.herName();
        herName.setText(name);
        avatar.setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
        refreshStatus();
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
            case "callStyle":
                refreshStatus();
                break;
            case "herName":
            case "aiKey":
                refresh();
                break;
            default:
                break;
        }
    }

    private void refreshStatus() {
        boolean on = s.serviceOn();
        String state = s.state();
        String text;
        switch (state) {
            case "listening": text = "listening…"; break;
            case "thinking": text = "typing…"; break;
            case "speaking": text = "speaking…"; break;
            default:
                long next = s.nextAt();
                text = on && next > 0 ? (s.callStyle() ? "calls you " : "checks in ") + Store.timeLabel(next) : "";
        }
        if (text.isEmpty()) text = on ? "Online" : "Calls are off";
        status.setText(text);
        dot.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean slowAi = AiClient.usingFreeAi(s);
        banner.setVisibility(!on || slowAi ? View.VISIBLE : View.GONE);
        if (!on) {
            bannerTitle.setText("Let her call you");
            bannerText.setText(s.herName() + (s.callStyle() ? " will call you every " : " will check on you every ")
                    + s.intervalMin() + (s.intervalMin() == 1 ? " minute" : " minutes") + " to see how you are doing.");
            bannerButton.setText("Turn on calls");
        } else {
            bannerTitle.setText("Make her reply instantly");
            bannerText.setText("The free AI takes 5 to 15 seconds. Add a free Groq key for instant, smarter replies.");
            bannerButton.setText("Add free key");
        }
        boolean listening = "listening".equals(state);
        boolean hasText = edit.getText().toString().trim().length() > 0;
        btnMic.setBackgroundResource(listening && !hasText ? R.drawable.bg_circle_red : R.drawable.bg_circle_accent);
    }

    private void updateSendButton() {
        boolean hasText = edit.getText().toString().trim().length() > 0;
        btnMic.setImageResource(hasText ? R.drawable.ic_send : R.drawable.ic_mic);
        btnMic.setContentDescription(hasText ? "Send" : "Talk");
        refreshStatus();
    }

    private void renderChat() {
        List<Store.Msg> msgs = s.messages();
        boolean thinking = "thinking".equals(s.state()) && "none".equals(s.callState());
        String key = msgs.size() + ":" + (msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).time) + ":" + thinking;
        if (key.equals(renderedKey)) return;
        renderedKey = key;
        chat.removeAllViews();

        if (msgs.isEmpty() && !thinking) {
            chat.addView(Ui.emptyState(a, "No messages yet",
                    "Tap the phone to call " + s.herName() + ", or type a message below."));
            return;
        }
        int maxWidth = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.74f);
        String prevRole = null;
        long prevTime = 0;
        for (int i = Math.max(0, msgs.size() - 60); i < msgs.size(); i++) {
            Store.Msg m = msgs.get(i);
            boolean newGroup = m.time - prevTime > GROUP_GAP_MS;
            if (newGroup) addTimestamp(m.time);
            if ("system".equals(m.role)) {
                addSystem(m.text);
                prevRole = null;
            } else {
                boolean ai = "ai".equals(m.role);
                boolean tight = !newGroup && m.role.equals(prevRole);
                addBubble(m.text, ai, maxWidth, false, tight);
                prevRole = m.role;
            }
            prevTime = m.time;
        }
        if (thinking) addBubble("•••", true, maxWidth, true, "ai".equals(prevRole));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addTimestamp(long t) {
        TextView tv = new TextView(a);
        tv.setText(stamp(t));
        tv.setTextSize(12);
        tv.setTextColor(a.getColor(R.color.muted));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, a.dp(14), 0, a.dp(4));
        chat.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addSystem(String text) {
        TextView tv = new TextView(a);
        boolean missed = text.startsWith("Missed") || text.startsWith("Declined") || text.startsWith("AI did not");
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(a.getColor(missed ? R.color.red : R.color.muted));
        tv.setBackgroundResource(R.drawable.bg_pill_soft);
        tv.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        tv.setGravity(Gravity.CENTER);
        tv.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.85f));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, a.dp(8), 0, a.dp(4));
        row.addView(tv);
        chat.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addBubble(String text, boolean ai, int maxWidth, boolean placeholder, boolean tight) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(a.getColor(placeholder ? R.color.muted : ai ? R.color.text : R.color.onAccent));
        tv.setMaxWidth(maxWidth);
        tv.setPadding(a.dp(16), a.dp(11), a.dp(16), a.dp(11));
        tv.setBackgroundResource(ai ? R.drawable.bg_bubble_ai : R.drawable.bg_bubble_user);
        tv.setTextIsSelectable(!placeholder);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(ai ? Gravity.START : Gravity.END);
        row.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(tight ? 3 : 10);
        chat.addView(row, lp);
    }

    private static String stamp(long t) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        String time = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t));
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            int diff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR);
            if (diff == 0) return "Today " + time;
            if (diff == 1) return "Yesterday " + time;
        }
        return new SimpleDateFormat("EEE, d MMM", Locale.US).format(new Date(t)) + " at " + time;
    }
}
