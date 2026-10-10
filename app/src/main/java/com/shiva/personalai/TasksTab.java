package com.shiva.personalai;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Tasks in the style of iOS Reminders: round checkboxes, pending first, completed below. */
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
        ((TextView) root.findViewById(R.id.titleTasks)).setTextColor(a.getColor(R.color.accent));
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
            summary.setText("Nothing planned yet");
            list.addView(Ios.emptyState(a, "No tasks",
                    "Add what you plan to do. " + s.herName() + " will ask about them on her calls."));
            return;
        }
        summary.setText(pending.size() + " remaining" + (done.isEmpty() ? "" : " · " + done.size() + " done"));
        if (!pending.isEmpty()) {
            list.addView(Ios.sectionHeader(a, "To do"));
            list.addView(card(pending));
        }
        if (!done.isEmpty()) {
            LinearLayout header = new LinearLayout(a);
            header.setGravity(android.view.Gravity.BOTTOM);
            header.addView(Ios.sectionHeader(a, "Completed"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView clear = new TextView(a);
            clear.setText("Clear");
            clear.setTextSize(15);
            clear.setTextColor(a.getColor(R.color.accent));
            clear.setPadding(a.dp(12), a.dp(20), a.dp(32), a.dp(7));
            clear.setOnClickListener(v -> Ios.confirm(a, "Clear completed?", done.size() + " completed tasks will be removed.", "Clear", () -> {
                todos.removeIf(t -> t.done);
                save();
            }));
            header.addView(clear);
            list.addView(header);
            list.addView(card(done));
        }
        list.addView(Ios.sectionFooter(a, "Tap a task to complete it. Long-press to delete."));
    }

    private LinearLayout card(List<Store.Todo> items) {
        LinearLayout card = Ios.groupCard(a, 50);
        for (Store.Todo t : items) card.addView(row(card, t));
        return card;
    }

    private View row(LinearLayout parent, Store.Todo t) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_task, parent, false);
        TextView text = v.findViewById(R.id.text);
        View check = v.findViewById(R.id.check);
        text.setText(t.text);
        text.setTextColor(a.getColor(t.done ? R.color.muted : R.color.text));
        text.setPaintFlags(t.done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        check.setBackgroundResource(t.done ? R.drawable.bg_check_on : R.drawable.bg_check_off);
        v.setOnClickListener(x -> {
            t.done = !t.done;
            save();
        });
        v.setOnLongClickListener(x -> {
            Ios.sheet(a, t.text, new String[]{t.done ? "Mark as not done" : "Mark as done", "Delete task"}, -1, i -> {
                if (i == 0) t.done = !t.done;
                else todos.remove(t);
                save();
            });
            return true;
        });
        return v;
    }
}
