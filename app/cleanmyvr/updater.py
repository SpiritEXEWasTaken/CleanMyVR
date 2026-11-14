"""Updates for CleanMyVR itself, from the GitHub releases of the project.

check() asks GitHub for the latest release (one small request at start-up; set CLEANMYVR_NO_UPDATE_CHECK=1
to turn it off). apply() then updates in the way that fits how CleanMyVR was installed:

- Windows app (CleanMyVR.exe): downloads CleanMyVR-windows.zip, checks it against the release's
  SHA256SUMS.txt, and swaps the files in after the app closes, then starts the new version.
- git checkout (macOS/Linux): `git pull --ff-only`, if there are no local changes.
- anything else: opens the release page so the user can download it.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import webbrowser
import zipfile
from pathlib import Path

from . import __version__

REPO = "SpiritEXEWasTaken/CleanMyVR"
LATEST = f"https://api.github.com/repos/{REPO}/releases/latest"
RELEASES = f"https://github.com/{REPO}/releases/latest"
ZIP = "CleanMyVR-windows.zip"


def _parse(v: str) -> tuple[int, ...]:
    return tuple((list(map(int, re.findall(r"\d+", v or "")[:3])) + [0, 0, 0])[:3])


def _get(url: str, timeout: float = 10) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": f"CleanMyVR/{__version__}",
                                               "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def install_kind() -> str:
    if getattr(sys, "frozen", False) and os.name == "nt":
        return "exe"
    if (Path(__file__).resolve().parents[2] / ".git").exists():
        return "git"
    return "manual"


def check() -> dict:
    """{"available": bool, "current", "latest", "notes", "url", "kind"}; never raises."""
    info = {"available": False, "current": __version__, "latest": None, "notes": "", "url": RELEASES,
            "kind": install_kind()}
    if os.environ.get("CLEANMYVR_NO_UPDATE_CHECK"):
        return info
    try:
        rel = json.loads(_get(LATEST))
    except Exception as e:      # offline, rate-limited, no releases yet: just say nothing
        info["error"] = str(e)
        return info
    info.update(latest=rel.get("tag_name", "").lstrip("v"), notes=rel.get("body") or "",
                url=rel.get("html_url") or RELEASES,
                assets={a["name"]: a["browser_download_url"] for a in rel.get("assets", [])})
    info["available"] = bool(info["latest"]) and _parse(info["latest"]) > _parse(__version__)
    return info


def apply(info: dict, progress=lambda m: None) -> dict:
    """Install the update described by check(). Returns {"done": bool, "restart": bool, "message": str}."""
    kind = info.get("kind") or install_kind()
    if kind == "git":
        return _git_pull(progress)
    if kind == "exe" and ZIP in info.get("assets", {}):
        return _windows(info, progress)
    webbrowser.open(info.get("url") or RELEASES)
    return {"done": False, "restart": False, "message": "Opened the download page in your browser."}


def _git_pull(progress) -> dict:
    root = Path(__file__).resolve().parents[2]
    git = lambda *a: subprocess.run(["git", "-C", str(root), *a], capture_output=True, text=True,
                                    stdin=subprocess.DEVNULL)
    if git("status", "--porcelain", "--untracked-files=no").stdout.strip():
        return {"done": False, "restart": False,
                "message": "You have local changes in this folder, so CleanMyVR didn't update itself. Run `git pull` yourself."}
    progress("Downloading the update with git…")
    r = git("pull", "--ff-only")
    if r.returncode:
        return {"done": False, "restart": False, "message": f"git pull failed: {(r.stderr or r.stdout).strip()}"}
    return {"done": True, "restart": True, "message": "Updated. Restart CleanMyVR to use the new version."}


def _windows(info: dict, progress) -> dict:
    assets = info["assets"]
    if "SHA256SUMS.txt" not in assets:
        raise RuntimeError("The release has no SHA256SUMS.txt, so the download can't be verified.")
    sums = dict(reversed(l.split(None, 1)) for l in _get(assets["SHA256SUMS.txt"]).decode().splitlines() if l.strip())
    want = sums.get(ZIP) or sums.get("*" + ZIP)
    progress(f"Downloading CleanMyVR {info['latest']}…")
    data = _get(assets[ZIP], timeout=300)
    if hashlib.sha256(data).hexdigest() != want:
        raise RuntimeError("The download doesn't match the release's SHA-256 checksum, so it wasn't installed.")
    progress("Download verified (SHA-256).")
    work = Path(tempfile.mkdtemp(prefix="cleanmyvr-update-"))
    zp = work / ZIP
    zp.write_bytes(data)
    new = work / "new"
    with zipfile.ZipFile(zp) as z:
        z.extractall(new)
    target = Path(sys.executable).resolve().parent
    exe = target / "CleanMyVR.exe"
    # The running .exe can't be overwritten, so a small script waits for this process to end, copies the new
    # files over and starts the new version.
    script = work / "update.bat"
    script.write_text(
        "@echo off\r\n"
        f":wait\r\ntasklist /FI \"PID eq {os.getpid()}\" | find \"{os.getpid()}\" >nul && (timeout /t 1 /nobreak >nul & goto wait)\r\n"
        f"xcopy /E /Y /Q \"{new}\\*\" \"{target}\\\" >nul\r\n"
        f"start \"\" \"{exe}\"\r\n", encoding="utf-8")
    subprocess.Popen(["cmd", "/c", str(script)], creationflags=0x00000008 | 0x00000200,   # detached, new group
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
    return {"done": True, "restart": True, "message": "Update ready. CleanMyVR will close and reopen as the new version."}
