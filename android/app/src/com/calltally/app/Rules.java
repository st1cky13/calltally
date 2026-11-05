package com.calltally.app;

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

/**
 * The rules for what counts. Plain Java with no Android parts, so it can be tested anywhere. Collector reads the phone's
 * tables into plain values and every decision about them is made here. Who counts comes from the people the user ticked.
 */
final class Rules {
    private static final int MAX_CONTACT = 190;
    // The start of the year 10000, past which the clock text has more than four digits for the year.
    private static final long MAX_EPOCH = 253402300800L;

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

    /**
     * A contact on the phone, or a person in the people table. key is the phone's lookup key for the contact; numbers are
     * as saved. ticked and onPhone only mean something for people in the table.
     */
    static final class Person {
        final String key;
        final String name;
        final List<String> numbers;
        final boolean ticked;
        final boolean onPhone;

        Person(String key, String name, List<String> numbers, boolean ticked, boolean onPhone) {
            this.key = key;
            this.name = name == null ? "" : name.trim();
            this.numbers = numbers;
            this.ticked = ticked;
            this.onPhone = onPhone;
        }

        /** The name to show and count under: the contact's name, or its first number when it has none. */
        String label() {
            if (!name.isEmpty()) return name;
            return numbers.isEmpty() ? key : numbers.get(0).trim();
        }
    }

    /**
     * The numbers that count, by their last ten digits, with the name each counts under: every number of every ticked
     * person. People still on the phone come last so their names win when two people share a number.
     */
    static Map<String, String> countingNumbers(List<Person> people) {
        Map<String, String> work = new HashMap<>();
        for (boolean onPhone : new boolean[] {false, true}) {
            for (Person person : people) {
                if (!person.ticked || person.onPhone != onPhone) continue;
                for (String number : person.numbers) {
                    String key = numberKey(number);
                    if (!key.isEmpty()) work.put(key, person.label());
                }
            }
        }
        return work;
    }

    /**
     * The people table after a read of the phone's contacts. A person found on the phone takes its current name, gains
     * any new numbers and is on the phone; one not found is no longer on the phone. Nothing is removed and ticks never
     * change. An empty read (null or no contacts) changes nothing, since contacts can come back empty for a moment.
     */
    static List<Person> refreshPeople(List<Person> known, List<Person> phone) {
        if (phone == null || phone.isEmpty()) return known;
        Map<String, Person> byKey = new HashMap<>();
        for (Person contact : phone) byKey.put(contact.key, contact);
        List<Person> out = new ArrayList<>();
        for (Person person : known) {
            Person contact = byKey.get(person.key);
            if (contact == null) {
                out.add(new Person(person.key, person.name, person.numbers, person.ticked, false));
                continue;
            }
            out.add(new Person(person.key, contact.name.isEmpty() ? person.name : contact.name,
                joinNumbers(person.numbers, contact.numbers), person.ticked, true));
        }
        return out;
    }

    /**
     * People in the table whose contact is no longer found by its key, but whose number now belongs to a contact with a
     * key the table doesn't know: Android gave the same contact a new key, as it can when contacts merge or sync again.
     * Gives old key to new key, each new key claimed once, so the person keeps one line, their tick and their numbers.
     */
    static Map<String, String> movedKeys(List<Person> known, List<Person> phone) {
        Map<String, String> moved = new java.util.LinkedHashMap<>();
        if (phone == null || phone.isEmpty()) return moved;
        Set<String> knownKeys = new HashSet<>(), phoneKeys = new HashSet<>();
        for (Person person : known) knownKeys.add(person.key);
        for (Person contact : phone) phoneKeys.add(contact.key);
        Set<String> claimed = new HashSet<>();
        for (Person person : known) {
            if (phoneKeys.contains(person.key)) continue;
            Set<String> mine = new HashSet<>();
            for (String number : person.numbers) if (!numberKey(number).isEmpty()) mine.add(numberKey(number));
            for (Person contact : phone) {
                if (knownKeys.contains(contact.key) || claimed.contains(contact.key)) continue;
                boolean shares = false;
                for (String number : contact.numbers) if (mine.contains(numberKey(number))) shares = true;
                if (shares) {
                    moved.put(person.key, contact.key);
                    claimed.add(contact.key);
                    break;
                }
            }
        }
        return moved;
    }

