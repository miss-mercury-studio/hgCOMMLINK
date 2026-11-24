#!/usr/bin/env python3
"""Chummer game data sets: fingerprint them, and run the engine's tests against a release's data.

  python tools/data_sets.py fingerprint PATH        PATH is a Chummer release zip, a Chummer folder, or a data folder
  python tools/data_sets.py test --zip Chummer5.226.0.zip [--checkout ../chummer5a]
  python tools/data_sets.py gate                    full core tests on every data set in chummer-supported.txt
  python tools/data_sets.py check                   fail unless chummer-supported.txt lists the pinned data (needs ../chummer5a at the pin)

The fingerprint is a SHA-256 over the top-level data/*.xml files: for each file, sorted by name, its name and the SHA-256 of its
bytes with carriage returns removed (so a Windows checkout, a Linux checkout and a release zip of the same data agree). The app
computes the same value (see docs/GAME_DATA_PLAN.md).

`test` builds a folder that is one release's data, lang and customdata plus the pinned checkout's test characters (which are the
same in every build tested so far), and runs `:core:test` against it. The pinned data itself is the normal local run.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SUPPORTED = os.path.join(ROOT, "chummer-supported.txt")
PIN = os.path.join(ROOT, "chummer-pin.txt")


def fingerprint(files):
    """files: {name: bytes} for the top-level data/*.xml files."""
    h = hashlib.sha256()
    for name in sorted(files):
        h.update(name.encode("utf-8") + b"\n")
        h.update(hashlib.sha256(files[name].replace(b"\r", b"")).hexdigest().encode("ascii") + b"\n")
    return h.hexdigest()


def read_zip_data(z):
    """The data/*.xml files of a Chummer zip, wherever its root is (`data/` or `Chummer/data/`, up to three folders down)."""
    names = [n for n in z.namelist() if re.fullmatch(r"(?:[^/]+/){0,3}data/[^/]+\.xml", n)]
    roots = {n.rsplit("/", 1)[0] for n in names}
    # The folder that holds metatypes.xml is the data folder.
    roots = [r for r in roots if r + "/metatypes.xml" in names]
    if not roots:
        raise SystemExit("No Chummer data folder (with metatypes.xml) found in the zip")
    root = min(roots, key=len)
    return root, {n[len(root) + 1:]: z.read(n) for n in names if n.rsplit("/", 1)[0] == root}


def read_dir_data(path):
    for cand in (path, os.path.join(path, "data"), os.path.join(path, "Chummer", "data")):
        if os.path.isfile(os.path.join(cand, "metatypes.xml")):
            return {f: open(os.path.join(cand, f), "rb").read() for f in os.listdir(cand) if f.endswith(".xml")}
    raise SystemExit("No Chummer data folder (with metatypes.xml) found in " + path)


def fingerprint_of(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            return fingerprint(read_zip_data(z)[1])
    return fingerprint(read_dir_data(path))


def pinned_fingerprint(checkout):
    pin = open(PIN).read().strip()
    # `git show` gives the blobs as committed, whatever the checkout's line endings are.
    ls = subprocess.run(["git", "-C", checkout, "ls-tree", "--name-only", pin, "Chummer/data/"], capture_output=True, text=True, check=True).stdout.split()
    files = {n.split("/")[-1]: subprocess.run(["git", "-C", checkout, "show", f"{pin}:{n}"], capture_output=True, check=True).stdout
             for n in ls if n.endswith(".xml")}
    return pin, fingerprint(files)


def supported():
    rows = []
    if os.path.isfile(SUPPORTED):
        for line in open(SUPPORTED, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#"):
                fp, name, source = [x.strip() for x in line.split("|")]
                rows.append((fp, name, source))
    return rows


def cmd_fingerprint(a):
    print(fingerprint_of(a.path))


def cmd_check(a):
    pin, fp = pinned_fingerprint(a.checkout)
    if any(f == fp for f, _, _ in supported()):
        print(f"ok: the pinned data ({pin[:8]}) is in chummer-supported.txt")
    else:
        sys.exit(f"chummer-supported.txt does not list the pinned data.\nAdd:  {fp} | Chummer nightly (pin {pin[:8]}) | pin")


REPO = "chummer5a/chummer5a"
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "hgcommlink-chummer")


def fetch_release(tag, want_fingerprint):
    """The zip of a chummer5a release, downloaded once and kept. Fails if its data is not the data that was tested."""
    d = os.path.join(CACHE, tag)
    os.makedirs(d, exist_ok=True)
    zips = [f for f in os.listdir(d) if f.endswith(".zip")]
    if not zips:
        subprocess.run(["gh", "release", "download", tag, "-R", REPO, "-p", "*.zip", "-D", d], check=True)
        zips = [f for f in os.listdir(d) if f.endswith(".zip")]
    if len(zips) != 1:
        sys.exit(f"Expected one zip for release {tag}, found {zips}")
    path = os.path.join(d, zips[0])
    got = fingerprint_of(path)
    if got != want_fingerprint:
        os.remove(path)
        sys.exit(f"Release {tag} no longer has the tested data (fingerprint {got[:12]}, listed {want_fingerprint[:12]}). "
                 "Chummer may have replaced the file; re-test it before listing it again.")
    return path


def gradle_cmd(args):
    return [os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew"), *args]


def gate(checkout, extra=()):
    """The release gate: the full core tests on every supported data set. Returns a list of problems (empty when all pass)."""
    problems = []
    checkout = os.path.abspath(checkout)
    pin = open(PIN).read().strip()
    head = subprocess.run(["git", "-C", checkout, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != pin:
        return [f"The Chummer checkout ({checkout}) is at {head[:8]}, not the pin {pin[:8]}. Check it out first."]
    rows = supported()
    pinned = pinned_fingerprint(checkout)[1]
    if not any(src == "pin" and fp == pinned for fp, _, src in rows):
        return ["chummer-supported.txt does not list the pinned data with source 'pin'. Run: python tools/data_sets.py check"]
    for fp, name, src in rows:
        print(f"== {name}", flush=True)
        if src == "pin":
            code = subprocess.run(gradle_cmd([f"-Pchummer.src={checkout.replace(chr(92), '/')}", ":core:test", "--continue", *extra]), cwd=ROOT, shell=(os.name == "nt")).returncode
        elif src.startswith("release:"):
            code = run_tests(fetch_release(src[len("release:"):], fp), checkout, list(extra))
        else:
            problems.append(f"{name}: unknown source {src!r}")
            continue
        if code != 0:
            problems.append(f"The core tests fail on {name}.")
    return problems


def cmd_gate(a):
    problems = gate(a.checkout)
    if problems:
        sys.exit("Data set gate failed:\n  - " + "\n  - ".join(problems))
    print("Every supported data set passes the core tests.")


def cmd_test(a):
    sys.exit(run_tests(a.zip, os.path.abspath(a.checkout), a.gradle))


def run_tests(zip_path, checkout, gradle_args):
    """Build a folder of one release's data plus the pinned checkout's test characters, and run :core:test on it. Returns the exit code."""
    pin = open(PIN).read().strip()
    work = tempfile.mkdtemp(prefix="chummer-data-set-")
    try:
        with zipfile.ZipFile(zip_path) as z:
            droot, _ = read_zip_data(z)
            base = droot[:-len("data")].rstrip("/")  # the zip's Chummer root, or "" when it is flat
            for n in z.namelist():
                rel = n[len(base) + 1:] if base else n
                if n.endswith("/") or not rel.startswith(("data/", "lang/", "customdata/")):
                    continue
                dest = os.path.join(work, "Chummer", rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(z.read(n))
        # The test characters come from the pinned checkout (identical in the builds compared so far).
        tar = os.path.join(work, "tests.tar")
        subprocess.run(["git", "-C", checkout, "archive", "-o", tar, pin, "Chummer.Tests/TestFiles"], check=True)
        subprocess.run(["tar", "-xf", tar, "-C", work], check=True)
        src = work.replace("\\", "/")
        print(f"Testing against {zip_path} (fingerprint {fingerprint_of(zip_path)[:12]}) in {work}", flush=True)
        return subprocess.run(gradle_cmd([f"-Pchummer.src={src}", ":core:test", "--continue", *gradle_args]), cwd=ROOT, shell=(os.name == "nt")).returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fingerprint"); f.add_argument("path"); f.set_defaults(fn=cmd_fingerprint)
    c = sub.add_parser("check"); c.add_argument("--checkout", default=os.path.join(ROOT, "..", "chummer5a")); c.set_defaults(fn=cmd_check)
    t = sub.add_parser("test"); t.add_argument("--zip", required=True); t.add_argument("--checkout", default=os.path.join(ROOT, "..", "chummer5a"))
    t.add_argument("gradle", nargs="*", help="extra Gradle arguments"); t.set_defaults(fn=cmd_test)
    g = sub.add_parser("gate", help="full core tests on every supported data set")
    g.add_argument("--checkout", default=os.path.join(ROOT, "..", "chummer5a")); g.set_defaults(fn=cmd_gate)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
