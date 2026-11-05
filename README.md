# calltally

A private calendar of work calls and texts. It opens on the current month and scrolls down to older ones. Each day shows
total call time and the number of texts. Click a day to see its entries, and delete any that shouldn't count. Deleted
entries stay deleted and can be restored.

A script on an Android phone (in Termux) finds calls and texts with contacts whose name ends in `-Work` and uploads them
about every 15 minutes, including history already on the phone (it reads the newest 2,000 calls and newest 5,000 texts
across all contacts). It uploads the time, contact, number, direction and call
length. It never uploads what a text said or anything about other contacts.

## Server

Python 3.11 or newer, standard library only. See `deploy/calltally.service`. The page has no login: keep it on a private
network such as Tailscale and never give it a public address.

## Phone

1. Install Tailscale, Termux and Termux:API. Get Termux and Termux:API from the same place (F-Droid or GitHub), not Google Play.
2. In Termux, run `curl -fsSL https://YOUR-SERVER/phone/setup.sh | sh` and allow each permission Android asks for.
3. If Android blocks the call log or SMS permission, open App info for Termux:API, choose the three-dot menu, and pick
   "Allow restricted settings". Then run the command again.
4. Set battery use for Termux and Termux:API to unrestricted, and turn off "remove permissions if app is unused" for
   Termux:API.

The page's "Updated" label turns yellow if the phone hasn't uploaded for three hours. Errors are in `~/.calltally/last.log`.

## What it can't see

- Calling and messaging apps such as WhatsApp and Signal.
- Picture messages, group messages and RCS chats, which aren't in the list of texts it reads.
- A work contact's extra numbers. Termux lists one number per contact. An extra number is still found from the name on
  its calls or received texts, but a text you send to one may be missed. Saving it as its own `-Work` contact fixes that.
- Calls the phone has already dropped from its log. Android keeps a limited number.
- Two texts with one contact, in the same direction, in the same second count as one.
- Removing `-Work` isn't always enough. A deleted contact's old calls can still upload, and so can old calls from a renamed
  contact's extra number, because the call log keeps the old name. Delete those entries on the page. Stopping them for
  good means clearing those calls from the phone's call log.

## Tests

`python -m unittest discover -s tests`

These records are a personal aid and may be incomplete. Check them before relying on them for pay or billing.
