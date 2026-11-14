"""Install / remove the CleanMyVR launcher (Library replacement + app store) on the headset."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

from . import state as S
from .device import Headset

PKG = "com.cleanmyvr.launcher"
SERVICE = f"{PKG}/{PKG}.LibraryInterceptor"
# Lets the launcher's crash guard restart its own Library takeover (only a computer can grant it).
GUARD_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"


def find_apk() -> Path | None:
    candidates = []
    if os.environ.get("CLEANMYVR_LAUNCHER_APK"):
        candidates.append(Path(os.environ["CLEANMYVR_LAUNCHER_APK"]))
    if getattr(sys, "_MEIPASS", None):
        candidates.append(Path(sys._MEIPASS) / "assets" / "launcher.apk")
    repo = Path(__file__).resolve().parents[2]
    candidates += [repo / "launcher" / "launcher.apk", repo / "app" / "assets" / "launcher.apk"]
    return next((c for c in candidates if c.exists()), None)


def _set_service(h: Headset, user: int, on: bool) -> None:
    cur = h.adb.sh(f"settings --user {user} get secure enabled_accessibility_services")
    # drop every form Android may have stored (".LibraryInterceptor" and the full class name)
    items = [s for s in ("" if cur == "null" else cur).split(":") if s and not s.startswith(PKG + "/")]
    if on:
        items.append(SERVICE)
    if items:
        h.adb.shell(f"settings --user {user} put secure enabled_accessibility_services {':'.join(items)}")
        h.adb.shell(f"settings --user {user} put secure accessibility_enabled 1")
    else:
        h.adb.shell(f"settings --user {user} delete secure enabled_accessibility_services")
        h.adb.shell(f"settings --user {user} put secure accessibility_enabled 0")


def install(h: Headset, user: int, progress=lambda m: None) -> dict:
    apk = find_apk()
    if not apk:
        raise RuntimeError("launcher.apk not found. Build it with launcher/build.sh, or use a release download.")
    r = h.adb.run("install", "-r", "--user", str(user), str(apk), timeout=300)
    if "Success" not in r.out:
        raise RuntimeError(f"Install failed: {r.text}")
    progress("Launcher installed")
    h.adb.shell(f"appops set --user {user} {PKG} GET_USAGE_STATS allow")
    h.adb.shell(f"appops set --user {user} {PKG} REQUEST_INSTALL_PACKAGES allow")
    h.adb.shell(f"pm grant --user {user} {PKG} {GUARD_PERMISSION}")
    _set_service(h, user, False)       # an update unbinds the service; toggling re-binds it
    _set_service(h, user, True)
    progress("Library takeover on, store installs and 'Continue playing' allowed")
    version = h.adb.sh(f"dumpsys package {PKG} | grep -m1 versionName").split("=")[-1]
    st = S.load(h)
    st["launcher"] = {"at": S.now(), "user": user, "version": version}
    S.record(st, "install_launcher", user, version=version)
    S.save(h, st)
    return {"version": version}


def takeover_state(h: Headset) -> str:
    """For the active profile (only its takeover runs): "running", "stopped" (on, but crashed / not running)
    or "off"."""
    user = h.adb.current_user()
    enabled = h.adb.sh(f"settings --user {user} get secure enabled_accessibility_services")
    if PKG + "/" not in enabled:
        return "off"
    dump = h.adb.sh("dumpsys accessibility")
    for block in dump.split("User state[")[1:]:
        if not re.search(rf"\bid={user}\b", block.split("\n", 2)[1] if "\n" in block else block):
            continue
        bound = re.search(r"Bound services:\{(.*)", block)
        crashed = re.search(r"Crashed services:\{(.*)", block)
        if crashed and PKG in crashed.group(1):
            return "stopped"
        # Android shows either the service's label or its class name, depending on the profile.
        names = ("Launcher: replace Library", "LibraryInterceptor")
        return "running" if bound and any(n in bound.group(1) for n in names) else "stopped"
    return "stopped"


def repair(h: Headset, user: int | None = None, progress=lambda m: None) -> None:
    """Restart the Library takeover (after a crash Android doesn't restart it) and make sure the launcher's own
    crash guard is allowed to do that next time."""
    user = h.adb.current_user() if user is None else user
    h.adb.shell(f"pm grant --user {user} {PKG} {GUARD_PERMISSION}")
    # After a crash Android can stay stuck "binding" the service; a force-stop clears that, then re-enable it.
    h.adb.shell(f"am force-stop --user {user} {PKG}")
    _set_service(h, user, False)
    _set_service(h, user, True)
    st = S.load(h)
    S.record(st, "repair_launcher", user)
    S.save(h, st)
    progress("Library takeover restarted")


def uninstall(h: Headset, user: int, progress=lambda m: None) -> None:
    _set_service(h, user, False)
    h.adb.shell(f"pm uninstall --user {user} {PKG}")
    st = S.load(h)
    st["launcher"] = None
    S.record(st, "uninstall_launcher", user)
    S.save(h, st)
    progress("Launcher removed; Meta's Library is back.")
