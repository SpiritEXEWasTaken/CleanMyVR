"""Core tests with a fake headset (no ADB or device needed): python3 -m unittest -v"""
import hashlib
import json
import re
import shlex
import tempfile
import unittest
from pathlib import Path

from unittest import mock

from cleanmyvr import backup, launcher, updater

from cleanmyvr import actions, device
from cleanmyvr import state as S
from cleanmyvr.adb import Result

REQUIRED = {"id", "name", "match", "tested", "tested_on", "debloat", "update_blocking", "never_remove",
            "never_remove_prefixes", "protected", "comes_back", "environment", "notes"}


class FakeAdb:
    """Simulates the shell commands CleanMyVR uses."""

    def __init__(self, packages, users=((0, "Owner"),), props=None, appops_ignored=(), state=None):
        self.pkgs = {u: set(packages) for u, _ in users}
        self.user_list = list(users)
        self.props = props or {}
        self.ignored = set(appops_ignored)
        self.files = {}
        if state is not None:
            self.files[S.STATE_PATH] = json.dumps(state)
        self.log = []
        self.media = {}            # (user, uri base) -> {id: {"name", "rel", "data"}}
        self.next_id = 1000

    def prop(self, name):
        return self.props.get(name, "")

    def packages(self, user, flags=""):
        return set(self.pkgs.get(user, set()))

    def users(self):
        return self.user_list

    def current_user(self):
        return 0

    def push_bytes(self, data, remote):
        self.files[remote] = data.decode()
        return Result(0, "")

    def run(self, *args, timeout=120):
        return Result(0, "Success")

    def stream_from(self, path, *args, timeout=1800):
        m = re.search(r"--user (\d+) --uri (\S+)/(\d+)", args[-1])
        self.media[(int(m.group(1)), m.group(2))][m.group(3)]["data"] = Path(path).read_bytes()
        return Result(0, "")

    def _content(self, cmd):
        user = int(re.search(r"--user (\d+)", cmd).group(1))
        uri = re.search(r"--uri (\S+)", cmd).group(1)
        if cmd.startswith("content read"):
            base, mid = uri.rsplit("/", 1)
            return Result(0, hashlib.sha256(self.media[(user, base)][mid]["data"]).hexdigest() + "  -")
        rows = self.media.setdefault((user, uri), {})
        if cmd.startswith("content insert"):
            b = dict(x.split(":s:", 1) for x in shlex.split(cmd)[1:] if ":s:" in x)
            self.next_id += 1
            rows[str(self.next_id)] = {"name": b["_display_name"], "rel": b["relative_path"], "data": b""}
            return Result(0, "")
        if cmd.startswith("content query"):
            where = shlex.split(cmd)[-1]
            hits = [i for i, r in rows.items() if f"_display_name='{r['name']}'" in where and f"relative_path='{r['rel']}'" in where]
            return Result(0, "\n".join(f"Row: 0 _id={i}" for i in hits) or "No result found.")
        return Result(0, "")

    def sh(self, cmd, timeout=120):
        return self.shell(cmd).out.strip()

    def shell(self, cmd, timeout=120):
        self.log.append(cmd)
        parts = cmd.split()
        if cmd.startswith("content "):
            return self._content(cmd)
        if cmd.startswith("am get-started-user-state"):
            return Result(0, "RUNNING_UNLOCKED")
        if cmd.startswith("dumpsys package com.cleanmyvr.launcher"):
            return Result(0, f"    versionName={self.props.get('launcher_version', '1.0.0')}")
        if cmd.startswith("cat "):
            return Result(0, self.files.get(parts[1], ""))
        if cmd.startswith("pm uninstall"):
            user, pkg = int(parts[parts.index("--user") + 1]), parts[-1]
            if pkg in self.pkgs[user]:
                self.pkgs[user].discard(pkg)
                return Result(0, "Success")
            return Result(1, "Failure [not installed for 0]")
        if cmd.startswith("cmd package install-existing"):
            user, pkg = int(parts[parts.index("--user") + 1]), parts[-1]
            self.pkgs[user].add(pkg)
            return Result(0, f"Package {pkg} installed for user: {user}")
        if cmd.startswith("cmd appops get"):
            return Result(0, "\n".join(f"{op}: ignore" for op in self.ignored))
        if cmd.startswith("cmd appops set"):
            self.ignored.add(parts[-2])
            return Result(0, "")
        return Result(0, "")


QUEST3_PROPS = {"ro.product.device": "eureka", "ro.product.model": "Quest 3",
                "ro.build.display.id": "UP1A.231005.007.A1", "ro.build.version.incremental": "53145330022100520"}


def headset(fake):
    return device.Headset.connect(fake)


