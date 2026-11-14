"""Optional extras: Aurora Store, to get Play Store apps on Quest without a Google account.

microG (an open stand-in for Google's services) can't be installed on Quest: Horizon OS refuses non-system apps
that declare permissions under Google's reserved names (INSTALL_FAILED_VERIFICATION_FAILURE: "not allowed to
declare com.google.android.c2dm.permission.SEND in a protected prefix"), and microG has to declare them.
So only Aurora Store is offered; removal still cleans up microG in case an older CleanMyVR installed it.
"""
from __future__ import annotations

import json
import tempfile
import urllib.request
from pathlib import Path

from . import state as S
from .device import Headset

MICROG_PACKAGES = ("com.google.android.gms", "com.android.vending")   # only removed, never installed
AURORA = "com.aurora.store"

NOTE = ("Aurora Store downloads Play Store apps anonymously. Apps that need Google's services (Google sign-in, "
        "push notifications, Play Integrity, many banking/streaming apps) won't run: Quest has no Google services, "
        "and microG, the open stand-in, can't be installed on Quest.")


def _get(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "CleanMyVR"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def status(h: Headset) -> dict:
    installed = h.adb.packages(h.adb.current_user())
    return {"aurora": AURORA in installed, "note": NOTE}


def install_play_apps(h: Headset, progress=lambda m: None) -> dict:
    """Install Aurora Store from F-Droid for the active profile."""
    user = h.adb.current_user()
    progress("Downloading Aurora Store from F-Droid…")
    meta = json.loads(_get(f"https://f-droid.org/api/v1/packages/{AURORA}"))
    code = meta["suggestedVersionCode"]
    with tempfile.TemporaryDirectory() as tmp:
        apk = Path(tmp) / "aurora.apk"
        apk.write_bytes(_get(f"https://f-droid.org/repo/{AURORA}_{code}.apk"))
        r = h.adb.run("install", "-r", "--user", str(user), str(apk), timeout=300)
        if "Success" not in r.out:
            raise RuntimeError(f"Aurora Store: install failed: {r.text}")
    done = {AURORA: str(code)}
    st = S.load(h)
    st.setdefault("extras", {})["play_apps"] = {"at": S.now(), "user": user, "versions": done}
    S.record(st, "install_play_apps", user, versions=done)
    S.save(h, st)
    progress("Installed Aurora Store. Open it and choose Anonymous to sign in. " + NOTE)
    return {"installed": done}


def remove_play_apps(h: Headset, progress=lambda m: None) -> None:
    user = h.adb.current_user()
    installed = h.adb.packages(user)
    for pkg in [AURORA, *MICROG_PACKAGES]:
        if pkg in installed:
            r = h.adb.shell(f"pm uninstall --user {user} {pkg}")
            progress(f"{pkg}: {'removed' if 'Success' in r.out else 'could not remove'}")
    st = S.load(h)
    st.setdefault("extras", {}).pop("play_apps", None)
    S.record(st, "remove_play_apps", user)
    S.save(h, st)
    progress("Aurora Store removed.")
