package com.shiva.personalai;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
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

/** Modern dialogs, bottom sheets and list helpers built from plain framework views. */
final class Ui {

    private Ui() { }

    static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable rounded(Activity a, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, radiusDp));
        return g;
    }

    private static int selectable(Activity a) {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    // ---------- page building blocks ----------

    /** A rounded card that holds rows, separated by thin lines starting after the icon. */
    static LinearLayout groupCard(Activity a, int insetDp) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        GradientDrawable line = new GradientDrawable();
        line.setColor(a.getColor(R.color.separator));
        line.setSize(1, Math.max(1, dp(a, 1)));
        card.setDividerDrawable(new InsetDrawable(line, dp(a, insetDp), 0, dp(a, 16), 0));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 16);
        lp.rightMargin = dp(a, 16);
        card.setLayoutParams(lp);
        return card;
    }

    static TextView sectionHeader(Activity a, String title) {
        TextView t = text(a, title, 14, a.getColor(R.color.accent), true);
        t.setPadding(dp(a, 28), dp(a, 24), dp(a, 28), dp(a, 10));
        return t;
    }

    static TextView sectionFooter(Activity a, String s) {
        TextView t = text(a, s, 13, a.getColor(R.color.muted), false);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(a, 28), dp(a, 10), dp(a, 28), 0);
        return t;
    }

    static View emptyState(Activity a, String title, String body) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(a, 40), dp(a, 48), dp(a, 40), 0);
        TextView t = text(a, title, 18, a.getColor(R.color.text), true);
        t.setGravity(Gravity.CENTER);
        TextView b = text(a, body, 14, a.getColor(R.color.muted), false);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(0, 1.2f);
        b.setPadding(0, dp(a, 6), 0, 0);
        box.addView(t);
        box.addView(b);
        return box;
    }

    // ---------- dialogs ----------

    private static TextView textButton(Activity a, String label, int color, Runnable onClick, Dialog d) {
        TextView b = text(a, label, 15, color, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(a, 44));
        b.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        b.setBackgroundResource(selectable(a));
        b.setOnClickListener(v -> {
            d.dismiss();
            if (onClick != null) onClick.run();
        });
        return b;
    }

    /** Rounded dialog: title, message, optional extra view, then text buttons on the right. */
    static Dialog alert(Activity a, String title, String message, View extra,
                        String cancel, String ok, boolean destructive, Runnable onOk) {
        Dialog d = new Dialog(a);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(a, a.getColor(R.color.sheet), 28));
        card.setPadding(dp(a, 24), dp(a, 24), dp(a, 16), dp(a, 12));
        if (title != null) {
            TextView t = text(a, title, 20, a.getColor(R.color.text), true);
            t.setPadding(0, 0, dp(a, 8), 0);
            card.addView(t);
        }
        if (message != null) {
            TextView m = text(a, message, 14, a.getColor(R.color.muted), false);
            m.setLineSpacing(0, 1.2f);
            m.setPadding(0, dp(a, 10), dp(a, 8), 0);
            card.addView(m);
        }
        if (extra != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 16);
            lp.rightMargin = dp(a, 8);
            card.addView(extra, lp);
        }
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(a, 16), 0, 0);
        if (cancel != null) buttons.addView(textButton(a, cancel, a.getColor(R.color.muted), null, d));
        buttons.addView(textButton(a, ok, destructive ? a.getColor(R.color.red) : a.getColor(R.color.accent), onOk, d));
        card.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(a, 360), a.getResources().getDisplayMetrics().widthPixels - dp(a, 48));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.5f);
        }
        d.show();
        return d;
    }

    static void confirm(Activity a, String title, String message, String ok, Runnable onOk) {
        alert(a, title, message, null, "Cancel", ok, true, onOk);
    }

    static void input(Activity a, String title, String message, String current, String hint, int inputType,
                      boolean cancelable, Consumer<String> onSave) {
        EditText e = new EditText(a);
        e.setText(current);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setTextColor(a.getColor(R.color.text));
        e.setHintTextColor(a.getColor(R.color.muted));
        e.setBackground(rounded(a, a.getColor(R.color.surface2), 16));
        e.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        e.setSelection(e.getText().length());
        Dialog d = alert(a, title, message, e, cancelable ? "Cancel" : null, "Save", false,
                () -> onSave.accept(e.getText().toString()));
        d.setCancelable(cancelable);
        e.requestFocus();
        Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private static Drawable radio(Activity a, boolean on) {
        int size = dp(a, 22);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setSize(size, size);
        if (!on) {
            ring.setStroke(dp(a, 2), a.getColor(R.color.tertiary));
            return ring;
        }
        ring.setStroke(dp(a, 2), a.getColor(R.color.accent));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(a.getColor(R.color.accent));
        LayerDrawable l = new LayerDrawable(new Drawable[]{ring, dot});
        int inset = dp(a, 6);
        l.setLayerInset(1, inset, inset, inset, inset);
        l.setBounds(0, 0, size, size);
        return l;
    }

    /**
     * Bottom sheet with a handle. With checked >= 0 the options show radio buttons (a choice);
     * with -1 they are plain actions.
     */
    static void sheet(Activity a, String title, String[] items, int checked, IntConsumer onPick) {
        Dialog d = new Dialog(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.sheet));
        float r = dp(a, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(0, dp(a, 10), 0, dp(a, 18));

        View handle = new View(a);
        handle.setBackground(rounded(a, a.getColor(R.color.tertiary), 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 8);
        root.addView(handle, hp);

        if (title != null) {
            TextView t = text(a, title, 18, a.getColor(R.color.text), true);
            t.setPadding(dp(a, 24), dp(a, 8), dp(a, 24), dp(a, 8));
            root.addView(t);
        }
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView item = text(a, items[i], 16, a.getColor(R.color.text), i == checked);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinHeight(dp(a, 56));
            item.setPadding(dp(a, 24), 0, dp(a, 24), 0);
            item.setBackgroundResource(selectable(a));
            if (checked >= 0) {
                Drawable rd = radio(a, i == checked);
                rd.setBounds(0, 0, dp(a, 22), dp(a, 22));
                item.setCompoundDrawables(null, null, rd, null);
                item.setCompoundDrawablePadding(dp(a, 16));
            }
            item.setOnClickListener(v -> {
                d.dismiss();
                onPick.accept(idx);
            });
            list.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6f);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(maxH, items.length * dp(a, 56))));

        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.5f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        d.show();
    }
}
