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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One upload run, the schedule that starts runs, and the status the app's screen shows. */
final class Sync {
    static final String[] PERMISSIONS = {Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS};
    private static final int BATCH = 500;
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
        return prefs(context).getString("status", "Not run yet.");
    }

    static boolean neverRun(Context context) {
        return !prefs(context).contains("status");
    }

    private static void note(Context context, String text) {
        String when = new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new java.util.Date());
        prefs(context).edit().putString("status", when + "\n" + text).apply();
    }

    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, SyncJob.class);
        if (jobs.getPendingJob(JOB_PERIODIC) == null) {
            jobs.schedule(new JobInfo.Builder(JOB_PERIODIC, service).setPeriodic(PERIOD_MS).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        }
        if (jobs.getPendingJob(JOB_CHANGES) == null) scheduleChanges(context);
    }

    /** Wakes soon after a call or message is saved. Android runs this once per schedule, so each run sets up the next. */
    static void scheduleChanges(Context context) {
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

    /** One run. Nothing checks in with the server unless everything could be read, so a problem shows on the page as out of date. */
    static boolean run(Context context) {
        return run(context, false);
    }

    /** Returns false if another run was already going, so this one did nothing. */
    static boolean run(Context context, boolean everything) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        try {
            if (!allowed(context)) {
                note(context, "Needs permission. Open this app to allow it.");
                return true;
            }
            if (everything) new File(context.getFilesDir(), SENT_FILE).delete();
            Collector collector = new Collector(context);
            List<Rules.Entry> found = collector.collect();
            Set<String> sent = readSent(context);
            List<Rules.Entry> fresh = new ArrayList<>();
            int skipped = 0;
            for (Rules.Entry entry : found) {
                // One unusable record must not hold up everything after it.
                if (!entry.acceptable()) skipped++;
                else if (!sent.contains(hash(entry.key()))) fresh.add(entry);
            }
            upload(context, fresh);
            note(context, (found.size() - skipped) + " work calls and texts found, " + fresh.size() + " sent this time."
                + (skipped > 0 ? "\n" + skipped + " couldn't be used and were skipped." : "")
                + "\n" + collector.groups() + " group chats with work contacts."
                + (collector.conversationListWorked() ? "" : "\nConversation list unavailable, using messages instead."));
        } catch (UploadProblem error) {
            note(context, error.refused ? "The server refused the upload. It will try again."
                : "Couldn't reach the server. Is Tailscale on? It will try again.");
        } catch (IOException error) {
            note(context, "Couldn't read this app's own records. It will try again.");
        } catch (SecurityException error) {
            note(context, "Needs permission. Open this app to allow it.");
        } catch (Collector.Problem error) {
            note(context, error.getMessage());
        } catch (RuntimeException error) {
            note(context, "Something went wrong: " + error.getClass().getSimpleName());
        } finally {
            RUNNING.set(false);
        }
        return true;
    }

    private static void upload(Context context, List<Rules.Entry> fresh) throws IOException, UploadProblem {
        // An empty first batch still tells the page the phone checked in.
        int start = 0;
        do {
            List<Rules.Entry> batch = fresh.subList(start, Math.min(start + BATCH, fresh.size()));
            post(Rules.body(batch));
            try (FileWriter out = new FileWriter(new File(context.getFilesDir(), SENT_FILE), true)) {
                for (Rules.Entry entry : batch) out.write(hash(entry.key()) + "\n");
            }
            start += BATCH;
        } while (start < fresh.size());
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

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
