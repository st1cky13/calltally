package com.calltally.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rules for what counts and what gets uploaded. Plain Java with no Android parts, so it can be tested anywhere.
 * Collector reads the phone's tables into plain values and every decision about them is made here.
 */
final class Rules {
    private static final Pattern WORK_NAME = Pattern.compile("-\\s*Work\\s*$");
    private static final int MAX_CONTACT = 190;
    private static final int MAX_STATUS = 200;
    // The start of the year 10000, past which the server can't read the time.
    private static final long MAX_EPOCH = 253402300800L;
    // At most this many entries per upload. The server takes up to 2000.
    static final int BATCH = 500;

    // Android's call log types (CallLog.Calls), copied so these rules need no Android parts.
    static final int CALL_INCOMING = 1;
    static final int CALL_OUTGOING = 2;
    static final int CALL_MISSED = 3;
    static final int CALL_REJECTED = 5;
    // Android's message boxes: received and sent. Drafts, outbox and failed messages are other boxes.
    static final int BOX_RECEIVED = 1;
    static final int BOX_SENT = 2;
    // Delivery and read reports sit in the same table, but they aren't messages. 130 is a picture message still waiting
    // to download: Android swaps it for a new row with a different time once it arrives, so counting both would double it.
    private static final Set<Integer> NOT_MESSAGES = new HashSet<>(Arrays.asList(130, 134, 135, 136));
    // Without the conversation list, this many of a conversation's latest messages name its people.
    static final int FALLBACK_MESSAGES = 5;

    // Why a row was left out.
    static final String SKIP_PERSONAL = "personal";
    static final String SKIP_KIND = "other kind";
    static final String SKIP_BOX = "not received or sent";
    static final String SKIP_NOT_MESSAGE = "not a message";
    static final String SKIP_NO_PEOPLE = "people unknown";

    private Rules() {}

    /** What one row of the call log or a message table becomes: an entry, or the reason it was left out. */
    static final class Decision {
        final Entry entry;
        final String skipped;
        private Decision(Entry entry, String skipped) {
            this.entry = entry;
            this.skipped = skipped;
        }
        static Decision keep(Entry entry) {
            return new Decision(entry, null);
        }
        static Decision skip(String reason) {
            return new Decision(null, reason);
        }
    }

    /** One saved number of one contact. Only names ending in -Work count, matched by the last ten digits. */
    static void addContact(Map<String, String> workNames, String name, String number) {
        String key = numberKey(number);
        if (!key.isEmpty() && isWork(name)) workNames.put(key, name.trim());
    }

    /**
     * One call log row. Only connected, missed and declined calls with a work contact are kept; missed and declined
     * calls stay listed but the server never counts their time. Voicemail, blocked and answered elsewhere are another kind.
     */
    static Decision call(Map<String, String> workNames, String number, long millis, int type, long seconds) {
        String name = workNames.get(numberKey(number));
        if (name == null) return Decision.skip(SKIP_PERSONAL);
        String direction;
        switch (type) {
            case CALL_INCOMING: direction = "in"; break;
            case CALL_OUTGOING: direction = "out"; break;
            case CALL_MISSED: direction = "missed"; break;
            case CALL_REJECTED: direction = "rejected"; break;
            default: return Decision.skip(SKIP_KIND);
        }
        return Decision.keep(new Entry("call", millis / 1000L, name, number, direction, seconds));
    }

    /**
     * Who is in each conversation, from the conversation list: thread ids with their recipient ids, and what each
     * recipient id stands for. A conversation with a recipient that can't be looked up is left out. Android leaves your
     * own number out of this list.
     */
    static Map<Long, List<String>> threadPeople(Map<String, String> addresses, Map<Long, String> recipientIds) {
        Map<Long, List<String>> people = new HashMap<>();
        for (Map.Entry<Long, String> thread : recipientIds.entrySet()) {
            String ids = thread.getValue();
            if (ids == null || ids.trim().isEmpty()) continue;
            List<String> found = new ArrayList<>();
            boolean complete = true;
            for (String id : ids.trim().split("\\s+")) {
                String address = addresses.get(id);
                if (address == null) complete = false;
                else found.add(address);
            }
            if (complete) people.put(thread.getKey(), found);
        }
        return people;
    }

