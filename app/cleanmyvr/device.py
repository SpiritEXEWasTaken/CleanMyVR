"""Headset profiles (devices/*.json) and the connected-headset wrapper."""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path

from .adb import Adb


def _resource_dir() -> Path:
    # PyInstaller unpacks data next to the frozen app
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "cleanmyvr"


DEVICES_DIR = _resource_dir() / "devices"


def load_profiles() -> list[dict]:
    out = []
    for f in sorted(DEVICES_DIR.glob("*.json")):
        if f.name.startswith("_"):
            continue
        out.append(json.loads(f.read_text(encoding="utf-8")))
    return out


GENERIC = {
    "id": "unknown", "name": "Unknown headset", "match": {}, "tested": False, "tested_on": [],
    "debloat": {"light": [], "balanced": []}, "update_blocking": {"remove": [], "restrict": None},
    "never_remove": [], "never_remove_prefixes": [], "protected": {}, "comes_back": [], "environment": {},
    "notes": ["No profile for this headset yet. Only safe actions (storage report, backups, recheck) are available. "
              "See docs/ADDING_DEVICES.md to add support."],
}


@dataclass
class Headset:
    adb: Adb
    profile: dict
    model: str
    codename: str
    build: str
    incremental: str

    @classmethod
    def connect(cls, adb: Adb) -> "Headset":
        codename = adb.prop("ro.product.device")
        model = adb.prop("ro.product.model")
        return cls(adb, match_profile(codename, model), model, codename,
                   adb.prop("ro.build.display.id"), adb.prop("ro.build.version.incremental"))

    @property
    def tested(self) -> bool:
        return bool(self.profile.get("tested"))

    @property
    def build_tested(self) -> bool:
        return any(t.get("incremental") == self.incremental for t in self.profile.get("tested_on", []))

    def debloat_list(self, level: str) -> list[dict]:
        """light = light; balanced = light + balanced; updates adds update blocking (handled separately)."""
        d = self.profile.get("debloat", {})
        items = list(d.get("light", []))
        if level in ("balanced", "updates"):
            items += d.get("balanced", [])
        return items

    def is_never_remove(self, pkg: str) -> bool:
        return pkg in self.profile.get("never_remove", []) or any(
            pkg.startswith(p) for p in self.profile.get("never_remove_prefixes", []))

    def summary(self) -> dict:
        return {"name": self.profile["name"], "profile": self.profile["id"], "model": self.model,
                "codename": self.codename, "build": self.build, "incremental": self.incremental,
                "tested": self.tested, "build_tested": self.build_tested,
                "notes": self.profile.get("notes", [])}


def match_profile(codename: str, model: str) -> dict:
    for p in load_profiles():
        m = p.get("match", {})
        if codename and codename in m.get("ro.product.device", []):
            return p
    for p in load_profiles():
        if model and model in p.get("match", {}).get("ro.product.model", []):
            return p
    return GENERIC
