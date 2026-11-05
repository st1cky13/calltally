package com.calltally.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Android starts this on the schedule and after a call or message is saved. */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Sync.run(getApplicationContext());
                } finally {
                    // Set up the next wake only now: scheduling this job while it runs would cancel it.
                    if (params.getJobId() == Sync.JOB_CHANGES) Sync.scheduleChanges(getApplicationContext());
                    else Sync.schedule(getApplicationContext());
                    jobFinished(params, false);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
