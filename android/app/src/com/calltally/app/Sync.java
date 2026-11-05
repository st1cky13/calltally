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
    static final String NOBODY = "Nobody is ticked yet. Tap the gear, then Who counts, to choose the people who count.";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** A run was asked for while one was going: the one going does another straight after, so nothing waits for the schedule. */
    private static final AtomicBoolean AGAIN = new AtomicBoolean(false);

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

    /** How long ago the last run started, in milliseconds, or Long.MAX_VALUE when none has. */
    static long sinceLastRun(Context context) {
        long started = prefs(context).getLong("run_started", 0);
        long now = System.currentTimeMillis();
        return started <= 0 || started > now ? Long.MAX_VALUE : now - started;
    }

    /**
     * The run's result for Options, and the problem the month screen shows, or none. Saved at once, since the emulator
     * check reads it as soon as the run ends.
     */
    private static void note(Context context, String text, String problem) {
        String when = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new java.util.Date());
        SharedPreferences.Editor edit = prefs(context).edit().putString("status", when + "\n" + text).remove("clean_at");
        if (problem == null || problem.isEmpty()) edit.remove("problem");
        else edit.putString("problem", problem);
        edit.commit();
    }

    /**
     * A run that read the phone. Opening the app can start two runs one after the other (the periodic job and the first
     * open), and the second finds nothing new; within a minute of a clean run, a clean run that added nothing keeps the
     * first one's words, so the status still says what came in.
     */
    private static void noteRead(Context context, String text, String problem, int added) {
        SharedPreferences saved = prefs(context);
        long now = System.currentTimeMillis();
        boolean clean = problem.isEmpty();
        long cleanAt = saved.getLong("clean_at", 0);
        if (clean && added == 0 && cleanAt > 0 && now - cleanAt < 60000 && saved.contains("status")) {
            saved.edit().putLong("updated_at", now).remove("problem").commit();
            return;
        }
        note(context, text, problem);
        SharedPreferences.Editor edit = saved.edit().putLong("updated_at", now);
        if (clean) edit.putLong("clean_at", now);
        edit.commit();
    }

    /** When a run last read the phone, or 0. */
    static long updatedAt(Context context) {
        return prefs(context).getLong("updated_at", 0);
    }

    /** What the month screen's red box says: the last run's problem, a schedule problem or a crash, or null. */
    static String problem(Context context) {
        SharedPreferences saved = prefs(context);
        StringBuilder out = new StringBuilder();
        String problem = saved.getString("problem", null);
        String schedule = saved.getString("schedule_problem", null);
        String crash = saved.getString("crash", null);
        if (problem != null) out.append(out.length() > 0 ? "\n" : "").append(problem);
        if (schedule != null) out.append(out.length() > 0 ? "\n" : "").append("Background runs aren't set up: ").append(schedule);
        if (crash != null) out.append(out.length() > 0 ? "\n" : "").append("Last crash: ").append(crash);
        // Catch up encrypted: the owner can never be left as the messaging app, or lose a picture message, without seeing it.
        if (Roles.holdsSmsRole(context)) out.append(out.length() > 0 ? "\n" : "").append(Passthrough.HOLDS_ROLE);
        if (saved.getBoolean("mms_pending", false)) out.append(out.length() > 0 ? "\n" : "").append(Passthrough.PICTURE_MISSING);
        return out.length() == 0 ? null : out.toString();
    }

    /** Left by the record when SQLite found it damaged and it was moved aside. Shown on the month screen from then on. */
    static void noteDamaged(Context context, String asideName) {
        String text = asideName != null
            ? "The record was damaged and couldn't be read. It was kept as " + asideName + " and a new record started from what the phone holds."
            : "The record is damaged and couldn't be read or moved aside.";
        prefs(context).edit().putString("damaged", text).commit();
    }

    /** The damaged-record note, until OK is tapped on the month screen, or null. */
    static String damaged(Context context) {
        return prefs(context).getString("damaged", null);
    }

    static void clearDamaged(Context context) {
        prefs(context).edit().remove("damaged").commit();
    }

    static SharedPreferences preferences(Context context) {
        return prefs(context);
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
     * Reads the phone into the record now. When a run is already going, this returns false at once and that run does one
     * more straight after it ends, so a new tick or a change never waits for the 15-minute schedule.
     */
    static boolean run(Context context) {
        AGAIN.set(true);
        boolean ran = false;
        // A request that comes just as a run ends finds RUNNING clear and starts the next one here.
        while (AGAIN.get() && RUNNING.compareAndSet(false, true)) {
            ran = true;
            try {
                while (AGAIN.getAndSet(false)) once(context);
            } finally {
                RUNNING.set(false);
            }
        }
        return ran;
    }

    /**
     * One run: reads the phone's contacts to keep the people table up to date, then reads calls and texts, decides with
     * the ticked people's numbers, and adds what's new to the record.
     */
    private static void once(Context context) {
        // Before anything is saved: what 0.6.0 and earlier left behind goes, once.
        StartActivity.forgetOldApp(context);
        long started = System.currentTimeMillis();
        try {
            prefs(context).edit().putLong("run_started", started).commit();
            if (!allowed(context)) {
                note(context, NOT_ALLOWED, NOT_ALLOWED);
                return;
            }
            Record record = Record.get(context);
            String contactsLine = "";
            try {
                record.refreshPeople(Collector.readContacts(context.getContentResolver()));
            } catch (Collector.Problem error) {
                // The people already ticked are remembered, so the run goes on with them.
                contactsLine = "\nContacts couldn't be read, so the people already ticked were used.";
            }
            Map<String, String> counting = record.countingNumbers();
            if (counting.isEmpty()) {
                note(context, NOBODY, NOBODY);
                prefs(context).edit().remove("crash").apply();
                return;
            }
            Collector collector = new Collector(context, counting, record.conversations());
            List<Rules.Entry> found = collector.collect();
            Ledger.Added added = record.add(found, collector.newlyFiled());
            String problem = collector.problem();
            int other = collector.otherCalls();
            int people = 0;
            for (Rules.Person person : record.people()) if (person.ticked) people++;
            // What the month screen's red box shows: texts or calls that couldn't be read, contacts that couldn't be read.
            String problems = problem + (contactsLine.isEmpty() ? "" : (problem.isEmpty() ? "" : "\n") + contactsLine.trim());
            noteRead(context, (found.size() - added.unusable) + " calls and texts with " + people + (people == 1 ? " ticked person" : " ticked people")
                + " found, " + added.added + " new in the record."
                + (problem.isEmpty() ? "" : "\n" + problem)
                + contactsLine
                + (added.unusable > 0 ? "\n" + added.unusable + " couldn't be used and were skipped." : "")
                + (other == 0 ? "" : other == 1 ? "\n1 call of another kind, such as voicemail or blocked, was left out."
                    : "\n" + other + " calls of other kinds, such as voicemail or blocked, were left out.")
                + "\n" + (collector.groups() == 1 ? "1 group chat" : collector.groups() + " group chats") + " with ticked people."
                + (collector.conversationListWorked() ? "" : "\nConversation list unavailable, using messages instead."), problems, added.added);
            prefs(context).edit().remove("crash").apply();
        } catch (Record.TooNew error) {
            note(context, error.getMessage(), error.getMessage());
        } catch (SecurityException error) {
            note(context, NOT_ALLOWED, NOT_ALLOWED);
        } catch (Collector.Problem error) {
            note(context, error.getMessage(), error.getMessage());
        } catch (RuntimeException error) {
            String text = "Something went wrong: " + error.getClass().getSimpleName();
            note(context, text, text);
        } finally {
            // Which run finished last, by the moment it started: the emulator check waits on this.
            prefs(context).edit().putLong("run_done", started).commit();
        }
    }
}
