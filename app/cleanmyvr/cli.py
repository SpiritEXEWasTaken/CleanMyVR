"""Guided command-line CleanMyVR (launch-commandline.sh / CleanMyVR-cmd.bat)."""
from __future__ import annotations

import os
import sys
from pathlib import Path

from . import __version__, actions, backup, extras, launcher, updater
from . import state as S
from .session import connect

USE_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None
if os.name == "nt":
    os.system("")      # enable ANSI colours in the Windows console


def c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if USE_COLOR else text


def ok(t): return c("32", t)
def warn(t): return c("33", t)
def bad(t): return c("31", t)
def dim(t): return c("2", t)
def bold(t): return c("1", t)


ICON = {"ok": ok("✔ OK     "), "drift": warn("! CHANGED"), "todo": dim("• to do  "),
        "problem": bad("✖ PROBLEM"), "gone": dim("• gone   ")}


def ask(prompt: str, default: str = "") -> str:
    try:
        v = input(prompt + (f" [{default}]" if default else "") + ": ").strip()
    except (EOFError, KeyboardInterrupt):
        print()
        sys.exit(0)
    return v or default


def yes(prompt: str) -> bool:
    return ask(prompt + " (y/N)").lower().startswith("y")


def progress(msg: str) -> None:
    print("   " + msg)


def pick_user(h) -> int:
    users = h.adb.users()
    if len(users) == 1:
        return users[0][0]
    for i, (uid, name) in enumerate(users, 1):
        print(f"   {i}) {name} (profile {uid})")
    n = ask("Which profile", "1")
    try:
        return users[int(n) - 1][0]
    except (ValueError, IndexError):
        return users[0][0]


def do_connect():
    print(bold(f"\nCleanMyVR {__version__}") + dim("  (guided command line)\n"))
    allow, serial = False, None
    while True:
        r = connect(serial=serial, allow_download=allow, progress=progress)
        if r["status"] == "ok":
            print(ok("✔ ") + r["message"])
            return r["headset"]
        print(warn("! ") + r["message"])
        if r["status"] == "no_adb":
            if yes("Download Google's platform-tools now?"):
                allow = True
                continue
            sys.exit(1)
        if r["status"] == "many":
            for i, d in enumerate(r["devices"], 1):
                print(f"   {i}) {d['serial']} {d['model']} ({d['state']})")
            n = ask("Pick the headset", "1")
            try:
                serial = r["devices"][int(n) - 1]["serial"]
            except (ValueError, IndexError):
                pass
            continue
        ask(dim("Press Enter to try again (Ctrl+C to quit)"))


def show_recheck(h) -> dict:
    print(bold("\nRecheck") + dim("  (compares this headset with what CleanMyVR recorded on it)"))
    rc = actions.recheck(h)
    for it in rc["items"]:
        who = f"profile {it['user']}: " if "user" in it else ""
        print(f"  {ICON.get(it['status'], it['status'])}  {it['area']:<11} {who}{it['text']}")
    return rc


def menu_debloat(h):
    if not h.tested:
        print(warn("Debloat is disabled: this headset profile isn't tested yet (see docs/ADDING_DEVICES.md)."))
        return
    user = pick_user(h)
    print("   1) light     Meta's extra apps only (Facebook, Instagram, tips, demos)")
    print("   2) balanced  + ads/promos, telemetry uploaders, business device management  (recommended)")
    print("   3) balanced + block system updates (best-effort; you lose fixes)")
    level = {"1": "light", "2": "balanced", "3": "updates"}.get(ask("Level", "2"), "balanced")
    plan = actions.plan_debloat(h, level, user)
    todo = [p for p in plan if p["status"] == "remove"]
    print(bold(f"\nApps that can be removed for profile {user}:"))
    for i, p in enumerate(todo, 1):
        mark = warn("keep  ") if p["keep"] else "remove"
        print(f"   {i:>2}) [{mark}] {p['pkg']:<38} {dim(p['why'])}")
    if not todo:
        print(dim("   nothing left to remove"))
        if level == "updates" and yes("Re-apply update blocking?"):
            actions.block_updates(h, progress)
        return
    keep = [p["pkg"] for p in todo if p["keep"]]
    ans = ask("Numbers to KEEP (e.g. 2,5), Enter to keep the marks above", "")
    if ans:
        try:
            keep = [todo[int(n) - 1]["pkg"] for n in ans.replace(" ", "").split(",") if n]
        except (ValueError, IndexError):
            print(warn("   Didn't understand that; nothing changed."))
            return
    n = len(todo) - len(keep)
    print(dim(f"   Will remove {n}, keep {len(keep)}. Kept apps are remembered and never removed later."))
    print(dim("   Everything can be restored later (menu: Restore)."))
    if yes("Go ahead?"):
        actions.debloat(h, level, user, progress, keep=keep)


def menu_backup(h):
    user = pick_user(h)
    default = str(Path.home() / "Quest-Backups" / f"profile-{user}")
    dest = ask("Back up to", default)
    print(dim("   Photos/videos of that profile + game saves found on shared storage. Every file is SHA-256 checked."))
    if yes("Start backup?"):
        backup.backup(h, user, dest, progress=progress)


