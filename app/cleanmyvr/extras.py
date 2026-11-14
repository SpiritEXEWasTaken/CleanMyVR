"""Optional extras: Google Play apps via microG + Aurora Store (experimental on Quest)."""
from __future__ import annotations

import hashlib
import json
import tempfile
import time
import urllib.request
from pathlib import Path

from . import state as S
from .device import Headset

MICROG_REPO = "https://repo.microg.org/fdroid/repo"
MICROG_PACKAGES = {
    "com.google.android.gms": "microG Services",
    "com.android.vending": "microG Companion (stands in for the Play Store)",
}
AURORA = "com.aurora.store"
MIN_AGE_DAYS = 14   # don't install a build that's only been out a few days

NOTE = ("Experimental on Quest: Quest has no 'signature spoofing', so apps that strictly verify Google's "
        "services (Google sign-in, Play Integrity, many banking/DRM apps) still won't work. Apps that just "
        "need the services to exist (push notifications, maps, many games) often do.")


def _get(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "CleanMyVR"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def _pick(versions: dict) -> dict:
    """Newest version that's at least MIN_AGE_DAYS old (falls back to the oldest available)."""
    vs = sorted(versions.values(), key=lambda v: -v["manifest"]["versionCode"])
    cutoff = (time.time() - MIN_AGE_DAYS * 86400) * 1000
    for v in vs:
        if v.get("added", 0) <= cutoff:
            return v
    return vs[-1]


def status(h: Headset) -> dict:
    installed = h.adb.packages(h.adb.current_user())
    return {"microg": all(p in installed for p in MICROG_PACKAGES), "aurora": AURORA in installed,
            "note": NOTE}


def install_play_apps(h: Headset, progress=lambda m: None) -> dict:
    user = h.adb.current_user()
    progress("Reading microG's repository index…")
    index = json.loads(_get(MICROG_REPO + "/index-v2.json"))
    done = {}
    with tempfile.TemporaryDirectory() as tmp:
        for pkg, label in MICROG_PACKAGES.items():
            entry = index["packages"].get(pkg)
            if not entry:
                raise RuntimeError(f"{pkg} isn't in microG's repository")
            v = _pick(entry["versions"])
            name, digest = v["file"]["name"], v["file"]["sha256"]
            progress(f"Downloading {label} {v['manifest']['versionName']}…")
            data = _get(MICROG_REPO + name)
            if hashlib.sha256(data).hexdigest() != digest:
                raise RuntimeError(f"{label}: download doesn't match the repository's SHA-256. Nothing installed.")
            apk = Path(tmp) / f"{pkg}.apk"
            apk.write_bytes(data)
            r = h.adb.run("install", "-r", "-g", "--user", str(user), str(apk), timeout=300)
            if "Success" not in r.out:
                raise RuntimeError(f"{label}: install failed: {r.text}")
            done[pkg] = v["manifest"]["versionName"]
            progress(f"Installed {label} (SHA-256 verified)")

        progress("Downloading Aurora Store from F-Droid…")
        meta = json.loads(_get(f"https://f-droid.org/api/v1/packages/{AURORA}"))
        code = meta["suggestedVersionCode"]
        apk = Path(tmp) / "aurora.apk"
        apk.write_bytes(_get(f"https://f-droid.org/repo/{AURORA}_{code}.apk"))
        r = h.adb.run("install", "-r", "--user", str(user), str(apk), timeout=300)
        if "Success" not in r.out:
            raise RuntimeError(f"Aurora Store: install failed: {r.text}")
        done[AURORA] = str(code)
        progress("Installed Aurora Store")

    # microG works better when it may run in the background
    h.adb.shell("cmd deviceidle whitelist +com.google.android.gms")
    st = S.load(h)
    st.setdefault("extras", {})["play_apps"] = {"at": S.now(), "user": user, "versions": done}
    S.record(st, "install_play_apps", user, versions=done)
    S.save(h, st)
    progress("Done. Open Aurora Store, choose Anonymous login, and install Play Store apps. " + NOTE)
    return {"installed": done}


def remove_play_apps(h: Headset, progress=lambda m: None) -> None:
    user = h.adb.current_user()
    for pkg in list(MICROG_PACKAGES) + [AURORA]:
        r = h.adb.shell(f"pm uninstall --user {user} {pkg}")
        progress(f"{pkg}: {'removed' if 'Success' in r.out else 'not installed'}")
    h.adb.shell("cmd deviceidle whitelist -com.google.android.gms")
    st = S.load(h)
    st.setdefault("extras", {}).pop("play_apps", None)
    S.record(st, "remove_play_apps", user)
    S.save(h, st)
