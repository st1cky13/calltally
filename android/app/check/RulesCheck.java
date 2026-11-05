package com.calltally.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Prints what the rules, the id rule and the record's statements decide, for tests/test_calltally.py to compare with tests/reference.py. */
public class RulesCheck {
    public static void main(String[] args) {
        Map<String, String> work = Rules.countingNumbers(Arrays.asList(
            person("a", "Dana-Work", true, true, "5551234567"), person("b", "Sam \"Q\" - Work", true, true, "5552223333")));
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
        System.out.println("seconds " + Rules.toSeconds(1793000000999L, false) + " " + Rules.toSeconds(1793000000L, true) + " " + Rules.toSeconds(1793000000999L, true));
        fixtures();
        decisions();
        people();
        ledger();
        statements();
        payPeriods();
        words();
        filing();
        own();
        moved();
        search();
        phoneCheck();
        passthrough();
    }

    /**
     * Catch up encrypted's plain pieces: the inbox row a text that arrives during the brief window is written as (unread,
     * passed through), and the words the screens show. Compared with tests/test_calltally.py.
     */
    private static void passthrough() {
        Map<String, Object> row = Passthrough.inboxRow("+15550009999", "hello during catch up", 1793000000000L, 1793000000500L, 42L, -1);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> field : row.entrySet()) {
            out.append(out.length() > 0 ? " " : "").append(field.getKey()).append("=").append(field.getValue());
        }
        System.out.println("passthrough-row " + out);
        Map<String, Object> withSub = Passthrough.inboxRow("+1", "x", 1L, 0L, 7L, 3);
        System.out.println("passthrough-sub " + withSub.get(Passthrough.SUBSCRIPTION_ID) + " " + row.containsKey(Passthrough.SUBSCRIPTION_ID));
        System.out.println("passthrough-added " + Passthrough.added(0) + " | " + Passthrough.added(1) + " | " + Passthrough.added(5));
        System.out.println("passthrough-result " + Passthrough.readResult(0, 0) + " | " + Passthrough.readResult(12, 3));
        System.out.println("passthrough-last " + Passthrough.lastCaughtUp(0, Locale.US) + " | " + Passthrough.lastCaughtUp(1793000000000L, Locale.US));
    }

    /**
     * Your own number: learned from the sender row of picture messages you sent, never from one you received, a
     * placeholder or an email, then left out of a conversation's people unless it is a ticked person's. Prints what was
     * learned, which messages were asked for their rows, and each classification as contact | number.
     */
    private static void own() {
        final Map<Long, List<Rules.AddressRow>> rows = new HashMap<>();
        rows.put(1L, Arrays.asList(new Rules.AddressRow("+1 (555) 000-9999", 137), new Rules.AddressRow("5551234567", 151),
            new Rules.AddressRow("5559998888", 151)));
        rows.put(2L, Arrays.asList(new Rules.AddressRow("5551234567", 137), new Rules.AddressRow("5550009999", 151)));
        rows.put(3L, Arrays.asList(new Rules.AddressRow("insert-address-token", 137), new Rules.AddressRow("5551234567", 151)));
        rows.put(4L, Arrays.asList(new Rules.AddressRow("me@example.com", 137), new Rules.AddressRow("5551234567", 151)));
        final List<Long> asked = new ArrayList<>();
        Rules.AddressRows addresses = new Rules.AddressRows() {
            @Override
            public List<Rules.AddressRow> on(long messageId) {
                asked.add(messageId);
                List<Rules.AddressRow> on = rows.get(messageId);
                return on == null ? new ArrayList<Rules.AddressRow>() : on;
            }
        };
        List<long[]> sentOnly = Arrays.asList(new long[] {1, Rules.BOX_SENT});
        List<long[]> receivedOnly = Arrays.asList(new long[] {2, Rules.BOX_RECEIVED});
        List<long[]> token = Arrays.asList(new long[] {3, Rules.BOX_SENT});
        List<long[]> email = Arrays.asList(new long[] {4, Rules.BOX_SENT});
        System.out.println("own-learn " + Rules.ownNumbers(sentOnly, addresses) + " " + Rules.ownNumbers(receivedOnly, addresses) + " "
            + Rules.ownNumbers(token, addresses) + " " + Rules.ownNumbers(email, addresses));
        asked.clear();
        // Twenty-five sent messages with no number among them: only the newest twenty are asked.
        List<long[]> many = new ArrayList<>();
        many.add(new long[] {2, Rules.BOX_RECEIVED});
        for (long id = 100; id < 125; id++) many.add(new long[] {id, Rules.BOX_SENT});
        Rules.ownNumbers(many, addresses);
        System.out.println("own-asked " + asked.size() + " " + asked.get(0) + " " + asked.get(asked.size() - 1));
        System.out.println("own-keep " + Rules.joinOwn(null, Arrays.asList("+1 (555) 000-9999")) + " | "
            + Rules.joinOwn("5550009999", Arrays.asList("2065550199", "5550009999")) + " | " + Rules.joinOwn("5550009999", new ArrayList<String>())
            + " | " + Rules.ownKeys("  ") + " " + Rules.ownKeys(null) + " " + Rules.ownKeys("5550009999 2065550199"));

        Map<String, String> work = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567")));
        java.util.Set<String> mine = new HashSet<>(Arrays.asList("5550009999"));
        show("own-chat", Rules.classify(40, Arrays.asList("5551234567", "5559998888", "+15550009999"), work, null, mine));
        show("own-pair", Rules.classify(41, Arrays.asList("5551234567", "555-000-9999"), work, null, mine));
        show("own-group", Rules.classify(42, Arrays.asList("5551234567", "5559998888", "5557776666", "5550009999"), work, null, mine));
        show("own-without", Rules.classify(42, Arrays.asList("5551234567", "5559998888", "5557776666", "5550009999"), work));
        Map<String, String> self = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567"),
            person("s", "Me", true, true, "5550009999")));
        show("own-ticked", Rules.classify(43, Arrays.asList("5551234567", "5559998888", "5550009999"), self, null, mine));
        Rules.Texts texts = new Rules.Texts(work, Collections.singletonMap(44L, Arrays.asList("5551234567", "5559998888", "5557776666",
            "5550009999")), new HashMap<Long, Rules.Who>(), mine);
        System.out.println("own-texts " + entryText(texts.mms(44, 1793000000L, Rules.BOX_SENT, 128)));
    }

    /**
     * A conversation keeps the way it was first filed when ticks change: a chat with Dana stays her chat after Mom is
     * ticked too, a group stays a group after one of its two ticked people is unticked, and with nobody ticked it counts
     * nothing. Prints each step's contact | number.
     */
    private static void filing() {
        List<String> pair = Arrays.asList("5551234567", "5559998888");
        Map<String, String> dana = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567")));
        Map<String, String> both = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567"),
            person("m", "Mom", true, true, "5559998888")));
        Map<String, String> mom = Rules.countingNumbers(Arrays.asList(person("m", "Mom", true, true, "5559998888")));
        Map<String, String> nobody = new HashMap<>();
        // First run: Dana alone is ticked, so the pair is her chat; the record remembers that.
        Rules.Texts first = new Rules.Texts(dana, Collections.singletonMap(7L, pair), new HashMap<Long, Rules.Who>());
        Rules.Entry one = first.sms(7, "5551234567", 1793000000000L, 1).entry;
        Map<Long, Rules.Who> filed = new HashMap<>(first.decided());
        // Later runs with other ticks, and the old rule with no memory, for comparison.
        Rules.Entry withBoth = new Rules.Texts(both, Collections.singletonMap(7L, pair), filed).sms(7, "5551234567", 1793000000000L, 1).entry;
        Rules.Entry onlyMom = new Rules.Texts(mom, Collections.singletonMap(7L, pair), filed).sms(7, "5559998888", 1793000060000L, 1).entry;
        Rules.Decision none = new Rules.Texts(nobody, Collections.singletonMap(7L, pair), filed).sms(7, "5551234567", 1793000000000L, 1);
        Rules.Entry unremembered = new Rules.Texts(both, Collections.singletonMap(7L, pair), new HashMap<Long, Rules.Who>()).sms(7, "5551234567", 1793000000000L, 1).entry;
        System.out.println("filing-chat " + one.contact + " | " + one.number + " ; " + withBoth.contact + " | " + withBoth.number
            + " ; " + onlyMom.contact + " | " + onlyMom.number + " ; " + none.skipped + " ; " + unremembered.contact + " | " + unremembered.number
            + " ; same-id " + Ledger.entryId(one.kind, one.number, one.epoch, one.direction).equals(
                Ledger.entryId(withBoth.kind, withBoth.number, withBoth.epoch, withBoth.direction)));
        // A group first filed with both ticked stays the group with Dana alone ticked.
        Rules.Texts group = new Rules.Texts(both, Collections.singletonMap(8L, pair), new HashMap<Long, Rules.Who>());
        Rules.Entry g1 = group.sms(8, "5551234567", 1793000000000L, 1).entry;
        Rules.Entry g2 = new Rules.Texts(dana, Collections.singletonMap(8L, pair), new HashMap<>(group.decided())).sms(8, "5551234567", 1793000000000L, 1).entry;
        System.out.println("filing-group " + g1.contact + " | " + g1.number + " ; " + g2.contact + " | " + g2.number);
        // Only first filings are handed back to be remembered.
        System.out.println("filing-decided " + first.decided().size() + " " + new Rules.Texts(both, Collections.singletonMap(7L, pair), filed).decided().size());
    }

    /** A contact given a new key by Android keeps its person; a new contact with an unrelated number does not. */
    private static void moved() {
        List<Rules.Person> known = Arrays.asList(person("old-dana", "Dana-Work", true, false, "+1 (555) 123-4567"),
            person("sam", "Sam - Work", true, true, "5552223333"), person("gone", "Gone-Work", false, false, "5554445555"));
        List<Rules.Person> phone = Arrays.asList(person("new-dana", "Dana-Work", false, true, "555-123-4567", "5550001111"),
            person("sam", "Sam - Work", false, true, "5552223333"), person("stranger", "Stranger", false, true, "5556667777"),
            person("also-sam", "Sam work", false, true, "5552223333"));
        System.out.println("moved " + new TreeMap<>(Rules.movedKeys(known, phone)) + " " + Rules.movedKeys(known, null) + " "
            + Rules.movedKeys(known, new ArrayList<Rules.Person>()));
    }

    private static void search() {
        StringBuilder out = new StringBuilder();
        for (String query : new String[] {"zz9", "555", "(555) 123-4567", "+1 555.123", "Dana", "9", "", "  ", "-", "Sam 2"}) {
            out.append(out.length() > 0 ? " | " : "").append(Rules.searchDigits(query));
        }
        System.out.println("search " + out);
    }

    /**
     * Every day from 2024 to 2029 falls in exactly one pay period, for a few starts and lengths: the period index() gives
     * holds the day, the next period starts the day after this one ends, and no other period holds it.
     */
    private static void payPeriods() {
        String[][] cases = {{"2026-10-25", "14"}, {"2026-11-08", "14"}, {"2028-02-29", "14"}, {"2026-12-31", "7"}, {"2025-01-01", "1"}, {"2026-10-25", "30"}};
        int checked = 0, wrong = 0;
        for (String[] c : cases) {
            PayPeriod p = PayPeriod.of(c[0], c[1]);
            for (java.time.LocalDate d = java.time.LocalDate.of(2024, 1, 1); d.isBefore(java.time.LocalDate.of(2030, 1, 1)); d = d.plusDays(1)) {
                long k = p.index(d);
                boolean inside = !d.isBefore(p.first(k)) && !d.isAfter(p.last(k));
                boolean neighbours = d.isAfter(p.last(k - 1)) && d.isBefore(p.first(k + 1));
                boolean joined = p.last(k).plusDays(1).equals(p.first(k + 1)) && p.last(k).equals(p.first(k).plusDays(p.days - 1));
                checked++;
                if (!(inside && neighbours && joined)) wrong++;
            }
        }
        System.out.println("pay-check " + checked + " " + wrong);
        PayPeriod p = PayPeriod.of("2026-10-25", "14");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 4);
        long k = p.index(today);
        System.out.println("pay-now " + k + " " + p.first(k) + " " + p.last(k) + " | " + p.label(k, today));
        System.out.println("pay-next " + p.label(k + 1, today) + " | " + p.label(k + 5, today) + " | " + p.label(-30, today));
        System.out.println("pay-before " + p.index(java.time.LocalDate.of(2026, 10, 24)) + " " + p.first(-1) + " " + p.last(-1));
        System.out.println("pay-current " + p.current(today) + " | " + PayPeriod.of("2026-11-08", "14").current(today) + " | "
            + PayPeriod.of("2026-10-24", "14").current(today) + " | " + PayPeriod.of("2026-12-27", "14").current(java.time.LocalDate.of(2027, 1, 2)));
        System.out.println("pay-span " + p.span(today) + " | " + PayPeriod.of("2026-12-28", "14").span(today) + " | "
            + PayPeriod.of("2026-11-28", "1").span(today) + " | " + PayPeriod.of("2026-10-25", "10").describe(today));
        System.out.println("pay-describe " + p.describe(today) + " | " + PayPeriod.of("2027-01-03", "7").describe(today));
        System.out.println("pay-bad " + PayPeriod.of(null, "14") + " " + PayPeriod.of("2026-13-01", "14") + " " + PayPeriod.of("2026-10-25", "0")
            + " " + PayPeriod.of("2026-10-25", "x"));
    }

    private static void words() {
        System.out.println("words-clock " + Words.clockTime(1026) + " " + Words.clockTime(4097) + " " + Words.clockTime(59) + " " + Words.clockTime(0)
            + " " + Words.clockTime(36000));
        System.out.println("words-full " + Words.fullTime(1183) + " | " + Words.fullTime(4179) + " | " + Words.fullTime(47) + " | " + Words.fullTime(3600));
        System.out.println("words-counts " + Words.counts(5, 9) + " | " + Words.counts(1, 1));
        System.out.println("words-at " + Words.clock("2026-11-03 00:05:09") + " " + Words.clock("2026-11-03 12:30:00") + " " + Words.clock("2026-11-03 15:08:00"));
        System.out.println("words-day " + Words.longDay(java.time.LocalDate.of(2026, 11, 3)) + " | "
            + Words.weekdayDay(java.time.LocalDate.of(2026, 10, 25), java.time.LocalDate.of(2026, 11, 4)) + " | "
            + Words.weekdayDay(java.time.LocalDate.of(2027, 1, 3), java.time.LocalDate.of(2026, 11, 4)));
        System.out.println("words-status " + Words.status("call", "out", "2026-11-03 15:08:00", 47) + " | "
            + Words.status("call", "out", "2026-11-03 15:08:00", 0) + " | " + Words.status("call", "missed", "2026-11-03 15:08:00", 7) + " | "
            + Words.status("call", "rejected", "2026-11-03 15:08:00", 3) + " | " + Words.status("text", "in", "2026-11-03 16:16:00", 0) + " | "
            + Words.status("text", "out", "2026-11-03 16:16:00", 0) + " | " + Words.status("call", "in", "2026-11-03 23:50:00", 1200) + " | "
            + Words.status("call", "in", "2026-12-31 23:00:00", 90000));
        long now = 1793000000000L;
        System.out.println("words-updated " + Words.updated(0, now) + " | " + Words.updated(now - 20000, now) + " | " + Words.updated(now - 4 * 60000, now)
            + " | " + Words.updated(now - 181 * 60000L, now) + " | " + Words.updated(now - 3 * 86400000L, now)
            + " | " + Words.staleness(now - 180 * 60000L, now) + Words.staleness(now - 181 * 60000L, now) + Words.staleness(now - 1440 * 60000L, now)
            + Words.staleness(0, now));
    }

    /**
     * The calls and texts the old Termux script was tested with, as the app reads them now that ticks decide: the call log
     * and SMS table give times in milliseconds. Dana and Sam are ticked; Mom and Work Plumbing are on the phone, not ticked.
     * The test pins the entry ids the Termux script produced for these: the id rule never changed.
     */
    private static void fixtures() {
        List<Rules.Person> people = Arrays.asList(
            person("dana", "Dana-Work", true, true, "+1 (555) 123-4567", "5550001111"), person("sam", "Sam - Work", true, true, "555-222-3333"),
            person("mom", "Mom", false, true, "5559998888"), person("plumb", "Work Plumbing", false, true, "5557776666"));
        Map<String, String> work = Rules.countingNumbers(people);
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
        System.out.println("fixture-body " + body(entries));
        System.out.println("fixture-ids " + ids(entries));
        List<Rules.Entry> group = new ArrayList<>();
        keep(group, texts.sms(4, "+15551234567", 1793836800000L, Rules.BOX_RECEIVED));
        System.out.println("fixture-group " + body(group));
        System.out.println("fixture-group-ids " + ids(group));
        System.out.println("fixture-groups " + texts.groups());
        // Bob unticked: his number no longer counts, so nothing of his is found.
        Map<String, String> unticked = Rules.countingNumbers(Collections.singletonList(person("bob", "Bob-Work", false, true, "5554443333")));
        show("unticked", Rules.classify(6, Arrays.asList("5554443333"), unticked));
        System.out.println("unticked-call " + skipped(Rules.call(unticked, "5554443333", 1793000000000L, Rules.CALL_INCOMING, 60)));
        System.out.println("unticked-text " + skipped(new Rules.Texts(unticked, new HashMap<Long, List<String>>())
            .sms(6, "5554443333", 1793000000000L, Rules.BOX_RECEIVED)));
    }

    /** Which rows are left out and why, the same-second rule, and how conversations get their people. */
    private static void decisions() {
        Map<String, String> work = Rules.countingNumbers(Arrays.asList(person("d", "Dana-Work", true, true, "555-123-4567"),
            person("m", "Mom", false, true, "5559998888")));
        long ms = 1793000000000L;
        StringBuilder calls = new StringBuilder();
        for (int type = 1; type <= 7; type++) calls.append(type).append('=').append(skipped(Rules.call(work, "5551234567", ms, type, 9))).append(' ');
        System.out.println("call-kinds " + calls.toString().trim());
        System.out.println("call-personal " + skipped(Rules.call(work, "5559998888", ms, Rules.CALL_INCOMING, 9)));
        System.out.println("call-missed " + json(Rules.call(work, "5551234567", ms + 999, Rules.CALL_MISSED, 7).entry));

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
        System.out.println("mms-millis " + json(texts.mms(2, 1793000000999L, Rules.BOX_SENT, 128).entry));
        System.out.println("groups " + texts.groups());

        // Two messages in one conversation, same direction, same second: the same fields, so the record keeps one.
        Rules.Entry a = texts.sms(1, "5551234567", ms + 100, Rules.BOX_RECEIVED).entry;
        Rules.Entry b = texts.sms(1, "5551234567", ms + 700, Rules.BOX_RECEIVED).entry;
        System.out.println("same-second " + json(a).equals(json(b)));
        Memory book = new Memory();
        Ledger.Added added = Ledger.add(book, Arrays.asList(a, b, new Rules.Entry("call", 0, "Dana-Work", "5551234567", "in", 1)));
        System.out.println("same-second-added " + added.added + " " + added.repeats + " " + added.unusable);

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
        rcs();
    }

    /**
     * RCS chats: the conversation list names them by the chat's id, so they are left out of it and their messages' address
     * rows name their people. Own is the phone's own number, which the address rows hold and the list leaves out.
     */
    private static void rcs() {
        Map<String, String> work = Rules.countingNumbers(Arrays.asList(
            person("a", "Dana-Work", true, true, "5551234567"), person("b", "Sam - Work", true, true, "5552223333")));
        String id = "d4vtcmrqheztgmjwge4tah3emi2dondfga4dmzdgmi2gizbumfrtoodcmy3gmyrtgfswintgmm======@rcs.google.com";
        String own = "+12065550111";
        Map<String, String> canonical = new HashMap<>();
        canonical.put("1", "+15551234567");
        canonical.put("2", "5559998888");
        canonical.put("5", id);
        canonical.put("6", "someone@example.com");
        canonical.put("7", "");
        Map<Long, String> recipients = new HashMap<>();
        recipients.put(10L, "1");
        recipients.put(11L, "1 2");
        recipients.put(919L, "5");
        recipients.put(920L, "1 5");
        recipients.put(921L, "6");
        recipients.put(922L, "7");
        Map<Long, List<String>> listed = Rules.threadPeople(canonical, recipients);
        System.out.println("rcs-thread-people " + new TreeMap<>(listed));
        System.out.println("rcs-phone " + Rules.phoneAddress("+1 (555) 123-4567") + Rules.phoneAddress(id) + Rules.phoneAddress("someone@example.com")
            + Rules.phoneAddress("") + Rules.phoneAddress(null) + Rules.phoneAddress("Verizon"));

        // Address rows shaped like chat 919 on the user's phone: cc, cc, cc, from, to; then a one-to-one RCS chat.
        final Map<Long, List<String>> rows = new HashMap<>();
        rows.put(1L, Arrays.asList("+15551234567", "5552223333", "5557776666", "+15551234567", own));
        rows.put(2L, Arrays.asList("+15551234567", own));
        rows.put(3L, Arrays.asList(own, "+15551234567"));
        rows.put(4L, Arrays.asList(own));
        rows.put(5L, new ArrayList<String>());
        rows.put(6L, Arrays.asList("5559998888", own));
        final List<Long> asked = new ArrayList<>();
        Rules.Addresses addresses = new Rules.Addresses() {
            @Override
            public List<String> on(long messageId) {
                asked.add(messageId);
                return rows.get(messageId);
            }
        };
        List<long[]> messages = new ArrayList<>();
        for (long[] message : new long[][] {{1, 919}, {9, 919}, {2, 930}, {3, 930}, {4, 931}, {6, 931}, {5, 932}, {7, 10}}) messages.add(message);
        Map<Long, List<String>> people = new TreeMap<>(listed);
        for (Map.Entry<Long, List<Long>> entry : new TreeMap<>(Rules.fallbackMessages(messages, listed.keySet())).entrySet()) {
            people.put(entry.getKey(), Rules.peopleFromMessages(entry.getValue(), addresses));
        }
        System.out.println("rcs-people " + people);
        System.out.println("rcs-asked " + asked);
        Rules.Texts texts = new Rules.Texts(work, people);
        long s = 1793000000L;
        StringBuilder out = new StringBuilder();
        out.append(entryText(texts.mms(919, s, Rules.BOX_RECEIVED, 132))).append(" | ");
        out.append(entryText(texts.mms(919, s + 1, Rules.BOX_SENT, 128))).append(" | ");
        out.append(entryText(texts.mms(930, s + 2, Rules.BOX_RECEIVED, 132))).append(" | ");
        out.append(entryText(texts.sms(930, "+15551234567", (s + 3) * 1000, Rules.BOX_RECEIVED))).append(" | ");
        out.append(entryText(texts.sms(932, "5551234567", (s + 4) * 1000, Rules.BOX_RECEIVED))).append(" | ");
        out.append(entryText(texts.sms(933, "5551234567", (s + 5) * 1000, Rules.BOX_RECEIVED))).append(" | ");
        out.append(entryText(texts.mms(931, s + 6, Rules.BOX_RECEIVED, 132))).append(" | ");
        out.append(entryText(texts.sms(10, "5551234567", (s + 7) * 1000, Rules.BOX_RECEIVED))).append(" | ");
        out.append(entryText(texts.mms(11, s + 8, Rules.BOX_RECEIVED, 132)));
        System.out.println("rcs-texts " + out);
        System.out.println("rcs-decided " + new TreeMap<>(texts.decided()).keySet() + " groups " + texts.groups());

        // The Phone check's chat line names why the chat was left out.
        Map<String, String> counting = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567")));
        System.out.println("rcs-ids " + PhoneCheck.ids("5", canonical) + " | " + PhoneCheck.leftOut("5", canonical) + PhoneCheck.leftOut("1 2", canonical)
            + PhoneCheck.leftOut("1 3", canonical) + " | " + PhoneCheck.ids("1 2 3", canonical));
        System.out.println("rcs-chat " + PhoneCheck.chatRow(919, null, people.get(919L), PhoneCheck.ids("5", canonical), PhoneCheck.leftOut("5", canonical),
            2, 0, "Group: Dana-Work +3", counting, Collections.<String, String>emptyMap(), java.time.ZoneId.of("America/Los_Angeles")));
    }

    private static String entryText(Rules.Decision decision) {
        return decision.entry == null ? "skip:" + decision.skipped : decision.entry.contact + " / " + decision.entry.number + " / " + decision.entry.direction;
    }

    /** The people table: which numbers count, and what a read of the phone's contacts changes. */
    private static void people() {
        List<Rules.Person> known = Arrays.asList(
            person("dana", "Dana-Work", true, true, "(555) 123-4567", "+1 555 000 1111"),
            person("gone", "Old Name", true, false, "5554443333"),
            person("lee", "Lee", false, true, "555-777-0000"),
            person("noname", "", true, true, "no digits", "15556667777"));
        System.out.println("counting " + new TreeMap<>(Rules.countingNumbers(known)));
        // A number shared by a person on the phone and one who left: the one on the phone names it.
        System.out.println("counting-shared " + new TreeMap<>(Rules.countingNumbers(Arrays.asList(
            person("a", "On Phone", true, true, "5551112222"), person("b", "Left", true, false, "5551112222")))));
        System.out.println("refresh-null " + people(Rules.refreshPeople(known, null)));
        System.out.println("refresh-empty " + people(Rules.refreshPeople(known, new ArrayList<Rules.Person>())));
        // Dana renamed with a new number and one number gone from the contact; Lee is gone; Old Name is back.
        List<Rules.Person> phone = Arrays.asList(
            person("dana", "Dana New-Work", false, true, "5551234567", "555-888-9999"),
            person("gone", "", false, true, "5554443333"),
            person("other", "Someone", false, true, "5550000000"));
        System.out.println("refresh " + people(Rules.refreshPeople(known, phone)));
        System.out.println("join " + Rules.joinNumbers(Arrays.asList("+1 555 123 4567", "none"), Arrays.asList("5551234567", " 555 000 1111 ")));
    }

    /**
     * The id rule and the repeat rule on fixed rows, added one at a time to a record kept in memory. The test adds the same
     * rows to tests/reference.py's Store and compares the ids, the decisions and the rows left.
     */
    private static void ledger() {
        List<Rules.Entry> rows = Arrays.asList(
            new Rules.Entry("call", 1793000000L, "2026-10-26 01:53:20", "Dana-Work", "+1 (555) 123-4567", "in", 60),
            // Same moment, number written another way, new name: a repeat that renames.
            new Rules.Entry("call", 1793000000L, "2026-10-26 01:53:20", " Dana Renamed-Work ", "5551234567", "in", 60),
            // Same clock time, another moment: a repeat (a run that read the zone wrongly).
            new Rules.Entry("call", 1793003600L, "2026-10-26 01:53:20", "Dana-Work", "15551234567", "in", 60),
            // Same moment, another clock time: a repeat by id (a change of time zone), keeping the first day.
            new Rules.Entry("call", 1793000000L, "2026-10-27 04:53:20", "Dana-Work", "5551234567", "in", 60),
            new Rules.Entry("call", 1793000000L, "2026-10-26 01:53:20", "Dana-Work", "5551234567", "out", 60),
            new Rules.Entry("text", 1793000010L, "2026-10-26 01:53:30", "Dana-Work", "5551234567", "in", 99),
            new Rules.Entry("text", 1793000010L, "2026-10-26 01:53:30", "Group: Dana-Work +2", "thread-1234567890", "in", 0),
            new Rules.Entry("text", 1793000010L, "2026-10-26 01:53:30", "Short", "1234567890", "in", 0),
            new Rules.Entry("text", 1793000020L, "2026-10-26 01:54:20", "Odd", " Unknown ", "in", 0),
            new Rules.Entry("text", 1793000030L, "2026-10-26 01:54:20", "Odd", "unknown", "in", 0),
            new Rules.Entry("call", 1793000040L, "2026-10-26 01:54:40", "Wide", "١٢٣٤٥٦٧٨٩٠١", "out", 5),
            new Rules.Entry("call", 0L, "1969-12-31 16:00:00", "Bad", "5551234567", "in", 1));
        Memory book = new Memory();
        StringBuilder steps = new StringBuilder("[");
        for (Rules.Entry entry : rows) {
            Ledger.Added added = Ledger.add(book, Collections.singletonList(entry));
            if (steps.length() > 1) steps.append(',');
            steps.append(quote(Ledger.entryId(entry.kind, entry.number, entry.epoch, entry.direction))).append(',')
                .append(quote(added.added == 1 ? "added" : added.repeats == 1 ? "repeat" : "unusable"));
        }
        System.out.println("ledger-in " + body(rows));
        System.out.println("ledger-steps " + steps.append(']'));
        StringBuilder kept = new StringBuilder("[");
        for (Ledger.Row row : book.rows) {
            if (kept.length() > 1) kept.append(',');
            kept.append("{\"id\":").append(quote(row.id)).append(",\"kind\":").append(quote(row.kind)).append(",\"epoch\":").append(row.epoch)
                .append(",\"at\":").append(quote(row.at)).append(",\"day\":").append(quote(row.day)).append(",\"contact\":").append(quote(row.contact))
                .append(",\"number\":").append(quote(row.number)).append(",\"number_key\":").append(quote(row.numberKey))
                .append(",\"direction\":").append(quote(row.direction)).append(",\"duration_s\":").append(row.durationS).append('}');
        }
        System.out.println("ledger-rows " + kept.append(']'));
        StringBuilder keys = new StringBuilder("[");
        for (String number : new String[] {"thread-12", "+1 (555) 123-4567", "  Unknown  ", "", " Private ", "١٢٣"}) {
            if (keys.length() > 1) keys.append(',');
            keys.append('[').append(quote(number)).append(',').append(quote(Ledger.numberKey(number))).append(']');
        }
        System.out.println("number-keys " + keys.append(']'));
    }

    private static void statements() {
        StringBuilder schema = new StringBuilder("[");
        for (String statement : Sql.SCHEMA) schema.append(schema.length() > 1 ? "," : "").append(quote(statement));
        System.out.println("sql-schema " + schema.append(']'));
        System.out.println("sql-find " + Sql.FIND);
        System.out.println("sql-days " + Sql.DAYS);
        System.out.println("sql-day " + Sql.DAY);
        System.out.println("sql-totals-all " + Sql.TOTALS_ALL);
        System.out.println("sql-totals-range " + Sql.TOTALS_RANGE);
        System.out.println("sql-contacts-all " + Sql.CONTACTS_ALL);
        System.out.println("sql-contacts-range " + Sql.CONTACTS_RANGE);
        System.out.println("sql-earliest " + Sql.EARLIEST);
    }

    /** A record kept in a list, with the repeat lookup the record's FIND statement makes. */
    private static final class Memory implements Ledger.Book {
        final List<Ledger.Row> rows = new ArrayList<>();
        final Map<String, String> renamed = new HashMap<>();

        @Override
        public String find(Ledger.Row row) {
            for (Ledger.Row kept : rows) {
                if (kept.id.equals(row.id) || (kept.kind.equals(row.kind) && kept.numberKey.equals(row.numberKey)
                    && kept.at.equals(row.at) && kept.direction.equals(row.direction))) return kept.id;
            }
            return null;
        }

        @Override
        public void insert(Ledger.Row row) {
            rows.add(row);
        }

        @Override
        public void rename(String id, String contact) {
            for (int i = 0; i < rows.size(); i++) {
                Ledger.Row kept = rows.get(i);
                if (!kept.id.equals(id)) continue;
                rows.set(i, new Ledger.Row(new Rules.Entry(kept.kind, kept.epoch, kept.at, contact, kept.number, kept.direction, kept.durationS)));
            }
        }
    }

    private static Rules.Person person(String key, String name, boolean ticked, boolean onPhone, String... numbers) {
        return new Rules.Person(key, name, Arrays.asList(numbers), ticked, onPhone);
    }

    private static String people(List<Rules.Person> people) {
        StringBuilder out = new StringBuilder();
        for (Rules.Person p : people) {
            out.append(p.key).append('=').append(p.name).append(p.numbers.toString().replace(", ", ";"))
                .append(p.ticked ? "+ticked" : "+unticked").append(p.onPhone ? "+on" : "+off").append(' ');
        }
        return out.toString().trim();
    }

    private static String ids(List<Rules.Entry> entries) {
        StringBuilder out = new StringBuilder("[");
        for (Rules.Entry e : entries) {
            if (out.length() > 1) out.append(',');
            out.append(quote(Ledger.entryId(e.kind, e.number, e.epoch, e.direction)));
        }
        return out.append(']').toString();
    }

    private static String json(Rules.Entry e) {
        return "{\"kind\":" + quote(e.kind) + ",\"at\":" + quote(e.at) + ",\"epoch\":" + e.epoch + ",\"contact\":" + quote(e.contact)
            + ",\"number\":" + quote(e.number) + ",\"direction\":" + quote(e.direction) + ",\"duration_s\":" + e.duration + "}";
    }

    private static String body(List<Rules.Entry> entries) {
        StringBuilder out = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            out.append(json(entries.get(i)));
        }
        return out.append("]}").toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) out.append(String.format(Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    /** The Phone check screen's rows, from plain values: a chat, a chat the app leaves out, a text row and a picture row. */
    private static void phoneCheck() {
        java.time.ZoneId zone = java.time.ZoneId.of("America/Los_Angeles");
        Map<String, String> counting = Rules.countingNumbers(Arrays.asList(person("a", "Dana-Work", true, true, "5551234567")));
        Map<String, String> unticked = Collections.singletonMap("5559998888", "Mom");
        long millis = 1793018700000L;
        Map<String, String> canonical = new HashMap<>();
        canonical.put("3", "+15551234567");
        canonical.put("4", "5559998888");
        System.out.println("phone-when " + PhoneCheck.when(millis, zone) + " | " + PhoneCheck.when(1793043000000L, zone));
        System.out.println("phone-top " + PhoneCheck.topLine("15", null, "com.google.android.apps.messaging"));
        System.out.println("phone-counts " + PhoneCheck.countsLine(12, 1, -1) + " | " + PhoneCheck.listLine(false));
        System.out.println("phone-own " + PhoneCheck.ownLine(new ArrayList<String>()) + " | "
            + PhoneCheck.ownLine(Arrays.asList("5550009999", "2065550199")));
        System.out.println("phone-chat " + PhoneCheck.chatRow(1003, Arrays.asList("+15551234567", "5559998888", "5557776666"), null,
            PhoneCheck.ids("3 4 5", canonical), PhoneCheck.leftOut("3 4 5", canonical), 1, millis, "Group: Dana-Work +2", counting, unticked, zone));
        System.out.println("phone-unknown " + PhoneCheck.chatRow(1004, null, Collections.<String>emptyList(), null, false, 0, 0, null,
            counting, unticked, zone));
        Rules.Texts texts = new Rules.Texts(counting, Collections.singletonMap(1005L, Collections.<String>emptyList()));
        Rules.Decision text = texts.sms(1002, "+15551234567", millis, 1);
        System.out.println("phone-text " + PhoneCheck.textRow(millis, 1, 1002, "+15551234567", null, "protocol 0",
            PhoneCheck.decision(text, PhoneCheck.branch(true, null, false, false), false), zone));
        Rules.Decision picture = texts.mms(1005, millis / 1000, 1, 132);
        System.out.println("phone-picture " + PhoneCheck.pictureRow(millis, 1, 132, 1005, "", 0, "com.google.android.apps.messaging",
            PhoneCheck.decision(picture, PhoneCheck.branch(false, Collections.<String>emptyList(), false, false), true), zone));
        System.out.println("phone-problem " + PhoneCheck.problem("Text rows", "IllegalArgumentException", "Invalid column\n creator") + " | "
            + PhoneCheck.problem("The conversation list", "NullPointerException", null));
        System.out.println("phone-other " + PhoneCheck.textRow(millis, 3, 1002, "5557776666", null, "",
            PhoneCheck.decision(texts.sms(1002, "5557776666", millis, 3), null, false), zone) + " | "
            + PhoneCheck.addresses(Arrays.asList("+15551234567", "insert-address-token"), Arrays.asList(137, 151)) + " | "
            + PhoneCheck.branch(false, Arrays.asList("a", "b"), true, false) + " | " + PhoneCheck.branch(false, Arrays.asList("a", "b"), true, true)
            + " | " + PhoneCheck.branch(false, null, false, false));
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
