package com.shiva.personalai;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Safety net that runs every ~15 minutes: if the check-in alarm was lost (process killed by the
 * phone's battery manager), it arms it again so the next check-in still happens.
 */
public class KeepAliveJob extends JobService {

    private static final int JOB_ID = 4201;

    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, KeepAliveJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        try {
            js.schedule(info);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Store s = new Store(this);
        if (s.serviceOn()) {
            long now = System.currentTimeMillis();
            long next = s.nextAt();
            if (next < now - 60_000) {
                CheckInService.armAlarm(this, now + 5000, CheckInService.ACTION_ALARM, CheckInService.REQ_CHECKIN);
            } else if (next > 0) {
                CheckInService.armAlarm(this, next, CheckInService.ACTION_ALARM, CheckInService.REQ_CHECKIN);
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