def menu_storage(h):
    rep = actions.storage_report(h)
    s = rep["storage"]
    print(bold(f"\nStorage: {s.get('free', '?')} free of {s.get('size', '?')}"))
    for a in rep["biggest_apps"][:10]:
        print(f"   {a['bytes'] / 1e9:6.2f} GB  {a['pkg']}")
    if rep.get("note"):
        print(dim("   " + rep["note"]))
    total = sum(rep["captures"].values())
    print(f"   Screenshots/recordings/photos/videos on this profile: {total / 1e9:.2f} GB")
    if yes("Clear app caches (safe, apps rebuild them)?"):
        actions.clear_caches(h, progress)
    if total and yes(bad("DELETE all screenshots/recordings/photos/videos on this profile? (back them up first!)")):
        if ask("Type DELETE to confirm") == "DELETE":
            actions.delete_captures(h, progress)


def menu_environment(h):
    env = actions.environment_status(h)
    if not env["available"]:
        print(warn("Couldn't read the home environment (put the headset on so the home shell wakes up)."))
        return
    print(f"   selected: {env['selected'] or '(default)'}\n   loaded:   {env['loaded'] or '(passthrough)'}")
    if env["problem"]:
        print(bad("   " + env["problem"]))
        print("   Fixes: a factory reset clears the stuck selection (back up first!), or see the README's\n"
              "   'white sky' section for the stand-in environment trick (needs the Android SDK).")
    else:
        print(ok("   Home environment is fine."))


def menu_restore(h):
    print("   1) Re-install apps CleanMyVR removed")
    print("   2) Copy a backup back onto the headset")
    choice = ask("Choice", "1")
    if choice == "1":
        user = pick_user(h)
        st = S.load(h)
        removed = S.user_state(st, user)["removed"]
        if not removed:
            print(dim("   Nothing recorded as removed on this profile."))
            return
        print("   " + ", ".join(removed))
        if yes(f"Restore these {len(removed)} app(s)?"):
            actions.restore(h, user, progress=progress)
    else:
        src = ask("Backup folder (contains SHA256SUMS.txt)")
        if not src:
            return
        user = pick_user(h)
        if yes(f"Copy it to profile {user}? (files already there are skipped)"):
            r = backup.restore_backup(h, src, user, progress)
            for s in r["skipped"]:
                print(warn("   skipped " + s))


def menu_launcher(h):
    user = h.adb.current_user()
    installed = launcher.PKG in h.adb.packages(user)
    if installed and yes("Launcher is installed. Update/reinstall it?") or not installed and yes("Install the launcher?"):
        launcher.install(h, user, progress)
    elif installed and yes("Remove the launcher instead?"):
        launcher.uninstall(h, user, progress)


def menu_extras(h):
    st = extras.status(h)
    print(bold("Google Play apps on Quest: microG + Aurora Store") + dim("  (experimental)"))
    print("   " + extras.NOTE)
    print(f"   Currently: microG {'installed' if st['microg'] else 'not installed'}, "
          f"Aurora Store {'installed' if st['aurora'] else 'not installed'}")
    if not st["microg"] and yes("Install microG Services + Companion and Aurora Store (verified downloads)?"):
        extras.install_play_apps(h, progress)
    elif st["microg"] and yes("Remove microG and Aurora Store?"):
        extras.remove_play_apps(h, progress)


def fix_drift(h, rc):
    for it in rc["items"]:
        if it["area"] == "debloat" and it["status"] == "drift":
            st = S.load(h)
            level = S.user_state(st, it["user"]).get("debloat_level") or "balanced"
            if yes(f"Remove the {len(it['came_back'])} app(s) that came back on profile {it['user']}?"):
                actions.debloat(h, level, it["user"], progress)
        if it["area"] == "updates" and it["status"] == "drift" and yes("Re-apply update blocking?"):
            actions.block_updates(h, progress)


def offer_update() -> None:
    u = updater.check()
    if not u["available"]:
        return
    print(bold(f"\nCleanMyVR {u['latest']} is available") + dim(f" (you have {u['current']})"))
    if u["notes"] and yes("Show what's new?"):
        print(dim("\n".join("   " + l for l in u["notes"].splitlines()[:30])))
    if yes("Update now?"):
        r = updater.apply(u, progress)
        print("   " + r["message"])
        if r["restart"]:
            sys.exit(0)


def main() -> None:
    offer_update()
    h = do_connect()
    for n in h.profile.get("notes", [])[:2]:
        print(dim("   note: " + n))
    while True:
        rc = show_recheck(h)
        drift = any(it["status"] == "drift" for it in rc["items"])
        print(bold("\nWhat do you want to do?"))
        options = [("Fix what changed", fix_drift)] if drift else []
        options += [("Debloat (remove Meta extras, optionally block updates)", menu_debloat),
                    ("Back up photos, videos and game saves", menu_backup),
                    ("Storage: see what's big, clear caches", menu_storage),
                    ("Check the home environment (white sky bug)", menu_environment),
                    ("Install / update / remove the launcher", menu_launcher),
                    ("Restore removed apps or a backup", menu_restore),
                    ("Extras: Google Play apps (microG + Aurora Store, experimental)", menu_extras)]
        for i, (label, _) in enumerate(options, 1):
            print(f"   {i}) {label}")
        print("   q) Quit")
        choice = ask("Choose", "q")
        if choice.lower().startswith("q"):
            return
        try:
            label, fn = options[int(choice) - 1]
        except (ValueError, IndexError):
            continue
        print(bold(f"\n{label}"))
        try:
            fn(h, rc) if fn is fix_drift else fn(h)
        except Exception as e:     # keep the guided session alive on errors
            print(bad(f"   Error: {e}"))
        ask(dim("\nPress Enter to continue"))


if __name__ == "__main__":
    main()
