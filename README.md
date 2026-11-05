# calltally

Call Tally is an Android app that shows your time on calls and your texts with the people you choose, by month, by pay
period and for all time. It keeps its own record on the phone. Nothing leaves the phone: there is no account and
nothing is sent anywhere.

It opens on the current month, with arrows to move between months and >> to come back to this month. Tap the month's
name to pick any month. Each day shows its exact call time and the number of texts. Above the calendar are the month's
totals and the pay period's, below it the totals for all time: call time, calls and texts. Tap a total to see it by
person. Tap a day to see its calls and texts on two tabs, and ignore any entry that shouldn't count. Ignored entries
stay ignored and can be restored. The gear opens Options: Contacts, the pay period start, hiding ignored entries, and
System, Light or Dark with five colour themes.

## The first start

The first time the app opens it says what it does and what it reads, then asks Android for three permissions: the call
log, texts and contacts. Then it lists your contacts so you can tick the people whose calls and texts should count, and
asks the day your current pay period started, on a calendar. That step can be skipped; with no pay period set, its row
stays hidden until you set one in Options. The page can be shown again from Options.

## What counts

- Only the people you tick count. Unticking someone keeps what was already counted, and a person removed from your
  contacts keeps counting under the name the app knew.
- Only calls that connected count toward call time and the number of calls. Missed and declined calls are listed but
  never counted.
- A group message counts if the group includes at least one ticked person, so a mixed group can count personal
  messages. Ignore those in the day view. A chat of you, one ticked person and one other person shows under that
  person's name. A group's "+N" counts the people in it who are not ticked, and your own number is left out once the
  app has seen a picture message you sent.
- Two messages in one conversation, in the same direction, in the same second count as one.
- The app reads when a call or text happened, who it was with, its direction and a call's length. It never reads what a
  text says.
- You can give each text a number of seconds in Options; that text time shows beside the text counts and is kept apart
  from call time.

## Encrypted chats

Some one-to-one chats with end-to-end encryption on are kept so that only the phone's own messaging app can read them,
so they don't show up in Call Tally. The "Catch up encrypted" button in Options reads them: it asks Android to make Call
Tally the phone's messaging app for a moment, which you approve in an Android dialog, reads once, then asks you to switch
back to your previous messaging app, which you approve in another dialog; when that app isn't known it falls back to
Google Messages. Call Tally never sends a message, and while it holds that
role it says so plainly, in Options and on the month screen, with a button to switch back. If a text arrives during that
brief moment it is delivered only to the messaging app, so Call Tally writes it straight into the phone's inbox, unread,
so your messaging app shows it after the switch back. That pass-through is the only time the app touches a message's words,
and it never reads them to count.

## Limits

- The app can only count what Android lets it see. It reads Android's shared call log and SMS and MMS tables. Whether
  RCS chats and group messages are there depends on the phone and its messaging app.
- WhatsApp, Signal and other apps that keep their own messages aren't included.
- Calls the phone has already dropped from its call log can't be recovered, and texts only go back as far as the phone
  keeps them.
- A group is known by its conversation on the phone. After moving to a new phone or restoring messages, its history may
  come in again as a new group.
- In this version the record is on the phone only, with no backup. Uninstalling the app, clearing its data or losing the
  phone loses the record.

## Building it

`sh android/app/build.sh` builds the app with plain Android tools, no Gradle, from a folder of tools and the signing key
kept outside the repository (see that file for what it expects). Every update must be signed with the same key, or it
can only be installed by uninstalling the old app first, which erases the record.

## Installing it on a phone

1. Download the built `calltally.apk` to the phone and open it. Allow installing from that source if Android asks.
2. To update, install the new file over the app already there. The record stays.
3. Open Call Tally and follow the first start. If Android won't offer a permission, open App info for Call Tally, tap the
   three-dot menu, choose "Allow restricted settings", then allow it under Permissions.
4. For runs in the background, set the app's battery use to unrestricted and turn off "remove permissions if app is
   unused".

The app reads the phone shortly after a call or text, about every 15 minutes, and whenever it is opened. The header shows
when it last did.

Updating over 0.6.0 or earlier, the app that uploaded to a server, starts fresh: the first-start page shows, the old
app's files are deleted, and the record is built from what the phone holds.

## Checking it

`python -m unittest discover -s tests` runs the unit tests.

`python3 tools/emulator_check.py` builds a test copy of the app, runs it on the Android emulator with made-up contacts,
calls and texts, goes through the first start, and checks the record the app keeps and its screens. It prints a pass or
fail line for each thing it checks, takes several minutes, and stops the emulator when it is done.

These records are a personal aid and may be incomplete. Check them before relying on them for pay or billing.