class Profiles(unittest.TestCase):
    def test_all_profiles_valid(self):
        profiles = device.load_profiles()
        self.assertTrue(profiles)
        for p in profiles:
            with self.subTest(p["id"]):
                self.assertFalse(REQUIRED - p.keys(), f"missing keys {REQUIRED - p.keys()}")
                pkgs = [i["pkg"] for lvl in p["debloat"].values() for i in lvl]
                self.assertEqual(len(pkgs), len(set(pkgs)), "duplicate package in debloat lists")
                for pkg in pkgs:
                    self.assertNotIn(pkg, p["never_remove"])
                    self.assertFalse(any(pkg.startswith(x) for x in p["never_remove_prefixes"]))
                    self.assertNotIn(pkg, p["protected"], "protected packages can't be removed")
                if not p["tested"]:
                    self.assertEqual(pkgs, [], "untested profiles must not have debloat lists")
                else:
                    self.assertTrue(p["tested_on"], "tested profiles need tested_on builds")

    def test_matching(self):
        self.assertEqual(device.match_profile("eureka", "")["id"], "quest3")
        self.assertEqual(device.match_profile("", "Quest 3")["id"], "quest3")
        self.assertEqual(device.match_profile("nope", "Mystery VR")["id"], "unknown")

    def test_toybox_is_a_game_not_bloat(self):
        q3 = device.match_profile("eureka", "")
        pkgs = [i["pkg"] for lvl in q3["debloat"].values() for i in lvl]
        self.assertNotIn("com.meta.curio.toybox", pkgs)


class Actions(unittest.TestCase):
    def setUp(self):
        self.fake = FakeAdb(["com.oculus.facebook", "com.oculus.quickpromotionservice", "com.oculus.store",
                             "com.oculus.updater", "com.cleanmyvr.launcher"], props=QUEST3_PROPS)
        self.h = headset(self.fake)

    def test_plan_and_debloat_records_state(self):
        plan = {p["pkg"]: p["status"] for p in actions.plan_debloat(self.h, "balanced", 0)}
        self.assertEqual(plan["com.oculus.facebook"], "remove")
        self.assertEqual(plan["com.oculus.igvr"], "absent")
        actions.debloat(self.h, "balanced", 0)
        st = S.load(self.h)
        self.assertIn("com.oculus.facebook", st["users"]["0"]["removed"])
        self.assertEqual(st["users"]["0"]["debloat_level"], "balanced")
        self.assertIn("com.oculus.store", self.fake.pkgs[0], "store must never be touched")

    def test_recheck_flags_reinstalled_app(self):
        actions.debloat(self.h, "balanced", 0)
        self.fake.pkgs[0].add("com.oculus.facebook")          # Meta reinstalls it
        items = actions.recheck(self.h)["items"]
        deb = next(i for i in items if i["area"] == "debloat")
        self.assertEqual(deb["status"], "drift")
        self.assertIn("com.oculus.facebook", deb["came_back"])

    def test_updates_drift_when_build_changes(self):
        actions.block_updates(self.h)
        self.assertEqual(actions.updates_status(self.h, S.load(self.h))["issues"], [])
        self.fake.props["ro.build.version.incremental"] = "99999"
        h2 = headset(self.fake)
        issues = actions.updates_status(h2, S.load(h2))["issues"]
        self.assertTrue(any("build changed" in i for i in issues))

    def test_restore(self):
        actions.debloat(self.h, "balanced", 0)
        actions.restore(self.h, 0)
        self.assertIn("com.oculus.facebook", self.fake.pkgs[0])
        self.assertEqual(S.load(self.h)["users"]["0"]["removed"], [])

    def test_unchecked_apps_are_kept_and_remembered(self):
        self.fake.pkgs[0] |= {"com.whatsapp", "com.facebook.arvr.quillplayer"}
        actions.debloat(self.h, "balanced", 0, keep=["com.whatsapp"])
        self.assertIn("com.whatsapp", self.fake.pkgs[0], "unchecked app must stay")
        self.assertNotIn("com.facebook.arvr.quillplayer", self.fake.pkgs[0])
        plan = {p["pkg"]: p for p in actions.plan_debloat(self.h, "balanced", 0)}
        self.assertTrue(plan["com.whatsapp"]["keep"], "the choice is remembered for next time")
        deb = next(i for i in actions.recheck(self.h)["items"] if i["area"] == "debloat")
        self.assertEqual(deb["status"], "ok", "a kept app isn't 'drift'")
        actions.debloat(self.h, "balanced", 0)            # e.g. Fix what changed: no keep passed
        self.assertIn("com.whatsapp", self.fake.pkgs[0], "fixes respect the remembered choice")

    def test_untested_headset_refuses_debloat(self):
        fake = FakeAdb(["com.oculus.facebook"], props={"ro.product.device": "hollywood", "ro.product.model": "Quest 2"})
        h = headset(fake)
        self.assertFalse(h.tested)
        with self.assertRaises(RuntimeError):
            actions.debloat(h, "balanced", 0)
        self.assertIn("com.oculus.facebook", fake.pkgs[0])


