package com.shiva.personalai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After reboot or an app update the alarms are gone: bring the assistant back via a restart alarm. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!new Store(context).serviceOn()) return;
        KeepAliveJob.schedule(context);
        CheckInService.armAlarm(context, System.currentTimeMillis() + 10_000,
                CheckInService.ACTION_RESTART, CheckInService.REQ_RESTART);
    }
}
