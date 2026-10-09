package com.shiva.personalai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** All app data: settings, todo list, learning log, chat history and live state. Plain SharedPreferences. */
public class Store {

    public static class Todo {
        public String text;
        public boolean done;

        Todo(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    public static class Msg {
        public final String role; // "ai" or "user"
        public final String text;
        public final long time;

        Msg(String role, String text, long time) {
            this.role = role;
            this.text = text;
            this.time = time;
        }
    }

    public static final String DEFAULT_AI_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String DEFAULT_AI_MODEL = "llama-3.3-70b-versatile";
    public static final String PREFS = "personalai";
    public static final String KEY_MESSAGES = "messages";
    public static final String KEY_STATE = "state";
    public static final String KEY_NEXT_AT = "nextAt";
    public static final String KEY_SERVICE_ON = "serviceOn";

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrate();
    }

    /** v3: the old default quiet window (8 AM - 10 PM) silenced evening check-ins; widen it to 7 AM - midnight. */
    private void migrate() {
        if (p.getInt("schema", 0) >= 3) return;
        SharedPreferences.Editor e = p.edit();
        if (p.getInt("startHour", 7) == 8) e.putInt("startHour", 7);
        if (p.getInt("endHour", 24) == 22) e.putInt("endHour", 24);
        e.putInt("schema", 3).apply();
    }

    public SharedPreferences prefs() { return p; }

    // ---- settings ----
    public String name() { return p.getString("name", "").trim(); }
    public String herName() { String n = p.getString("herName", "").trim(); return n.isEmpty() ? "Aisha" : n; }
    public boolean hindi() { return "hi".equals(p.getString("lang", "hi")); }
    public int intervalMin() { return p.getInt("interval", 30); }
    public int startHour() { return p.getInt("startHour", 7); }
    public int endHour() { return p.getInt("endHour", 24); }
    public boolean onlineVoice() { return p.getBoolean("onlineVoice", true); }
    public String voiceName() { return p.getString("voice", ""); }
    public boolean listen() { return p.getBoolean("listen", true); }
    public String aiUrl() { return p.getString("aiUrl", DEFAULT_AI_URL).trim(); }
    public String aiModel() { return p.getString("aiModel", DEFAULT_AI_MODEL).trim(); }
    public String aiKey() { return p.getString("aiKey", "").trim(); }
    public boolean onboarded() { return p.getBoolean("onboarded", false); }
    public void setOnboarded() { p.edit().putBoolean("onboarded", true).apply(); }

    public void setName(String v) { p.edit().putString("name", v.trim()).apply(); }
    public void setHerName(String v) { p.edit().putString("herName", v.trim()).apply(); }
    public void setHindi(boolean v) { p.edit().putString("lang", v ? "hi" : "en").apply(); }
    public void setIntervalMin(int v) { p.edit().putInt("interval", Math.max(1, v)).apply(); }
    public void setStartHour(int v) { p.edit().putInt("startHour", Math.max(0, Math.min(23, v))).apply(); }
    public void setEndHour(int v) { p.edit().putInt("endHour", Math.max(1, Math.min(24, v))).apply(); }
    public void setOnlineVoice(boolean v) { p.edit().putBoolean("onlineVoice", v).apply(); }
    public void setVoiceName(String v) { p.edit().putString("voice", v).apply(); }
    public void setListen(boolean v) { p.edit().putBoolean("listen", v).apply(); }
    public void setAiUrl(String v) { p.edit().putString("aiUrl", v.trim().isEmpty() ? DEFAULT_AI_URL : v.trim()).apply(); }
    public void setAiModel(String v) { p.edit().putString("aiModel", v.trim().isEmpty() ? DEFAULT_AI_MODEL : v.trim()).apply(); }
    public void setAiKey(String v) { p.edit().putString("aiKey", v.trim()).apply(); }

    /** "7:00 AM", hour 0 and 24 are both midnight. */
    public static String hourLabel(int h) {
        int hh = h % 24;
        int twelve = hh % 12 == 0 ? 12 : hh % 12;
        return twelve + ":00 " + (hh < 12 ? "AM" : "PM") + (hh == 0 ? " (midnight)" : "");
    }

    /** "11:30 PM" today, "tomorrow 7:00 AM" otherwise. */
    public static String timeLabel(long t) {
        String time = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t));
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        if (c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
                && c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) return time;
        return "tomorrow " + time;
    }

    // ---- live state (written by the service, shown by the UI) ----
    public boolean serviceOn() { return p.getBoolean(KEY_SERVICE_ON, false); }
    public void setServiceOn(boolean on) { p.edit().putBoolean(KEY_SERVICE_ON, on).apply(); }
    public String state() { return p.getString(KEY_STATE, "idle"); }
    public void setState(String s) { p.edit().putString(KEY_STATE, s).apply(); }
    public long nextAt() { return p.getLong(KEY_NEXT_AT, 0); }
    public void setNextAt(long t) { p.edit().putLong(KEY_NEXT_AT, t).apply(); }

    /** Returns the next check-in type (care / career / todo) and rotates to the following one. */
    public int nextTypeAndAdvance() {
        int t = p.getInt("type", 0);
        p.edit().putInt("type", (t + 1) % 3).apply();
        return t;
    }

    // ---- chat history ----
    public void addMessage(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONArray a = new JSONArray(p.getString(KEY_MESSAGES, "[]"));
            a.put(new JSONObject().put("role", role).put("text", text.trim()).put("time", System.currentTimeMillis()));
            while (a.length() > 80) a.remove(0);
            p.edit().putString(KEY_MESSAGES, a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public List<Msg> messages() {
        List<Msg> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(KEY_MESSAGES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Msg(o.getString("role"), o.getString("text"), o.optLong("time", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void clearMessages() { p.edit().remove(KEY_MESSAGES).apply(); }

    // ---- todos ----
    public List<Todo> todos() {
        List<Todo> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("todos", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Todo(o.getString("text"), o.optBoolean("done", false)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void saveTodos(List<Todo> todos) {
        JSONArray a = new JSONArray();
        try {
            for (Todo t : todos) a.put(new JSONObject().put("text", t.text).put("done", t.done));
        } catch (JSONException ignored) {
        }
        p.edit().putString("todos", a.toString()).apply();
    }

    public List<String> pendingTexts() {
        List<String> out = new ArrayList<>();
        for (Todo t : todos()) if (!t.done) out.add(t.text);
        return out;
    }

    public List<String> doneTexts() {
        List<String> out = new ArrayList<>();
        for (Todo t : todos()) if (t.done) out.add(t.text);
        return out;
    }

    // ---- learnings ("aaj kya seekha") ----
    public void addLearning(String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONArray a = new JSONArray(p.getString("learnings", "[]"));
            String date = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(new Date());
            a.put(new JSONObject().put("date", date).put("text", text.trim()));
            while (a.length() > 200) a.remove(0);
            p.edit().putString("learnings", a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Newest first, {date, text}. */
    public List<String[]> learningEntries() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("learnings", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("date"), o.getString("text")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Newest first, "date - text". */
    public List<String> learnings() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("learnings", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                out.add(o.getString("date") + " - " + o.getString("text"));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }
}