class QuestHomeSwitcher(unittest.TestCase):
    def setUp(self):
        from cleanmyvr import extras
        self.extras = extras
        self.apk = b"qhs-apk"
        old = "2026-01-01T00:00:00Z"
        self.files = {
            "rels": json.dumps([
                {"tag_name": "v9.9.9", "published_at": "2999-01-01T00:00:00Z", "draft": False, "prerelease": False,
                 "assets": [{"name": "Q-9.apk", "browser_download_url": "new.apk"}, {"name": "SHA256SUMS.txt", "browser_download_url": "new.sums"}]},
                {"tag_name": "v2.3.0", "published_at": old, "draft": False, "prerelease": False,
                 "assets": [{"name": "Q-2.3.0.apk", "browser_download_url": "a.apk"}, {"name": "SHA256SUMS.txt", "browser_download_url": "a.sums"}]},
            ]).encode(),
            "a.apk": self.apk,
            "a.sums": f"{hashlib.sha256(self.apk).hexdigest()}  Q-2.3.0.apk\n".encode(),
        }

    def get(self, url, timeout=120):
        return self.files["rels"] if "api.github.com" in url else self.files[url]

    def run_install(self, home):
        fake = FakeAdb([], props=QUEST3_PROPS)
        fake.run = mock.Mock(return_value=Result(0, "Success"))
        h = headset(fake)
        env = {"available": True, "selected": home, "resolved": home, "loaded": home}
        with mock.patch.object(self.extras, "_get", side_effect=self.get), \
                mock.patch.object(self.extras.time, "sleep"), \
                mock.patch("cleanmyvr.actions.environment_status", return_value=env):
            return fake, h, self.extras.install_qhs(h)

    def test_installs_newest_release_older_than_14_days_verified(self):
        fake, h, r = self.run_install("apk://com.meta.shell.env.footprint.haven2025/x")
        self.assertEqual(r["version"], "2.3.0", "the brand-new 9.9.9 is skipped")
        args = fake.run.call_args[0]
        self.assertIn("-g", args)
        self.assertTrue(any(QHS_START in c for c in fake.log))
        self.assertTrue(any(c == "settings put global adb_wifi_enabled 1" for c in fake.log), "wireless debugging on")
        self.assertEqual(S.load(h)["history"][-1]["action"], "install_quest_home_switcher")

    def test_refuses_without_loft(self):
        with self.assertRaises(RuntimeError):
            self.run_install("apk://com.oculus.environment.prod.cyberhome/x")

    def test_bad_checksum_installs_nothing(self):
        self.files["a.sums"] = b"0" * 64 + b"  Q-2.3.0.apk\n"
        with self.assertRaises(RuntimeError):
            self.run_install("haven2025")


QHS_START = "am start --user 0 -n app.questhomeswitcher/"


class Takeover(unittest.TestCase):
    DUMP = """User state[
     attributes:{id=0, touchExplorationEnabled=false}
     Bound services:{%s}
     Enabled services:{{com.cleanmyvr.launcher/com.cleanmyvr.launcher.LibraryInterceptor}}
     Crashed services:{%s}
"""

    def fake(self, enabled, bound, crashed=""):
        f = FakeAdb(["com.cleanmyvr.launcher"], props=QUEST3_PROPS)
        orig = f.shell
        def shell(cmd, timeout=120):
            if cmd.startswith("settings --user 0 get secure enabled_accessibility_services"):
                return Result(0, launcher.SERVICE if enabled else "null")
            if cmd == "dumpsys accessibility":
                return Result(0, self.DUMP % ("Service[label=Launcher: replace Library, x]" if bound else "", crashed))
            return orig(cmd, timeout)
        f.shell = shell
        return f

    def test_states(self):
        self.assertEqual(launcher.takeover_state(headset(self.fake(True, True))), "running")
        # some profiles show the class name instead of the label
        f = self.fake(True, False)
        self.DUMP_BOUND = "Service[label=com.cleanmyvr.launcher.LibraryInterceptor, x]"
        orig = f.shell
        f.shell = lambda cmd, timeout=120: Result(0, self.DUMP % (self.DUMP_BOUND, "")) if cmd == "dumpsys accessibility" else orig(cmd, timeout)
        self.assertEqual(launcher.takeover_state(headset(f)), "running")
        self.assertEqual(launcher.takeover_state(headset(self.fake(True, False))), "stopped")
        self.assertEqual(launcher.takeover_state(headset(self.fake(True, True, "com.cleanmyvr.launcher/x"))), "stopped")
        self.assertEqual(launcher.takeover_state(headset(self.fake(False, False))), "off")

    def test_recheck_offers_repair_and_repair_restarts(self):
        f = self.fake(True, False)
        h = headset(f)
        item = next(i for i in actions.recheck(h)["items"] if i["area"] == "launcher")
        self.assertEqual(item["status"], "drift")
        launcher.repair(h, 0)
        self.assertTrue(any("pm grant --user 0 com.cleanmyvr.launcher android.permission.WRITE_SECURE_SETTINGS" in c for c in f.log))
        self.assertEqual(S.load(h)["history"][-1]["action"], "repair_launcher")


