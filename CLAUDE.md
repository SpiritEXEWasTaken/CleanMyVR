# CLAUDE.md

Instructions for Claude Code. The human contributor guide is CONTRIBUTING.md; device verification is
docs/ADDING_DEVICES.md (follow it step by step when asked to add or update a headset).

CleanMyVR debloats, cleans, backs up and fixes Meta Quest headsets over ADB, without root. Only the
Quest 3 profile is tested. A mistake here can break a real person's headset, so the rules below override
convenience.

## Commands

- Unit tests (no headset; run after every change): `cd app && python3 -m unittest discover -s tests -t . -v`
  (`-s tests` matters: plain discovery imports the GUI and fails without pywebview)
- CLI with a headset: `./launch-commandline.sh`. GUI smoke test: `CLEANMYVR_SMOKE_TEST=1 ./launch-gui.sh`
- Launcher: `cd launcher && ./build.sh && ./install.sh` (JDK 17 + Android SDK, no Gradle)
- Windows EXE: `cd app && pyinstaller packaging/cleanmyvr.spec` (CI: `.github/workflows/release.yml`)

## Commits and releases

- Commit subjects start with `feat:`, `fix:`, `docs:`, `ci:`, `test:`, `refactor:` or `chore:`; release
  notes are generated from them (`.github/scripts/release_notes.py`).
- A release happens automatically when `__version__` in `app/cleanmyvr/__init__.py` changes on `main`
  (`.github/workflows/release.yml`). Only bump it when the user asks for a release. The launcher's
  versionName/versionCode are stamped from it by `launcher/build.sh`.
- Never change or regenerate the launcher signing key (repo secrets); installed launchers couldn't update.

## Map

- `app/cleanmyvr/devices/*.json`: per-headset debloat lists, `never_remove`, `protected`, `comes_back`,
  update blocking. Schema enforced by `tests/test_core.py::Profiles`; start new ones from `_template.json`
- `updater.py` (self-update from GitHub releases), `adb.py` (all adb calls), `device.py` (profile matching, `is_never_remove`), `state.py` (on-headset
  record), `actions.py`, `backup.py`, `launcher.py`, `extras.py`, `cli.py`, `gui/` (pywebview `Api` +
  plain `gui/web/` HTML/CSS/JS; GUI and CLI must both expose new features)
- `launcher/src/com/cleanmyvr/launcher/`: Java launcher. Store picks live in the separate repo
  SpiritEXEWasTaken/launcher-appstore (`catalog.json`, fetched daily via `Store.CATALOG_URL`; edit it there).
  `launcher/assets/catalog.json` is only the offline fallback; releases refresh it from that repo
- The accessibility service (`LibraryInterceptor`) may read window content only for the Force stop flow
  (`forceStop`, App info of the app the user chose, time-limited). Keep it that way; README promises it
- Tests use `FakeAdb` in `tests/test_core.py`; extend it rather than calling a real device in tests

## Hard rules

1. Never add a package to a debloat list, or set `"tested": true` / add to `tested_on`, unless the user
   removed it on a real headset of that model, rebooted, and confirmed Home, Store, a game and Meta login
   work. Don't infer safety from another model, a forum, or the package name.
2. Removal is only `pm uninstall -k --user <id>`; restore is `cmd package install-existing --user <id>`.
   Never `pm uninstall` without `--user`, `pm disable` loops, or `rm` under `/system` or `/data/app`.
3. Anything the Store, games or Meta accounts need goes in `never_remove`. When unsure, leave it out.
4. Untested profiles keep `"tested": false` and empty debloat lists.
5. Every action that changes the headset calls `state.record` + `state.save`.
6. Every adb subprocess gets `stdin=subprocess.DEVNULL` (or a file via `Adb.stream_from`). Otherwise adb
   eats the user's typed input. Same for shell loops: `adb ... </dev/null`.
7. Never commit personal data (backups, ROMs, `.glb`, logs with serials) or add downloads of copyrighted
   games/ROMs.
8. With a headset connected, ask the user before any command that changes it (uninstall, install,
   settings, deleting files). Read-only commands (`pm list`, `dumpsys`, `getprop`, `content query`) are fine.

## Quest facts (non-obvious, verified)

- Other profiles' storage isn't reachable at `/storage/emulated/<id>`. Use MediaStore after
  `am start-user <id>`: `content query/read --user <id>`. To write: `content insert` (`_display_name`,
  `relative_path`, `mime_type`; no `is_pending`, pending rows are hidden from queries), then
  `content write --uri .../<id>` with the file on stdin (`backup.restore_media_file`). Quote the whole
  remote command; names contain spaces.
- Apps can't read logcat or the shell's private displays. Debug the launcher with `adb logcat` from the computer.
- `oculuspreferences` (home environment) needs root to write; read it with `dumpsys -t 2 DumpsysProxy VRShell`.
- `com.oculus.updater` and `com.oculus.q4bservice` can't be uninstalled or disabled (`protected`).
- Meta's store reinstalls some removed apps (`comes_back`); Recheck reports them as drift.
- `com.android.settings/.Settings` isn't exported to apps; open App info (`ACTION_APPLICATION_DETAILS_SETTINGS`) instead.
