# calltally

A private calendar of work calls and texts. It opens on the current month, with arrows to move between months and that
month's total call time at the top. Each day shows total call time and the number of texts. Click a day to see its entries, and delete any that shouldn't count. Deleted
entries stay deleted and can be restored.

An Android app finds calls and texts with contacts whose name ends in `-Work` and uploads them soon after they happen, and
about every 15 minutes in case one is missed, including history already on the phone. It uploads the time, contact, number,
direction and call length. It never reads what a text said or uploads anything about other contacts, except that a group
with a work contact shows how many other people are in it.

## Server

Python 3.11 or newer, standard library only. See `deploy/calltally.service`. The page has no login: keep it on a private
network such as Tailscale and never give it a public address.

## Phone

Build with `CALLTALLY_SERVER=https://YOUR-SERVER sh android/app/build.sh` (see that file for the tools it needs), put the
result in the server's data folder as `calltally.apk`, and download it on the phone from `/phone/calltally.apk`.

1. Install Tailscale and the app, open it and allow what it asks for. If Android blocks a permission, open App info for
   Call Tally Sync, choose the three-dot menu, and pick "Allow restricted settings".
2. Set its battery use to unrestricted, and turn off "remove permissions if app is unused".

The page's "Updated" label turns yellow if the phone hasn't uploaded for three hours. The app's screen shows the last result.

Termux and `phone/calltally-phone.py` are the older way to upload. Entries match, so moving between them adds no copies.

## What counts

- A message in a group counts if the group includes at least one `-Work` contact, so a mixed group can count personal
  messages. Delete those on the page. A chat of you, one `-Work` contact and one other person shows under that contact's name.
- Only calls that connected count toward time and the call count.
- It reads Android's shared SMS and MMS tables. A messaging app that keeps messages only in its own storage can't be seen.
- Apps such as WhatsApp and Signal aren't included.
- Calls the phone has already dropped from its log can't be recovered.
- Two messages in one conversation, in the same direction, in the same second count as one.
- A group is known by its conversation on the phone. After moving to a new phone or restoring messages, its history may upload again as a new group.

## Tests

`python -m unittest discover -s tests`

These records are a personal aid and may be incomplete. Check them before relying on them for pay or billing.
