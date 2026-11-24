"""Prepare a release: python tools/release.py 0.1.2 [--push] [--dry-run]

Does the manual steps in docs/RELEASING.md, with checks, so they cannot be done half-way:
  1. refuses unless the tree is clean, on master, up to date with origin, and the tag is new;
  2. refuses an empty [Unreleased] section or a version that is not higher than the current one;
  3. renames [Unreleased] to [VERSION] - today, adds a fresh empty [Unreleased], sets app.versionName;
  4. runs the change log test, commits "Release VERSION" and tags vVERSION;
  5. with --push, pushes master and the tag, which starts the release workflow. Without it, prints the command.

Nothing is pushed unless you pass --push. --dry-run changes nothing and only reports what it would do.
"""
import argparse
import datetime
import os
import re
import subprocess
import sys

TRAILER = "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"


def run(root, *cmd, check=True):
    p = subprocess.run(cmd, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    if check and p.returncode != 0:
        sys.exit("Command failed: %s\n%s" % (" ".join(cmd), p.stdout))
    return p.stdout.strip()


def parse_version(v):
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", v)
    if not m:
        sys.exit("Version must look like 0.1.2 (major.minor.patch), got %r" % v)
    return tuple(int(x) for x in m.groups())


def unreleased_body(text):
    """The lines under ## [Unreleased], up to the next ## heading."""
    m = re.search(r"^## \[Unreleased\][^\n]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m:
        sys.exit("CHANGELOG.md has no '## [Unreleased]' section.")
    return m.group(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("version")
    ap.add_argument("--push", action="store_true", help="push master and the tag (starts the release workflow)")
    ap.add_argument("--dry-run", action="store_true", help="check and report; change nothing")
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."), help=argparse.SUPPRESS)
    ap.add_argument("--skip-tests", action="store_true", help=argparse.SUPPRESS)
    ap.add_argument("--no-remote", action="store_true", help=argparse.SUPPRESS)
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    new = parse_version(a.version)
    tag = "v" + a.version

    props_path = os.path.join(root, "gradle.properties")
    log_path = os.path.join(root, "CHANGELOG.md")
    with open(props_path, encoding="utf-8", newline="") as f:
        props = f.read()
    m = re.search(r"^app\.versionName=(\S+)", props, re.M)
    if not m:
        sys.exit("gradle.properties has no app.versionName.")
    current = parse_version(m.group(1))
    if new <= current:
        sys.exit("Version %s is not higher than the current %s." % (a.version, m.group(1)))

    problems = []
    if run(root, "git", "status", "--porcelain"):
        problems.append("The working tree has uncommitted changes. Commit or stash them first.")
    branch = run(root, "git", "rev-parse", "--abbrev-ref", "HEAD")
    if branch != "master":
        problems.append("On branch %s; releases are cut from master." % branch)
    if run(root, "git", "tag", "--list", tag):
        problems.append("Tag %s already exists." % tag)
    if not a.no_remote:
        run(root, "git", "fetch", "origin", "--tags", "--quiet")
        behind = run(root, "git", "rev-list", "--count", "HEAD..origin/master", check=False)
        if behind not in ("", "0"):
            problems.append("master is %s commit(s) behind origin/master. Pull first." % behind)
        if run(root, "git", "ls-remote", "--tags", "origin", tag):
            problems.append("Tag %s already exists on origin." % tag)

    with open(log_path, encoding="utf-8", newline="") as f:
        log = f.read()
    nl = "\r\n" if "\r\n" in log else "\n"
    log = log.replace("\r\n", "\n")
    body = unreleased_body(log)
    if not any(line.lstrip().startswith("- ") for line in body.splitlines()):
        problems.append("[Unreleased] has no '- ' entries. Add a line for each player-visible change first.")
    if problems:
        sys.exit("Not releasing:\n  - " + "\n  - ".join(problems))

    today = datetime.date.today().isoformat()
    new_log = re.sub(r"^## \[Unreleased\][^\n]*\n", "## [Unreleased]\n\n## [%s] - %s\n" % (a.version, today), log, count=1, flags=re.M)
    new_props = re.sub(r"^app\.versionName=\S+", "app.versionName=" + a.version, props, count=1, flags=re.M)

    print("Releasing %s (was %s) dated %s" % (a.version, m.group(1), today))
    print("Entries going out:\n%s" % "\n".join("  " + l for l in body.strip().splitlines()))
    if a.dry_run:
        print("\nDry run: nothing changed.")
        return

    with open(log_path, "w", encoding="utf-8", newline="") as f:
        f.write(new_log.replace("\n", nl))
    with open(props_path, "w", encoding="utf-8", newline="") as f:
        f.write(new_props)

    if not a.skip_tests:
        gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
        print("Running the change log test...")
        out = subprocess.run([os.path.join(root, gradle) if os.name == "nt" else gradle, ":core:test", "--tests", "chummer.core.ChangelogTest"],
                             cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
        if out.returncode != 0:
            run(root, "git", "checkout", "--", "CHANGELOG.md", "gradle.properties")
            sys.exit("The change log test failed; the edits were reverted.\n" + out.stdout[-2000:])

    run(root, "git", "add", "CHANGELOG.md", "gradle.properties")
    run(root, "git", "commit", "-m", "Release %s\n\n%s" % (a.version, TRAILER))
    run(root, "git", "tag", tag)
    print("Committed and tagged %s." % tag)

    if a.push:
        run(root, "git", "push", "origin", "master", tag)
        print("Pushed. The release workflow is now running: gh run list --limit 1")
    else:
        print("Not pushed. When ready:\n  git push origin master %s" % tag)


if __name__ == "__main__":
    main()
