package com.shiva.personalai;

import android.view.Gravity;
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
            TextView empty = new TextView(a);
            empty.setText("Nothing here yet.\nWhen " + s.herName() + " asks what you learned today, your answer is saved here.");
            empty.setTextColor(a.getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.2f);
            empty.setPadding(a.dp(24), a.dp(40), a.dp(24), 0);
            list.addView(empty);
            return;
        }
        summary.setText(entries.size() + (entries.size() == 1 ? " entry" : " entries"));
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        card.setDividerDrawable(a.getDrawable(R.drawable.divider));
        for (String[] e : entries) {
            View v = LayoutInflater.from(a).inflate(R.layout.item_journal, card, false);
            ((TextView) v.findViewById(R.id.date)).setText(e[0]);
            ((TextView) v.findViewById(R.id.text)).setText(e[1]);
            card.addView(v);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(4);
        list.addView(card, lp);
    }
}