    /**
     * Without the conversation list: which messages to ask for their addresses. messages holds message id and thread id
     * pairs, newest first. Conversations already known are skipped.
     */
    static Map<Long, List<Long>> fallbackMessages(List<long[]> messages, Set<Long> known) {
        Map<Long, List<Long>> ids = new HashMap<>();
        for (long[] message : messages) {
            long thread = message[1];
            if (known.contains(thread)) continue;
            List<Long> list = ids.get(thread);
            if (list == null) {
                list = new ArrayList<>();
                ids.put(thread, list);
            }
            if (list.size() < FALLBACK_MESSAGES) list.add(message[0]);
        }
        return ids;
    }

    /** Everyone named on a conversation's messages, sorted, once each. */
    static List<String> peopleOnMessages(Collection<List<String>> addressesPerMessage) {
        Set<String> people = new TreeSet<>();
        for (List<String> addresses : addressesPerMessage) people.addAll(addresses);
        return new ArrayList<>(people);
    }

    /** An address on a picture message that names a person. Android fills in a placeholder for your own until it's sent. */
    static boolean usableAddress(String address) {
        return address != null && !address.isEmpty() && !address.equals("insert-address-token");
    }

    /**
     * The decisions for one run's texts. Each conversation is classified once and remembered, so a group keeps one name
     * for the whole run and groups() can count them.
     */
    static final class Texts {
        private final Map<String, String> workNames;
        private final Map<Long, List<String>> threadPeople;
        private final Map<Long, Who> threadWho = new HashMap<>();

        Texts(Map<String, String> workNames, Map<Long, List<String>> threadPeople) {
            this.workNames = workNames;
            this.threadPeople = threadPeople;
        }

        /**
         * One row of the SMS table. A conversation with more than one person is classified as a whole; otherwise the
         * row's own address decides. Times are in milliseconds.
         */
        Decision sms(long thread, String address, long millis, int box) {
            if (box != BOX_RECEIVED && box != BOX_SENT) return Decision.skip(SKIP_BOX);
            Who who = null;
            List<String> people = threadPeople.get(thread);
            if (people != null && people.size() > 1) {
                who = whoInThread(thread, people);
            } else {
                String name = workNames.get(numberKey(address));
                if (name != null) who = new Who(name, address);
            }
            if (who == null) return Decision.skip(SKIP_PERSONAL);
            return Decision.keep(new Entry("text", toSeconds(millis, false), who.contact, who.number, box == BOX_SENT ? "out" : "in", 0));
        }

        /** One row of the MMS table. messageType is -1 when the phone has no such column. Times are normally in seconds. */
        Decision mms(long thread, long stored, int box, int messageType) {
            if (box != BOX_RECEIVED && box != BOX_SENT) return Decision.skip(SKIP_BOX);
            if (NOT_MESSAGES.contains(messageType)) return Decision.skip(SKIP_NOT_MESSAGE);
            List<String> people = threadPeople.get(thread);
            if (people == null) return Decision.skip(SKIP_NO_PEOPLE);
            Who who = whoInThread(thread, people);
            if (who == null) return Decision.skip(SKIP_PERSONAL);
            return Decision.keep(new Entry("text", toSeconds(stored, true), who.contact, who.number, box == BOX_SENT ? "out" : "in", 0));
        }

        private Who whoInThread(long thread, List<String> people) {
            if (!threadWho.containsKey(thread)) threadWho.put(thread, classify(thread, people, workNames));
            return threadWho.get(thread);
        }

        /** Group conversations with a work contact met so far. */
        int groups() {
            int count = 0;
            for (Who who : threadWho.values()) {
                if (who != null && who.number.startsWith("thread-")) count++;
            }
            return count;
        }
    }

    /** What one run sends: entries not sent before, and how many couldn't be used. */
    static final class Fresh {
        final List<Entry> entries = new ArrayList<>();
        int unusable;
    }

    /**
     * Leaves out entries already sent (by the hash of their key) and entries the server would refuse. Two messages in
     * one conversation in the same direction and second have the same key and the same fields: both go up in one run,
     * and the server keeps one, since its id is built from those fields.
     */
    static Fresh fresh(List<Entry> found, Set<String> sentHashes) {
        Fresh out = new Fresh();
        for (Entry entry : found) {
            // One unusable record must not hold up everything after it.
            if (!entry.acceptable()) out.unusable++;
            else if (!sentHashes.contains(hash(entry.key()))) out.entries.add(entry);
        }
        return out;
    }

