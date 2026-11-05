package com.calltally.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Prints what the rules decide, for tests/test_calltally.py to compare and to send to a real server. */
public class RulesCheck {
    public static void main(String[] args) {
        Map<String, String> work = new HashMap<>();
        work.put("5551234567", "Dana-Work");
        work.put("5552223333", "Sam \"Q\" - Work");
        long t = 1793000000L;
        show("one", Rules.classify(1, Arrays.asList("+1 (555) 123-4567"), work));
        show("personal", Rules.classify(2, Arrays.asList("5559998888"), work));
        show("mixed", Rules.classify(3, Arrays.asList("5551234567", "5559998888", "5557776666"), work));
        show("two-work", Rules.classify(4, Arrays.asList("5551234567", "5552223333"), work));
        show("one-extra", Rules.classify(5, Arrays.asList("5551234567", "5559998888"), work));
        Rules.Entry far = new Rules.Entry("call", 0, "Dana-Work", "5551234567", "in", 1);
        Rules.Entry longNumber = new Rules.Entry("call", t, "Dana-Work", "1234567890123456789012345678901234567890123456789012345678901", "in", 1);
        Rules.Entry fine = new Rules.Entry("call", t, "Dana-Work", "5551234567", "in", 1);
        Rules.Entry tooLate = new Rules.Entry("call", 1793000000000000L, "Dana-Work", "5551234567", "in", 1);
        System.out.println("acceptable " + far.acceptable() + longNumber.acceptable() + tooLate.acceptable() + fine.acceptable());
        System.out.println("names " + Rules.isWork("Dana-Work") + Rules.isWork("Sam - Work") + Rules.isWork("Mom") + Rules.isWork("Dana-Work Jr") + Rules.isWork(null));
        System.out.println("names-more " + Rules.isWork("Lee -Work ") + Rules.isWork("Kim- Work") + Rules.isWork("Work Plumbing") + Rules.isWork("Dana-work") + Rules.isWork("Bets"));
        System.out.println("seconds " + Rules.toSeconds(1793000000999L, false) + " " + Rules.toSeconds(1793000000L, true) + " " + Rules.toSeconds(1793000000999L, true));
        System.out.println("body " + Rules.body(Arrays.asList(
            new Rules.Entry("call", t, "Dana-Work", "5551234567", "in", 60),
            new Rules.Entry("text", t + 1, "Sam \"Q\" - Work", "thread-4", "out", 0))));
        System.out.println("status " + Rules.statusBody("Texts couldn't be read.\nAndroid  returned \"no\" messages. "));
        System.out.println("status-empty " + Rules.statusBody(""));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) longText.append("0123456789");
        System.out.println("status-long " + Rules.statusBody(longText.toString()));
        termuxFixtures();
        decisions();
    }

    /**
     * The calls and texts the old Termux script was tested with, as the app reads them: the call log and SMS table give
     * times in milliseconds, and the contact list gives every number on a contact. The test pins the entry ids the Termux
     * script produced for these, so rows it uploaded are never added twice when the app sends them again. Every decision
     * goes through the same Rules functions Collector calls on the phone.
     */
    private static void termuxFixtures() {
        Map<String, String> work = contacts(
            "Dana-Work", "+1 (555) 123-4567", "Dana-Work", "5550001111", "Sam - Work", "555-222-3333",
            "Mom", "5559998888", "Work Plumbing", "5557776666");
        System.out.println("fixture-work " + new TreeMap<>(work));
        List<Rules.Entry> entries = new ArrayList<>();
        call(entries, work, "15551234567", 1793812320000L, Rules.CALL_INCOMING, 872);
        call(entries, work, "5552223333", 1793826300000L, Rules.CALL_OUTGOING, 3968);
        call(entries, work, "5551234567", 1793833200000L, Rules.CALL_MISSED, 0);
        call(entries, work, "5559998888", 1793840400000L, Rules.CALL_INCOMING, 1800);
        call(entries, work, "5557776666", 1793842200000L, Rules.CALL_OUTGOING, 180);
        call(entries, work, "5550001111", 1793728800000L, Rules.CALL_OUTGOING, 120);
        Map<Long, List<String>> threads = new HashMap<>();
        threads.put(1L, Arrays.asList("+15551234567"));
        threads.put(2L, Arrays.asList("5559998888"));
        threads.put(3L, Arrays.asList("5550001111"));
        // A message from Dana in a chat with one other person, and the same moment in a group of three others.
        threads.put(5L, Arrays.asList("+15551234567", "5559998888"));
        threads.put(4L, Arrays.asList("+15551234567", "5559998888", "5557776666"));
        Rules.Texts texts = new Rules.Texts(work, threads);
        keep(entries, texts.sms(1, "+15551234567", 1793814000000L, Rules.BOX_RECEIVED));
        keep(entries, texts.sms(1, "+15551234567", 1793814060000L, Rules.BOX_SENT));
        keep(entries, texts.sms(2, "5559998888", 1793822400000L, Rules.BOX_RECEIVED));
        keep(entries, texts.sms(3, "5550001111", 1793729100000L, Rules.BOX_SENT));
        keep(entries, texts.sms(5, "+15551234567", 1793836800000L, Rules.BOX_RECEIVED));
        System.out.println("fixture-body " + Rules.body(entries));
        List<Rules.Entry> group = new ArrayList<>();
        keep(group, texts.sms(4, "+15551234567", 1793836800000L, Rules.BOX_RECEIVED));
        System.out.println("fixture-group " + Rules.body(group));
        System.out.println("fixture-groups " + texts.groups());
        // Bob-Work renamed to Bob: his number is no longer a work number, so nothing of his is found.
        Map<String, String> renamed = contacts("Bob", "5554443333");
        show("renamed", Rules.classify(6, Arrays.asList("5554443333"), renamed));
        System.out.println("renamed-call " + skipped(Rules.call(renamed, "5554443333", 1793000000000L, Rules.CALL_INCOMING, 60)));
        System.out.println("renamed-text " + skipped(new Rules.Texts(renamed, new HashMap<Long, List<String>>())
            .sms(6, "5554443333", 1793000000000L, Rules.BOX_RECEIVED)));
    }

    /** Which rows are left out and why, the same-second rule, batching, and how conversations get their people. */
    private static void decisions() {
        Map<String, String> work = contacts("Dana-Work", "555-123-4567", "Mom", "5559998888");
        long ms = 1793000000000L;
        StringBuilder calls = new StringBuilder();
        for (int type = 1; type <= 7; type++) calls.append(type).append('=').append(skipped(Rules.call(work, "5551234567", ms, type, 9))).append(' ');
        System.out.println("call-kinds " + calls.toString().trim());
        System.out.println("call-personal " + skipped(Rules.call(work, "5559998888", ms, Rules.CALL_INCOMING, 9)));
        System.out.println("call-missed " + Rules.call(work, "5551234567", ms + 999, Rules.CALL_MISSED, 7).entry.json());

        Map<Long, List<String>> threads = new HashMap<>();
        threads.put(1L, Arrays.asList("5551234567"));
        threads.put(2L, Arrays.asList("5551234567", "5559998888", "5557776666"));
        Rules.Texts texts = new Rules.Texts(work, threads);
        StringBuilder boxes = new StringBuilder();
        for (int box = 1; box <= 6; box++) boxes.append(box).append('=').append(skipped(texts.sms(1, "5551234567", ms, box))).append(' ');
        System.out.println("sms-boxes " + boxes.toString().trim());
        System.out.println("sms-personal " + skipped(texts.sms(9, "5559998888", ms, Rules.BOX_RECEIVED)));
        StringBuilder mms = new StringBuilder();
        for (int box = 1; box <= 4; box++) mms.append(box).append('=').append(skipped(texts.mms(2, 1793000000L, box, 132))).append(' ');
        System.out.println("mms-boxes " + mms.toString().trim());
        StringBuilder kinds = new StringBuilder();
        for (int kind : new int[] {-1, 128, 130, 132, 134, 135, 136}) kinds.append(kind).append('=').append(skipped(texts.mms(2, 1793000000L, Rules.BOX_RECEIVED, kind))).append(' ');
        System.out.println("mms-kinds " + kinds.toString().trim());
        System.out.println("mms-unknown-thread " + skipped(texts.mms(7, 1793000000L, Rules.BOX_RECEIVED, 132)));
        System.out.println("mms-millis " + texts.mms(2, 1793000000999L, Rules.BOX_SENT, 128).entry.json());
        System.out.println("groups " + texts.groups());

        // Two messages in one conversation, same direction, same second.
        Rules.Entry a = texts.sms(1, "5551234567", ms + 100, Rules.BOX_RECEIVED).entry;
        Rules.Entry b = texts.sms(1, "5551234567", ms + 700, Rules.BOX_RECEIVED).entry;
        System.out.println("same-second " + a.key().equals(b.key()) + " " + a.json().equals(b.json()));
        Rules.Fresh both = Rules.fresh(Arrays.asList(a, b), new HashSet<String>());
        Set<String> sent = new HashSet<>(Collections.singletonList(Rules.hash(a.key())));
        Rules.Fresh again = Rules.fresh(Arrays.asList(a, b, new Rules.Entry("call", 0, "Dana-Work", "5551234567", "in", 1)), sent);
        System.out.println("fresh " + both.entries.size() + " " + both.unusable + " " + again.entries.size() + " " + again.unusable);
        System.out.println("same-second-body " + Rules.body(both.entries));

        StringBuilder sizes = new StringBuilder();
        for (int n : new int[] {0, 1, 499, 500, 501, 1000, 1001, 1201}) {
            List<Rules.Entry> many = new ArrayList<>();
            for (int i = 0; i < n; i++) many.add(new Rules.Entry("call", 1793000000L + i, "Dana-Work", "5551234567", "in", 1));
            List<Integer> parts = new ArrayList<>();
            List<Rules.Entry> joined = new ArrayList<>();
            for (List<Rules.Entry> batch : Rules.batches(many)) {
                parts.add(batch.size());
                joined.addAll(batch);
            }
            sizes.append(n).append('=').append(parts.toString().replace(" ", "")).append(joined.equals(many) ? "" : "!order").append(' ');
        }
        System.out.println("batches " + sizes.toString().trim());
        List<Rules.Entry> full = new ArrayList<>();
        for (int i = 0; i < 501; i++) full.add(new Rules.Entry("call", 1793000000L + i, "Dana-Work", "5551234567", "in", 1));
        List<List<Rules.Entry>> split = Rules.batches(full);
        System.out.println("batch-body-1 " + Rules.body(split.get(0)));
        System.out.println("batch-body-2 " + Rules.body(split.get(1)));

        Map<String, String> addresses = new HashMap<>();
        addresses.put("1", "+15551234567");
        addresses.put("2", "5559998888");
        Map<Long, String> recipients = new HashMap<>();
        recipients.put(10L, "1");
        recipients.put(11L, " 1  2 ");
        recipients.put(12L, "1 3");
        recipients.put(13L, "");
        recipients.put(14L, null);
        System.out.println("thread-people " + new TreeMap<>(Rules.threadPeople(addresses, recipients)));
        List<long[]> messages = new ArrayList<>();
        for (long id = 1; id <= 8; id++) messages.add(new long[] {id, 20});
        messages.add(new long[] {9, 10});
        messages.add(new long[] {10, 21});
        System.out.println("fallback " + new TreeMap<>(Rules.fallbackMessages(messages, new HashSet<>(Collections.singletonList(10L)))));
        System.out.println("people-on-messages " + Rules.peopleOnMessages(Arrays.asList(
            Arrays.asList("5559998888", "+15551234567"), Arrays.asList("+15551234567"), new ArrayList<String>())));
        System.out.println("usable " + Rules.usableAddress("5551234567") + Rules.usableAddress("insert-address-token")
            + Rules.usableAddress("") + Rules.usableAddress(null));
        Map<String, String> contacts = contacts("Dana-Work ", "(555) 123-4567", "Dana-Work", "+1 555 000 1111", "Mom", "555-123-4567",
            "Lee-Work", "no digits", "Kim -Work", "15557770000");
        System.out.println("contacts " + new TreeMap<>(contacts));
    }

    /** As Collector reads the contact list: each saved number of each contact, in order. */
    private static Map<String, String> contacts(String... nameThenNumber) {
        Map<String, String> work = new HashMap<>();
        for (int i = 0; i < nameThenNumber.length; i += 2) Rules.addContact(work, nameThenNumber[i], nameThenNumber[i + 1]);
        return work;
    }

    private static void call(List<Rules.Entry> out, Map<String, String> work, String number, long millis, int type, long seconds) {
        keep(out, Rules.call(work, number, millis, type, seconds));
    }

    private static void keep(List<Rules.Entry> out, Rules.Decision decision) {
        if (decision.entry != null) out.add(decision.entry);
    }

    private static String skipped(Rules.Decision decision) {
        return decision.entry != null ? decision.entry.direction : "skip:" + decision.skipped.replace(' ', '-');
    }

    private static void show(String label, Rules.Who who) {
        System.out.println(label + " " + (who == null ? "none" : who.contact + " | " + who.number));
    }
}
