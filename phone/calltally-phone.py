#!/usr/bin/env python3
"""Runs on the phone in Termux. Finds calls and texts with contacts whose name ends in -Work and uploads them.

Only the time, contact name, number, direction and call length ever leave the phone: never what a text said, and
nothing about any other contact.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

WORK_NAME = re.compile(r"-\s*Work\s*$")
CALL_LIMIT = 2000
TEXT_LIMIT = 5000
BATCH = 500
CALL_DIRECTIONS = {"INCOMING": "in", "OUTGOING": "out", "MISSED": "missed", "REJECTED": "rejected"}
TEXT_DIRECTIONS = {"inbox": "in", "sent": "out"}
CONFIG = Path(os.environ.get("CALLTALLY_CONFIG", Path.home() / ".calltally" / "server"))


class Problem(Exception):
    pass


def run_json(*command):
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=120)
    except FileNotFoundError:
        raise Problem(f"{command[0]} is missing. Install the termux-api package.") from None
    except subprocess.TimeoutExpired:
        raise Problem(f"{command[0]} did not answer. Is the Termux:API app installed?") from None
    if done.returncode != 0:
        raise Problem(f"{command[0]} failed: {done.stderr.strip()[:200]}")
    if not done.stdout.strip():
        # A real empty list comes back as []. No reply at all usually means a missing permission.
        raise Problem(f"{command[0]} gave no reply. Check that Termux:API still has its permissions.")
    try:
        result = json.loads(done.stdout)
    except ValueError:
        raise Problem(f"{command[0]} gave an unreadable reply. Check its permissions.") from None
    if isinstance(result, dict):
        # Termux:API reports a refused permission as an object, not a list.
        raise Problem(f"{command[0]}: {result.get('error') or result.get('message') or 'permission refused'}")
    if not isinstance(result, list):
        raise Problem(f"{command[0]} gave an unexpected reply.")
    return result


def number_key(number):
    digits = re.sub(r"\D", "", number or "")
    return digits[-10:]


def is_work(name):
    return isinstance(name, str) and WORK_NAME.search(name) is not None


def parse_duration(text):
    try:
        parts = [int(part) for part in str(text).split(":")]
    except ValueError:
        return 0
    seconds = 0
    for part in parts:
        seconds = seconds * 60 + part
    return seconds


def to_epoch(at):
    # Android writes the time in the phone's current zone, so reading it back in that zone gives the true moment.
    return int(time.mktime(time.strptime(at, "%Y-%m-%d %H:%M:%S")))


def work_numbers(contacts, calls, texts):
    """Map number to work name.

    The contact list and the name on a received text are current. The name on a call was saved when the call happened,
    so it only counts for a number nothing current knows: renaming Bob-Work to Bob must stop Bob's uploads. It is still
    needed because Termux lists just one number per contact.
    """
    current = {}
    sources = [(c.get("number"), c.get("name")) for c in contacts]
    sources += [(t.get("address") or t.get("number"), t.get("sender")) for t in texts if t.get("type") == "inbox"]
    for number, name in sources:
        key = number_key(number)
        if key and isinstance(name, str) and (is_work(name) or not is_work(current.get(key))):
            current[key] = name.strip()
    names = {key: name for key, name in current.items() if is_work(name)}
    for call in calls:
        key = number_key(call.get("phone_number"))
        if key and key not in current and is_work(call.get("name")):
            names[key] = call["name"].strip()
    return names


def build_entries(contacts, calls, texts):
    names = work_numbers(contacts, calls, texts)
    entries = []
    for call in calls:
        number = call.get("phone_number") or ""
        direction = CALL_DIRECTIONS.get(call.get("type"))
        name = names.get(number_key(number))
        if not name or not direction or not call.get("date"):
            continue
        entries.append({
            "kind": "call",
            "at": call["date"],
            "epoch": to_epoch(call["date"]),
            "contact": name,
            "number": number,
            "direction": direction,
            "duration_s": parse_duration(call.get("duration", "0")),
        })
    for text in texts:
        number = text.get("address") or text.get("number") or ""
        direction = TEXT_DIRECTIONS.get(text.get("type"))
        name = names.get(number_key(number))
        if not name or not direction or not text.get("received"):
            continue
        # Built field by field so the words of a text can't reach the upload.
        entries.append({
            "kind": "text",
            "at": text["received"],
            "epoch": to_epoch(text["received"]),
            "contact": name,
            "number": number,
            "direction": direction,
            "duration_s": 0,
        })
    return entries


def upload(base, entries):
    added = 0
    # An empty first batch still tells the page the phone checked in.
    for start in range(0, max(len(entries), 1), BATCH):
        body = json.dumps({"entries": entries[start:start + BATCH]}).encode()
        request = urllib.request.Request(
            base.rstrip("/") + "/api/upload", data=body, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as reply:
                added += json.load(reply)["added"]
        except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
            raise Problem(f"could not reach the server: {error}") from None
    return added


def main():
    try:
        base = os.environ.get("CALLTALLY_SERVER") or CONFIG.read_text().strip()
    except OSError:
        print("No server address. Run the setup again.", file=sys.stderr)
        return 2
    try:
        contacts = run_json("termux-contact-list")
        calls = run_json("termux-call-log", "-l", str(CALL_LIMIT))
        texts = run_json("termux-sms-list", "-t", "all", "-l", str(TEXT_LIMIT))
        entries = build_entries(contacts, calls, texts)
        added = upload(base, entries)
    except Problem as problem:
        print(f"calltally: {problem}", file=sys.stderr)
        return 1
    print(f"calltally: {len(entries)} work entries found, {added} new")
    return 0


if __name__ == "__main__":
    sys.exit(main())
