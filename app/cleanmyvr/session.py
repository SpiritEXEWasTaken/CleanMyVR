"""Connecting to a headset: shared by GUI and CLI."""
from __future__ import annotations

from .adb import Adb, AdbError, download_platform_tools, find_adb
from .device import Headset

HELP = {
    "no_adb": "ADB (Android platform-tools) isn't installed. CleanMyVR can download Google's official copy.",
    "none": ("No headset found. 1) Turn on Developer Mode in the Meta Horizon phone app (Devices > your headset > "
             "Headset settings > Developer mode). 2) Plug the headset into this computer with a data-capable USB cable. "
             "3) Put the headset on."),
    "unauthorized": ("The headset hasn't allowed this computer yet. Put it on, tick 'Always allow from this computer' "
                     "and choose Allow in the 'Allow USB debugging?' prompt. If no prompt shows, re-plug the cable."),
    "offline": "The headset is connected but not responding. Re-plug the cable or restart the headset.",
    "no permissions": ("Your system blocked access to the USB device. On Linux, add a udev rule for Meta "
                       "(vendor 2833) or run once with sudo; see the README."),
    "many": "More than one device is connected. Pick the headset.",
}


def connect(serial: str | None = None, allow_download: bool = False, progress=lambda m: None) -> dict:
    """Returns {"status": ..., "message": ..., "headset": Headset|None, "devices": [...]}."""
    path = find_adb()
    if not path:
        if not allow_download:
            return {"status": "no_adb", "message": HELP["no_adb"], "headset": None, "devices": []}
        path = download_platform_tools(progress)
    try:
        adb = Adb(path, serial)
    except AdbError as e:
        return {"status": "no_adb", "message": str(e), "headset": None, "devices": []}
    devs = adb.devices()
    listing = [{"serial": d.serial, "state": d.state, "model": d.model} for d in devs]
    if serial:
        devs = [d for d in devs if d.serial == serial]
    if not devs:
        return {"status": "none", "message": HELP["none"], "headset": None, "devices": listing}
    if len(devs) > 1:
        return {"status": "many", "message": HELP["many"], "headset": None, "devices": listing}
    d = devs[0]
    if d.state != "device":
        return {"status": d.state, "message": HELP.get(d.state, d.state), "headset": None, "devices": listing}
    h = Headset.connect(Adb(path, d.serial))
    msg = f"Connected to {h.profile['name']} ({h.model}, build {h.build})."
    if not h.tested:
        msg += " This headset isn't tested yet: only safe actions are enabled."
    elif not h.build_tested:
        msg += " This system build is newer than the ones CleanMyVR was tested on; recheck results carefully."
    return {"status": "ok", "message": msg, "headset": h, "devices": listing}
