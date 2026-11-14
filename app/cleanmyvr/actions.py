"""Everything CleanMyVR can do to a headset. Shared by the GUI and the command line.

Every function takes a `progress(str)` callback for live output and records its result in the
on-headset state (see state.py).
"""
from __future__ import annotations

import json
import re
import shlex

from . import __version__
from .updater import _parse as _vtuple
from . import state as S
from .device import Headset

Progress = callable


def _noop(_msg: str) -> None:
    pass


# ------------------------------------------------------------------ profiles / users

def profiles(h: Headset) -> list[dict]:
    return [{"id": uid, "name": name, "current": uid == h.adb.current_user()} for uid, name in h.adb.users()]


# ------------------------------------------------------------------ debloat

def plan_debloat(h: Headset, level: str, user: int) -> list[dict]:
    """What a debloat would do, without changing anything. Apps you chose to keep start unchecked."""
    installed = h.adb.packages(user)
    kept = set(S.user_state(S.load(h), user).get("kept", []))
    items = h.debloat_list(level)
    if level == "updates":
        items += h.profile.get("update_blocking", {}).get("remove", [])
    out = []
    for it in items:
        pkg = it["pkg"]
        status = "remove" if pkg in installed else "absent"
        if h.is_never_remove(pkg):
            status = "refused"            # profile safety net: never touch these
        out.append({"pkg": pkg, "why": it.get("why", ""), "status": status, "keep": pkg in kept})
    return out


def debloat(h: Headset, level: str, user: int, progress=_noop, keep: list[str] | None = None) -> dict:
    """Remove the level's apps for a profile. `keep` = apps you unchecked; they're left alone and
    remembered, so later rechecks and fixes don't remove them either (pass keep=None to reuse them)."""
    if not h.tested:
        raise RuntimeError(f"Debloat is disabled for {h.profile['name']}: this headset profile hasn't been tested yet.")
    st = S.load(h)
    us = S.user_state(st, user)
    level_pkgs = {it["pkg"] for it in h.debloat_list(level)}
    if keep is not None:
        # your choice for this level replaces the old one; keeps for other levels are kept
        us["kept"] = sorted((set(us.get("kept", [])) - level_pkgs) | (set(keep) & level_pkgs))
    kept = set(us.get("kept", []))
    removed, failed = [], []
    for it in plan_debloat(h, level, user):
        if it["status"] != "remove":
            continue
        if it["pkg"] in kept:
            progress(f"kept     {it['pkg']}  (unchecked)")
            continue
        r = h.adb.shell(f"pm uninstall -k --user {user} {shlex.quote(it['pkg'])}")
        if "Success" in r.out:
            removed.append(it["pkg"])
            progress(f"removed  {it['pkg']}  ({it['why']})")
        else:
            failed.append({"pkg": it["pkg"], "error": r.text})
            progress(f"FAILED   {it['pkg']}: {r.text}")
    us["removed"] = sorted(set(us["removed"]) | set(removed))
    us["debloat_level"] = level if level != "updates" else "balanced"
    us["updated"] = S.now()
    S.record(st, "debloat", user, level=level, removed=removed, failed=[f["pkg"] for f in failed])
    if level == "updates":
        block_updates(h, progress, st=st)
    S.save(h, st)
    progress(f"Done: {len(removed)} removed, {len(failed)} failed. Undo any time with Restore.")
    return {"removed": removed, "failed": failed}


def restore(h: Headset, user: int, packages: list[str] | None = None, progress=_noop) -> dict:
    st = S.load(h)
    us = S.user_state(st, user)
    pkgs = packages or list(us["removed"])
    back, store = [], []
    for pkg in pkgs:
        r = h.adb.shell(f"cmd package install-existing --user {user} {shlex.quote(pkg)}")
        if "installed for user" in r.out:
            back.append(pkg)
            progress(f"restored {pkg}")
        else:
            store.append(pkg)
            progress(f"{pkg}: not on the system image, reinstall it from the Meta Store")
    us["removed"] = [p for p in us["removed"] if p not in back and p not in store]
    if not us["removed"]:
        us["debloat_level"] = None
    us["updated"] = S.now()
    S.record(st, "restore", user, restored=back, needs_store=store)
    S.save(h, st)
    return {"restored": back, "needs_store": store}


# ------------------------------------------------------------------ updates

def block_updates(h: Headset, progress=_noop, st: dict | None = None) -> dict:
    cfg = h.profile.get("update_blocking") or {}
    own_state = st is None
    st = st or S.load(h)
    done = []
    # Removed on every profile: each profile has its own copy (Meta's updater itself runs on profile 0).
    for uid, _ in h.adb.users():
        for it in cfg.get("remove", []):
            r = h.adb.shell(f"pm uninstall -k --user {uid} {shlex.quote(it['pkg'])}")
            if "Success" in r.out or it["pkg"] not in h.adb.packages(uid):
                done.append(f"removed {it['pkg']} (profile {uid})")
    rs = cfg.get("restrict")
    if rs:
        pkg = rs["pkg"]
        for op in rs.get("appops", []):
            h.adb.shell(f"cmd appops set --user 0 {pkg} {op} ignore")
        if rs.get("remove_from_deviceidle_whitelist"):
            h.adb.shell(f"cmd deviceidle sys-whitelist -{pkg}")
        done.append(f"restricted {pkg} ({', '.join(rs.get('appops', []))})")
    for d in done:
        progress(d)
    st["updates_blocked"] = {"at": S.now(), "build": h.build, "incremental": h.incremental,
                             "best_effort": bool(rs)}
    S.record(st, "block_updates", steps=done)
    if own_state:
        S.save(h, st)
    if rs:
        progress("Note: " + rs.get("note", "best-effort blocking"))
    return {"steps": done}


