"""Verified backups of photos/videos (any profile) and game saves, with on-headset SHA-256 checks."""
from __future__ import annotations

import hashlib
import re
import shlex
import time
from pathlib import Path

from . import state as S
from .device import Headset

MEDIA_EXT = ("mp4", "mkv", "webm", "mov", "jpg", "jpeg", "png", "gif", "webp")
SAVE_PATTERNS = ["*save*", "*.sav", "*player*data*", "*progress*", "*profile*.json", "*settings*.json", "*.dat"]
SAVE_EXCLUDE = re.compile(r"/cache/|/Unity/|il2cpp|analytics|crash|/shader", re.I)


def _ensure_user_running(h: Headset, user: int, progress) -> bool:
    """Other profiles must be running (in the background) for their storage to be readable."""
    if user == h.adb.current_user():
        return False
    st = h.adb.sh(f"am get-started-user-state {user}")
    if "RUNNING_UNLOCKED" in st:
        return False
    progress(f"Starting profile {user} in the background to read its files…")
    h.adb.shell(f"am start-user {user}")
    for _ in range(15):
        if "RUNNING_UNLOCKED" in h.adb.sh(f"am get-started-user-state {user}"):
            return True
        time.sleep(1)
    raise RuntimeError(f"Profile {user} didn't unlock. Switch to it once in the headset, then try again.")


def list_media(h: Headset, user: int) -> list[dict]:
    r = h.adb.shell(f"content query --user {user} --uri content://media/external/file --projection _id:_data:_size")
    files = []
    for line in r.out.splitlines():
        m = re.search(r"_id=(\d+), _data=(.*?), _size=(\d+)", line)
        if not m:
            continue
        path = m.group(2)
        if path.rsplit(".", 1)[-1].lower() in MEDIA_EXT and "/Android/" not in path:
            files.append({"id": m.group(1), "path": path, "size": int(m.group(3)),
                          "rel": path.split(f"/storage/emulated/{user}/", 1)[-1]})
    return files


def list_saves(h: Headset) -> list[str]:
    """Save-like files in /sdcard/Android/data (current profile only; other apps' private storage is unreachable)."""
    names = " -o ".join(f"-iname {shlex.quote(p)}" for p in SAVE_PATTERNS)
    out = h.adb.sh(f"find /sdcard/Android/data -maxdepth 4 -type f \\( {names} \\) 2>/dev/null")
    return [l for l in out.splitlines() if l and not SAVE_EXCLUDE.search(l)
            and "/com.cleanmyvr." not in l]


def backup(h: Headset, user: int, dest: str, media: bool = True, saves: bool = True, progress=lambda m: None) -> dict:
    dest_p = Path(dest).expanduser()
    dest_p.mkdir(parents=True, exist_ok=True)
    started = _ensure_user_running(h, user, progress)
    sums, ok, bad, total = [], 0, [], 0
    try:
        if media:
            files = list_media(h, user)
            progress(f"{len(files)} photos/videos ({sum(f['size'] for f in files) / 1e9:.2f} GB)")
            for i, f in enumerate(files, 1):
                local = dest_p / f["rel"]
                local.parent.mkdir(parents=True, exist_ok=True)
                uri = f"content://media/external/file/{f['id']}"
                h.adb.stream_to(local, "exec-out", "content", "read", "--user", str(user), "--uri", uri)
                dev = h.adb.sh(f"content read --user {user} --uri {uri} | sha256sum", timeout=600).split()[:1]
                loc = _sha(local)
                if dev and dev[0] == loc and local.stat().st_size == f["size"]:
                    ok += 1
                    total += f["size"]
                    sums.append(f"{loc}  {f['rel']}")
                else:
                    bad.append(f["rel"])
                progress(f"[{i}/{len(files)}] {f['rel']}")
        if saves and user == h.adb.current_user():
            for path in list_saves(h):
                rel = "game-saves/" + path.split("/sdcard/Android/data/", 1)[1]
                local = dest_p / rel
                local.parent.mkdir(parents=True, exist_ok=True)
                h.adb.run("pull", path, str(local))
                dev = h.adb.sh(f"sha256sum {shlex.quote(path)}").split()[:1]
                loc = _sha(local) if local.exists() else ""
                if dev and dev[0] == loc:
                    ok += 1
                    total += local.stat().st_size
                    sums.append(f"{loc}  {rel}")
                    progress(f"save: {rel}")
                else:
                    bad.append(rel)
    finally:
        if started:
            h.adb.shell(f"am stop-user {user}")
    (dest_p / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
    st = S.load(h)
    st["backups"].append({"at": S.now(), "user": user, "files": ok, "bytes": total, "failed": len(bad)})
    st["backups"] = st["backups"][-50:]
    S.record(st, "backup", user, files=ok, failed=len(bad))
    S.save(h, st)
    progress(f"Backed up {ok} files ({total / 1e9:.2f} GB), every one SHA-256 verified on the headset."
             + (f" {len(bad)} FAILED: {', '.join(bad[:5])}" if bad else ""))
    return {"files": ok, "bytes": total, "failed": bad, "dest": str(dest_p)}


MIME = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp",
        "mp4": "video/mp4", "mkv": "video/x-matroska", "webm": "video/webm", "mov": "video/quicktime"}


