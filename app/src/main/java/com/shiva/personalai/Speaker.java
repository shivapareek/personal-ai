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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Speaks text: online neural voice (EdgeTts, cached as MP3) with automatic fallback to the device's
 * TextToSpeech engine. Callbacks run on the main thread.
 */
public class Speaker {

    public interface Done {
        void run();
    }

    private final Context ctx;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private int seq;
    private Done current;

    public Speaker(Context ctx, Store store) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        audioManager = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(this.ctx, status -> ttsReady = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> finish(idToSeq(id))); }
            @Override public void onError(String id) { main.post(() -> finish(idToSeq(id))); }
        });
    }

    public boolean ready() { return ttsReady; }

    /** Speak with the saved settings. */
    public void speak(String text, Done done) {
        speak(text, store.onlineVoice(), store.hindi(), store.voiceName(), done);
    }

    public void speak(String text, boolean online, boolean hindi, String deviceVoice, Done done) {
        stop();
        final int my = ++seq;
        current = done;
        if (online) {
            new Thread(() -> {
                try {
                    File f = synthesizeCached(text, hindi ? EdgeTts.VOICE_HI : EdgeTts.VOICE_EN);
                    main.post(() -> {
                        if (my == seq) play(f, text, hindi, deviceVoice, my);
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (my == seq) deviceSpeak(text, hindi, deviceVoice, my, 0);
                    });
                }
            }).start();
        } else {
            deviceSpeak(text, hindi, deviceVoice, my, 0);
        }
    }

    public void stop() {
        seq++;
        current = null;
        releasePlayer();
        if (tts != null) tts.stop();
        abandonFocus();
    }

    public void shutdown() {
        stop();
        if (tts != null) tts.shutdown();
    }

    // ---- online voice ----

    private File synthesizeCached(String text, String voice) throws Exception {
        File dir = new File(ctx.getCacheDir(), "tts");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, sha1(voice + "|" + text) + ".mp3");
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        byte[] mp3 = EdgeTts.synthesize(text, voice);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mp3);
        }
        if (!tmp.renameTo(f)) throw new Exception("cache write failed");
        prune(dir);
        return f;
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 120) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - 100; i++) files[i].delete();
    }

    private static String sha1(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void play(File f, String text, boolean hindi, String deviceVoice, int my) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                if (my != seq) return;
                requestFocus();
                mp.start();
            });
            player.setOnCompletionListener(mp -> {
                releasePlayer();
                finish(my);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                releasePlayer();
                f.delete();
                if (my == seq) deviceSpeak(text, hindi, deviceVoice, my, 0);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            deviceSpeak(text, hindi, deviceVoice, my, 0);
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

    // ---- device voice ----

    private void deviceSpeak(String text, boolean hindi, String deviceVoice, int my, int attempt) {
        if (my != seq) return;
        if (!ttsReady) {
            if (attempt < 10) main.postDelayed(() -> deviceSpeak(text, hindi, deviceVoice, my, attempt + 1), 1000);
            else finish(my);
            return;
        }
        applyVoice(hindi, deviceVoice);
        requestFocus();
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + my);
        if (r != TextToSpeech.SUCCESS) finish(my);
    }

    private void applyVoice(boolean hindi, String deviceVoice) {
        try {
            tts.setLanguage(hindi ? new Locale("hi", "IN") : new Locale("en", "IN"));
            if (deviceVoice != null && !deviceVoice.isEmpty() && tts.getVoices() != null) {
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
            return Integer.parseInt(id.substring(1));
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- audio focus ----

    private void requestFocus() {
        try {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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

    private void finish(int my) {
        abandonFocus();
        if (my != seq) return;
        Done d = current;
        current = null;
        if (d != null) d.run();
    }
}