def updates_status(h: Headset, st: dict) -> dict:
    cfg = h.profile.get("update_blocking") or {}
    issues = []
    for uid, _ in h.adb.users():
        installed = h.adb.packages(uid)
        for it in cfg.get("remove", []):
            if it["pkg"] in installed:
                issues.append(f"{it['pkg']} is back on profile {uid}")
    rs = cfg.get("restrict")
    if rs:
        ops = h.adb.sh(f"cmd appops get --user 0 {rs['pkg']}")
        for op in rs.get("appops", []):
            if not re.search(rf"^{op}: ignore", ops, re.M):
                issues.append(f"{rs['pkg']} may run in the background again ({op})")
    rec = st.get("updates_blocked")
    if rec and rec.get("incremental") and rec["incremental"] != h.incremental:
        issues.append(f"system build changed since blocking ({rec['build']} → {h.build}): an update got through")
    return {"recorded": bool(rec), "issues": issues}


# ------------------------------------------------------------------ storage

def storage_report(h: Headset, user: int = 0) -> dict:
    df = h.adb.sh("df -h /sdcard | tail -1").split()
    free = {"size": df[1], "used": df[2], "free": df[3]} if len(df) >= 4 else {}
    installed = h.adb.packages(user)
    apps = []
    m = h.adb.sh("dumpsys diskstats")
    try:
        names = json.loads(re.search(r"Package Names: (\[.*?\])", m).group(1))
        app = json.loads(re.search(r"App Sizes: (\[.*?\])", m).group(1))
        data = json.loads(re.search(r"App Data Sizes: (\[.*?\])", m).group(1))
        apps = sorted(({"pkg": n, "bytes": a + d} for n, a, d in zip(names, app, data) if n in installed),
                      key=lambda x: -x["bytes"])[:15]
    except (AttributeError, ValueError):
        pass   # Android measures app sizes about once a day; right after a reset there's nothing yet
    captures = {}
    for d in ("Oculus/VideoShots", "Oculus/Screenshots", "Movies", "DCIM", "Pictures"):
        out = h.adb.sh(f"du -s /sdcard/{d} 2>/dev/null").split()
        captures[d] = int(out[0]) * 1024 if out and out[0].isdigit() else 0
    note = None if apps else "Android hasn't measured app sizes yet (it does this about once a day)."
    return {"storage": free, "biggest_apps": apps, "captures": captures, "note": note}


def clear_caches(h: Headset, progress=_noop) -> None:
    h.adb.shell("pm trim-caches 999G")
    st = S.load(h)
    S.record(st, "clear_caches")
    S.save(h, st)
    progress("App caches cleared.")


def delete_captures(h: Headset, progress=_noop) -> None:
    """Delete screenshots/recordings/photos/videos on the current profile (keeps the folders)."""
    for d in ("Oculus/VideoShots", "Oculus/Screenshots", "Movies", "DCIM", "Pictures"):
        h.adb.shell(f"find /sdcard/{d} -type f -delete 2>/dev/null")
        progress(f"emptied {d}")
    st = S.load(h)
    S.record(st, "delete_captures", h.adb.current_user())
    S.save(h, st)


# ------------------------------------------------------------------ environment

def environment_status(h: Headset) -> dict:
    """What the home shell selected and actually loaded (no root needed)."""
    cmd = h.profile.get("environment", {}).get("dump_command", "dumpsys -t 2 DumpsysProxy VRShell")
    dump = h.adb.sh(f"timeout 4 {cmd} 2>/dev/null", timeout=20)
    prefs, env = {}, {}
    for line in dump.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "systemPreferences" in d:
            prefs = d["systemPreferences"]
        if "envLoaded" in d:
            env = d
    selected = prefs.get("environmentSelected", "")
    pkg = re.match(r"apk://([^/]+)/", selected or "")
    missing = bool(pkg) and pkg.group(1) not in h.adb.packages(h.adb.current_user())
    retired = bool(pkg) and pkg.group(1) in h.profile.get("environment", {}).get("retired_environments", [])
    problem = None
    if missing:
        problem = (f"Your home is set to {pkg.group(1)}, which isn't installed"
                   + (" (Meta retired it)" if retired else "") + ". Home shows a white/empty void.")
    elif env.get("envLoadFailureReason"):
        problem = f"Environment failed to load: {env['envLoadFailureReason']}"
    return {"selected": selected, "resolved": prefs.get("environmentResolved", ""),
            "vista": prefs.get("vistaResolved", ""), "loaded": env.get("envLoaded", ""),
            "state": env.get("currentStateName", ""), "problem": problem,
            "available": bool(prefs or env)}


