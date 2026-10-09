package com.shiva.personalai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Optional AI reply via any OpenAI-compatible chat endpoint (default: Groq free tier). Call off the main thread. */
public class AiClient {

    public static class Result {
        public String reply = "";
        public List<Integer> done = new ArrayList<>();
    }

    public static Result ask(Store s, int type, String question, String userSaid, List<String> pending) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt(s, type, question, pending)));
        messages.put(new JSONObject().put("role", "user").put("content", userSaid));
        JSONObject body = new JSONObject()
                .put("model", s.aiModel())
                .put("temperature", 0.7)
                .put("messages", messages);

        HttpURLConnection c = (HttpURLConnection) new URL(s.aiUrl()).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + s.aiKey());
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception("HTTP " + code + ": " + resp);

        String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
        return parse(content, pending.size());
    }

    private static String systemPrompt(Store s, int type, String question, List<String> pending) {
        String name = s.name().isEmpty() ? "the user" : s.name();
        StringBuilder b = new StringBuilder();
        b.append("You are a warm, caring young woman who is ").append(name)
                .append("'s personal companion and motivator. You talk like a close friend who genuinely cares. ");
        b.append(s.hindi()
                ? "Speak in natural Hindi written in Devanagari script (a few common English words are fine). "
                : "Speak in simple, friendly Indian English. ");
        b.append("Your reply will be read aloud by text-to-speech, so write 1 to 3 short spoken sentences, no emojis, no lists, no markdown. ");
        b.append("You just asked ").append(name).append(": \"").append(question).append("\". ");
        if (type == Messages.TYPE_CARE) {
            b.append("Respond to how they are feeling with care; if they seem stressed or tired, give one gentle, practical tip. ");
        } else if (type == Messages.TYPE_CAREER) {
            b.append("Appreciate what they learned today, and encourage them or ask one tiny follow-up question. ");
        } else {
            b.append("Their pending tasks, numbered: ");
            for (int i = 0; i < pending.size(); i++) b.append(i + 1).append(". ").append(pending.get(i)).append(" ");
            b.append(". Decide which task numbers they say are completed. Briefly confirm those, mention what is still pending, and encourage them. ");
        }
        b.append("Reply ONLY with a JSON object of this exact shape and nothing else: ")
                .append("{\"reply\": \"<what you say>\", \"done\": [<completed task numbers, empty if none>]}");
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
        if (r.reply.isEmpty()) r.reply = content.trim();
        return r;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
