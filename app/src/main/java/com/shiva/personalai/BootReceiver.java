package com.shiva.personalai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new Store(context).serviceOn()) return;
        try {
            context.startForegroundService(new Intent(context, CheckInService.class)
                    .setAction(CheckInService.ACTION_START));
        } catch (Exception ignored) {
        }
    }
}