def _sql(v: str) -> str:
    return "'" + v.replace("'", "''") + "'"


def _media_id(h: Headset, user: int, base: str, name: str, rel_dir: str) -> str | None:
    where = f"_display_name={_sql(name)} AND relative_path={_sql(rel_dir)}"
    out = h.adb.sh(f"content query --user {user} --uri {base} --projection _id --where {shlex.quote(where)}")
    m = re.search(r"_id=(\d+)", out)
    return m.group(1) if m else None


def _device_sha(h: Headset, user: int, uri: str) -> str:
    out = h.adb.sh(f"content read --user {user} --uri {uri} | sha256sum", timeout=900).split()
    return out[0] if out else ""


def restore_media_file(h: Headset, user: int, local: Path, rel: str, digest: str) -> str:
    """Put one photo/video into a profile through MediaStore (works for any profile, unlike adb push,
    and the file shows up in the gallery right away). Returns "copied", "present" or "failed"."""
    ext = rel.rsplit(".", 1)[-1].lower()
    base = f"content://media/external_primary/{'video' if MIME[ext].startswith('video') else 'images'}/media"
    name = rel.rsplit("/", 1)[-1]
    rel_dir = rel.rsplit("/", 1)[0] + "/" if "/" in rel else ""
    mid = _media_id(h, user, base, name, rel_dir)
    if mid and _device_sha(h, user, f"{base}/{mid}") == digest:
        return "present"
    if not mid:
        binds = " ".join(f"--bind {shlex.quote(b)}" for b in
                         (f"_display_name:s:{name}", f"relative_path:s:{rel_dir}", f"mime_type:s:{MIME[ext]}"))
        h.adb.shell(f"content insert --user {user} --uri {base} {binds}")
        mid = _media_id(h, user, base, name, rel_dir)
        if not mid:
            return "failed"
    uri = f"{base}/{mid}"
    h.adb.stream_from(local, "shell", f"content write --user {user} --uri {uri}")
    if _device_sha(h, user, uri) == digest:
        return "copied"
    h.adb.shell(f"content delete --user {user} --uri {uri}")      # don't leave a broken file behind
    return "failed"


def restore_backup(h: Headset, src: str, user: int | None = None, progress=lambda m: None) -> dict:
    """Copy a backup made by backup() onto a profile (default: the active one), SHA-256 verified on the headset.
    Photos/videos work for any profile; game saves only for the active profile and only for installed games."""
    src_p = Path(src).expanduser()
    current = h.adb.current_user()
    user = current if user is None else int(user)
    sums = {}
    for line in (src_p / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        if "  " in line:
            digest, rel = line.split("  ", 1)
            sums[rel] = digest
    installed = h.adb.packages(user)
    copied, present, skipped, failed = 0, 0, [], []
    started = _ensure_user_running(h, user, progress)
    try:
        for i, (rel, digest) in enumerate(sums.items(), 1):
            local = src_p / rel
            if not local.exists() or _sha(local) != digest:
                skipped.append(f"{rel} (changed or missing on this computer)")
                continue
            if rel.startswith("game-saves/"):
                pkg = rel.split("/")[1]
                if user != current:
                    skipped.append(f"{rel} (game saves only restore to the active profile)")
                    continue
                if pkg not in installed:
                    skipped.append(f"{rel} (install {pkg} first, but don't open it yet)")
                    continue
                remote = "/sdcard/Android/data/" + rel.split("/", 1)[1]
                h.adb.shell(f"mkdir -p {shlex.quote(remote.rsplit('/', 1)[0])}")
                h.adb.run("push", str(local), remote, timeout=900)
                ok = h.adb.sh(f"sha256sum {shlex.quote(remote)}").split()[:1] == [digest]
                result = "copied" if ok else "failed"
            elif rel.rsplit(".", 1)[-1].lower() in MIME:
                result = restore_media_file(h, user, local, rel, digest)
            else:
                skipped.append(f"{rel} (not a photo, video or game save)")
                continue
            if result == "copied":
                copied += 1
            elif result == "present":
                present += 1
            else:
                failed.append(rel)
            progress(f"[{i}/{len(sums)}] {rel}: {'already there' if result == 'present' else result}")
    finally:
        if started:
            h.adb.shell(f"am stop-user {user}")
    st = S.load(h)
    S.record(st, "restore_backup", user, files=copied, already_there=present,
             skipped=len(skipped), failed=len(failed), verified="sha256")
    S.save(h, st)
    progress(f"Restored {copied} file(s) to profile {user}, {present} were already there; every one SHA-256 verified."
             + (f" {len(failed)} FAILED: {', '.join(failed[:5])}" if failed else ""))
    return {"copied": copied, "present": present, "skipped": skipped, "failed": failed, "user": user}


def _sha(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
