package com.shiva.personalai;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Journal: everything the user said they learned, newest first, one card per entry. */
class JournalTab {

    private final MainActivity a;
    private final Store s;
    private final LinearLayout list;
    private final TextView summary;

    JournalTab(MainActivity a, View root) {
        this.a = a;
        this.s = a.store();
        list = root.findViewById(R.id.journalList);
        summary = root.findViewById(R.id.textJournalSummary);
    }

    void refresh() {
        List<String[]> entries = s.learningEntries();
        list.removeAllViews();
        if (entries.isEmpty()) {
            summary.setText("What you have learned");
            list.addView(Ui.emptyState(a, "Nothing here yet",
                    "When " + s.herName() + " asks what you learned today, your answer is saved here."));
            return;
        }
        summary.setText(entries.size() + (entries.size() == 1 ? " learning saved" : " learnings saved"));
        for (String[] e : entries) {
            View v = LayoutInflater.from(a).inflate(R.layout.item_journal, list, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = a.dp(16);
            lp.rightMargin = a.dp(16);
            lp.topMargin = a.dp(10);
            v.setLayoutParams(lp);
            ((TextView) v.findViewById(R.id.date)).setText(e[0]);
            ((TextView) v.findViewById(R.id.text)).setText(e[1]);
            list.addView(v);
        }
    }
}
