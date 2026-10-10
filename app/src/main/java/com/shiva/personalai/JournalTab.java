package com.shiva.personalai;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Journal: everything the user said they learned, newest first. */
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
            list.addView(Ios.emptyState(a, "Nothing here yet",
                    "When " + s.herName() + " asks what you learned today, your answer is saved here."));
            return;
        }
        summary.setText(entries.size() + (entries.size() == 1 ? " entry" : " entries"));
        list.addView(Ios.sectionHeader(a, "Learnings"));
        LinearLayout card = Ios.groupCard(a, 16);
        for (String[] e : entries) {
            View v = LayoutInflater.from(a).inflate(R.layout.item_journal, card, false);
            ((TextView) v.findViewById(R.id.text)).setText(e[1]);
            ((TextView) v.findViewById(R.id.date)).setText(e[0]);
            card.addView(v);
        }
        list.addView(card);
    }
}
