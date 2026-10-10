package com.shiva.personalai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks sentence by sentence: while one sentence plays, the next is already being synthesized, so
 * she starts talking as soon as the first sentence exists (even while the AI is still writing).
 * Online neural voice (EdgeTts, cached MP3) with automatic fallback to the phone's TextToSpeech.
 * All public methods must be called on the main thread; callbacks run on the main thread.
 */
public class Speaker {

    public interface Done {
        void run();
    }

    private static class Item {
        final String text;
        final File file;

        Item(String text, File file) {
            this.text = text;
            this.file = file;
        }
    }

    private final Context ctx;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final ExecutorService synth = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private AudioAttributes attrs = assistantAttrs();

    // current stream
    private int seq;
    private final ArrayDeque<Item> ready = new ArrayDeque<>();
    private int pendingSynth;
    private boolean ended;
    private boolean playing;
    private Done onDone;
    private boolean online;
    private boolean hindi;
    private String deviceVoice = "";
    private int utterance;

    public Speaker(Context ctx, Store store) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        audioManager = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(this.ctx, status -> ttsReady = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> itemDone(idToSeq(id))); }
            @Override public void onError(String id) { main.post(() -> itemDone(idToSeq(id))); }
        });
    }

    private static AudioAttributes assistantAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** During a phone call she should sound like a call: voice-communication audio (earpiece or speaker). */
    public void setCallAudio(boolean call) {
        attrs = call
                ? new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                : assistantAttrs();
        focus = null;
        try {
            if (tts != null) tts.setAudioAttributes(attrs);
        } catch (Exception ignored) {
        }
    }

    // ---------- one-shot ----------

    public void speak(String text, Done done) {
        speak(text, store.onlineVoice(), store.hindi(), store.voiceName(), done);
    }

    public void speak(String text, boolean online, boolean hindi, String deviceVoice, Done done) {
        begin(online, hindi, deviceVoice);
        for (String s : sentences(text)) add(s);
        end(done);
    }

    // ---------- streaming ----------

    /** Starts a new utterance with the saved voice settings (stops anything playing). */
    public void begin() {
        begin(store.onlineVoice(), store.hindi(), store.voiceName());
    }

    public void begin(boolean online, boolean hindi, String deviceVoice) {
        stop();
        this.online = online;
        this.hindi = hindi;
        this.deviceVoice = deviceVoice == null ? "" : deviceVoice;
    }

    /** Queues one sentence; synthesis starts immediately in the background. */
    public void add(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) return;
        final String text = sentence.trim();
        final int my = seq;
        if (!online) {
            ready.add(new Item(text, null));
            pump();
            return;
        }
        pendingSynth++;
        final String voice = hindi ? EdgeTts.VOICE_HI : EdgeTts.VOICE_EN;
        final int rate = store.speechRate();
        synth.execute(() -> {
            File f = null;
            try {
                f = synthesizeCached(text, voice, rate);
            } catch (Exception ignored) {
            }
            final File file = f;
            main.post(() -> {
                if (my != seq) return;
                pendingSynth--;
                ready.add(new Item(text, file));
                pump();
            });
        });
    }

    /** No more sentences; `done` runs after the last one finished playing. */
    public void end(Done done) {
        onDone = done;
        ended = true;
        pump();
    }

    public void stop() {
        seq++;
        ready.clear();
        pendingSynth = 0;
        ended = false;
        playing = false;
        onDone = null;
        releasePlayer();
        if (tts != null) tts.stop();
        abandonFocus();
    }

    public void shutdown() {
        stop();
        synth.shutdownNow();
        if (tts != null) tts.shutdown();
    }

    /** Pre-synthesizes text (blocking; call from a worker thread) so it plays instantly later. */
    public void warm(String text) {
        if (!store.onlineVoice()) return;
        String voice = store.hindi() ? EdgeTts.VOICE_HI : EdgeTts.VOICE_EN;
        for (String s : sentences(text)) {
            try {
                synthesizeCached(s, voice, store.speechRate());
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private void pump() {
        if (playing) return;
        Item it = ready.poll();
        if (it == null) {
            if (ended && pendingSynth == 0) {
                Done d = onDone;
                onDone = null;
                ended = false;
                abandonFocus();
                if (d != null) d.run();
            }
            return;
        }
        playing = true;
        requestFocus();
        if (it.file != null) playFile(it, seq);
        else deviceSay(it.text, seq, 0);
    }

    private void itemDone(int my) {
        if (my != seq) return;
        playing = false;
        pump();
    }

    // ---------- sentence splitting ----------

    /** Splits text into speakable sentences; very short pieces are merged so the voice stays natural. */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            boolean end = c == '.' || c == '!' || c == '?' || c == '।' || c == '\n';
            boolean boundary = end && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (boundary && cur.toString().trim().length() >= 12) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) {
            if (!out.isEmpty() && cur.toString().trim().length() < 12) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + cur.toString().trim());
            } else {
                out.add(cur.toString().trim());
            }
        }
        return out;
    }

    // ---------- online voice ----------

    private File synthesizeCached(String text, String voice, int rate) throws Exception {
        File dir = new File(ctx.getCacheDir(), "tts");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, sha1(voice + "|" + rate + "|" + text) + ".mp3");
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        byte[] mp3 = EdgeTts.synthesize(text, voice, rate);
        File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mp3);
        }
        if (!tmp.renameTo(f) && !f.exists()) throw new Exception("cache write failed");
        tmp.delete();
        prune(dir);
        return f;
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 300) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - 250; i++) files[i].delete();
    }

    private static String sha1(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void playFile(Item it, int my) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(it.file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                if (my == seq) mp.start();
            });
            player.setOnCompletionListener(mp -> {
                releasePlayer();
                itemDone(my);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                releasePlayer();
                it.file.delete();
                if (my == seq) deviceSay(it.text, my, 0);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            deviceSay(it.text, my, 0);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ---------- device voice ----------

    private void deviceSay(String text, int my, int attempt) {
        if (my != seq) return;
        if (!ttsReady) {
            if (attempt < 10) main.postDelayed(() -> deviceSay(text, my, attempt + 1), 500);
            else itemDone(my);
            return;
        }
        applyVoice();
        tts.setSpeechRate(1f + store.speechRate() / 100f);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + my + "_" + (++utterance));
        if (r != TextToSpeech.SUCCESS) itemDone(my);
    }

    private void applyVoice() {
        try {
            tts.setLanguage(hindi ? new Locale("hi", "IN") : new Locale("en", "IN"));
            if (!deviceVoice.isEmpty() && tts.getVoices() != null) {
                for (Voice v : tts.getVoices()) {
                    if (deviceVoice.equals(v.getName())) {
                        tts.setVoice(v);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static int idToSeq(String id) {
        try {
            return Integer.parseInt(id.substring(1, id.indexOf('_')));
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- audio focus ----------

    private void requestFocus() {
        try {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .build();
            }
            audioManager.requestAudioFocus(focus);
        } catch (Exception ignored) {
        }
    }

    private void abandonFocus() {
        try {
            if (focus != null) audioManager.abandonAudioFocusRequest(focus);
        } catch (Exception ignored) {
        }
    }
}
