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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One upload run, the schedule that starts runs, and the status the app's screen shows. */
final class Sync {
    static final String[] PERMISSIONS = {Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS};
    private static final int JOB_PERIODIC = 1;
    static final int JOB_CHANGES = 2;
    private static final long PERIOD_MS = 15 * 60 * 1000L;
    private static final String SENT_FILE = "sent.txt";
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
        if (schedule != null) text += "\n\nBackground uploads aren't set up: " + schedule;
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

    private static void note(Context context, String text) {
        String when = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new java.util.Date());
        prefs(context).edit().putString("status", when + "\n" + text).apply();
    }

    static void schedule(Context context) {
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            ComponentName service = new ComponentName(context, SyncJob.class);
            if (jobs.getPendingJob(JOB_PERIODIC) == null) {
                jobs.schedule(new JobInfo.Builder(JOB_PERIODIC, service).setPeriodic(PERIOD_MS).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
            }
            if (jobs.getPendingJob(JOB_CHANGES) == null) changesJob(context);
            prefs(context).edit().remove("schedule_problem").apply();
        } catch (RuntimeException error) {
            // Uploading by hand still works, so this shows on the screen rather than closing the app.
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
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setTriggerContentUpdateDelay(10000).setTriggerContentMaxDelay(60000);
        for (Uri uri : new Uri[] {CallLog.Calls.CONTENT_URI, Uri.parse("content://sms"), Uri.parse("content://mms"), Uri.parse("content://mms-sms")}) {
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
        }
        context.getSystemService(JobScheduler.class).schedule(job.build());
    }

    /** The server said no, or couldn't be reached. Kept apart from a problem with this phone's own files. */
    private static final class UploadProblem extends Exception {
        final boolean refused;
        UploadProblem(boolean refused) {
            this.refused = refused;
        }
    }

    /**
     * One run. Calls and texts go up when they could be read, even if the other part couldn't. Then the run's result goes
     * to the page as one plain sentence, or an empty one when all went well. A run that couldn't reach the server sends
     * nothing, so the page shows it only as out of date.
     */
    static boolean run(Context context) {
        return run(context, false);
    }

    /** Returns false if another run was already going, so this one did nothing. */
    static boolean run(Context context, boolean everything) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        // What the page is told at the end. Null sends nothing.
        String report = null;
        try {
            if (!allowed(context)) {
                report = "Needs permission. Open this app to allow it.";
                note(context, report);
                return true;
            }
            if (everything) new File(context.getFilesDir(), SENT_FILE).delete();
            Collector collector = new Collector(context);
            List<Rules.Entry> found = collector.collect();
            Rules.Fresh chosen = Rules.fresh(found, readSent(context));
            List<Rules.Entry> fresh = chosen.entries;
            int skipped = chosen.unusable;
            String problem = collector.problem();
            int other = collector.otherCalls();
            String otherLine = other == 0 ? "" : other == 1 ? "\nSkipped 1 call of another kind." : "\nSkipped " + other + " calls of other kinds.";
            try {
                upload(context, fresh);
            } catch (UploadProblem error) {
                // The part that couldn't be read still shows, so the screen matches what the page is told later.
                String text = uploadProblem(error);
                note(context, text + (problem.isEmpty() ? "" : "\n" + problem));
                if (error.refused) report = text;
                return true;
            }
            note(context, (found.size() - skipped) + " work calls and texts found, " + fresh.size() + " sent this time."
                + (problem.isEmpty() ? "" : "\n" + problem)
                + (skipped > 0 ? "\n" + skipped + " couldn't be used and were skipped." : "")
                + otherLine
                + "\n" + collector.groups() + " group chats with work contacts."
                + (collector.conversationListWorked() ? "" : "\nConversation list unavailable, using messages instead."));
            report = problem;
            prefs(context).edit().remove("crash").apply();
        } catch (IOException error) {
            report = "Couldn't read this app's own records. It will try again.";
            note(context, report);
        } catch (SecurityException error) {
            report = "Needs permission. Open this app to allow it.";
            note(context, report);
        } catch (Collector.Problem error) {
            report = error.getMessage();
            note(context, report);
        } catch (RuntimeException error) {
            report = "Something went wrong: " + error.getClass().getSimpleName();
            note(context, report);
        } finally {
            try {
                if (report != null) sendStatus(report);
            } finally {
                RUNNING.set(false);
            }
        }
        return true;
    }

    private static String uploadProblem(UploadProblem error) {
        return error.refused ? "The server refused the upload. It will try again."
            : "Couldn't reach the server. Is Tailscale on? It will try again.";
    }

    /** Tells the page how the run went. Never a name, number or count. If it can't get through, the next run tries again. */
    private static void sendStatus(String text) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(Config.SERVER + "/api/status").openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = Rules.statusBody(text).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            connection.getResponseCode();
        } catch (IOException | RuntimeException error) {
            // The status is only a note for the page. Failing to send it must never change the run's result.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void upload(Context context, List<Rules.Entry> fresh) throws IOException, UploadProblem {
        // An empty first batch still tells the page the phone checked in.
        for (List<Rules.Entry> batch : Rules.batches(fresh)) {
            post(Rules.body(batch));
            try (FileWriter out = new FileWriter(new File(context.getFilesDir(), SENT_FILE), true)) {
                for (Rules.Entry entry : batch) out.write(Rules.hash(entry.key()) + "\n");
            }
        }
    }

    private static void post(String body) throws UploadProblem {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(Config.SERVER + "/api/upload").openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            if (connection.getResponseCode() != 200) throw new UploadProblem(true);
        } catch (IOException error) {
            throw new UploadProblem(false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Set<String> readSent(Context context) throws IOException {
        Set<String> sent = new HashSet<>();
        File file = new File(context.getFilesDir(), SENT_FILE);
        if (!file.exists()) return sent;
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) sent.add(line);
        }
        return sent;
    }
}