    /** Uploads of at most BATCH entries. Nothing to send still gives one empty upload, so the page sees the phone checked in. */
    static List<List<Entry>> batches(List<Entry> entries) {
        List<List<Entry>> out = new ArrayList<>();
        int start = 0;
        do {
            out.add(entries.subList(start, Math.min(start + BATCH, entries.size())));
            start += BATCH;
        } while (start < entries.size());
        return out;
    }

    /** What the app keeps to remember an entry was sent: the start of the SHA-256 of its key. */
    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static boolean isWork(String name) {
        return name != null && WORK_NAME.matcher(name).find();
    }

    /** The last ten digits, so +1 (555) 123-4567 and 5551234567 match. Empty if there are no digits. */
    static String numberKey(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** Who a conversation is with, or null when it has no work contact. */
    static final class Who {
        final String contact;
        final String number;
        Who(String contact, String number) {
            this.contact = contact;
            this.number = number;
        }
    }

    /**
     * addresses: the people in a conversation. Phones differ on whether this holds your own number, so one extra address
     * beside a single work contact counts as a chat with that contact. Calling it a group would give it a new identity and
     * count its texts a second time. Personal contacts appear only as a count.
     */
    static Who classify(long thread, List<String> addresses, Map<String, String> workNames) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> work = new ArrayList<>();
        String workNumber = null;
        int others = 0;
        for (String address : addresses) {
            String key = numberKey(address);
            if (key.isEmpty() || !seen.add(key)) continue;
            String name = workNames.get(key);
            if (name == null) {
                others++;
            } else {
                work.add(name);
                workNumber = address;
            }
        }
        if (work.isEmpty()) return null;
        if (work.size() == 1 && others <= 1) return new Who(work.get(0), workNumber);
        StringBuilder label = new StringBuilder("Group: ").append(String.join(", ", work));
        if (others > 0) label.append(" +").append(others);
        String text = label.length() > MAX_CONTACT ? label.substring(0, MAX_CONTACT) : label.toString();
        return new Who(text, "thread-" + thread);
    }

    static long toSeconds(long stored, boolean storedInSeconds) {
        // A phone may keep MMS times in milliseconds even though they're normally seconds.
        return storedInSeconds && stored < 100000000000L ? stored : stored / 1000L;
    }

    static String clockText(long epochSeconds) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(epochSeconds * 1000L));
    }

    static final class Entry {
        final String kind;
        final long epoch;
        final String at;
        final String contact;
        final String number;
        final String direction;
        final long duration;

        Entry(String kind, long epoch, String contact, String number, String direction, long duration) {
            this.kind = kind;
            this.epoch = epoch;
            this.at = clockText(epoch);
            this.contact = contact;
            this.number = number;
            this.direction = direction;
            this.duration = duration;
        }

        /** Same entry, same key. A new contact name changes it, so a rename reaches the server. */
        String key() {
            return kind + "|" + numberKey(number) + "|" + number.startsWith("thread-") + "|" + epoch + "|" + direction + "|" + contact;
        }

        /** The same limits the server applies. Anything outside them would make it refuse the whole batch. */
        boolean acceptable() {
            return epoch > 0 && epoch < MAX_EPOCH && !contact.trim().isEmpty() && contact.length() <= 200 && !number.trim().isEmpty()
                && number.length() <= 60 && duration >= 0 && duration <= 7 * 86400L;
        }

        String json() {
            return "{\"kind\":" + quote(kind) + ",\"at\":" + quote(at) + ",\"epoch\":" + epoch + ",\"contact\":" + quote(contact)
                + ",\"number\":" + quote(number) + ",\"direction\":" + quote(direction) + ",\"duration_s\":" + duration + "}";
        }
    }

    static String body(List<Entry> entries) {
        StringBuilder out = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            out.append(entries.get(i).json());
        }
        return out.append("]}").toString();
    }

    /** The run's result for the page: one line, at most the server's 200 characters. Empty means the run was fine. */
    static String statusBody(String text) {
        String line = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (line.length() > MAX_STATUS) line = line.substring(0, MAX_STATUS);
        return "{\"text\":" + quote(line) + "}";
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