    /** The first list, then each number of the second whose last ten digits aren't in it yet. Numbers without digits are left out. */
    static List<String> joinNumbers(List<String> have, List<String> more) {
        List<String> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (List<String> list : Arrays.asList(have, more)) {
            for (String number : list) {
                String key = numberKey(number);
                if (!key.isEmpty() && keys.add(key)) out.add(number.trim());
            }
        }
        return out;
    }

    /**
     * One call log row. Only connected, missed and declined calls with a ticked person are kept; missed and declined
     * calls stay listed but their time never counts. Voicemail, blocked and answered elsewhere are another kind.
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
        private final Map<Long, Who> remembered;
        private final Map<Long, Who> decided = new HashMap<>();

        Texts(Map<String, String> workNames, Map<Long, List<String>> threadPeople) {
            this(workNames, threadPeople, new HashMap<Long, Who>());
        }

        /** remembered: how earlier runs filed each conversation of more than one person, by thread id. */
        Texts(Map<String, String> workNames, Map<Long, List<String>> threadPeople, Map<Long, Who> remembered) {
            this.workNames = workNames;
            this.threadPeople = threadPeople;
            this.remembered = remembered;
        }

        /** Conversations filed for the first time in this run, for the record to remember. */
        Map<Long, Who> decided() {
            return decided;
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
            if (!threadWho.containsKey(thread)) {
                Who who = classify(thread, people, workNames, remembered.get(thread));
                threadWho.put(thread, who);
                if (who != null && !remembered.containsKey(thread)) decided.put(thread, who);
            }
            return threadWho.get(thread);
        }

        /** Group conversations with a ticked person met so far. */
        int groups() {
            int count = 0;
            for (Who who : threadWho.values()) {
                if (who != null && who.number.startsWith("thread-")) count++;
            }
            return count;
        }
    }

    /**
     * Who counts' search: the digits to look for in numbers when the text is made only of digits and phone punctuation
     * (spaces, + - . ( )), or null when it holds anything else and names are searched instead.
     */
    static String searchDigits(String query) {
        String text = query == null ? "" : query.trim();
        if (!text.matches("[0-9+\\-.()\\s]+")) return null;
        String digits = text.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /** The last ten digits, so +1 (555) 123-4567 and 5551234567 match. Empty if there are no digits. */
    static String numberKey(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** Who a conversation is with, or null when it has no ticked person. */
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
     * beside a single ticked person counts as a chat with that person. Calling it a group would give it a new identity and
     * count its texts a second time. Anyone not ticked appears only as a count.
     */
    static Who classify(long thread, List<String> addresses, Map<String, String> workNames) {
        return classify(thread, addresses, workNames, null);
    }

    /**
     * The same, keeping a conversation filed the way it was first filed (filed, or null the first time). Ticks change
     * which of the two a conversation would be, a chat with one person or a group, and filing it the other way would give
     * its texts a second identity and count them twice. A conversation with no ticked person still counts nothing.
     */
    static Who classify(long thread, List<String> addresses, Map<String, String> workNames, Who filed) {
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
        if (filed != null && !filed.number.startsWith("thread-")) {
            // A chat stays the chat with that number, under its current name while it is ticked, else the name kept.
            String name = workNames.get(numberKey(filed.number));
            return new Who(name != null ? name : filed.contact, filed.number);
        }
        boolean group = filed != null || !(work.size() == 1 && others <= 1);
        if (!group) return new Who(work.get(0), workNumber);
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
            this(kind, epoch, clockText(epoch), contact, number, direction, duration);
        }

        /** With a clock text of its own, for checks of the repeat rule. */
        Entry(String kind, long epoch, String at, String contact, String number, String direction, long duration) {
            this.kind = kind;
            this.epoch = epoch;
            this.at = at;
            this.contact = contact;
            this.number = number;
            this.direction = direction;
            this.duration = duration;
        }

        /** The limits the server applied, kept so the record holds only what the server would have taken. */
        boolean acceptable() {
            return epoch > 0 && epoch < MAX_EPOCH && !contact.trim().isEmpty() && contact.length() <= 200 && !number.trim().isEmpty()
                && number.length() <= 60 && duration >= 0 && duration <= 7 * 86400L;
        }
    }
}
