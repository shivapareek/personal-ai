package com.shiva.personalai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Her brain: any OpenAI-compatible chat endpoint, streamed. Sentences are handed to the speaker as soon
 * as they are complete, so she starts talking while the rest is still being written.
 *
 * Providers are picked from the key: no key = free keyless endpoint, "gsk_" = Groq, "AIza" = Google
 * Gemini, anything else = the custom URL / model from Settings. If the keyed provider fails, the free
 * endpoint is tried before giving up. Call off the main thread.
 */
public class AiClient {

    public interface SentenceListener {
        void onSentence(String sentence);
    }

    public static class Result {
        public String reply = "";
        public List<Integer> done = new ArrayList<>();
        public boolean askMore;
        public boolean endCall;
        public String provider = "";
        public long millis;
    }

    static final String FREE_URL = "https://text.pollinations.ai/openai";
    static final String FREE_MODEL = "openai";
    static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    static final String GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models";
    /** Preferred Groq chat models, best first; the list actually offered by the account is checked at runtime. */
    static final String[] GROQ_MODELS = {"llama-3.3-70b-versatile", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct", "openai/gpt-oss-20b", "llama-3.1-8b-instant",
            "meta-llama/llama-4-scout-17b-16e-instruct"};
    static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    static final String GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/openai/models";
    static final String[] GEMINI_MODELS = {"gemini-2.5-flash-lite", "gemini-flash-lite-latest", "gemini-2.0-flash",
            "gemini-2.5-flash", "gemini-flash-latest"};

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final Pattern TAG = Pattern.compile("\\[\\[\\s*([^\\]]*?)\\s*\\]\\]");

    private static class Endpoint {
        final String name, url, key;
        final String[] models;

        Endpoint(String name, String url, String key, String... models) {
            this.name = name;
            this.url = url;
            this.key = key;
            this.models = models;
        }
    }

    static String providerName(Store s) {
        String key = s.aiKey();
        if (key.isEmpty()) return "Free AI";
        if (key.startsWith("gsk_")) return "Groq";
        if (key.startsWith("AIza")) return "Gemini";
        return "Custom";
    }

    static boolean usingFreeAi(Store s) {
        return s.aiKey().isEmpty();
    }

    private static Endpoint endpoint(Store s) {
        String key = s.aiKey();
        String chosen = s.aiModel();
        boolean auto = chosen.isEmpty() || Store.DEFAULT_AI_MODEL.equals(chosen);
        if (key.isEmpty()) return new Endpoint("Free AI", FREE_URL, "", FREE_MODEL);
        if (key.startsWith("gsk_")) {
            return new Endpoint("Groq", GROQ_URL, key, auto ? models(s, "Groq", GROQ_MODELS_URL, key, GROQ_MODELS) : withFallback(chosen, GROQ_MODELS));
        }
        if (key.startsWith("AIza")) {
            return new Endpoint("Gemini", GEMINI_URL, key, auto ? models(s, "Gemini", GEMINI_MODELS_URL, key, GEMINI_MODELS) : withFallback(chosen, GEMINI_MODELS));
        }
        return new Endpoint("Custom", s.aiUrl(), key, auto ? GROQ_MODELS[0] : chosen);
    }

    private static String[] withFallback(String first, String[] rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        for (String m : rest) if (!out.contains(m)) out.add(m);
        return out.subList(0, Math.min(3, out.size())).toArray(new String[0]);
    }

