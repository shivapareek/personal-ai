package com.shiva.personalai;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** iOS-style alerts, text prompts and action sheets built from plain framework views. */
final class Ios {

    private Ios() { }

    static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable rounded(Activity a, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, radiusDp));
        return g;
    }

    private static TextView label(Activity a, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private static View hairline(Activity a, boolean vertical) {
        View v = new View(a);
        v.setBackgroundColor(a.getColor(R.color.separator));
        v.setLayoutParams(vertical
                ? new LinearLayout.LayoutParams(Math.max(1, dp(a, 0.5f)), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 0.5f))));
        return v;
    }

    private static TextView button(Activity a, String text, int color, boolean bold, Runnable onClick, Dialog d) {
        TextView b = label(a, text, 17, color, bold);
        b.setMinHeight(dp(a, 44));
        b.setBackgroundResource(selectable(a));
        b.setOnClickListener(v -> {
            d.dismiss();
            if (onClick != null) onClick.run();
        });
        return b;
    }

    private static int selectable(Activity a) {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** iOS inset-grouped card; separators start after `insetDp` like UITableView. */
    static LinearLayout groupCard(Activity a, int insetDp) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        GradientDrawable line = new GradientDrawable();
        line.setColor(a.getColor(R.color.separator));
        line.setSize(1, Math.max(1, dp(a, 0.5f)));
        card.setDividerDrawable(new android.graphics.drawable.InsetDrawable(line, dp(a, insetDp), 0, 0, 0));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 16);
        lp.rightMargin = dp(a, 16);
        card.setLayoutParams(lp);
        return card;
    }

    static TextView sectionHeader(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text.toUpperCase(java.util.Locale.ROOT));
        t.setTextSize(13);
        t.setTextColor(a.getColor(R.color.muted));
        t.setLetterSpacing(0.02f);
        t.setPadding(dp(a, 32), dp(a, 26), dp(a, 32), dp(a, 7));
        return t;
    }

    static TextView sectionFooter(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(a.getColor(R.color.muted));
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(a, 32), dp(a, 7), dp(a, 32), 0);
        return t;
    }

    static TextView emptyState(Activity a, String title, String body) {
        TextView t = new TextView(a);
        t.setText(title + "\n" + body);
        t.setTextSize(15);
        t.setTextColor(a.getColor(R.color.muted));
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(a, 40), dp(a, 56), dp(a, 40), 0);
        return t;
    }

    /** Centered alert with one or two buttons. */
    static Dialog alert(Activity a, String title, String message, View extra,
                        String cancel, String ok, boolean destructive, Runnable onOk) {
        Dialog d = new Dialog(a);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(a, a.getColor(R.color.sheet), 14));

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 16), dp(a, 20), dp(a, 16), dp(a, 18));
        if (title != null) body.addView(label(a, title, 17, a.getColor(R.color.text), true));
        if (message != null) {
            TextView m = label(a, message, 13, a.getColor(R.color.text), false);
            m.setPadding(0, dp(a, 4), 0, 0);
            body.addView(m);
        }
        if (extra != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 14);
            body.addView(extra, lp);
        }
        card.addView(body);
        card.addView(hairline(a, false));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        int accent = a.getColor(R.color.accent);
        if (cancel != null) {
            buttons.addView(button(a, cancel, accent, false, null, d), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            buttons.addView(hairline(a, true));
        }
        buttons.addView(button(a, ok, destructive ? a.getColor(R.color.red) : accent, true, onOk, d),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(buttons);

        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(a, 280), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.4f);
        }
        d.show();
        return d;
    }

    static void confirm(Activity a, String title, String message, String ok, Runnable onOk) {
        alert(a, title, message, null, "Cancel", ok, true, onOk);
    }

    /** Alert with a text field. */
    static void input(Activity a, String title, String message, String current, String hint, int inputType,
                      boolean cancelable, Consumer<String> onSave) {
        EditText e = new EditText(a);
        e.setText(current);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setTextColor(a.getColor(R.color.text));
        e.setHintTextColor(a.getColor(R.color.muted));
        GradientDrawable bg = rounded(a, a.getColor(R.color.surface), 7);
        bg.setStroke(Math.max(1, dp(a, 0.5f)), a.getColor(R.color.separator));
        e.setBackground(bg);
        e.setPadding(dp(a, 8), dp(a, 7), dp(a, 8), dp(a, 7));
        e.setSelection(e.getText().length());
        Dialog d = alert(a, title, message, e, cancelable ? "Cancel" : null, "Save", false,
                () -> onSave.accept(e.getText().toString()));
        d.setCancelable(cancelable);
        e.requestFocus();
        Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /** Bottom action sheet; the current choice is shown with a check mark. */
    static void sheet(Activity a, String title, String[] items, int checked, IntConsumer onPick) {
        Dialog d = new Dialog(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 8));

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(a, a.getColor(R.color.sheet), 14));
        card.setClipToOutline(true);
        if (title != null) {
            TextView t = label(a, title, 13, a.getColor(R.color.muted), true);
            t.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
            card.addView(t);
            card.addView(hairline(a, false));
        }
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        int accent = a.getColor(R.color.accent);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            if (i > 0) list.addView(hairline(a, false));
            TextView item = label(a, (i == checked ? "✓  " : "") + items[i], 19, accent, i == checked);
            item.setMinHeight(dp(a, 56));
            item.setBackgroundResource(selectable(a));
            item.setOnClickListener(v -> {
                d.dismiss();
                onPick.accept(idx);
            });
            list.addView(item);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        int wanted = items.length * dp(a, 57);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, wanted)));
        root.addView(card);

        TextView cancel = label(a, "Cancel", 19, accent, true);
        cancel.setMinHeight(dp(a, 56));
        cancel.setBackground(rounded(a, a.getColor(R.color.sheet), 14));
        cancel.setForeground(a.getDrawable(selectable(a)));
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 8);
        root.addView(cancel, lp);

        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.4f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        d.show();
    }
}