class UpdateBlocking(unittest.TestCase):
    def test_blocking_covers_every_profile(self):
        fake = FakeAdb(["com.oculus.nux.ota", "com.oculus.updater"], users=((0, "Owner"), (10, "Sister")), props=QUEST3_PROPS)
        h = headset(fake)
        self.assertTrue(any("profile 10" in i for i in actions.updates_status(h, S.load(h))["issues"]))
        actions.block_updates(h)
        self.assertNotIn("com.oculus.nux.ota", fake.pkgs[0])
        self.assertNotIn("com.oculus.nux.ota", fake.pkgs[10])
        self.assertEqual(actions.updates_status(h, S.load(h))["issues"], [])


class RestoreBackup(unittest.TestCase):
    def test_media_goes_to_another_profile_verified_and_reruns_skip(self):
        fake = FakeAdb([], users=((0, "Owner"), (10, "Sister")), props=QUEST3_PROPS)
        h = headset(fake)
        with tempfile.TemporaryDirectory() as d:
            files = {"Oculus/Screenshots/a.jpg": b"jpg", "Movies/Gorilla Tag/b c.mp4": b"video",
                     "game-saves/com.game/save.dat": b"save"}
            for rel, data in files.items():
                (Path(d) / rel).parent.mkdir(parents=True, exist_ok=True)
                (Path(d) / rel).write_bytes(data)
            (Path(d) / "SHA256SUMS.txt").write_text(
                "".join(f"{hashlib.sha256(v).hexdigest()}  {k}\n" for k, v in files.items()))
            r = backup.restore_backup(h, d, 10)
            self.assertEqual((r["copied"], r["failed"]), (2, []))
            self.assertTrue(any("active profile" in s for s in r["skipped"]), "saves never go to another profile")
            vids = fake.media[(10, "content://media/external_primary/video/media")]
            self.assertEqual([(v["rel"], v["name"], v["data"]) for v in vids.values()],
                             [("Movies/Gorilla Tag/", "b c.mp4", b"video")])
            self.assertEqual(backup.restore_backup(h, d, 10)["present"], 2, "a re-run copies nothing twice")
        self.assertEqual(S.load(h)["history"][-1]["user"], 10)


class Updates(unittest.TestCase):
    def release(self, tag, assets=()):
        return json.dumps({"tag_name": tag, "body": "notes", "html_url": "u",
                           "assets": [{"name": n, "browser_download_url": n} for n in assets]}).encode()

    def test_newer_release_is_offered_and_same_or_older_is_not(self):
        with mock.patch.object(updater, "__version__", "1.2.0"):
            for tag, expect in (("v1.10.0", True), ("v1.2.1", True), ("v1.2.0", False), ("v1.1.9", False), ("v1.2", False)):
                with self.subTest(tag), mock.patch.object(updater, "_get", return_value=self.release(tag)):
                    self.assertEqual(updater.check()["available"], expect)

    def test_offline_says_nothing(self):
        with mock.patch.object(updater, "_get", side_effect=OSError("offline")):
            self.assertFalse(updater.check()["available"])

    def test_windows_update_refuses_a_download_with_the_wrong_checksum(self):
        files = {"SHA256SUMS.txt": f"{'0' * 64}  CleanMyVR-windows.zip\n".encode(), "CleanMyVR-windows.zip": b"zip"}
        info = {"latest": "9.0.0", "kind": "exe", "assets": {n: n for n in files}}
        with mock.patch.object(updater, "_get", side_effect=lambda url, timeout=10: files[url]), \
                mock.patch.object(updater.subprocess, "Popen") as popen:
            with self.assertRaises(RuntimeError):
                updater.apply(info)
            popen.assert_not_called()

    def test_recheck_flags_an_old_launcher(self):
        fake = FakeAdb(["com.cleanmyvr.launcher"], props=dict(QUEST3_PROPS, launcher_version="0.9"))
        item = next(i for i in actions.recheck(headset(fake))["items"] if i["area"] == "launcher")
        self.assertEqual(item["status"], "todo")
        fake.props["launcher_version"] = actions.__version__
        item = next(i for i in actions.recheck(headset(fake))["items"] if i["area"] == "launcher")
        self.assertEqual(item["status"], "ok")


if __name__ == "__main__":
    unittest.main()
