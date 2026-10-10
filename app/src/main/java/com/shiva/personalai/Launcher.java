package com.shiva.personalai;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Opens the call screen from the background. Android 10+ blocks background activity starts unless the
 * app may draw over other apps, and Android 15 additionally requires one of its overlay windows to be
 * on screen at that moment, so a 1-pixel transparent overlay is shown for the launch.
 */
final class Launcher {

    private Launcher() { }

    static void open(Context c, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Foreground.visible() || !Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(intent);
            } catch (Exception ignored) {
            }
            return;
        }
        WindowManager wm = c.getSystemService(WindowManager.class);
        View dot = new View(c);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        boolean added;
        try {
            wm.addView(dot, lp);
            added = true;
        } catch (Exception e) {
            added = false;
        }
        Handler h = new Handler(Looper.getMainLooper());
        final boolean remove = added;
        // let the overlay become visible for a frame before launching
        h.postDelayed(() -> {
            try {
                c.startActivity(intent);
            } catch (Exception ignored) {
            }
            if (remove) {
                h.postDelayed(() -> {
                    try {
                        wm.removeView(dot);
                    } catch (Exception ignored) {
                    }
                }, 1500);
            }
        }, added ? 120 : 0);
    }
}
