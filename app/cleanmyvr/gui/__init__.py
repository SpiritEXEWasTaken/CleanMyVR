"""Desktop GUI: a pywebview window showing gui/web/index.html, backed by the shared core."""
from __future__ import annotations

import json
import threading
import traceback
from pathlib import Path

import webview  # pywebview

from .. import __version__, actions, backup, blocker, extras, launcher, updater
from .. import state as S
from ..device import _resource_dir
from ..session import connect

WEB = _resource_dir() / "gui" / "web"


class Api:
    """Every public method is callable from JavaScript as window.pywebview.api.<name>(...)."""

    def __init__(self):
        self._h = None
        self._window = None
        self._busy = False

    # ---------------------------------------------------------------- info
    def version(self):
        return __version__

    def connect(self, allow_download=False, serial=None):
        r = connect(serial=serial or None, allow_download=bool(allow_download), progress=self._log)
        self._h = r.pop("headset")
        if self._h:
            r["device"] = self._h.summary()
            r["users"] = actions.profiles(self._h)
            r["launcher_apk"] = bool(launcher.find_apk())
        return r

    def recheck(self):
        rc = actions.recheck(self._need())
        return {"items": rc["items"], "history": rc["state"].get("history", [])[-15:],
                "backups": rc["state"].get("backups", [])[-5:]}

    def plan_debloat(self, level, user):
        return actions.plan_debloat(self._need(), level, int(user))

    def removed(self, user):
        return S.user_state(S.load(self._need()), int(user))["removed"]

    def storage(self):
        return actions.storage_report(self._need())

    def environment(self):
        return actions.environment_status(self._need())

    def blocker_status(self):
        return blocker.status(self._need())

    def extras_status(self):
        h = self._need()
        return {**extras.status(h), "qhs": extras.qhs_status(h)}

    def default_backup_dir(self, user):
        return str(Path.home() / "Quest-Backups" / f"profile-{user}")

    def choose_folder(self):
        r = self._window.create_file_dialog(webview.FileDialog.FOLDER)
        return r[0] if r else None

    # ---------------------------------------------------------------- updates (no headset needed)
    def check_update(self):
        self._update = updater.check()
        return {k: v for k, v in self._update.items() if k != "assets"}

    def apply_update(self):
        info = getattr(self, "_update", None) or updater.check()

        def worker():
            try:
                r, err = updater.apply(info, self._log), None
            except Exception as e:
                r, err = None, str(e) or e.__class__.__name__
            self._js("onUpdateDone", r, err)
            if r and r["restart"] and info.get("kind") == "exe":
                self._window.destroy()          # the update script waits for this process, then reopens
        threading.Thread(target=worker, daemon=True).start()
        return {"started": True}

    # ---------------------------------------------------------------- tasks (run in the background)
    def run(self, task, args=None):
        if self._busy:
            return {"started": False, "error": "Another task is still running."}
        h = self._need()
        args = args or {}
        jobs = {
            "debloat": lambda: actions.debloat(h, args["level"], int(args["user"]), self._log,
                                               keep=args.get("keep")),
            "restore": lambda: actions.restore(h, int(args["user"]), progress=self._log),
            "block_updates": lambda: actions.block_updates(h, self._log),
            "fix_drift": lambda: self._fix_drift(h),
            "backup": lambda: backup.backup(h, int(args["user"]), args["dest"], args.get("media", True),
                                            args.get("saves", True), self._log),
            "restore_backup": lambda: backup.restore_backup(h, args["src"], int(args["user"]), self._log),
            "clear_caches": lambda: actions.clear_caches(h, self._log),
            "delete_captures": lambda: actions.delete_captures(h, self._log),
            "install_launcher": lambda: launcher.install(h, h.adb.current_user(), self._log),
            "uninstall_launcher": lambda: launcher.uninstall(h, h.adb.current_user(), self._log),
            "repair_launcher": lambda: launcher.repair(h, None, self._log),
            "adopt": lambda: actions.adopt(h, self._log),
            "install_play_apps": lambda: extras.install_play_apps(h, self._log),
            "remove_play_apps": lambda: extras.remove_play_apps(h, self._log),
            "install_qhs": lambda: extras.install_qhs(h, self._log),
            "block": lambda: blocker.block(h, list(args["pkgs"]), self._log),
            "unblock": lambda: blocker.unblock(h, list(args["pkgs"]), self._log),
            "remove_qhs": lambda: extras.remove_qhs(h, self._log),
        }
        if task not in jobs:
            return {"started": False, "error": f"unknown task {task}"}
        self._busy = True

        def worker():
            try:
                result, error = jobs[task](), None
            except Exception as e:  # report to the UI instead of dying silently
                result, error = None, str(e) or e.__class__.__name__
                traceback.print_exc()
            self._busy = False
            self._js("onTaskDone", task, result, error)

        threading.Thread(target=worker, daemon=True).start()
        return {"started": True}

    def _fix_drift(self, h):
        st = S.load(h)
        for it in actions.recheck(h)["items"]:
            if it["status"] != "drift":
                continue
            if it["area"] == "debloat":
                level = S.user_state(st, it["user"]).get("debloat_level") or "balanced"
                actions.debloat(h, level, it["user"], self._log)
            elif it["area"] == "updates":
                actions.block_updates(h, self._log)
            elif it["area"] == "blocked":
                blocker.block(h, it.get("packages", []), self._log)
            elif it["area"] == "launcher":
                launcher.repair(h, it.get("user"), self._log)
        return {"fixed": True}

    # ---------------------------------------------------------------- helpers
    def _need(self):
        if not self._h:
            raise RuntimeError("No headset connected")
        return self._h

    def _log(self, msg):
        self._js("onLog", str(msg))

    def _js(self, fn, *args):
        if self._window:
            self._window.evaluate_js(f"window.{fn} && window.{fn}(" + ",".join(json.dumps(a) for a in args) + ")")


def main() -> None:
    api = Api()
    api._window = webview.create_window(f"CleanMyVR {__version__}", str(WEB / "index.html"), js_api=api,
                                       width=1100, height=760, min_size=(820, 600), background_color="#0E0F13")
    import os
    if os.environ.get("CLEANMYVR_SMOKE_TEST"):     # CI / dev: connect, check the page rendered, then quit
        def smoke():
            import time
            for _ in range(60):
                time.sleep(0.5)
                txt = api._window.evaluate_js("document.querySelector('#devname') && document.querySelector('#devname').innerText")
                n = api._window.evaluate_js("document.querySelectorAll('#ov-items .card').length")
                if txt and txt != "Not connected" and n:
                    break
            print("SMOKE devname:", repr(txt))
            print("SMOKE overview items:", api._window.evaluate_js("document.querySelectorAll('#ov-items .card').length"))
            api._window.destroy()
        webview.start(smoke, gui=_backend())
    else:
        webview.start(gui=_backend())


def _backend():
    import platform
    return "gtk" if platform.system() == "Linux" else None
