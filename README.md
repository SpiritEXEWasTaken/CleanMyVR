# CleanMyVR

Debloat, clean up, back up and fix a **Meta Quest** over USB, with a guided app for Windows, macOS
and Linux. No root, nothing to sideload onto the headset, and every change can be undone. Inspired by
[cleanyourtv](https://github.com/Gathin23/cleanyourtv).

Your games keep working: nothing that games, the Meta Store or your Meta account rely on is touched.

> Tested on a **Meta Quest 3** (Horizon OS v210). Other Quests are detected, but only safe actions
> (backups, storage, checks) are enabled until someone verifies them.
> [Help add your headset →](docs/ADDING_DEVICES.md)

## Get started

1. Turn on **Developer Mode** for your headset in the Meta Horizon phone app.
2. Plug the headset into your computer and put it on; allow **USB debugging** when asked.
3. Start CleanMyVR:

| | Window | Guided command line |
| --- | --- | --- |
| **Windows** | `CleanMyVR.exe` (from the release zip) | `CleanMyVR-cmd.bat` |
| **macOS / Linux** | `./launch-gui.sh` | `./launch-commandline.sh` |

No ADB? CleanMyVR offers to download Google's official platform-tools for you. macOS/Linux need
Python 3.9+; the window also needs pywebview, which `launch-gui.sh` installs into a private `.venv`.
On Linux, if the headset shows as "no permissions", add a udev rule for Meta's USB vendor ID `2833`.

## What it does

- **Recheck**: compares the headset with what CleanMyVR recorded *on the headset* in
  `/sdcard/CleanMyVR/state.json`. It shows what's still in place, what Meta quietly reinstalled, and
  whether a system update slipped through. **Fix what changed** puts it right again.
- **Debloat** (per profile): *light* (Meta's extra apps), *balanced* (+ promos, telemetry uploaders,
  business management) or *balanced + block updates* (best-effort). There's a preview, and Restore undoes it.
- **Block permanently** (Debloat page): for apps Meta keeps reinstalling (WhatsApp, Facebook, Instagram...).
  CleanMyVR uninstalls the app completely and puts a tiny invisible placeholder with the same name in its
  place, so Meta's updates can't bring it back. Every profile; Meta shows a one-time "unauthorized copy"
  message per app. **Bring it back** removes the placeholder, and the Store can install the real app again.
- **Backup**: photos and videos from **any** profile plus game saves on shared storage. Every file is
  SHA-256-checked against the headset, and `SHA256SUMS.txt` lets you re-verify later.
- **Storage**: biggest apps, clearing caches, deleting captures (after typing DELETE).
- **Environment**: diagnoses the "Home is just a white sky" bug (see below).
- **Launcher**: installs the companion launcher (Library replacement plus an app store).
- **Restore**: reinstalls removed apps, or copies a backup back (game saves only into installed games).
- **Extras → Quest Home Switcher:** one button sets up [Quest Home Switcher](https://github.com/nikitat21/Quest-Home-Switcher)
  for switching Home environments: downloads it (SHA-256 checked, never a build less than 14 days old),
  installs it with its permissions granted so you skip the Wireless ADB pairing, turns on Wireless
  debugging (it pairs with that by itself), and opens it to finish setup. Needs Loft as your Home and Wi-Fi.
  It's a third-party app that gets shell-level access once set up; removing it turns Wireless debugging off.
- **Extras → Google Play apps:** installs Aurora Store (from F-Droid), which downloads Play Store apps
  without a Google account. Apps that need Google's services (Google sign-in, push notifications, Play
  Integrity, many banking/streaming apps) won't run: Quest has no Google services, and microG (the open
  stand-in) can't be installed, because Horizon OS blocks apps that declare Google's reserved permissions.

Removal is `pm uninstall -k --user <profile>`, so system apps stay on the system partition and come
back with Restore. Store apps that only existed for one profile need reinstalling from the Meta Store.
A factory reset undoes everything, and also wipes the state file.

**Updates:** CleanMyVR checks GitHub for a new release when it starts (one request to GitHub's API;
set `CLEANMYVR_NO_UPDATE_CHECK=1` to turn it off) and offers to update itself. The Windows app downloads
the new version, checks its SHA-256 and restarts; a git checkout runs `git pull`. Recheck tells you when the
launcher on the headset is older than the one CleanMyVR comes with, and the launcher's own Store can
update it too.

