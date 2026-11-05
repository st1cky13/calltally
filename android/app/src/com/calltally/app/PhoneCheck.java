package com.calltally.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The words of the Phone check screen, one line per row. Plain Java with no Android parts, so the tests can check them.
 * The screen reads the phone and hands plain values here; nothing here reads a message's words.
 */
final class PhoneCheck {
    // Android's address types on a picture or group message.
    static final int ADDR_FROM = 137;
    static final int ADDR_TO = 151;
    static final int ADDR_CC = 130;
    static final int ADDR_BCC = 129;

    static final String NOTE = "Numbers only. The app never reads what a message says.";

    private PhoneCheck() {}

    /** One line of the screen: a section's heading, a row, or a muted note. */
    static final class Line {
        static final int HEADING = 0;
        static final int ROW = 1;
        static final int NOTE = 2;
        final int kind;
        final String text;

        Line(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /** "Nov 5, 4:45 AM" in the given zone. */
    static String when(long millis, ZoneId zone) {
        ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
        int h = at.getHour();
        return Words.monthName(at.getMonthValue()).substring(0, 3) + " " + at.getDayOfMonth() + ", " + (h % 12 == 0 ? 12 : h % 12) + ":"
            + (at.getMinute() < 10 ? "0" : "") + at.getMinute() + (h < 12 ? " AM" : " PM");
    }

    static String topLine(String android, String messages, String defaultApp) {
        return "Android " + android + ", Google Messages " + (messages == null ? "not installed" : messages)
            + ", default messaging app " + (defaultApp == null || defaultApp.isEmpty() ? "none" : defaultApp);
    }

    /** Counts of the conversation list, the text table and the picture and group table; -1 when one couldn't be read. */
    static String countsLine(int chats, int texts, int pictures) {
        return (chats < 0 ? "chats on the phone couldn't be counted" : Words.plural(chats, "chat") + " on the phone") + ", "
            + (texts < 0 ? "text rows couldn't be read" : Words.plural(texts, "text row")) + ", "
            + (pictures < 0 ? "picture and group rows couldn't be read" : Words.plural(pictures, "picture and group row"));
    }

    static String listLine(boolean readable) {
        return "Conversation list: " + (readable ? "readable" : "not readable");
    }

    /**
     * A number as the app would count it: the ticked person's name when it is a counting number, else the unticked
     * person's name from the people table, else the address as stored.
     */
    static String person(String address, Map<String, String> counting, Map<String, String> unticked) {
        String key = Rules.numberKey(address);
        if (!key.isEmpty() && counting.containsKey(key)) return counting.get(key);
        if (!key.isEmpty() && unticked.containsKey(key)) return unticked.get(key);
        return address == null || address.isEmpty() ? "no address" : address;
    }

    static String people(List<String> addresses, Map<String, String> counting, Map<String, String> unticked) {
        if (addresses == null || addresses.isEmpty()) return "nobody";
        List<String> names = new ArrayList<>();
        for (String address : addresses) names.add(person(address, counting, unticked));
        return String.join(", ", names);
    }

    /**
     * One chat. listed: the conversation list's addresses, or null when the app has no entry for the chat there;
     * fromMessages: what its messages name then. ids: the conversation list's ids for it with what each stands for, or
     * null when the list has no row for it; leftOut: the list has a row but the app left it out. filed: how the app files
     * it, or null when it counts nothing. newest is in milliseconds, or 0.
     */
    static String chatRow(long thread, List<String> listed, List<String> fromMessages, String ids, boolean leftOut, int count, long newest,
                          String filed, Map<String, String> counting, Map<String, String> unticked, ZoneId zone) {
        String who = listed != null ? people(listed, counting, unticked)
            : "people unknown to the conversation list, its messages name "
                + (fromMessages == null || fromMessages.isEmpty() ? "nobody" : people(fromMessages, counting, unticked));
        return "chat " + thread + " · " + who + " · " + (ids == null ? "no row in the conversation list" : "ids " + ids)
            + (leftOut ? " · left out of the conversation list by the app" : "") + " · " + Words.plural(count, "message")
            + (newest > 0 ? " · newest " + when(newest, zone) : "") + " · filed as " + (filed == null ? "not counted" : filed);
    }

    /**
     * The conversation list's ids for a chat, each with the address it stands for: "3 +12065550122, 7 no address". The
     * app leaves a chat out of the list when any id has no address.
     */
    static String ids(String recipientIds, Map<String, String> addresses) {
        if (recipientIds == null || recipientIds.trim().isEmpty()) return "none";
        List<String> parts = new ArrayList<>();
        for (String id : recipientIds.trim().split("\\s+")) {
            String address = addresses.get(id);
            parts.add(id + " " + (address == null ? "no address" : address));
        }
        return String.join(", ", parts);
    }

    /** True when the list has ids for the chat but the app can't use them all, as Rules.threadPeople decides. */
    static boolean leftOut(String recipientIds, Map<String, String> addresses) {
        if (recipientIds == null || recipientIds.trim().isEmpty()) return false;
        for (String id : recipientIds.trim().split("\\s+")) if (!addresses.containsKey(id)) return true;
        return false;
    }

    static String box(int box) {
        if (box == Rules.BOX_RECEIVED) return "received";
        if (box == Rules.BOX_SENT) return "sent";
        return "other [" + box + "]";
    }

    static String addressType(int type) {
        switch (type) {
            case ADDR_FROM: return "from";
            case ADDR_TO: return "to";
            case ADDR_CC: return "cc";
            case ADDR_BCC: return "bcc";
            default: return "type [" + type + "]";
        }
    }

    /**
     * Which of the app's ways decided a row. text: a row of the text table. people: who the app has for its chat, or null;
     * fromList: they came from the conversation list rather than the chat's messages; remembered: the record kept a filing
     * for the chat and the row counted under it. Null when no way was reached, as for a picture row whose people are unknown.
     */
    static String branch(boolean text, List<String> people, boolean fromList, boolean remembered) {
        if (text && (people == null || people.size() <= 1)) return "by the row's address";
        if (people == null) return null;
        if (remembered) return "by the remembered filing";
        return fromList ? "by the conversation list" : "by the messages' addresses";
    }

    /**
     * What the app does with a row and which way decided it: "counts under Dana-Work, by the row's address", or why it is
     * skipped. noPeople: a picture row whose chat the app knows with nobody in it.
     */
    static String decision(Rules.Decision decision, String branch, boolean noPeople) {
        if (decision == null) return "skipped: the app couldn't decide";
        if (Rules.SKIP_BOX.equals(decision.skipped) || Rules.SKIP_NOT_MESSAGE.equals(decision.skipped)) return "skipped: " + decision.skipped;
        String what = decision.entry != null ? "counts under " + decision.entry.contact
            : noPeople ? "counts nothing: no people found" : "skipped: " + decision.skipped;
        return what + (branch == null ? "" : ", " + branch);
    }

    static String creator(String creator) {
        return creator == null || creator.isEmpty() ? "creator unknown" : "creator " + creator;
    }

    /** One row of the text table. extra: protocol and the like, or empty. */
    static String textRow(long millis, int box, long thread, String address, String creator, String extra, String decision, ZoneId zone) {
        return when(millis, zone) + " · " + box(box) + " · text row · chat " + thread + " · address "
            + (address == null || address.isEmpty() ? "none" : address) + " · " + creator(creator)
            + (extra == null || extra.isEmpty() ? "" : " · " + extra) + " · " + decision;
    }

    /**
     * One row of the picture and group table. addresses: "from +1541…, to +1541…", or null when its addresses couldn't
     * be read; rows: how many address rows it has. messageType is -1 when the phone has no such column.
     */
    static String pictureRow(long millis, int box, int messageType, long thread, String addresses, int rows, String creator, String decision,
                             ZoneId zone) {
        return when(millis, zone) + " · " + box(box) + " · picture or group row" + (messageType >= 0 ? " [" + messageType + "]" : "")
            + " · chat " + thread + " · " + (addresses == null ? "addresses couldn't be read"
                : rows == 0 ? "no addresses on the row" : (rows == 1 ? "1 address" : rows + " addresses") + " on the row: " + addresses)
            + " · " + creator(creator) + " · " + decision;
    }

    /** "from +1541…, to +1541…" from address and type pairs. */
    static String addresses(List<String> addresses, List<Integer> types) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < addresses.size(); i++) parts.add(addressType(types.get(i)) + " " + addresses.get(i));
        return String.join(", ", parts);
    }

    /** The whole screen as plain text, one line per row, for Share. */
    static String shareText(List<Line> lines) {
        StringBuilder out = new StringBuilder("Call Tally phone check\n");
        for (Line line : lines) {
            if (line.kind == Line.HEADING) out.append('\n');
            out.append(line.text).append('\n');
        }
        return out.toString();
    }
}
