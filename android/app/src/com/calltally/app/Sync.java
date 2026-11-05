package com.calltally.app;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.CallLog;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** One run that reads the phone into the record, the schedule that starts runs, and the status the app's screen shows. */
final class Sync {
    static final String[] PERMISSIONS = {Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS};
    private static final int JOB_PERIODIC = 1;
    static final int JOB_CHANGES = 2;
    private static final long PERIOD_MS = 15 * 60 * 1000L;
    static final String NOT_ALLOWED = "Needs permission. Open this app to allow it.";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Sync() {}

    static boolean allowed(Context context) {
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("status", Context.MODE_PRIVATE);
    }

    static String status(Context context) {
        SharedPreferences saved = prefs(context);
        String text = saved.getString("status", "Not run yet.");
        String schedule = saved.getString("schedule_problem", null);
        String crash = saved.getString("crash", null);
        if (schedule != null) text += "\n\nBackground runs aren't set up: " + schedule;
        if (crash != null) text += "\n\nLast crash: " + crash;
        return text;
    }

    /** Keeps the reason for a crash for the screen to show. Only the error's type and this app's code location, nothing personal. */
    static void noteCrashes(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashNote) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashNote(app, previous));
    }

    private static final class CrashNote implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        CrashNote(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                String where = "";
                for (StackTraceElement frame : error.getStackTrace()) {
                    if (frame.getClassName().startsWith("com.calltally.app")) {
                        where = " at " + frame.getFileName() + ":" + frame.getLineNumber();
                        break;
                    }
                }
                prefs(context).edit().putString("crash", error.getClass().getSimpleName() + where).commit();
            } catch (Throwable ignored) {
                // Saving the reason must never get in the way of the normal crash.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
            }
        }
    }

    static boolean neverRun(Context context) {
        return !prefs(context).contains("status");
    }

    /** The run's result for the screen. Saved at once, since the emulator check reads it as soon as the run ends. */
    private static void note(Context context, String text) {
        String when = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new java.util.Date());
        prefs(context).edit().putString("status", when + "\n" + text).commit();
    }

    static void schedule(Context context) {
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            ComponentName service = new ComponentName(context, SyncJob.class);
            JobInfo periodic = jobs.getPendingJob(JOB_PERIODIC);
            // 0.6.0 and earlier asked for a network, which this app no longer has permission to check.
            if (periodic == null || periodic.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
                jobs.schedule(new JobInfo.Builder(JOB_PERIODIC, service).setPeriodic(PERIOD_MS).setPersisted(true).build());
            }
            if (jobs.getPendingJob(JOB_CHANGES) == null) changesJob(context);
            prefs(context).edit().remove("schedule_problem").apply();
        } catch (RuntimeException error) {
            // Run now still works, so this shows on the screen rather than closing the app.
            prefs(context).edit().putString("schedule_problem", error.getClass().getSimpleName()).apply();
        }
    }

    /** Wakes soon after a call or message is saved. Android runs this once per schedule, so each run sets up the next. */
    static void scheduleChanges(Context context) {
        try {
            changesJob(context);
            prefs(context).edit().remove("schedule_problem").apply();
        } catch (RuntimeException error) {
            prefs(context).edit().putString("schedule_problem", error.getClass().getSimpleName()).apply();
        }
    }

    private static void changesJob(Context context) {
        JobInfo.Builder job = new JobInfo.Builder(JOB_CHANGES, new ComponentName(context, SyncJob.class))
            .setTriggerContentUpdateDelay(10000).setTriggerContentMaxDelay(60000);
        for (Uri uri : new Uri[] {CallLog.Calls.CONTENT_URI, Uri.parse("content://sms"), Uri.parse("content://mms"), Uri.parse("content://mms-sms")}) {
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
        }
        context.getSystemService(JobScheduler.class).schedule(job.build());
    }

    /**
     * One run: reads the phone's contacts to keep the people table up to date, then reads calls and texts, decides with
     * the ticked people's numbers, and adds what's new to the record. Returns false if another run was already going, so
     * this one did nothing.
     */
    static boolean run(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        long started = System.currentTimeMillis();
        try {
            prefs(context).edit().putLong("run_started", started).commit();
            if (!allowed(context)) {
                note(context, NOT_ALLOWED);
                return true;
            }
            Record record = Record.get(context);
            // Left by 0.6.0 and earlier, which uploaded.
            new File(context.getFilesDir(), "sent.txt").delete();
            String contactsLine = "";
            try {
                record.refreshPeople(Collector.readContacts(context.getContentResolver()));
            } catch (Collector.Problem error) {
                // The people already ticked are remembered, so the run goes on with them.
                contactsLine = "\nContacts couldn't be read, so the people already ticked were used.";
            }
            Map<String, String> counting = record.countingNumbers();
            if (counting.isEmpty()) {
                note(context, "Nobody is ticked yet. Open Who counts to choose the people who count.");
                prefs(context).edit().remove("crash").apply();
                return true;
            }
            Collector collector = new Collector(context, counting);
            List<Rules.Entry> found = collector.collect();
            Ledger.Added added = record.add(found);
            String problem = collector.problem();
            int other = collector.otherCalls();
            int people = 0;
            for (Rules.Person person : record.people()) if (person.ticked) people++;
            note(context, (found.size() - added.unusable) + " calls and texts with " + people + (people == 1 ? " ticked person" : " ticked people")
                + " found, " + added.added + " new in the record."
                + (problem.isEmpty() ? "" : "\n" + problem)
                + contactsLine
                + (added.unusable > 0 ? "\n" + added.unusable + " couldn't be used and were skipped." : "")
                + (other == 0 ? "" : other == 1 ? "\nSkipped 1 call of another kind." : "\nSkipped " + other + " calls of other kinds.")
                + "\n" + collector.groups() + " group chats with ticked people."
                + (collector.conversationListWorked() ? "" : "\nConversation list unavailable, using messages instead."));
            prefs(context).edit().remove("crash").apply();
        } catch (Record.TooNew error) {
            note(context, error.getMessage());
        } catch (SecurityException error) {
            note(context, NOT_ALLOWED);
        } catch (Collector.Problem error) {
            note(context, error.getMessage());
        } catch (RuntimeException error) {
            note(context, "Something went wrong: " + error.getClass().getSimpleName());
        } finally {
            try {
                // Which run finished last, by the moment it started: the emulator check waits on this.
                prefs(context).edit().putLong("run_done", started).commit();
            } finally {
                RUNNING.set(false);
            }
        }
        return true;
    }
}
