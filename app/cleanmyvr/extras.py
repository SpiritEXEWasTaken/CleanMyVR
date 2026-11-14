"""Optional extras: Aurora Store, to get Play Store apps on Quest without a Google account.

microG (an open stand-in for Google's services) can't be installed on Quest: Horizon OS refuses non-system apps
that declare permissions under Google's reserved names (INSTALL_FAILED_VERIFICATION_FAILURE: "not allowed to
declare com.google.android.c2dm.permission.SEND in a protected prefix"), and microG has to declare them.
So only Aurora Store is offered; removal still cleans up microG in case an older CleanMyVR installed it.
"""
from __future__ import annotations

import json
import tempfile
import time
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


# ---------------------------------------------------------------------------------------- Quest Home Switcher
# https://github.com/nikitat21/Quest-Home-Switcher : switches the Home environment. Installed with -g
# (WRITE_SECURE_SETTINGS etc. pre-granted), it sets itself up on first launch with no Wireless ADB pairing.
# It needs Loft (haven2025) as the active home, and once set up it has shell-level access through its own
# Wireless ADB. It's closed source, so it's opt-in and the app says so.
QHS = "app.questhomeswitcher"
QHS_REPO = "nikitat21/Quest-Home-Switcher"
QHS_ACTIVITY = f"{QHS}/io.github.nikitat21.questhomeswitcher.v2lab.PanelSwitcherV2Activity"
QHS_MIN_AGE_DAYS = 14   # like everything CleanMyVR downloads: never a build that's only days old
QHS_NOTE = ("Quest Home Switcher is a third-party app (closed source). Once set up it has shell-level access to the "
            "headset through its own Wireless ADB, and it needs Loft as your Home environment.")


def _vt(v: str) -> tuple:
    import re
    return tuple((list(map(int, re.findall(r"\d+", v or "")[:3])) + [0, 0, 0])[:3])


def wireless_debugging(h: Headset) -> bool:
    return h.adb.sh("settings get global adb_wifi_enabled") == "1"


def set_wireless_debugging(h: Headset, on: bool) -> bool:
    """Quest Home Switcher talks to the headset over Wireless debugging (it pairs with itself). Returns the new
    state; Android only turns it on while the headset is on Wi-Fi."""
    h.adb.shell(f"settings put global adb_wifi_enabled {1 if on else 0}")
    time.sleep(2)
    return wireless_debugging(h)


def qhs_status(h: Headset) -> dict:
    user = h.adb.current_user()
    wifi = wireless_debugging(h)
    if QHS not in h.adb.packages(user):
        return {"installed": False, "version": None, "wireless": wifi, "note": QHS_NOTE}
    v = h.adb.sh(f"dumpsys package {QHS} | grep -m1 versionName").split("=")[-1].strip()
    return {"installed": True, "version": v, "wireless": wifi, "note": QHS_NOTE}


def _qhs_release() -> dict:
    """Newest release that's at least QHS_MIN_AGE_DAYS old and has an APK plus SHA256SUMS.txt."""
    import datetime
    rels = json.loads(_get(f"https://api.github.com/repos/{QHS_REPO}/releases?per_page=20"))
    cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=QHS_MIN_AGE_DAYS)
    for r in rels:
        if r.get("draft") or r.get("prerelease"):
            continue
        published = datetime.datetime.fromisoformat(r["published_at"].replace("Z", "+00:00"))
        assets = {a["name"]: a["browser_download_url"] for a in r.get("assets", [])}
        apk = next((n for n in assets if n.endswith(".apk")), None)
        if published <= cutoff and apk and "SHA256SUMS.txt" in assets:
            return {"tag": r["tag_name"], "apk": apk, "apk_url": assets[apk], "sums_url": assets["SHA256SUMS.txt"]}
    raise RuntimeError("No Quest Home Switcher release older than 14 days with checksums was found.")


def install_qhs(h: Headset, progress=lambda m: None) -> dict:
    """Download (SHA-256 checked), install with permissions pre-granted, and open it once to finish setup."""
    import hashlib
    from . import actions
    user = h.adb.current_user()
    env = actions.environment_status(h)
    home = " ".join(str(env.get(k, "")) for k in ("selected", "resolved", "loaded"))
    if env.get("available") and "haven2025" not in home:
        raise RuntimeError("Quest Home Switcher needs Loft as your Home environment (Settings → Environment → "
                           "Loft), or a factory reset if your Home is stuck on a retired one. Nothing was installed.")
    st = qhs_status(h)
    rel = _qhs_release()
    if st["installed"] and _vt(st["version"]) >= _vt(rel["tag"]):
        progress(f"Quest Home Switcher {st['version']} is already installed (newest allowed: {rel['tag']}); finishing setup.")
        version = st["version"]
        h.adb.shell(f"pm grant --user {user} {QHS} android.permission.WRITE_SECURE_SETTINGS")
    else:
        progress(f"Downloading Quest Home Switcher {rel['tag']} from GitHub…")
        data = _get(rel["apk_url"], timeout=300)
        sums = {}
        for line in _get(rel["sums_url"]).decode().splitlines():
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].lstrip("*")] = parts[0].lower()
        if hashlib.sha256(data).hexdigest() != sums.get(rel["apk"]):
            raise RuntimeError("The download doesn't match the project's published SHA-256. Nothing was installed.")
        progress("Download verified (SHA-256).")
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "qhs.apk"
            apk.write_bytes(data)
            r = h.adb.run("install", "-r", "-g", "--user", str(user), str(apk), timeout=300)
            if "Success" not in r.out:
                raise RuntimeError(f"Quest Home Switcher: install failed: {r.text}")
        version = rel["tag"].lstrip("v")
        progress(f"Installed Quest Home Switcher {version} (permissions granted, so no Wireless ADB pairing needed)")
    if set_wireless_debugging(h, True):
        progress("Wireless debugging is on (Quest Home Switcher pairs with it by itself)")
    else:
        progress("Couldn't turn on Wireless debugging: connect the headset to Wi-Fi, then press this button again.")
    h.adb.shell(f"am start --user {user} -n {QHS_ACTIVITY}")
    progress("Opened it in the headset: it finishes setting itself up there. Pick a Home and press Apply.")
    s = S.load(h)
    s.setdefault("extras", {})["quest_home_switcher"] = {"at": S.now(), "user": user, "version": version}
    S.record(s, "install_quest_home_switcher", user, version=version)
    S.save(h, s)
    return {"version": version}


def remove_qhs(h: Headset, progress=lambda m: None) -> None:
    user = h.adb.current_user()
    r = h.adb.shell(f"pm uninstall --user {user} {QHS}")
    # Nothing else needs it: don't leave the headset accepting debugging connections over the network.
    if not set_wireless_debugging(h, False):
        progress("Wireless debugging turned off")
    s = S.load(h)
    s.setdefault("extras", {}).pop("quest_home_switcher", None)
    S.record(s, "remove_quest_home_switcher", user)
    S.save(h, s)
    progress("Quest Home Switcher removed." if "Success" in r.out else "Quest Home Switcher wasn't installed.")
