package com.calltally.app;

import java.util.Arrays;
import java.util.HashMap;
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
        System.out.println("seconds " + Rules.toSeconds(1793000000999L, false) + " " + Rules.toSeconds(1793000000L, true) + " " + Rules.toSeconds(1793000000999L, true));
        System.out.println("body " + Rules.body(Arrays.asList(
            new Rules.Entry("call", t, "Dana-Work", "5551234567", "in", 60),
            new Rules.Entry("text", t + 1, "Sam \"Q\" - Work", "thread-4", "out", 0))));
        System.out.println("status " + Rules.statusBody("Texts couldn't be read.\nAndroid  returned \"no\" messages. "));
        System.out.println("status-empty " + Rules.statusBody(""));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) longText.append("0123456789");
        System.out.println("status-long " + Rules.statusBody(longText.toString()));
    }

    private static void show(String label, Rules.Who who) {
        System.out.println(label + " " + (who == null ? "none" : who.contact + " | " + who.number));
    }
}
