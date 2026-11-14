# Adding (or updating) support for a headset

CleanMyVR only removes apps that someone has verified on that exact headset model. This guide turns
an untested profile into a tested one. It works the same whether you do it by hand or ask Claude Code
("follow docs/ADDING_DEVICES.md for my Quest 2").

You need the headset, a USB cable, Developer Mode on, and about an hour. **Back up first**
(CleanMyVR → Backup) and be ready to factory reset if something goes wrong.

## 1. Identify the headset

```bash
adb shell getprop ro.product.device      # codename, e.g. eureka (Quest 3), hollywood (Quest 2)
adb shell getprop ro.product.model
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.incremental
```

If `app/cleanmyvr/devices/` has a profile for that codename, edit it. Otherwise copy `_template.json`.

## 2. Collect candidates (read-only)

```bash
adb shell pm list packages -s --user 0 | sort > system.txt     # system apps
adb shell pm list packages -3 --user 0 | sort > store.txt      # Meta Store / user apps
```

Untested profiles for other Quests include `candidates_from_quest3`: what was safe on a Quest 3.
Start there. **Never** consider anything matching `never_remove` / `never_remove_prefixes`.

## 3. Verify each candidate, one at a time

For each package:

```bash
adb shell pm uninstall -k --user 0 <package>       # reversible
adb reboot                                          # wait for it to come back
```

Then check, in the headset:

- [ ] Home loads (environment, not a white void)
- [ ] Meta Store opens and shows your library
- [ ] A Store game launches and online features work
- [ ] Settings, Wi-Fi and Bluetooth open
- [ ] Guardian / boundary works
- [ ] No repeating error dialogs

If anything breaks: `adb shell cmd package install-existing --user 0 <package>`, reboot, and mark the package
as unsafe in your notes. If removal fails (`DELETE_FAILED_INTERNAL_ERROR`), add it to `protected`.

## 4. Fill in the profile

- `debloat.light`: optional Meta apps (social apps, tips, demos). Not games.
- `debloat.balanced`: background services (promos, telemetry uploaders, business management).
- `update_blocking`: only what you tested. Keep `restrict` best-effort and say so in `note`.
- `never_remove`: everything you found the Store, games, accounts or Home depend on.
- `comes_back`: packages Meta reinstalled on its own while you tested.
- `tested: true`, and add `{build, incremental, horizon_os, date}` to `tested_on`.

Write a one-line `why` for every package; users see it before anything is removed.

## 5. Check and submit

```bash
cd app && python3 -m unittest discover -s tests -t . -v                  # profile JSON must load and validate
./launch-commandline.sh                           # Debloat preview should list exactly your packages
```

Open a pull request with the profile, the build you tested on, and the checklist above.
Please don't mark a profile tested based on someone else's headset or a different model.
