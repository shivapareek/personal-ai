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

/** All app data: settings, todo list and "what I learned" log. Plain SharedPreferences, no AI. */
public class Store {

    public static class Todo {
        public String text;
        public boolean done;

        Todo(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    public static final String DEFAULT_AI_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String DEFAULT_AI_MODEL = "llama-3.3-70b-versatile";

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getSharedPreferences("personalai", Context.MODE_PRIVATE);
    }

    // ---- settings ----
    public String name() { return p.getString("name", "").trim(); }
    public boolean hindi() { return "hi".equals(p.getString("lang", "hi")); }
    public int intervalMin() { return p.getInt("interval", 60); }
    public int startHour() { return p.getInt("startHour", 8); }
    public int endHour() { return p.getInt("endHour", 22); }
    public String voiceName() { return p.getString("voice", ""); }
    public boolean listen() { return p.getBoolean("listen", true); }
    public String aiUrl() { return p.getString("aiUrl", DEFAULT_AI_URL).trim(); }
    public String aiModel() { return p.getString("aiModel", DEFAULT_AI_MODEL).trim(); }
    public String aiKey() { return p.getString("aiKey", "").trim(); }
    public boolean aiEnabled() { return !aiKey().isEmpty(); }

    public void saveSettings(String name, boolean hindi, int intervalMin, int startHour, int endHour,
                             String voice, boolean listen, String aiUrl, String aiModel, String aiKey) {
        p.edit()
                .putString("name", name)
                .putString("lang", hindi ? "hi" : "en")
                .putInt("interval", Math.max(1, intervalMin))
                .putInt("startHour", clampHour(startHour))
                .putInt("endHour", clampHour(endHour))
                .putString("voice", voice)
                .putBoolean("listen", listen)
                .putString("aiUrl", aiUrl)
                .putString("aiModel", aiModel)
                .putString("aiKey", aiKey)
                .apply();
    }

    private static int clampHour(int h) { return Math.max(0, Math.min(23, h)); }

    public boolean serviceOn() { return p.getBoolean("serviceOn", false); }
    public void setServiceOn(boolean on) { p.edit().putBoolean("serviceOn", on).apply(); }

    /** Returns the next check-in type (care / career / todo) and rotates to the following one. */
    public int nextTypeAndAdvance() {
        int t = p.getInt("type", 0);
        p.edit().putInt("type", (t + 1) % 3).apply();
        return t;
    }

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
            for (Todo t : todos) {
                a.put(new JSONObject().put("text", t.text).put("done", t.done));
            }
        } catch (JSONException ignored) {
        }
        p.edit().putString("todos", a.toString()).apply();
    }

    public List<String> pendingTexts() {
        List<String> out = new ArrayList<>();
        for (Todo t : todos()) if (!t.done) out.add(t.text);
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
