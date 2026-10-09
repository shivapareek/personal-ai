package com.shiva.personalai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private Store store;
    private List<Store.Todo> todos = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView status;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        status = findViewById(R.id.textStatus);
        toggle = findViewById(R.id.btnToggle);
        list = findViewById(R.id.listTodos);
        EditText edit = findViewById(R.id.editTodo);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, new ArrayList<>());
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            todos.get(pos).done = !todos.get(pos).done;
            store.saveTodos(todos);
            refreshTodos();
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            new AlertDialog.Builder(this)
                    .setMessage("\"" + todos.get(pos).text + "\" delete karein?")
                    .setPositiveButton("Delete", (d, w) -> {
                        todos.remove(pos);
                        store.saveTodos(todos);
                        refreshTodos();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty()) return;
            todos.add(new Store.Todo(text, false));
            store.saveTodos(todos);
            edit.setText("");
            refreshTodos();
        });

        toggle.setOnClickListener(v -> {
            if (store.serviceOn()) {
                startService(serviceIntent(CheckInService.ACTION_STOP));
                store.setServiceOn(false);
            } else {
                if (store.name().isEmpty()) {
                    Toast.makeText(this, "Pehle Settings me apna naam aur voice set karo", Toast.LENGTH_LONG).show();
                }
                startForegroundService(serviceIntent(CheckInService.ACTION_START));
                store.setServiceOn(true);
            }
            refreshStatus();
        });

        findViewById(R.id.btnNow).setOnClickListener(v -> {
            startForegroundService(serviceIntent(CheckInService.ACTION_CHECK_IN));
            store.setServiceOn(true);
            refreshStatus();
        });

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btnLearnings).setOnClickListener(v -> {
            List<String> items = store.learnings();
            AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Aapne kya seekha");
            if (items.isEmpty()) b.setMessage("Abhi kuch nahi. Jab wo puchegi \"aaj kya seekha?\" to aapka jawab yahan save hoga.");
            else b.setItems(items.toArray(new String[0]), null);
            b.setPositiveButton("OK", null).show();
        });

        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        todos = store.todos();
        refreshTodos();
        refreshStatus();
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, CheckInService.class).setAction(action);
    }

    private void refreshTodos() {
        adapter.clear();
        for (Store.Todo t : todos) adapter.add(t.text);
        for (int i = 0; i < todos.size(); i++) list.setItemChecked(i, todos.get(i).done);
    }

    private void refreshStatus() {
        if (store.serviceOn()) {
            status.setText("Status: ON  -  har " + store.intervalMin() + " min, "
                    + store.startHour() + ":00 se " + store.endHour() + ":00 tak");
            toggle.setText("Stop");
        } else {
            status.setText("Status: OFF  -  Start dabao");
            toggle.setText("Start");
        }
    }

    private void requestPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }
}