The original bash scripts still work and live in `scripts/` (`debloat.sh`, `restore.sh`,
`clean-storage.sh`, `check-environment.sh`).

## Contributing / adding headsets

Read [CONTRIBUTING.md](CONTRIBUTING.md) (project layout and safety rules) and
[docs/ADDING_DEVICES.md](docs/ADDING_DEVICES.md). Using Claude Code? It reads [CLAUDE.md](CLAUDE.md)
automatically. Headset knowledge lives in
`app/cleanmyvr/devices/<model>.json`, and a package only goes in a debloat list after it's been verified
on that model. Tests: `cd app && python3 -m unittest discover -s tests -t .`

## Launcher (`launcher/`)

A replacement for Meta's Library. It's a dark grid of big tiles, with tabs for Favorites,
VR Games, Apps (2D), Sideloaded, and your own categories. It also has search, a
"Continue playing" row, star-to-favorite, hide apps, and custom cover images.

```bash
cd launcher
./build.sh        # needs JDK 17 + Android SDK (no Gradle)
./install.sh      # installs and turns on "Library takeover"
./install.sh --uninstall
```

**How the takeover works:** Horizon OS's Home (`vrshell`) also draws every panel,
passthrough and the boundary, so it can't be replaced without root. Instead, a small
accessibility service watches for Meta's Library panel opening, closes it, and opens this
launcher. With a panel open (or in a game), Horizon shows only Meta's system bar instead of the Library; the
launcher treats that as the Meta button too. It reads the name of the panel that opened, nothing else
(except for Close/Restart App, below). To use
Meta's Library on purpose, use **⋯ → Open Meta's Library once** (it lets exactly one opening through).

**In-game menu:** inside a VR game, the Meta button opens the launcher too, over a blurred background
made from the game's own cover art. A menu shows the game's name and package and has **Open Another
App**, **Restart App** and **Close App**, plus **Resume** and **Meta menu** (Meta's own menu, for quick
settings). Apps aren't allowed to close other apps, so Close/Restart open the game's Android App info
page and press **Force stop** for you. Only then does the service read a screen's content: that one App
info page, for a few seconds. Turn the in-game menu off in ⋯ → In-game menu.

- **Favorites:** tap the ☆ on a tile, or long-press → Add to Favorites
- **Categories:** "+ New category" tab, or long-press a game → Categories. Long-press a
  category tab to rename or delete it
- **Games:** the launcher guesses game vs app from what each app declares about itself, whether it's
  VR or 2D, how it was installed and its name. When it isn't sure (e.g. sideloaded VR apps) it asks
  you. Change any app with long-press → Mark as game / Not a game. "Continue playing" and the Games
  tab only show games
- **Names:** every tile shows the package under the name; long-press → Rename… to tell look-alikes
  apart (e.g. a private Rec Room server)
- **Cover art:** long-press → Find cover online… searches Steam (no key needed) and SteamGridDB
  (with a free API key from steamgriddb.com → Preferences → API). Or Upload cover image…, or
  ⋯ → Covers → Fetch missing game covers. Android TV banners can be turned off in ⋯ → Covers.
  Files live in `/sdcard/Android/data/com.cleanmyvr.launcher/files/covers/<package>.img`
