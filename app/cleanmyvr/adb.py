"""Finding ADB (or downloading Google's official platform-tools) and talking to a headset."""
from __future__ import annotations

import io
import os
import platform
import shlex
import shutil
import stat
import subprocess
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

PLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-{os}.zip"


class AdbError(RuntimeError):
    pass


def data_dir() -> Path:
    """Per-user folder for downloaded tools and settings."""
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    elif platform.system() == "Darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    d = base / "CleanMyVR"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _exe(name: str) -> str:
    return name + (".exe" if os.name == "nt" else "")


def find_adb() -> str | None:
    """ADB from $CLEANMYVR_ADB, our own download, or PATH (in that order)."""
    env = os.environ.get("CLEANMYVR_ADB")
    if env and Path(env).exists():
        return env
    ours = data_dir() / "platform-tools" / _exe("adb")
    if ours.exists():
        return str(ours)
    return shutil.which("adb")


def download_platform_tools(progress=None) -> str:
    """Download Google's platform-tools for this OS into our data folder. Returns the adb path."""
    osname = {"Windows": "windows", "Darwin": "darwin"}.get(platform.system(), "linux")
    url = PLATFORM_TOOLS_URL.format(os=osname)
    if progress:
        progress(f"Downloading Android platform-tools from Google ({osname})…")
    req = urllib.request.Request(url, headers={"User-Agent": "CleanMyVR"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    target = data_dir()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for member in z.namelist():
            # only extract inside platform-tools/ (no path tricks)
            if not member.startswith("platform-tools/") or ".." in Path(member).parts:
                continue
            z.extract(member, target)
    adb = target / "platform-tools" / _exe("adb")
    if os.name != "nt":
        adb.chmod(adb.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    if progress:
        progress("platform-tools installed.")
    return str(adb)


@dataclass
class Result:
    code: int
    out: str
    err: str = ""

    @property
    def ok(self) -> bool:
        return self.code == 0

    @property
    def text(self) -> str:
        return (self.out + self.err).replace("\r", "").strip()


@dataclass
class DeviceInfo:
    serial: str
    state: str                       # device | unauthorized | offline | no permissions
    model: str = ""
    extra: dict = field(default_factory=dict)


class Adb:
    def __init__(self, adb_path: str | None = None, serial: str | None = None):
        self.path = adb_path or find_adb()
        if not self.path:
            raise AdbError("adb not found")
        self.serial = serial

    def _base(self) -> list[str]:
        return [self.path] + (["-s", self.serial] if self.serial else [])

    def run(self, *args: str, timeout: float = 120, input_bytes: bytes | None = None) -> Result:
        try:
            p = subprocess.run(self._base() + list(args), capture_output=True, timeout=timeout,
                               input=input_bytes, stdin=None if input_bytes is not None else subprocess.DEVNULL,
                               **_no_window())
        except subprocess.TimeoutExpired:
            return Result(124, "", f"timed out: {' '.join(args)}")
        return Result(p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace"))

    def run_bytes(self, *args: str, timeout: float = 600) -> bytes:
        p = subprocess.run(self._base() + list(args), capture_output=True, timeout=timeout,
                           stdin=subprocess.DEVNULL, **_no_window())
        return p.stdout

    def stream_to(self, path, *args: str, timeout: float = 1800) -> int:
        """Run adb and write its raw stdout straight to a file (for large files). Returns exit code."""
        with open(path, "wb") as f:
            p = subprocess.run(self._base() + list(args), stdout=f, stderr=subprocess.PIPE,
                               stdin=subprocess.DEVNULL, timeout=timeout, **_no_window())
        return p.returncode

    def stream_from(self, path, *args: str, timeout: float = 1800) -> Result:
        """Run adb with a local file as its stdin (for large files, e.g. `shell content write`)."""
        with open(path, "rb") as f:
            p = subprocess.run(self._base() + list(args), stdin=f, capture_output=True,
                               timeout=timeout, **_no_window())
        return Result(p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace"))

    def shell(self, cmd: str, timeout: float = 120) -> Result:
        return self.run("shell", cmd, timeout=timeout)

    def sh(self, cmd: str, timeout: float = 120) -> str:
        """Shell command output (stripped); empty on failure."""
        return self.shell(cmd, timeout).out.replace("\r", "").strip()

    def devices(self) -> list[DeviceInfo]:
        r = Adb(self.path).run("devices", "-l", timeout=20)
        out = []
        for line in r.out.splitlines()[1:]:
            parts = line.split()
            if len(parts) < 2:
                continue
            info = DeviceInfo(parts[0], parts[1] if parts[1] != "no" else "no permissions")
            for p in parts[2:]:
                if p.startswith("model:"):
                    info.model = p.split(":", 1)[1]
            out.append(info)
        return out

    # --- convenience -------------------------------------------------------
    def prop(self, name: str) -> str:
        return self.sh(f"getprop {shlex.quote(name)}")

    def push_bytes(self, data: bytes, remote: str) -> Result:
        """Write bytes to a file on the device (via stdin, no temp file on this computer)."""
        return self.run("shell", f"cat > {shlex.quote(remote)}", input_bytes=data, timeout=60)

    def packages(self, user: int, flags: str = "") -> set[str]:
        r = self.shell(f"pm list packages {flags} --user {user}")
        return {l.split(":", 1)[1].strip() for l in r.out.splitlines() if l.startswith("package:")}

    def current_user(self) -> int:
        try:
            return int(self.sh("am get-current-user") or 0)
        except ValueError:
            return 0

    def users(self) -> list[tuple[int, str]]:
        out = []
        for line in self.sh("pm list users").splitlines():
            line = line.strip()
            if line.startswith("UserInfo{"):
                inner = line[len("UserInfo{"):].split("}")[0].split(":")
                if len(inner) >= 2 and inner[0].isdigit():
                    out.append((int(inner[0]), inner[1]))
        return out


def _no_window() -> dict:
    """Keep console windows from flashing up on Windows when the GUI runs adb."""
    if os.name == "nt":
        return {"creationflags": 0x08000000}  # CREATE_NO_WINDOW
    return {}
