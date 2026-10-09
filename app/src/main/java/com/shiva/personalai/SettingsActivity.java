package com.shiva.personalai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends Activity {

    private Store store;
    private EditText editName, editHerName, editStart, editEnd, editAiUrl, editAiModel, editAiKey;
    private RadioButton rbHindi, rbEnglish;
    private Spinner spinnerVoice;
    private Switch switchOnlineVoice, switchListen;
    private SeekBar seekInterval;
    private TextView textInterval;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Speaker speaker;
    private final List<Voice> voices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new Store(this);
        speaker = new Speaker(this, store);

        editName = findViewById(R.id.editName);
        editHerName = findViewById(R.id.editHerName);
        editStart = findViewById(R.id.editStart);
        editEnd = findViewById(R.id.editEnd);
        editAiUrl = findViewById(R.id.editAiUrl);
        editAiModel = findViewById(R.id.editAiModel);
        editAiKey = findViewById(R.id.editAiKey);
        rbHindi = findViewById(R.id.rbHindi);
        rbEnglish = findViewById(R.id.rbEnglish);
        spinnerVoice = findViewById(R.id.spinnerVoice);
        switchOnlineVoice = findViewById(R.id.switchOnlineVoice);
        switchListen = findViewById(R.id.switchListen);
        seekInterval = findViewById(R.id.seekInterval);
        textInterval = findViewById(R.id.textInterval);

        editName.setText(store.name());
        editHerName.setText(store.herName());
        editStart.setText(String.valueOf(store.startHour()));
        editEnd.setText(String.valueOf(store.endHour()));
        editAiUrl.setText(store.aiUrl());
        editAiModel.setText(store.aiModel());
        editAiKey.setText(store.aiKey());
        switchOnlineVoice.setChecked(store.onlineVoice());
        switchListen.setChecked(store.listen());
        if (store.hindi()) rbHindi.setChecked(true); else rbEnglish.setChecked(true);

        seekInterval.setProgress(Math.max(0, Math.min(35, (store.intervalMin() - 5) / 5)));
        textInterval.setText("Har " + minutes() + " minute me");
        seekInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { textInterval.setText("Har " + minutes() + " minute me"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        RadioGroup langGroup = findViewById(R.id.radioLang);
        langGroup.setOnCheckedChangeListener((g, id) -> loadVoices());

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            runOnUiThread(this::loadVoices);
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnTestVoice).setOnClickListener(v -> testVoice());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestIgnoreBattery());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    private int minutes() {
        return 5 + seekInterval.getProgress() * 5;
    }

    private Locale locale() {
        return rbHindi.isChecked() ? new Locale("hi", "IN") : new Locale("en", "IN");
    }

    private void loadVoices() {
        voices.clear();
        List<String> names = new ArrayList<>();
        if (ttsReady) {
            try {
                String lang = locale().getLanguage();
                for (Voice v : tts.getVoices()) {
                    if (!lang.equals(v.getLocale().getLanguage())) continue;
                    if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    voices.add(v);
                }
            } catch (Exception ignored) {
            }
        }
        Collections.sort(voices, (a, b) -> a.getName().compareTo(b.getName()));
        names.add("Default voice");
        for (Voice v : voices) names.add(v.getName());
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVoice.setAdapter(ad);
        String saved = store.voiceName();
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(saved)) {
                spinnerVoice.setSelection(i + 1);
                break;
            }
        }
    }

    private String selectedVoiceName() {
        int pos = spinnerVoice.getSelectedItemPosition();
        return pos <= 0 || pos > voices.size() ? "" : voices.get(pos - 1).getName();
    }

    private void testVoice() {
        boolean hi = rbHindi.isChecked();
        Toast.makeText(this, switchOnlineVoice.isChecked() ? "Online voice la rahi hu..." : "Device voice", Toast.LENGTH_SHORT).show();
        speaker.speak(Messages.testVoice(store, hi), switchOnlineVoice.isChecked(), hi, selectedVoiceName(), () -> { });
    }

    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Phone settings me jaake is app ki battery optimization band karo", Toast.LENGTH_LONG).show();
            }
        }
    }

    private int intOf(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void save() {
        store.saveSettings(
                editName.getText().toString().trim(),
                editHerName.getText().toString().trim(),
                rbHindi.isChecked(),
                minutes(),
                intOf(editStart, 8),
                intOf(editEnd, 22),
                switchOnlineVoice.isChecked(),
                selectedVoiceName(),
                switchListen.isChecked(),
                editAiUrl.getText().toString().trim(),
                editAiModel.getText().toString().trim(),
                editAiKey.getText().toString().trim());
        if (store.serviceOn()) {
            startForegroundService(new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_RESCHEDULE));
        }
        Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
