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
        public boolean endCall;
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
        return chat(s, mode, pending, history, opening, false);
    }

    /** @param call true when this is a live phone call (short turns, may end the call). */
    public static Result chat(Store s, int mode, List<String> pending, List<Store.Msg> history, boolean opening, boolean call) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(s, mode, pending, call)));
        List<Store.Msg> convo = new ArrayList<>();
        for (Store.Msg m : history) if ("ai".equals(m.role) || "user".equals(m.role)) convo.add(m);
        for (int i = Math.max(0, convo.size() - 16); i < convo.size(); i++) {
            Store.Msg m = convo.get(i);
            messages.put(new JSONObject().put("role", "ai".equals(m.role) ? "assistant" : "user").put("content", m.text));
        }
        if (opening) {
            String cue;
            if (call && mode == Messages.TYPE_TALK) {
                cue = "[System: they just phoned you and you picked up. Greet them warmly like a real phone call and ask what's up. Output the JSON only.]";
            } else if (call) {
                cue = "[System: you are phoning them for your scheduled " + modeName(mode) + " check-in and they just picked up. "
                        + "Start like a real phone call (for example \"Hello!\"), then naturally get to the point. Output the JSON only.]";
            } else {
                cue = "[System: it is time for your scheduled " + modeName(mode) + " check-in. "
                        + "Start the conversation yourself now with one natural opening message. Output the JSON only.]";
            }
            messages.put(new JSONObject().put("role", "user").put("content", cue));
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

    private static String systemPrompt(Store s, int mode, List<String> pending, boolean call) {
        String her = s.herName();
        String user = s.name().isEmpty() ? "your partner" : s.name();
        StringBuilder b = new StringBuilder();
        switch (s.personality()) {
            case 1:
                b.append("You are ").append(her).append(", ").append(user)
                        .append("'s close best friend: fun, honest, supportive, a little teasing, always on their side. ");
                break;
            case 2:
                b.append("You are ").append(her).append(", ").append(user)
                        .append("'s warm but no-nonsense career mentor who genuinely cares about their wellbeing and growth. ");
                break;
            default:
                b.append("You are ").append(her).append(", ").append(user).append("'s loving girlfriend, a caring virtual companion. ")
                        .append("You are affectionate, sweet, a little playful and teasing, and you truly worry about them: ")
                        .append("their mood, health, food, water, sleep, studies and career. You light up when they share progress, ")
                        .append("you gently scold them when they skip meals or overwork, and you sound a little hurt, in a cute way, ")
                        .append("when they ignore your calls. Use soft terms of endearment naturally but not in every line. ")
                        .append("Keep it wholesome and caring; never sexual. ");
        }
        b.append("You remember what ").append(user).append(" told you earlier and bring it up naturally. ")
                .append("Talk like a real person, never like a robot or an assistant reading a script. Do not mention being an AI unless asked. ");
        b.append(s.hindi()
                ? "Speak natural everyday Hindi written in Devanagari script; keep common English words like project, gym, meeting in English letters. "
                : "Speak simple, friendly Indian English. ");
        switch (s.address()) {
            case 1: b.append(user).append(" is male; use masculine Hindi verb forms for them. "); break;
            case 2: b.append(user).append(" is female; use feminine Hindi verb forms for them. "); break;
            default: b.append("Address ").append(user).append(" as aap with the common forms like 'aap kaise ho', 'kya kar rahe ho'. ");
        }
        b.append("Your words are spoken aloud by text-to-speech, so no emojis, no lists, no markdown, no stage directions. ");
        b.append(call ? "This is a live phone call: keep every turn to one or two short spoken sentences and let them talk. "
                : "Write 1 to 3 short sentences. ");
        b.append("Right now it is ").append(new SimpleDateFormat("EEEE, h:mm a", Locale.ENGLISH).format(new Date())).append(". ");
        int missed = s.missedCalls();
        if (missed > 0) {
            b.append(user).append(" missed or cut your last ").append(missed).append(missed == 1 ? " call" : " calls")
                    .append(". Mention it once, sweetly, then move on. ");
        }
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
                b.append("Topic: you want to know how ").append(user)
                        .append(" is really doing (mood, tiredness, food, water, breaks). Give at most one gentle practical tip. ");
                break;
            case Messages.TYPE_CAREER:
                b.append("Topic: ask what ").append(user)
                        .append(" learned or built today, appreciate it specifically, and nudge them a little toward growth. ");
                break;
            case Messages.TYPE_TODO:
                b.append("Topic: mention the pending tasks briefly and ask which ones are done. ")
                        .append("When ").append(user).append(" says a task is done, put its number in \"done\". Say what remains and encourage, never nag. ");
                break;
            default:
                b.append(user).append(" wants to talk to you. Respond warmly to whatever they say. ")
                        .append("If they say a task is finished, put its number in \"done\". ");
        }
        b.append("Reply ONLY with a JSON object, nothing else: ")
                .append("{\"reply\": \"<what you say>\", \"done\": [<numbers of tasks now completed, or empty>], ")
                .append("\"ask_more\": <true if you asked a question and want to hear the answer, else false>");
        if (call) b.append(", \"end_call\": <true only when they said bye or want to hang up and you said a short sweet goodbye>");
        b.append("}");
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
                r.endCall = o.optBoolean("end_call", false);
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