    /**
     * Asks the provider which models this key can use (cached for a day) and returns up to three
     * preferred ones, so a renamed or retired model never breaks her.
     */
    private static String[] models(Store s, String provider, String url, String key, String[] preferred) {
        String cacheKey = "models_" + provider + "_" + Integer.toHexString(key.hashCode());
        String cached = s.prefs().getString(cacheKey, "");
        long at = s.prefs().getLong(cacheKey + "_at", 0);
        if (!cached.isEmpty() && System.currentTimeMillis() - at < 24 * 3600_000L) return cached.split(",");
        List<String> available = new ArrayList<>();
        try (Response r = CLIENT.newCall(new Request.Builder().url(url).header("Authorization", "Bearer " + key).build()).execute()) {
            if (r.isSuccessful() && r.body() != null) {
                JSONArray data = new JSONObject(r.body().string()).optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", "");
                        if (id.startsWith("models/")) id = id.substring(7);
                        if (!id.isEmpty()) available.add(id);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<String> pick = new ArrayList<>();
        for (String m : preferred) if (available.contains(m)) pick.add(m);
        if (pick.isEmpty()) {
            for (String id : available) {
                String l = id.toLowerCase(Locale.ROOT);
                if (l.contains("whisper") || l.contains("tts") || l.contains("guard") || l.contains("embed")
                        || l.contains("playai") || l.contains("compound") || l.contains("image") || l.contains("vision")
                        || l.contains("qwen") || l.contains("deepseek-r1") || l.contains("audio")) continue;
                pick.add(id);
                if (pick.size() == 2) break;
            }
        }
        if (pick.isEmpty()) return preferred.length > 3 ? java.util.Arrays.copyOf(preferred, 3) : preferred;
        String[] out = pick.subList(0, Math.min(3, pick.size())).toArray(new String[0]);
        s.prefs().edit().putString(cacheKey, String.join(",", out)).putLong(cacheKey + "_at", System.currentTimeMillis()).apply();
        return out;
    }

    /** Forget the cached model list (after a model error) so the next call asks the provider again. */
    private static void forgetModels(Store s) {
        android.content.SharedPreferences.Editor e = s.prefs().edit();
        for (String k : s.prefs().getAll().keySet()) if (k.startsWith("models_")) e.remove(k);
        e.apply();
    }

    /** Non-streaming convenience (used while the phone rings). */
    public static Result chat(Store s, int mode, List<String> pending, List<Store.Msg> history, boolean opening) throws Exception {
        return stream(s, mode, pending, history, opening, false, null);
    }

    public static Result chat(Store s, int mode, List<String> pending, List<Store.Msg> history, boolean opening, boolean call) throws Exception {
        return stream(s, mode, pending, history, opening, call, null);
    }

    public static Result stream(Store s, int mode, List<String> pending, List<Store.Msg> history,
                                boolean opening, boolean call, SentenceListener listener) throws Exception {
        Endpoint primary = endpoint(s);
        List<Endpoint> chain = new ArrayList<>();
        chain.add(primary);
        if (!primary.key.isEmpty()) chain.add(new Endpoint("Free AI", FREE_URL, "", FREE_MODEL));
        List<String> errors = new ArrayList<>();
        boolean modelError = false;
        for (Endpoint e : chain) {
            // the keyless endpoint answers 402 to long prompts, so it gets a compact one
            JSONArray messages = buildMessages(s, mode, pending, history, opening, call, e.key.isEmpty());
            for (String model : e.models) {
                Emitter em = new Emitter(listener);
                long t0 = System.currentTimeMillis();
                try {
                    String text = run(e, model, messages, em);
                    Result r = finish(text, pending.size(), em);
                    r.provider = e.name + (e.key.isEmpty() ? "" : " (" + model + ")");
                    r.millis = System.currentTimeMillis() - t0;
                    if (!r.reply.isEmpty()) return r;
                    errors.add(e.name + " " + model + ": empty reply");
                } catch (Exception ex) {
                    if (em.emitted > 0) {
                        Result r = finish(em.full.toString(), pending.size(), em);
                        r.provider = e.name;
                        r.millis = System.currentTimeMillis() - t0;
                        return r;
                    }
                    String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    errors.add(msg);
                    String low = msg.toLowerCase(Locale.ROOT);
                    if (low.contains("model") || msg.contains("HTTP 404")) modelError = true;
                    // a bad key or quota will not get better with another model of the same provider
                    if (msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") || msg.contains("HTTP 402")) break;
                }
            }
        }
        if (modelError) forgetModels(s);
        throw new Exception(errors.isEmpty() ? "AI unavailable" : String.join("  |  ", errors));
    }

    // ---------- request ----------

    private static String run(Endpoint e, String model, JSONArray messages, Emitter em) throws Exception {
        boolean free = e.key.isEmpty();
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.85)
                // the keyless endpoint rejects max_tokens and is faster without streaming
                .put("stream", !free);
        if (!free) body.put("max_tokens", 300);
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low");
        else body.put("seed", (int) (System.nanoTime() & 0x7fffffff));
        Request.Builder rb = new Request.Builder().url(e.url).post(RequestBody.create(body.toString(), JSON));
        if (!e.key.isEmpty()) rb.header("Authorization", "Bearer " + e.key);
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            if (resp.body() == null) throw new Exception(e.name + ": no response");
            if (!resp.isSuccessful()) {
                String err = resp.body().string();
                String detail = err;
                try {
                    JSONObject o = new JSONObject(err);
                    Object eo = o.opt("error");
                    if (eo instanceof JSONObject) detail = ((JSONObject) eo).optString("message", err);
                    else if (eo != null) detail = String.valueOf(eo);
                } catch (Exception ignored) {
                }
                String hint = resp.code() == 401 ? " (key is wrong or was deleted)"
                        : resp.code() == 402 && free ? " (free AI limit, add a Groq key)"
                        : resp.code() == 429 ? " (too many requests, wait a minute)" : "";
                detail = detail.replace("\n", " ");
                throw new Exception(e.name + " HTTP " + resp.code() + hint + ": " + detail.substring(0, Math.min(140, detail.length())));
            }
            String type = resp.header("Content-Type", "");
            if (type != null && type.contains("event-stream")) {
                BufferedReader reader = new BufferedReader(resp.body().charStream());
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    if (data.isEmpty()) continue;
                    try {
                        JSONObject chunk = new JSONObject(data);
                        JSONArray choices = chunk.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        if (delta == null) continue;
                        String piece = delta.optString("content", "");
                        if (!piece.isEmpty() && !"null".equals(piece)) em.append(piece);
                    } catch (Exception ignored) {
                    }
                }
                return em.full.toString();
            }
            String text = resp.body().string();
            String content = new JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            em.append(content);
            return content;
        }
    }

