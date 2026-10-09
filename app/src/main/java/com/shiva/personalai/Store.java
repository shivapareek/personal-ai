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
    }

    public SharedPreferences prefs() { return p; }

    // ---- settings ----
    public String name() { return p.getString("name", "").trim(); }
    public String herName() { String n = p.getString("herName", "").trim(); return n.isEmpty() ? "Aisha" : n; }
    public boolean hindi() { return "hi".equals(p.getString("lang", "hi")); }
    public int intervalMin() { return p.getInt("interval", 30); }
    public int startHour() { return p.getInt("startHour", 8); }
    public int endHour() { return p.getInt("endHour", 22); }
    public boolean onlineVoice() { return p.getBoolean("onlineVoice", true); }
    public String voiceName() { return p.getString("voice", ""); }
    public boolean listen() { return p.getBoolean("listen", true); }
    public String aiUrl() { return p.getString("aiUrl", DEFAULT_AI_URL).trim(); }
    public String aiModel() { return p.getString("aiModel", DEFAULT_AI_MODEL).trim(); }
    public String aiKey() { return p.getString("aiKey", "").trim(); }
    public boolean onboarded() { return p.getBoolean("onboarded", false); }
    public void setOnboarded() { p.edit().putBoolean("onboarded", true).apply(); }

    public void saveSettings(String name, String herName, boolean hindi, int intervalMin, int startHour, int endHour,
                             boolean onlineVoice, String voice, boolean listen, String aiUrl, String aiModel, String aiKey) {
        p.edit()
                .putString("name", name)
                .putString("herName", herName)
                .putString("lang", hindi ? "hi" : "en")
                .putInt("interval", Math.max(1, intervalMin))
                .putInt("startHour", clampHour(startHour))
                .putInt("endHour", clampHour(endHour))
                .putBoolean("onlineVoice", onlineVoice)
                .putString("voice", voice)
                .putBoolean("listen", listen)
                .putString("aiUrl", aiUrl)
                .putString("aiModel", aiModel)
                .putString("aiKey", aiKey)
                .apply();
    }

    private static int clampHour(int h) { return Math.max(0, Math.min(23, h)); }

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
