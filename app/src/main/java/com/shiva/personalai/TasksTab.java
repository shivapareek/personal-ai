package com.shiva.personalai;

import android.graphics.Paint;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Tasks: add, tick, delete. Pending first, completed below. */
class TasksTab {

    private final MainActivity a;
    private final Store s;
    private final LinearLayout list;
    private final TextView summary;
    private final EditText edit;
    private List<Store.Todo> todos = new ArrayList<>();

    TasksTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        list = root.findViewById(R.id.taskList);
        summary = root.findViewById(R.id.textTaskSummary);
        edit = root.findViewById(R.id.editTask);
        root.findViewById(R.id.btnAddTask).setOnClickListener(v -> add());
        edit.setOnEditorActionListener((v, id, e) -> {
            add();
            return true;
        });
    }

    private void add() {
        String text = edit.getText().toString().trim();
        if (text.isEmpty()) return;
        todos = s.todos();
        todos.add(new Store.Todo(text, false));
        edit.setText("");
        save();
    }

    private void save() {
        s.saveTodos(todos);
        refresh();
    }

    void refresh() {
        todos = s.todos();
        list.removeAllViews();
        List<Store.Todo> pending = new ArrayList<>(), done = new ArrayList<>();
        for (Store.Todo t : todos) (t.done ? done : pending).add(t);

        if (todos.isEmpty()) {
            summary.setText("No tasks yet");
            TextView empty = new TextView(a);
            empty.setText("Add what you plan to do today.\n" + s.herName() + " will follow up on it during check-ins.");
            empty.setTextColor(a.getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.2f);
            empty.setPadding(a.dp(24), a.dp(40), a.dp(24), 0);
            list.addView(empty);
            return;
        }
        summary.setText(pending.size() + " pending · " + done.size() + " completed");
        if (!pending.isEmpty()) addSection("To do", pending, null);
        if (!done.isEmpty()) addSection("Completed", done, "Clear");
    }

    private void addSection(String title, List<Store.Todo> items, String actionText) {
        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(4), a.dp(16), 0, a.dp(8));
        TextView label = new TextView(a);
        label.setText(title);
        label.setTextAppearance(R.style.SectionLabel);
        header.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (actionText != null) {
            TextView action = new TextView(a);
            action.setText(actionText);
            action.setTextColor(a.getColor(R.color.accent));
            action.setTextSize(13);
            action.setPadding(a.dp(12), a.dp(4), a.dp(4), a.dp(4));
            action.setOnClickListener(v -> {
                todos.removeIf(t -> t.done);
                save();
            });
            header.addView(action);
        }
        list.addView(header);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        card.setDividerDrawable(a.getDrawable(R.drawable.divider));
        card.setClipToOutline(true);
        for (Store.Todo t : items) card.addView(row(card, t));
        list.addView(card);
    }

    private View row(LinearLayout parent, Store.Todo t) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_task, parent, false);
        TextView text = v.findViewById(R.id.text);
        View check = v.findViewById(R.id.check);
        text.setText(t.text);
        text.setTextColor(a.getColor(t.done ? R.color.muted : R.color.text));
        text.setPaintFlags(t.done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        check.setBackgroundResource(t.done ? R.drawable.bg_checkbox_on : R.drawable.bg_checkbox_off);
        v.setOnClickListener(x -> {
            t.done = !t.done;
            save();
        });
        v.findViewById(R.id.btnDelete).setOnClickListener(x -> {
            todos.remove(t);
            save();
        });
        return v;
    }
}
