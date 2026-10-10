package com.shiva.personalai;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Tasks: progress card, add field, and one card per task (pending first, completed below). */
class TasksTab {

    private final MainActivity a;
    private final Store s;
    private final LinearLayout list;
    private final TextView summary, progressLabel;
    private final ProgressBar progress;
    private final View progressCard;
    private final EditText edit;
    private List<Store.Todo> todos = new ArrayList<>();

    TasksTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        list = root.findViewById(R.id.taskList);
        summary = root.findViewById(R.id.textTaskSummary);
        progressLabel = root.findViewById(R.id.progressLabel);
        progress = root.findViewById(R.id.progressTasks);
        progressCard = root.findViewById(R.id.progressCard);
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
            summary.setText("Plan your day, she will follow up");
            progressCard.setVisibility(View.GONE);
            list.addView(Ui.emptyState(a, "No tasks yet",
                    "Add what you want to finish today. " + s.herName() + " will ask about them on her calls."));
            return;
        }
        progressCard.setVisibility(View.VISIBLE);
        summary.setText(pending.isEmpty() ? "All done for now" : pending.size() + " left to do");
        progressLabel.setText(done.size() + " of " + todos.size() + " done");
        progress.setProgress(todos.isEmpty() ? 0 : done.size() * 100 / todos.size());

        if (!pending.isEmpty()) {
            list.addView(Ui.sectionHeader(a, "To do"));
            for (Store.Todo t : pending) list.addView(row(t));
        }
        if (!done.isEmpty()) {
            LinearLayout header = new LinearLayout(a);
            header.setGravity(android.view.Gravity.BOTTOM);
            header.addView(Ui.sectionHeader(a, "Completed"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView clear = new TextView(a);
            clear.setText("Clear");
            clear.setTextSize(14);
            clear.setTextColor(a.getColor(R.color.muted));
            clear.setPadding(a.dp(12), a.dp(20), a.dp(28), a.dp(10));
            clear.setOnClickListener(v -> Ui.confirm(a, "Clear completed tasks?", done.size() + " completed tasks will be removed.", "Clear", () -> {
                todos.removeIf(t -> t.done);
                save();
            }));
            header.addView(clear);
            list.addView(header);
            for (Store.Todo t : done) list.addView(row(t));
        }
    }

    private View row(Store.Todo t) {
        View v = LayoutInflater.from(a).inflate(R.layout.item_task, list, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.bottomMargin = a.dp(8);
        v.setLayoutParams(lp);
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
        View.OnClickListener menu = x -> Ui.sheet(a, t.text,
                new String[]{t.done ? "Mark as not done" : "Mark as done", "Edit", "Delete"}, -1, i -> {
                    if (i == 0) {
                        t.done = !t.done;
                        save();
                    } else if (i == 1) {
                        Ui.input(a, "Edit task", null, t.text, "Task",
                                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true, val -> {
                                    if (!val.trim().isEmpty()) t.text = val.trim();
                                    save();
                                });
                    } else {
                        todos.remove(t);
                        save();
                    }
                });
        v.findViewById(R.id.btnMore).setOnClickListener(menu);
        v.setOnLongClickListener(x -> {
            menu.onClick(x);
            return true;
        });
        return v;
    }
}
