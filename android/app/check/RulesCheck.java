package com.calltally.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    }

    /**
     * The calls and texts the old Termux script was tested with, as the app reads them: the call log and SMS table give
     * times in milliseconds, and the contact list gives every number on a contact. The test pins the entry ids the Termux
     * script produced for these, so rows it uploaded are never added twice when the app sends them again.
     */
    private static void termuxFixtures() {
        Map<String, String> work = workFrom(
            "Dana-Work", "+1 (555) 123-4567", "Dana-Work", "5550001111", "Sam - Work", "555-222-3333",
            "Mom", "5559998888", "Work Plumbing", "5557776666");
        System.out.println("fixture-work " + new java.util.TreeMap<>(work));
        List<Rules.Entry> entries = new ArrayList<>();
        call(entries, work, "15551234567", 1793812320000L, "in", 872);
        call(entries, work, "5552223333", 1793826300000L, "out", 3968);
        call(entries, work, "5551234567", 1793833200000L, "missed", 0);
        call(entries, work, "5559998888", 1793840400000L, "in", 1800);
        call(entries, work, "5557776666", 1793842200000L, "out", 180);
        call(entries, work, "5550001111", 1793728800000L, "out", 120);
        text(entries, 1, Arrays.asList("+15551234567"), work, 1793814000000L, "in");
        text(entries, 1, Arrays.asList("+15551234567"), work, 1793814060000L, "out");
        text(entries, 2, Arrays.asList("5559998888"), work, 1793822400000L, "in");
        text(entries, 3, Arrays.asList("5550001111"), work, 1793729100000L, "out");
        // A message from Dana in a chat with one other person, and the same moment in a group of three others.
        text(entries, 5, Arrays.asList("+15551234567", "5559998888"), work, 1793836800000L, "in");
        System.out.println("fixture-body " + Rules.body(entries));
        List<Rules.Entry> group = new ArrayList<>();
        text(group, 4, Arrays.asList("+15551234567", "5559998888", "5557776666"), work, 1793836800000L, "in");
        System.out.println("fixture-group " + Rules.body(group));
        // Bob-Work renamed to Bob: his number is no longer a work number, so nothing of his is found.
        Map<String, String> renamed = workFrom("Bob", "5554443333");
        show("renamed", Rules.classify(6, Arrays.asList("5554443333"), renamed));
    }

    /** As the app reads the contact list: only names ending in -Work, every number on them, matched by the last ten digits. */
    private static Map<String, String> workFrom(String... nameThenNumber) {
        Map<String, String> work = new HashMap<>();
        for (int i = 0; i < nameThenNumber.length; i += 2) {
            String key = Rules.numberKey(nameThenNumber[i + 1]);
            if (!key.isEmpty() && Rules.isWork(nameThenNumber[i])) work.put(key, nameThenNumber[i].trim());
        }
        return work;
    }

    private static void call(List<Rules.Entry> out, Map<String, String> work, String number, long millis, String direction, long seconds) {
        String name = work.get(Rules.numberKey(number));
        if (name != null) out.add(new Rules.Entry("call", millis / 1000L, name, number, direction, seconds));
    }

    private static void text(List<Rules.Entry> out, long thread, List<String> people, Map<String, String> work, long millis, String direction) {
        Rules.Who who = Rules.classify(thread, people, work);
        if (who != null) out.add(new Rules.Entry("text", Rules.toSeconds(millis, false), who.contact, who.number, direction, 0));
    }

    private static void show(String label, Rules.Who who) {
        System.out.println(label + " " + (who == null ? "none" : who.contact + " | " + who.number));
    }
}
