"""Block apps permanently: for apps Meta keeps reinstalling (WhatsApp, Facebook, ...).

Normal debloat removes an app per profile (`pm uninstall -k --user`), which keeps its APK on the headset, and
Meta's installer (com.oculus.ocms) puts it back with its next update. Blocking instead fully uninstalls the app
and installs a tiny placeholder with the same package name, a huge version number and our own signature. Meta's
update is then refused by Android (version downgrade / signatures don't match). Horizon shows a one-time
"unauthorized copy" warning per app. Bring it back = uninstall the placeholder.

Only for apps in the profile's `blockable` list: never system apps, never anything in never_remove. The
placeholders live in cleanmyvr/placeholders/ (built by packaging/build-placeholders.sh).
"""
from __future__ import annotations

import re
import shlex

from . import state as S
from .device import Headset, _resource_dir

PLACEHOLDER_VERSION = 2100000000
PLACEHOLDER_DIR = _resource_dir() / "placeholders"


def blockable(h: Headset) -> list[dict]:
    return h.profile.get("blockable", [])


def placeholder_apk(pkg: str):
    p = PLACEHOLDER_DIR / f"{pkg}.apk"
    return p if p.exists() else None


def blocked_packages(h: Headset) -> set[str]:
    """Packages currently held by a placeholder (recognised by its version number)."""
    out = h.adb.sh(f"pm list packages --show-versioncode --user {h.adb.current_user()}")
    return {m.group(1) for m in re.finditer(r"package:(\S+) versionCode:(\d+)", out)
            if int(m.group(2)) == PLACEHOLDER_VERSION}


def status(h: Headset) -> list[dict]:
    blocked = blocked_packages(h)
    installed = h.adb.packages(h.adb.current_user())
    out = []
    for it in blockable(h):
        pkg = it["pkg"]
        state = "blocked" if pkg in blocked else "installed" if pkg in installed else "removed"
        out.append({"pkg": pkg, "name": it.get("name", pkg), "why": it.get("why", ""),
                    "tested": bool(it.get("tested")), "state": state, "available": bool(placeholder_apk(pkg))})
    return out


def _check(h: Headset, pkg: str) -> None:
    if pkg not in {it["pkg"] for it in blockable(h)}:
        raise RuntimeError(f"{pkg} isn't on this headset's list of apps that can be blocked.")
    if h.is_never_remove(pkg):
        raise RuntimeError(f"{pkg} is needed by the Store, games or your account; it can't be blocked.")
    if h.adb.sh(f"pm list packages -s {shlex.quote(pkg)}").splitlines().count(f"package:{pkg}"):
        raise RuntimeError(f"{pkg} is a system app; only apps Meta installs on top can be blocked.")
    if not placeholder_apk(pkg):
        raise RuntimeError(f"No placeholder for {pkg} comes with this CleanMyVR.")


def block(h: Headset, packages: list[str], progress=lambda m: None) -> dict:
    """Fully uninstall each app and put a placeholder in its place, on every profile."""
    already = blocked_packages(h)
    users = [uid for uid, _ in h.adb.users()]
    done, failed = [], []
    st = S.load(h)
    for pkg in packages:
        if pkg in already:
            progress(f"{pkg}: already blocked")
            continue
        try:
            _check(h, pkg)
            h.adb.shell(f"pm uninstall {shlex.quote(pkg)}")          # the whole app, every profile (not a system app)
            r = h.adb.run("install", str(placeholder_apk(pkg)), timeout=120)
            if "Success" not in r.out:
                raise RuntimeError(f"placeholder install failed: {r.text}")
            for uid in users:                                         # make sure every profile has the placeholder
                h.adb.shell(f"cmd package install-existing --user {uid} {shlex.quote(pkg)}")
            if pkg not in blocked_packages(h):
                raise RuntimeError("the placeholder didn't stick")
            st.setdefault("blocked", {})[pkg] = {"at": S.now(), "placeholder_version": PLACEHOLDER_VERSION}
            done.append(pkg)
            progress(f"blocked  {pkg}")
        except Exception as e:
            failed.append({"pkg": pkg, "error": str(e)})
            progress(f"FAILED   {pkg}: {e}")
    S.record(st, "block_packages", None, blocked=done, failed=[f["pkg"] for f in failed])
    S.save(h, st)
    if done:
        progress("Done. Meta shows a one-time \"unauthorized copy\" message for each app; that's expected. "
                 "Bring it back any time.")
    return {"blocked": done, "failed": failed}


def unblock(h: Headset, packages: list[str], progress=lambda m: None) -> dict:
    """Remove the placeholders; Meta's Store can then install the real apps again."""
    blocked = blocked_packages(h)
    st = S.load(h)
    done = []
    for pkg in packages:
        if pkg in blocked:
            r = h.adb.shell(f"pm uninstall {shlex.quote(pkg)}")
            if "Success" in r.out:
                done.append(pkg)
                progress(f"unblocked {pkg} (reinstall it from the Meta Store if you want it)")
            else:
                progress(f"FAILED   {pkg}: {r.text}")
        st.setdefault("blocked", {}).pop(pkg, None)
    S.record(st, "unblock_packages", None, unblocked=done)
    S.save(h, st)
    return {"unblocked": done}
