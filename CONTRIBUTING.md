# Contributing to CleanMyVR

Thanks for helping! CleanMyVR was built and tested on **one Meta Quest 3**, so the most useful thing you
can do is **verify it on another headset** (Quest 2, Quest 3S, Quest Pro, or a newer Horizon OS build).
[docs/ADDING_DEVICES.md](docs/ADDING_DEVICES.md) walks you through it step by step.

Other welcome contributions: bug reports (with your headset model and build), Store picks (in
[launcher-appstore](https://github.com/SpiritEXEWasTaken/launcher-appstore)),
translations of the UI text, and fixes.

## How the project is laid out

| Path | What it is |
| --- | --- |
| `app/cleanmyvr/` | The Python app. The core uses only the standard library; the window adds pywebview |
| `app/cleanmyvr/devices/*.json` | **Everything headset-specific**: which apps each debloat level removes, what must never be removed, update blocking. Most contributions only touch these files |
| `app/cleanmyvr/actions.py` | Debloat, restore, update blocking, storage, environment check, recheck |
| `app/cleanmyvr/backup.py` | Backups and restores, every file SHA-256 checked on the headset |
| `app/cleanmyvr/state.py` | The record CleanMyVR keeps on the headset (`/sdcard/CleanMyVR/state.json`) |
| `app/cleanmyvr/cli.py`, `app/cleanmyvr/gui/` | Guided command line, and the window (`gui/web/` is plain HTML/CSS/JS) |
| `app/tests/` | Unit tests that use a fake headset, so you don't need one to run them |
| `launcher/` | The on-headset launcher app (Java, built with plain Android SDK tools, no Gradle) |
| `launcher/assets/catalog.json` | Offline copy of the Store's recommended apps. The real list is in [launcher-appstore](https://github.com/SpiritEXEWasTaken/launcher-appstore); suggest apps there |
| `envstub/` | The white-sky Home fix (repackages an installed environment) |
| `scripts/` | The original bash scripts. They still work, but the app replaces them |

## Safety rules

CleanMyVR runs on people's real headsets, so these rules aren't negotiable. Each one exists because
breaking it can leave someone with a broken Store, broken games, or a factory reset.

1. **Only verified packages go in a debloat list.** "Verified" means removed on a real headset of that
   model, rebooted, and checked: Home loads, the Store opens, a game launches, Meta login works. A
   package that's safe on a Quest 3 might not be on a Quest 2.
2. **Removal is always `pm uninstall -k --user <id>`.** That hides the app for one profile and keeps it
   on the system partition, so Restore can bring it back. No `pm disable` loops, no deleting files.
3. **When in doubt, leave it in.** Anything the Store, games or Meta accounts depend on goes in
   `never_remove`. The app refuses to remove those even if a list is wrong.
4. **Untested headsets get empty debloat lists** (`"tested": false`). The app then only offers safe
   actions: backup, storage, checks.
5. **Every change is recorded on the headset**, so Recheck can tell users the truth about what's in place.
6. **No personal data in the repo:** no backups, logs with serial numbers, ROMs or 3D models.
   `.gitignore` covers the usual places.
7. **No copyrighted game files or ROMs**, in the store or anywhere else.

## Testing

```bash
cd app && python3 -m unittest discover -s tests -t . -v     # no headset needed
./launch-commandline.sh                                     # with a headset; Recheck is read-only
CLEANMYVR_SMOKE_TEST=1 ./launch-gui.sh                      # opens the window, connects, closes
```

Every action that changes the headset asks first, so it's safe to explore. Still, back up before
testing anything that removes apps.

## Building

- **Launcher:** `cd launcher && ./build.sh` (needs JDK 17 and the Android SDK). Set `KEYSTORE_PASS` to
  sign with your own key.
- **Windows EXE:** built automatically by `.github/workflows/release.yml` when a release is tagged.
  Locally: `cd app && pyinstaller packaging/cleanmyvr.spec`.

## Quest quirks worth knowing

These cost a lot of time to figure out, so they're written down here:

- **Other profiles' files can't be reached with `adb push`/`pull`.** CleanMyVR goes through Android's
  media database instead (`content` commands with `--user`), after starting that profile in the background.
- **Apps can't read logs** on Quest, so the launcher can't be debugged with logcat from the app side.
- **The Home environment setting needs root to change.** It can be read without root, which is how the
  white-sky check works.
- **Meta's Store quietly reinstalls some removed apps.** Profiles list these under `comes_back`, and
  Recheck shows them as changed.
- **Android's Settings app can't be opened directly by other apps.** The launcher opens Settings' App
  info page instead, which has an OPEN button.

## Using Claude Code?

[CLAUDE.md](CLAUDE.md) has instructions written for Claude Code, which reads it automatically. You can
ask it things like "follow docs/ADDING_DEVICES.md for my Quest 2".

## Commit messages

Release notes are written automatically from commit messages, so start each one with what it is:

- `feat: restore backups to any profile`: a new feature
- `fix: launcher rename keeps the name`: a bug fix
- `docs:`, `ci:`, `test:`, `refactor:`, `chore:`: maintenance (listed last)

Anything else ends up under "Other changes". With a squash-merged pull request, the PR title is the
commit message, so the same applies to PR titles.

## Releases (maintainers)

Change `__version__` in `app/cleanmyvr/__init__.py` (e.g. `1.0.0` → `1.1.0`) and push it to `main`. The
Release workflow then builds the Windows app and the signed launcher, writes the notes (new features, bug
fixes, new contributors), and publishes the release. Everyone's CleanMyVR offers the update on its next
start, and the launcher's Store offers the new launcher. The launcher shares this version number.

To preview a release without publishing, run the Release workflow from the Actions tab with "dry run" on.
The launcher signing key lives in the repo secrets `LAUNCHER_KEYSTORE_B64` / `LAUNCHER_KEYSTORE_PASS`.
Never replace it: a launcher signed with a different key can't update the installed one.

## Pull requests

- Keep each PR to one thing (one headset profile, one fix).
- For a headset profile, include the model, build (`ro.build.display.id`), and the checklist from
  ADDING_DEVICES.md. Please only mark a profile tested if you tested it on your own headset.
- Make sure the unit tests pass.