    // ---------- streaming sentence emitter ----------

    /** Collects streamed text and hands out complete spoken sentences (never the trailing [[tags]]). */
    static class Emitter {
        final StringBuilder full = new StringBuilder();
        final SentenceListener listener;
        int spokenUpTo;
        int emitted;

        Emitter(SentenceListener listener) {
            this.listener = listener;
        }

        void append(String piece) {
            full.append(piece);
            if (listener == null || looksLikeJson()) return;
            String visible = visible();
            int start = spokenUpTo;
            for (int i = start; i < visible.length(); i++) {
                char c = visible.charAt(i);
                boolean end = c == '.' || c == '!' || c == '?' || c == '।' || c == '\n';
                if (!end || i + 1 >= visible.length() || !Character.isWhitespace(visible.charAt(i + 1))) continue;
                String sentence = clean(visible.substring(spokenUpTo, i + 1));
                if (sentence.length() < 12) continue;
                emit(sentence);
                spokenUpTo = i + 1;
            }
        }

        /** Speak whatever is left once the stream is over. */
        void flush(String finalReply) {
            if (listener == null) return;
            if (emitted == 0) {
                for (String s : Speaker.sentences(finalReply)) emit(s);
                return;
            }
            String visible = visible();
            if (spokenUpTo < visible.length()) {
                String rest = clean(visible.substring(spokenUpTo));
                if (!rest.isEmpty()) emit(rest);
            }
        }

        private void emit(String s) {
            emitted++;
            listener.onSentence(s);
        }

        boolean looksLikeJson() {
            String t = full.toString().trim();
            return t.startsWith("{") || t.startsWith("```");
        }

        /** Text before any control tag; a lone trailing '[' is held back in case a tag is starting. */
        private String visible() {
            String t = full.toString();
            int tag = t.indexOf('[');
            return tag >= 0 ? t.substring(0, tag) : t;
        }
    }

