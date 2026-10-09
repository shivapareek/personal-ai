package com.shiva.personalai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The assistant's "brain": any OpenAI-compatible chat endpoint.
 * Without a key it uses the free, keyless Pollinations endpoint; with a Groq key it uses Groq.
 * Call off the main thread.
 */
public class AiClient {

    public static class Result {
        public String reply = "";
        public List<Integer> done = new ArrayList<>();
        public boolean askMore;
    }

    static final String FREE_URL = "https://text.pollinations.ai/openai";
    static final String FREE_MODEL = "openai";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    /**
     * @param mode    Messages.TYPE_CARE / TYPE_CAREER / TYPE_TODO / TYPE_TALK
     * @param opening true when the assistant should produce the opening message of a check-in
     */
    public static Result chat(Store s, int mode, List<String> pending, List<Store.Msg> history, boolean opening) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(s, mode, pending)));
        int from = Math.max(0, history.size() - 14);
        for (int i = from; i < history.size(); i++) {
            Store.Msg m = history.get(i);
            messages.put(new JSONObject().put("role", "ai".equals(m.role) ? "assistant" : "user").put("content", m.text));
        }
        if (opening) {
            messages.put(new JSONObject().put("role", "user").put("content",
                    "[System: it is time for your scheduled " + modeName(mode) + " check-in. "
                            + "Start the conversation yourself now with one natural opening message. Output the JSON only.]"));
        }
        JSONObject body = new JSONObject()
                .put("model", s.aiKey().isEmpty() ? FREE_MODEL : s.aiModel())
                .put("temperature", 0.8)
                .put("messages", messages);

        Request.Builder rb = new Request.Builder()
                .url(s.aiKey().isEmpty() ? FREE_URL : s.aiUrl())
                .post(RequestBody.create(body.toString(), JSON));
        if (!s.aiKey().isEmpty()) rb.header("Authorization", "Bearer " + s.aiKey());

        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code() + ": " + text);
            String content = new JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            return parse(content, pending.size());
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case Messages.TYPE_CARE: return "care";
            case Messages.TYPE_CAREER: return "career";
            case Messages.TYPE_TODO: return "to-do";
            default: return "chat";
        }
    }

    private static String systemPrompt(Store s, int mode, List<String> pending) {
        String her = s.herName();
        String user = s.name().isEmpty() ? "your friend" : s.name();
        StringBuilder b = new StringBuilder();
        b.append("You are ").append(her).append(", a warm, caring, slightly playful young Indian woman. You are ")
                .append(user).append("'s personal companion: part close friend who genuinely cares about them, part gentle career coach. ")
                .append("You remember what ").append(user).append(" told you earlier in this conversation and refer back to it naturally. ")
                .append("Talk like a real person, never like a robot or an assistant reading a script. Do not mention being an AI unless asked. ");
        b.append(s.hindi()
                ? "Speak natural everyday Hindi written in Devanagari script; keep common English words like project, gym, meeting in English letters. "
                : "Speak simple, friendly Indian English. ");
        b.append("Your words are spoken aloud by text-to-speech, so write 1 to 3 short sentences. No emojis, no lists, no markdown, no stage directions. ");
        b.append("Right now it is ").append(new SimpleDateFormat("EEEE, h:mm a", Locale.ENGLISH).format(new Date())).append(". ");
        if (pending.isEmpty()) {
            b.append(user).append(" has no pending tasks. ");
        } else {
            b.append(user).append("'s pending tasks, numbered: ");
            for (int i = 0; i < pending.size(); i++) b.append(i + 1).append(". ").append(pending.get(i)).append("; ");
        }
        List<String> done = s.doneTexts();
        if (!done.isEmpty()) b.append("Already done: ").append(String.join("; ", done)).append(". ");
        List<String> learned = s.learnings();
        if (!learned.isEmpty()) {
            b.append("Things ").append(user).append(" recently said they learned: ");
            for (int i = 0; i < Math.min(5, learned.size()); i++) b.append(learned.get(i)).append("; ");
        }
        switch (mode) {
            case Messages.TYPE_CARE:
                b.append("This is a care check-in: you want to know how ").append(user)
                        .append(" is really doing (mood, tiredness, food, water, breaks). Be affectionate, notice things, give at most one gentle practical tip. ");
                break;
            case Messages.TYPE_CAREER:
                b.append("This is a career check-in: ask what ").append(user)
                        .append(" learned or built today, appreciate it specifically, and nudge them a little toward growth. ");
                break;
            case Messages.TYPE_TODO:
                b.append("This is a to-do check-in: mention the pending tasks briefly and ask which ones are done. ")
                        .append("When ").append(user).append(" says a task is done, put its number in \"done\". Say what remains and encourage, never nag. ");
                break;
            default:
                b.append(user).append(" tapped the mic to talk to you. Respond helpfully and warmly to whatever they say. ")
                        .append("If they say a task is finished, put its number in \"done\". ");
        }
        b.append("Reply ONLY with a JSON object, nothing else: ")
                .append("{\"reply\": \"<what you say>\", \"done\": [<numbers of tasks now completed, or empty>], ")
                .append("\"ask_more\": <true if you asked a question and want to keep listening for the answer, else false>}");
        return b.toString();
    }

    static Result parse(String content, int pendingCount) {
        Result r = new Result();
        int a = content.indexOf('{');
        int z = content.lastIndexOf('}');
        if (a >= 0 && z > a) {
            try {
                JSONObject o = new JSONObject(content.substring(a, z + 1));
                r.reply = o.optString("reply", "").trim();
                r.askMore = o.optBoolean("ask_more", false);
                JSONArray d = o.optJSONArray("done");
                if (d != null) {
                    for (int i = 0; i < d.length(); i++) {
                        int n = d.optInt(i, 0) - 1;
                        if (n >= 0 && n < pendingCount && !r.done.contains(n)) r.done.add(n);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (r.reply.isEmpty()) {
            r.reply = content.replace("```json", "").replace("```", "").trim();
            r.askMore = r.reply.endsWith("?");
        }
        return r;
    }
}