- **Store:** the recommended apps come from
  [launcher-appstore](https://github.com/SpiritEXEWasTaken/launcher-appstore), fetched once a day, so new
  picks show up without a launcher update (suggest apps there). The Store tab installs and updates apps straight from GitHub, GitLab or
  Codeberg/Gitea/Forgejo releases, like Obtainium. It comes with Quest-friendly picks (Wolvic,
  ALVR, QuestCraft, Moonlight, Better xCloud, LocalSend, NewPipe, Droid-ify, SideQuest, Quest Home Switcher, VirtualBoyGo, Aurora Store, and the launcher itself), and "Add source" takes any repo
  link, an `f-droid.org/packages/<app>` link, (plus an optional APK-name filter for projects that ship several APKs) or a direct
  "always the latest APK" link; updates show up when that link starts pointing at a new file
- **Store names, banners and categories:** many games have a placeholder Android name (Animal Rivals
  shows up as "Deadeye") because Meta's own UI uses the store name. The launcher looks each app up in
  [MetaMetadata](https://github.com/threethan/MetaMetadata), a community database of Quest Store and
  SideQuest apps by threethan (the same source Lightning Launcher uses), for the store name, the wide
  store banner, and whether it's a game. This sends your apps' package names to GitHub; turn it off in
  ⋯ → Covers → Online names & banners. Your own covers and renames always win
- **Meta Stock:** a tab with the built-in apps you still need (Store, Browser, Meta settings, TV, Gallery,
  Camera, Files, Quest Link, Remote Desktop, Boundary setup...). Everything else Meta ships (Accounts Center,
  helper screens, background services) stays hidden. ⋯ → Settings ▸ has Meta settings and Android settings
- **Crash guard:** if the launcher crashes, a small watcher in its own process restarts the Library takeover
  (Android doesn't) and reopens the launcher if it was on screen. CleanMyVR grants the one permission this
  needs when it installs the launcher; Recheck and Launcher → Repair fix a stopped takeover too
- **Pull to refresh:** at the top of the list, drag down and let go to re-read installed apps
- **Install any APK:** ⋯ → Install an APK file… picks an .apk (Downloads, a USB drive, ...) and installs it
- **Settings backup:** favorites, renames, categories and store sources are copied next to your covers in
  `Android/data/com.cleanmyvr.launcher/files/`, and restored automatically after a reinstall if you copy
  that folder back
- **Android settings / App info:** Quest blocks apps from opening Android's Settings directly, so
  ⋯ → Android settings (or the Settings tile) opens Settings' own App info page: press **OPEN**
  there. Long-press → App info opens Android's app info page (not Meta's)
- **Wallpaper:** ⋯ → Wallpaper: solid color, gradient (8 directions) or an image, with
  optional dimming

## The "white sky" Home bug

Symptoms: Home is a white/grey void, and double-tapping into virtual Home shows
"Loft environment downloading..." forever. People will tell you to factory reset.

Open **CleanMyVR → Environment** (or run `./scripts/check-environment.sh`). If your headset is still set to the retired **Cyber**
environment (`com.oculus.environment.prod.cyberhome`), here's what's going on. Meta removed
the package, but the selection lives somewhere a shell-data clear doesn't reach, and the
environment picker is hidden on newer OS versions. Reinstalling Haven/Calming doesn't help
either: the Loft world's asset check still fails.

**Fix (no reset):** give the shell a package with the name it's looking for. Horizon OS
accepts sideloaded environments ("Skipping entitlement check for sideloaded environment").
`envstub/build-env.sh` repackages a scene from an environment you already have under that
name:

```bash
mkdir -p backups/environments
adb pull "$(adb shell pm path com.meta.environment.prod.theaterhome | cut -d: -f2 | tr -d '\r')" \
  backups/environments/theaterhome.apk
envstub/build-env.sh backups/environments/theaterhome.apk --install
adb shell am force-stop com.oculus.vrshell      # then double-tap into virtual Home
```

Any classic ("combined") environment works as the source. Theater and the built-in
`com.meta.environment.prod.nuxd` both do. Undo with
`adb uninstall com.oculus.environment.prod.cyberhome`.

**The permanent fix is a factory reset** (back up first with CleanMyVR → Backup). The selection is
stored with `oculuspreferences`, which needs root to change. A reset clears it, and Home goes back to
**Loft** (Haven 2025 + Calming). The stand-in keeps the old Cyber selection alive, and Home-switcher
apps such as Quest Home Switcher need Loft as the active home, so they won't work with it.

**Custom environments?** The scene format (`assets/scene.zip`) is Meta's compiled Horizon
format (`TXTR` textures, cooked meshes and materials), not glTF or plain PNG. Repackaging
existing scenes works; authoring new ones would mean reverse-engineering that format.
The shell still contains the classic glTF loader (`GLTFOVRSCENE`, `_WORLD_MODEL.gltf.ovrscene`),
which is what old community custom environments used. As of Horizon OS v210, though, every
environment is loaded as HSR, so a glTF `.ovrscene` package fails with
`Invalid ResPath for HSR package`. Tested with and without HSR metadata.

## License and disclaimer

MIT, see [LICENSE](LICENSE). Not affiliated with Meta. Use at your own risk; everything here is reversible, but you are
modifying your device.