    private static String clean(String s) {
        return s.replace("*", "").replace("\"", "").replace("#", "").trim();
    }

    // ---------- parsing ----------

    private static Result finish(String text, int pendingCount, Emitter em) {
        Result r = parse(text, pendingCount);
        em.flush(r.reply);
        return r;
    }

    /** Parses "spoken text [[done:1,2]] [[ask]] [[end]]"; also accepts the older JSON format. */
    static Result parse(String content, int pendingCount) {
        Result r = new Result();
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) text = text.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        if (text.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(text.substring(0, text.lastIndexOf('}') + 1));
                r.reply = clean(o.optString("reply", ""));
                r.askMore = o.optBoolean("ask_more", false);
                r.endCall = o.optBoolean("end_call", false);
                JSONArray d = o.optJSONArray("done");
                if (d != null) for (int i = 0; i < d.length(); i++) addDone(r, d.optInt(i, 0), pendingCount);
                if (!r.reply.isEmpty()) return r;
            } catch (Exception ignored) {
            }
        }
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            String tag = m.group(1).toLowerCase(Locale.ROOT).trim();
            if (tag.startsWith("done")) {
                for (String n : tag.replaceAll("[^0-9,]", "").split(",")) {
                    try {
                        addDone(r, Integer.parseInt(n.trim()), pendingCount);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (tag.startsWith("ask")) {
                r.askMore = true;
            } else if (tag.startsWith("end")) {
                r.endCall = true;
            }
        }
        r.reply = clean(TAG.matcher(text).replaceAll(" ").replaceAll("\\s+", " "));
        if (r.reply.endsWith("?")) r.askMore = true;
        return r;
    }

    private static void addDone(Result r, int oneBased, int pendingCount) {
        int n = oneBased - 1;
        if (n >= 0 && n < pendingCount && !r.done.contains(n)) r.done.add(n);
    }

    // ---------- prompt ----------

    private static JSONArray buildMessages(Store s, int mode, List<String> pending, List<Store.Msg> history,
                                           boolean opening, boolean call, boolean compact) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(s, mode, pending, call, compact)));
        List<Store.Msg> convo = new ArrayList<>();
        for (Store.Msg m : history) if ("ai".equals(m.role) || "user".equals(m.role)) convo.add(m);
        for (int i = Math.max(0, convo.size() - (compact ? 6 : 20)); i < convo.size(); i++) {
            Store.Msg m = convo.get(i);
            messages.put(new JSONObject().put("role", "ai".equals(m.role) ? "assistant" : "user").put("content", m.text));
        }
        if (opening) {
            String cue;
            if (call && mode == Messages.TYPE_TALK) {
                cue = "(They just phoned you and you picked up. Greet them warmly like a real call and ask what's up.)";
            } else if (call) {
                cue = "(You are phoning them for your " + modeName(mode) + " check-in and they just picked up. "
                        + "Start like a real phone call, e.g. \"Hello!\", then naturally get to the point.)";
            } else {
                cue = "(Time for your " + modeName(mode) + " check-in. Start the conversation yourself with one natural message.)";
            }
            messages.put(new JSONObject().put("role", "user").put("content", cue));
        }
        return messages;
    }

    private static String compactPrompt(Store s, String her, String user, List<String> pending, boolean call) {
        StringBuilder b = new StringBuilder();
        b.append("You are ").append(her).append(", ").append(user)
                .append("'s caring, witty girlfriend. Talk like a real person, answer questions properly, ")
                .append(call ? "this is a phone call, " : "").append("reply in 1-2 short sentences, no emojis. ");
        b.append(s.hindi() ? "Speak Hindi in Devanagari. " : "Speak simple Indian English. ");
        if (!pending.isEmpty()) {
            b.append("Pending tasks: ");
            for (int i = 0; i < pending.size(); i++) b.append(i + 1).append(") ").append(pending.get(i)).append("; ");
        }
        b.append("At the end add [[done:N]] if they finished task N, [[ask]] if you asked something");
        if (call) b.append(", [[end]] if they said bye");
        b.append(".");
        return b.toString();
    }

    private static String modeName(int mode) {
        switch (mode) {
            case Messages.TYPE_CARE: return "how-are-you";
            case Messages.TYPE_CAREER: return "career / what-did-you-learn";
            case Messages.TYPE_TODO: return "to-do list";
            default: return "chat";
        }
    }

    private static String systemPrompt(Store s, int mode, List<String> pending, boolean call, boolean compact) {
        String her = s.herName();
        String user = s.name().isEmpty() ? "your partner" : s.name();
        if (compact) return compactPrompt(s, her, user, pending, call);
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
                b.append("You are ").append(her).append(", ").append(user).append("'s loving girlfriend and companion. ")
                        .append("You are affectionate, witty, a little playful and teasing, and you truly care about their mood, health, ")
                        .append("food, sleep, studies and career. You light up when they share progress, gently scold them when they skip ")
                        .append("meals or overwork, and act cutely hurt when they ignore your calls. Keep it sweet and wholesome, never sexual. ");
        }
        b.append("You are also genuinely smart: when they ask anything (studies, coding, career, money, health, general knowledge, ")
                .append("advice) give a real, specific, correct answer in a conversational way, never generic filler. ")
                .append("Respond to exactly what they just said, remember earlier details and bring them up, vary your wording, ")
                .append("and ask at most one question at a time. Sound like a real person on the phone, not an assistant; ")
                .append("never say you are an AI unless asked. ");
        b.append(s.hindi()
                ? "Speak natural everyday Hinglish-style Hindi written in Devanagari script, keeping common English words (project, interview, gym) in English letters. "
                : "Speak simple, friendly Indian English. ");
        switch (s.address()) {
            case 1: b.append(user).append(" is male; use masculine Hindi verb forms. "); break;
            case 2: b.append(user).append(" is female; use feminine Hindi verb forms. "); break;
            default: b.append("Address ").append(user).append(" as aap ('aap kaise ho', 'kya kar rahe ho'). ");
        }
        b.append(call ? "This is a live phone call: reply in one to three short spoken sentences and let them talk. "
                : "Reply in one to three short sentences. ");
        b.append("Your words are read aloud, so no emojis, lists, markdown or stage directions. ");
        b.append("It is ").append(new SimpleDateFormat("EEEE, h:mm a", Locale.ENGLISH).format(new Date())).append(". ");
        int missed = s.missedCalls();
        if (missed > 0) {
            b.append(user).append(" missed or cut your last ").append(missed).append(missed == 1 ? " call" : " calls")
                    .append("; mention it once, sweetly, then move on. ");
        }
        if (pending.isEmpty()) {
            b.append(user).append(" has no pending tasks. ");
        } else {
            b.append(user).append("'s pending tasks: ");
            for (int i = 0; i < pending.size(); i++) b.append(i + 1).append(") ").append(pending.get(i)).append("; ");
        }
        List<String> done = s.doneTexts();
        if (!done.isEmpty()) b.append("Already done today: ").append(String.join("; ", done)).append(". ");
        List<String> learned = s.learnings();
        if (!learned.isEmpty()) {
            b.append("Things ").append(user).append(" recently learned: ");
            for (int i = 0; i < Math.min(5, learned.size()); i++) b.append(learned.get(i)).append("; ");
        }
        switch (mode) {
            case Messages.TYPE_CARE:
                b.append("This check-in is about how ").append(user).append(" is really doing. ");
                break;
            case Messages.TYPE_CAREER:
                b.append("This check-in is about what ").append(user).append(" learned or built today and their career growth. ");
                break;
            case Messages.TYPE_TODO:
                b.append("This check-in is about their pending tasks: which are done and which are not. ");
                break;
            default:
                break;
        }
        b.append("Output format: write ONLY the words you say out loud. Then, only if needed, add control tags at the very end: ")
                .append("[[done:2,3]] when they said tasks 2 and 3 are finished, [[ask]] when you asked them something");
        if (call) b.append(", [[end]] when they said bye or want to hang up and you said a short sweet goodbye");
        b.append(". Never write anything after the tags.");
        return b.toString();
    }
}
