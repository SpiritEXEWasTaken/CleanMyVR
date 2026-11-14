"""What CleanMyVR has done to a headset, stored ON the headset at /sdcard/CleanMyVR/state.json.

Keeping it on the headset means: any computer running CleanMyVR sees the same history, a factory reset
wipes it together with the changes it describes, and re-runs can do a quick recheck instead of redoing.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone

from . import __version__
from .device import Headset

STATE_DIR = "/sdcard/CleanMyVR"
STATE_PATH = STATE_DIR + "/state.json"
README_PATH = STATE_DIR + "/README.txt"
SCHEMA = 1

README = """This folder belongs to CleanMyVR (https://github.com/SpiritEXEWasTaken/CleanMyVR).
state.json records what CleanMyVR changed on this headset (removed apps per profile, update
blocking, launcher, environment fixes, backups), so a later run can re-check instead of redoing.
It contains no personal data. Deleting it is harmless: CleanMyVR just forgets its history.
A factory reset removes it along with the changes it describes.
"""


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def empty(h: Headset) -> dict:
    return {"schema": SCHEMA, "tool": f"CleanMyVR {__version__}", "created": now(), "updated": now(),
            "device": {"profile": h.profile["id"], "model": h.model, "build": h.build,
                       "incremental": h.incremental},
            "users": {}, "updates_blocked": None, "launcher": None, "environment": None,
            "backups": [], "history": []}


def load(h: Headset) -> dict:
    r = h.adb.shell(f"cat {STATE_PATH} 2>/dev/null")
    try:
        data = json.loads(r.out) if r.out.strip() else None
    except json.JSONDecodeError:
        data = None
    if not isinstance(data, dict) or data.get("schema") != SCHEMA:
        return empty(h)
    return data


def save(h: Headset, st: dict) -> None:
    st["updated"] = now()
    st["tool"] = f"CleanMyVR {__version__}"
    st["device"].update({"build": h.build, "incremental": h.incremental})
    h.adb.shell(f"mkdir -p {STATE_DIR}")
    h.adb.push_bytes(README.encode(), README_PATH)
    h.adb.push_bytes(json.dumps(st, indent=2).encode(), STATE_PATH)


def record(st: dict, action: str, user: int | None = None, **details) -> None:
    entry = {"at": now(), "action": action}
    if user is not None:
        entry["user"] = user
    entry.update(details)
    st["history"].append(entry)
    st["history"] = st["history"][-200:]


def user_state(st: dict, user: int) -> dict:
    us = st["users"].setdefault(str(user), {"debloat_level": None, "removed": [], "updated": None})
    us.setdefault("kept", [])
    return us
