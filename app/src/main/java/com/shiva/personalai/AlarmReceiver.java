package com.shiva.personalai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Forwards exact alarms (check-in or restart) to the service. Alarms are allowed to start a foreground service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() == null ? CheckInService.ACTION_ALARM : intent.getAction();
        CheckInService.send(context, action);
    }
}
