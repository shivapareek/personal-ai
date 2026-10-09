package com.shiva.personalai;

import android.app.Activity;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

public class TodoActivity extends Activity {

    private Store store;
    private List<Store.Todo> todos;
    private TextView textCount, textEmpty;
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return todos.size(); }
        @Override public Object getItem(int i) { return todos.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert != null ? convert : LayoutInflater.from(TodoActivity.this).inflate(R.layout.item_todo, parent, false);
            Store.Todo t = todos.get(i);
            TextView text = v.findViewById(R.id.text);
            View check = v.findViewById(R.id.check);
            text.setText(t.text);
            text.setAlpha(t.done ? 0.45f : 1f);
            text.setPaintFlags(t.done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            check.setBackgroundResource(t.done ? R.drawable.bg_check_on : R.drawable.bg_check_off);
            View.OnClickListener toggle = x -> {
                t.done = !t.done;
                save();
            };
            check.setOnClickListener(toggle);
            text.setOnClickListener(toggle);
            v.findViewById(R.id.btnDelete).setOnClickListener(x -> {
                todos.remove(t);
                save();
            });
            return v;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo);
        store = new Store(this);
        todos = store.todos();
        textCount = findViewById(R.id.textCount);
        textEmpty = findViewById(R.id.textEmpty);
        ListView list = findViewById(R.id.listTodos);
        EditText edit = findViewById(R.id.editTodo);
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty()) return;
            todos.add(new Store.Todo(text, false));
            edit.setText("");
            save();
        });
        edit.setOnEditorActionListener((v, id, e) -> {
            findViewById(R.id.btnAdd).performClick();
            return true;
        });
        refresh();
    }

    private void save() {
        store.saveTodos(todos);
        refresh();
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
        int pending = 0;
        for (Store.Todo t : todos) if (!t.done) pending++;
        textCount.setText(pending + " baaki");
        textEmpty.setVisibility(todos.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
