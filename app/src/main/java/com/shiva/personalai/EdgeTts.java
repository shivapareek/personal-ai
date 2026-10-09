package com.shiva.personalai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Free neural text-to-speech using the Microsoft Edge "Read Aloud" service (same protocol as the
 * open-source edge-tts project). Returns MP3 bytes. Pure Java, no Android dependencies.
 */
public class EdgeTts {

    public static final String VOICE_HI = "hi-IN-SwaraNeural";
    public static final String VOICE_EN = "en-IN-NeerjaNeural";

    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROME_FULL = "130.0.2849.68";
    private static final String CHROME_MAJOR = "130";
    private static final String WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public static byte[] synthesize(String text, String voice) throws Exception {
        return synthesize(text, voice, 0);
    }

    private static byte[] synthesize(String text, String voice, long clockSkewSec) throws Exception {
        String url = WSS + "?TrustedClientToken=" + TOKEN
                + "&Sec-MS-GEC=" + secMsGec(clockSkewSec)
                + "&Sec-MS-GEC-Version=1-" + CHROME_FULL
                + "&ConnectionId=" + uuid();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                        + CHROME_MAJOR + ".0.0.0 Safari/537.36 Edg/" + CHROME_MAJOR + ".0.0.0")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicInteger httpCode = new AtomicInteger(0);
        final AtomicReference<String> serverDate = new AtomicReference<>();

        WebSocket ws = CLIENT.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(configMessage());
                webSocket.send(ssmlMessage(text, voice));
            }

            @Override
            public void onMessage(WebSocket webSocket, String msg) {
                if (msg.contains("Path:turn.end")) {
                    webSocket.close(1000, null);
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (bytes.size() < 2) return;
                int headerLen = ((bytes.getByte(0) & 0xff) << 8) | (bytes.getByte(1) & 0xff);
                if (bytes.size() < 2 + headerLen) return;
                String header = bytes.substring(2, 2 + headerLen).utf8();
                if (header.contains("Path:audio")) {
                    byte[] chunk = bytes.substring(2 + headerLen).toByteArray();
                    synchronized (audio) {
                        audio.write(chunk, 0, chunk.length);
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                error.set(t);
                if (response != null) {
                    httpCode.set(response.code());
                    serverDate.set(response.header("Date"));
                }
                latch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                latch.countDown();
            }
        });

        if (!latch.await(40, TimeUnit.SECONDS)) {
            ws.cancel();
            throw new Exception("Edge TTS timeout");
        }
        if (error.get() != null) {
            // 403 usually means our clock differs from the server's: retry once with the server's time.
            if (httpCode.get() == 403 && clockSkewSec == 0 && serverDate.get() != null) {
                long serverSec = parseHttpDate(serverDate.get());
                if (serverSec > 0) return synthesize(text, voice, serverSec - System.currentTimeMillis() / 1000);
            }
            throw new Exception("Edge TTS failed (HTTP " + httpCode.get() + "): " + error.get().getMessage(), error.get());
        }
        byte[] out;
        synchronized (audio) {
            out = audio.toByteArray();
        }
        if (out.length == 0) throw new Exception("Edge TTS returned no audio");
        return out;
    }

    static String secMsGec(long clockSkewSec) throws Exception {
        long ticks = System.currentTimeMillis() / 1000 + clockSkewSec + 11644473600L; // Windows epoch
        ticks -= ticks % 300;
        ticks *= 10_000_000L; // seconds -> 100ns ticks
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest((ticks + TOKEN).getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static String configMessage() {
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    private static String ssmlMessage(String text, String voice) {
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                + escape(text) + "</prosody></voice></speak>";
        return "X-RequestId:" + uuid() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "Z\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static long parseHttpDate(String date) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            return f.parse(date).getTime() / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Smoke test from a plain JVM: java EdgeTts "<text>" out.mp3 [voice] */
    public static void main(String[] args) throws Exception {
        String voice = args.length > 2 ? args[2] : VOICE_HI;
        byte[] mp3 = synthesize(args[0], voice);
        java.nio.file.Files.write(java.nio.file.Paths.get(args[1]), mp3);
        System.out.println("wrote " + mp3.length + " bytes to " + args[1]);
        CLIENT.dispatcher().executorService().shutdown();
        CLIENT.connectionPool().evictAll();
    }
}
