"""Write release notes for a new tag: new features, bug fixes, other changes and new contributors.

Usage: python3 release_notes.py <owner/repo> <new tag> <commit sha>  > notes.md
Needs the full git history (actions/checkout with fetch-depth: 0) and GH_TOKEN for GitHub logins.

Commits are sorted by their subject:  "feat: ..." / "add ..." -> New features,  "fix: ..." -> Bug fixes,
"docs/ci/chore/test/refactor/build: ..." -> Maintenance,  anything else -> Other changes.
"""
import re
import subprocess
import sys

REPO, TAG, SHA = sys.argv[1:4]
URL = f"https://github.com/{REPO}"
SKIP = re.compile(r"^(release|bump version|merge branch)\b", re.I)
GROUPS = [
    ("New features", re.compile(r"^(feat|feature|add|new)(\([^)]*\))?[:!\s]", re.I)),
    ("Bug fixes", re.compile(r"^(fix|bug|bugfix|hotfix)(\([^)]*\))?[:!\s]", re.I)),
    ("Maintenance", re.compile(r"^(docs?|ci|chore|test|tests|refactor|build|style|perf)(\([^)]*\))?[:!\s]", re.I)),
]
PREFIX = re.compile(r"^\w+(\([^)]*\))?!?:\s*")


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.strip()


def login(sha):
    r = subprocess.run(["gh", "api", f"repos/{REPO}/commits/{sha}", "--jq", ".author.login"],
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 and r.stdout.strip() not in ("", "null") else None


tags = [t for t in git("tag", "--merged", SHA, "--sort=-v:refname", "--list", "v*").splitlines() if t != TAG]
prev = tags[0] if tags else None
rng = f"{prev}..{SHA}" if prev else SHA
commits = []
for line in git("log", "--no-merges", "--reverse", "--format=%H%x1f%s%x1f%an%x1f%ae", rng).splitlines():
    sha, subject, name, email = line.split("\x1f")
    commits.append({"sha": sha, "subject": subject, "name": name, "email": email})

out = []
if not prev:
    out.append(f"The first release of CleanMyVR. See the [README]({URL}#readme) for everything it does.\n")
else:
    sections = {g: [] for g, _ in GROUPS}
    sections["Other changes"] = []
    for c in commits:
        if SKIP.match(c["subject"]):
            continue
        group = next((g for g, rx in GROUPS if rx.match(c["subject"])), "Other changes")
        text = PREFIX.sub("", c["subject"]) if group != "Other changes" else c["subject"]
        text = text[:1].upper() + text[1:]
        text = re.sub(r"\(#(\d+)\)", rf"([#\1]({URL}/pull/\1))", text)
        sections[group].append(f"- {text} ({c['sha'][:7]})")
    for group in ("New features", "Bug fixes", "Other changes", "Maintenance"):
        if sections[group]:
            out.append(f"## {group}\n" + "\n".join(sections[group]) + "\n")
    if not any(sections.values()):
        out.append("Small internal changes only.\n")

    # New contributors: authors whose first commit is in this release (matched by email).
    seen, new = set(), []
    for c in commits:
        if c["email"] in seen or "[bot]" in c["name"]:
            continue
        seen.add(c["email"])
        if not git("log", "-1", "--format=%H", f"--author=<{c['email']}>", prev):
            who = login(c["sha"])
            new.append(f"- {'@' + who if who else c['name']} made their first contribution in {c['sha'][:7]}")
    if new:
        out.append("## New contributors\n" + "\n".join(new) + "\n")

out.append("""## Download
- **Windows:** `CleanMyVR-windows.zip`, then run `CleanMyVR.exe` (window) or `CleanMyVR-cmd.bat` (guided command line)
- **macOS / Linux:** download the source code below and run `./launch-gui.sh` or `./launch-commandline.sh`
- **Launcher only:** `launcher.apk` (CleanMyVR → Launcher installs it for you)
""")
if prev:
    out.append(f"**Full changelog:** [{prev}...{TAG}]({URL}/compare/{prev}...{TAG})")
print("\n".join(out))
