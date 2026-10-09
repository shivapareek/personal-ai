package com.shiva.personalai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startForegroundService(new Intent(context, CheckInService.class)
                    .setAction(CheckInService.ACTION_ALARM));
        } catch (Exception ignored) {
        }
    }
}