# ------------------------------------------------------------------ adopt

def adopt(h: Headset, progress=_noop) -> dict:
    """Record changes made before the state file existed (older scripts, by hand): profile apps that are
    already gone, update blocking that's already in place, an installed launcher."""
    st = S.load(h)
    found = {}
    for uid, name in h.adb.users():
        installed = h.adb.packages(uid)
        gone = [it["pkg"] for it in h.debloat_list("balanced") if it["pkg"] not in installed]
        if gone:
            us = S.user_state(st, uid)
            us["removed"] = sorted(set(us["removed"]) | set(gone))
            light = {it["pkg"] for it in h.debloat_list("light")}
            us["debloat_level"] = us["debloat_level"] or ("balanced" if set(gone) - light else "light")
            us["updated"] = S.now()
            found[uid] = len(gone)
            progress(f"{name}: {len(gone)} profile apps already removed")
    if not st.get("updates_blocked") and updates_status(h, {})["issues"] == [] and \
            (h.profile.get("update_blocking") or {}).get("restrict"):
        st["updates_blocked"] = {"at": S.now(), "build": h.build, "incremental": h.incremental,
                                 "best_effort": True, "adopted": True}
        progress("Update blocking already in place")
    if "com.cleanmyvr.launcher" in h.adb.packages(h.adb.current_user()) and not st.get("launcher"):
        version = h.adb.sh("dumpsys package com.cleanmyvr.launcher | grep -m1 versionName").split("=")[-1]
        st["launcher"] = {"at": S.now(), "user": h.adb.current_user(), "version": version, "adopted": True}
        progress("Launcher already installed")
    S.record(st, "adopt", found=found)
    S.save(h, st)
    return {"found": found}


# ------------------------------------------------------------------ recheck

def recheck(h: Headset) -> dict:
    """Compare the recorded state with the headset as it is now."""
    st = S.load(h)
    items = []
    known_users = {uid for uid, _ in h.adb.users()}
    for uid_s, us in st.get("users", {}).items():
        uid = int(uid_s)
        if uid not in known_users:
            items.append({"area": "debloat", "user": uid, "status": "gone",
                          "text": f"Profile {uid} no longer exists"})
            continue
        if not us.get("removed") and not us.get("debloat_level"):
            continue
        installed = h.adb.packages(uid)
        level_pkgs = {it["pkg"] for it in h.debloat_list(us.get("debloat_level") or "light")}
        level_pkgs -= set(us.get("kept", []))
        # removed apps that came back, plus any app from the chosen level that Meta (re)installed
        back = sorted(((set(us["removed"]) | level_pkgs) - set(us.get("kept", []))) & installed)
        items.append({"area": "debloat", "user": uid, "status": "drift" if back else "ok",
                      "text": (f"{len(back)} removed app(s) came back: {', '.join(back)}" if back
                               else f"{len(us['removed'])} apps still removed ({us.get('debloat_level')})"),
                      "came_back": back})
    for uid, name in h.adb.users():
        if str(uid) not in st.get("users", {}) or not st["users"][str(uid)].get("removed"):
            items.append({"area": "debloat", "user": uid, "status": "todo", "text": f"{name}: not debloated yet"})
    up = updates_status(h, st)
    if up["recorded"]:
        items.append({"area": "updates", "status": "drift" if up["issues"] else "ok",
                      "text": "; ".join(up["issues"]) or "Update blocking still in place (best-effort)"})
    else:
        items.append({"area": "updates", "status": "todo", "text": "Updates not blocked"})
    launcher = "com.cleanmyvr.launcher" in h.adb.packages(h.adb.current_user())
    if not launcher:
        items.append({"area": "launcher", "status": "todo", "text": "Launcher not installed"})
    else:
        have = h.adb.sh("dumpsys package com.cleanmyvr.launcher | grep -m1 versionName").split("=")[-1].strip()
        old = _vtuple(have) < _vtuple(__version__)
        from . import launcher as L
        if L.takeover_state(h) == "stopped":
            items.append({"area": "launcher", "status": "drift", "user": h.adb.current_user(),
                          "text": "Library takeover stopped (the launcher crashed); Fix restarts it"})
        else:
            items.append({"area": "launcher", "status": "todo" if old else "ok",
                          "text": f"Launcher {have} installed; {__version__} comes with this CleanMyVR (Launcher → Install / update)"
                          if old else f"Launcher {have} installed"})
    env = environment_status(h)
    if env["available"]:
        items.append({"area": "environment", "status": "problem" if env["problem"] else "ok",
                      "text": env["problem"] or f"Home environment OK ({_short(env['loaded'] or env['resolved'])})"})
    return {"state": st, "items": items, "device": h.summary()}


def _short(uri: str) -> str:
    m = re.match(r"apk://([^/]+)/", uri or "")
    return m.group(1) if m else (uri or "passthrough")
