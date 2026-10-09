package com.shiva.personalai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends Activity {

    private Store store;
    private EditText editName, editInterval, editStart, editEnd, editAiUrl, editAiModel, editAiKey;
    private RadioButton rbHindi, rbEnglish;
    private Spinner spinnerVoice;
    private CheckBox checkListen;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<Voice> voices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new Store(this);

        editName = findViewById(R.id.editName);
        editInterval = findViewById(R.id.editInterval);
        editStart = findViewById(R.id.editStart);
        editEnd = findViewById(R.id.editEnd);
        editAiUrl = findViewById(R.id.editAiUrl);
        editAiModel = findViewById(R.id.editAiModel);
        editAiKey = findViewById(R.id.editAiKey);
        rbHindi = findViewById(R.id.rbHindi);
        rbEnglish = findViewById(R.id.rbEnglish);
        spinnerVoice = findViewById(R.id.spinnerVoice);
        checkListen = findViewById(R.id.checkListen);

        editName.setText(store.name());
        editInterval.setText(String.valueOf(store.intervalMin()));
        editStart.setText(String.valueOf(store.startHour()));
        editEnd.setText(String.valueOf(store.endHour()));
        editAiUrl.setText(store.aiUrl());
        editAiModel.setText(store.aiModel());
        editAiKey.setText(store.aiKey());
        checkListen.setChecked(store.listen());
        if (store.hindi()) rbHindi.setChecked(true); else rbEnglish.setChecked(true);

        RadioGroup langGroup = findViewById(R.id.radioLang);
        langGroup.setOnCheckedChangeListener((g, id) -> loadVoices());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                runOnUiThread(this::loadVoices);
            } else {
                Toast.makeText(this, "Text-to-speech engine nahi mila. Google Speech Services install karo.", Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.btnTestVoice).setOnClickListener(v -> testVoice());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestIgnoreBattery());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
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
        spinnerVoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String saved = store.voiceName();
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(saved)) { spinnerVoice.setSelection(i + 1); break; }
        }
    }

    private String selectedVoiceName() {
        int pos = spinnerVoice.getSelectedItemPosition();
        return pos <= 0 || pos > voices.size() ? "" : voices.get(pos - 1).getName();
    }

    private void testVoice() {
        if (!ttsReady) {
            Toast.makeText(this, "Voice engine abhi ready nahi hai", Toast.LENGTH_SHORT).show();
            return;
        }
        tts.setLanguage(locale());
        int pos = spinnerVoice.getSelectedItemPosition();
        if (pos > 0 && pos <= voices.size()) tts.setVoice(voices.get(pos - 1));
        tts.speak(Messages.testVoice(store, rbHindi.isChecked()), TextToSpeech.QUEUE_FLUSH, null, "test");
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
                rbHindi.isChecked(),
                intOf(editInterval, 60),
                intOf(editStart, 8),
                intOf(editEnd, 22),
                selectedVoiceName(),
                checkListen.isChecked(),
                editAiUrl.getText().toString().trim(),
                editAiModel.getText().toString().trim(),
                editAiKey.getText().toString().trim());
        if (store.serviceOn()) {
            startForegroundService(new Intent(this, CheckInService.class).setAction(CheckInService.ACTION_START));
        }
        Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
