#!/usr/bin/env python3
"""What a Chummer nightly changes for us. Used by .github/workflows/chummer-nightly-watch.yml, and runnable by hand.

  python tools/nightly_watch.py diff OLD NEW [--out diff.md]
      Items added, removed and changed between two Chummer data sets (a release zip, a Chummer folder, or a data folder),
      file by file. An item is identified by its <id>, or its <name>.
  python tools/nightly_watch.py compare --base RESULTS_ON_PIN --other RESULTS_ON_NIGHTLY --public public.md --private private.md
      What the nightly's data breaks that the pinned data does not, from two full test runs (Gradle test-results folders). Run from the private
      fixtures repository. The public text names only tests and counts; the private text also has the per-character lines.
  python tools/nightly_watch.py report --tag TAG --zip NIGHTLY.zip --pin-data PATH --results core/build/test-results/test
                                       [--previous-state STATE] --out body.md --diff-out diff.md --state-out state.txt
      The text of the rolling "Chummer nightly status" issue: the drift and public test results on the nightly, and what its data
      changes since the pinned data. The state line is kept in the issue so the next run knows what it saw last.

Only Chummer's public data is read, so nothing here is private.
"""
import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import data_sets  # noqa: E402

MAX_LISTED = 12          # names listed per file and kind of change
MAX_BODY = 60000         # GitHub allows 65536 characters in an issue body
# A nightly is never on the supported list (its data changes daily), so this test always fails on one and says nothing.
EXPECTED_FAILURES = {("GameDataTest", "theDataUnderTestIsOnTheSupportedList")}


def load_data(path):
    """{file name: bytes} of the top-level data/*.xml files of a zip, a Chummer folder or a data folder."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            return data_sets.read_zip_data(z)[1]
    return data_sets.read_dir_data(path)


def canonical(elem):
    """The element as text with whitespace between nodes removed, so a reformat is not a change."""
    for e in elem.iter():
        if e.text is not None and not e.text.strip():
            e.text = None
        elif e.text is not None:
            e.text = e.text.strip()
        e.tail = None
    return ET.tostring(elem, encoding="unicode")


def items(raw):
    """{item key: (display name, canonical text)} for the items in the data file's containers."""
    out = {}
    root = ET.fromstring(raw)
    for container in root:
        for item in container:
            key = (item.findtext("id") or item.findtext("name") or (item.text or "")).strip()
            if not key:
                continue
            name = (item.findtext("name") or item.text or key).strip()
            out[container.tag + "/" + key] = (name, canonical(item))
    return out


def diff_files(old, new):
    """{file: (added names, removed names, changed names)} for the files that differ."""
    result = {}
    for name in sorted(set(old) | set(new)):
        if name not in new:
            result[name] = ([], ["(the whole file)"], [])
            continue
        if name not in old:
            result[name] = (["(the whole file)"], [], [])
            continue
        if old[name].replace(b"\r", b"") == new[name].replace(b"\r", b""):
            continue
        try:
            a, b = items(old[name]), items(new[name])
        except ET.ParseError as e:
            result[name] = ([], [], ["(could not read: %s)" % e])
            continue
        added = sorted(b[k][0] for k in b if k not in a)
        removed = sorted(a[k][0] for k in a if k not in b)
        changed = sorted(b[k][0] for k in b if k in a and a[k][1] != b[k][1])
        if added or removed or changed:
            result[name] = (added, removed, changed)
    return result


def listing(names):
    shown = ", ".join(names[:MAX_LISTED])
    return shown + (" and %d more" % (len(names) - MAX_LISTED) if len(names) > MAX_LISTED else "")


def diff_markdown(result, old_label, new_label):
    if not result:
        return "No data differences between %s and %s.\n" % (old_label, new_label)
    totals = [sum(len(v[i]) for v in result.values()) for i in range(3)]
    lines = ["Data changes from %s to %s: **%d added, %d removed, %d changed** in %d file(s).\n" % ((old_label, new_label) + tuple(totals) + (len(result),))]
    for name, (added, removed, changed) in result.items():
        lines.append("- `%s`: +%d, -%d, ~%d" % (name, len(added), len(removed), len(changed)))
        for label, names in (("added", added), ("removed", removed), ("changed", changed)):
            if names:
                lines.append("  - %s: %s" % (label, listing(names)))
    return "\n".join(lines) + "\n"


def junit(results_dir):
    """(tests run, [(class, test, message)] for the failures) from a Gradle test-results folder."""
    run, failed = 0, []
    for f in glob.glob(os.path.join(results_dir, "*.xml")):
        root = ET.parse(f).getroot()
        for case in root.iter("testcase"):
            run += 1
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                failed.append((case.get("classname", "").split(".")[-1], case.get("name", ""), (bad.get("message") or "").strip().splitlines()[0:1]))
    return run, sorted(failed)


def miss_lines(results_dir):
    """The per-character `MISS ...` lines the golden tests print, from the test output in a Gradle test-results folder."""
    out = set()
    for f in glob.glob(os.path.join(results_dir, "*.xml")):
        root = ET.parse(f).getroot()
        cls = os.path.basename(f)[len("TEST-"):-len(".xml")].split(".")[-1]
        for tag in ("system-out",):
            for node in root.iter(tag):
                for line in (node.text or "").splitlines():
                    line = line.strip()
                    if line.startswith("MISS "):
                        out.add(cls + ": " + line[len("MISS "):])
    return out


def cmd_compare(a):
    _, base_failed = junit(a.base)
    _, other_failed = junit(a.other)
    ignore = lambda rows: {(c, t) for c, t, _ in rows if (c, t) not in EXPECTED_FAILURES}
    new_fail, fixed = sorted(ignore(other_failed) - ignore(base_failed)), sorted(ignore(base_failed) - ignore(other_failed))
    base_miss, other_miss = miss_lines(a.base), miss_lines(a.other)
    new_miss, gone_miss = sorted(other_miss - base_miss), sorted(base_miss - other_miss)

    def head():
        return ["Full tests (with the private characters) on the nightly's data compared with the pinned data:", "",
                "- Tests that fail only on the nightly: **%d**" % len(new_fail),
                "- Tests that fail only on the pinned data: %d" % len(fixed),
                "- Per-character differences that are new on the nightly: **%d** (gone: %d)" % (len(new_miss), len(gone_miss)), ""]

    public = head() + ["- `%s.%s`" % f for f in new_fail[:25]] + ([] if len(new_fail) <= 25 else ["- and %d more" % (len(new_fail) - 25)])
    private = head() + ["Failing only on the nightly:"] + ["- `%s.%s`" % f for f in new_fail] + ["", "New per-character differences:"] + ["- " + m for m in new_miss]
    private += ["", "Gone on the nightly:"] + ["- " + m for m in gone_miss]
    if a.public:
        open(a.public, "w", encoding="utf-8").write("\n".join(public) + "\n")
    if a.private:
        open(a.private, "w", encoding="utf-8").write("\n".join(private) + "\n")
    print("\n".join(public))
    # A workflow can branch on this.
    print("::notice::new_failures=%d new_misses=%d" % (len(new_fail), len(new_miss)))


def cmd_diff(a):
    text = diff_markdown(diff_files(load_data(a.old), load_data(a.new)), a.old, a.new)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)
    print(text)


STATE_RE = re.compile(r"<!-- nightly-state: (.*?) -->")


def parse_state(text):
    m = STATE_RE.search(text or "")
    return dict(p.split("=", 1) for p in m.group(1).split() if "=" in p) if m else {}


def cmd_report(a):
    fingerprint = data_sets.fingerprint_of(a.zip)
    ran, failed = junit(a.results)
    failed = [f for f in failed if (f[0], f[1]) not in EXPECTED_FAILURES]
    drift = [f for f in failed if f[0] == "DataSetDriftTest"]
    others = [f for f in failed if f[0] != "DataSetDriftTest"]
    changes = diff_files(load_data(a.pin_data), load_data(a.zip))
    diff_md = diff_markdown(changes, "the pinned data", a.tag)
    if a.diff_out:
        open(a.diff_out, "w", encoding="utf-8").write(diff_md)

    result = "passed" if not failed else "FAILED"
    state = "tag=%s fingerprint=%s result=%s failures=%d drift=%d" % (a.tag, fingerprint[:12], result, len(others), len(drift))
    previous = parse_state(open(a.previous_state, encoding="utf-8").read()) if a.previous_state and os.path.isfile(a.previous_state) else {}

    out = ["The newest Chummer nightly is **%s**, checked by the Chummer nightly watch workflow. This is a status page, rewritten on every new nightly; "
           "a comment is added only when the result changes. A nightly is never added to `chummer-supported.txt`: its data changes daily." % a.tag, "",
           "| | |", "|---|---|",
           "| Data fingerprint | `%s` |" % fingerprint[:12],
           "| Data drift test (new bonus shapes, empty catalogs) | **%s** |" % ("FAILED" if drift else "passed"),
           "| Tests on Chummer's own test characters, run against this data | **%s** (%d run, %d failed) |" % ("passed" if not others else "FAILED", ran, len(others)),
           "| Changes since the pinned data | %s |" % ("none" if not changes else "%d file(s), listed below" % len(changes)), ""]
    if drift:
        out += ["### Drift", ""] + ["- `%s`: %s" % (f[1], f[2][0][:600] if f[2] else "") for f in drift] + [""]
    if others:
        out += ["### Tests that fail on this data", "", "These pass on the pinned data, so the data or the engine changed:", ""]
        out += ["- `%s.%s`%s" % (f[0], f[1], (": " + f[2][0][:200]) if f[2] else "") for f in others[:25]]
        if len(others) > 25:
            out.append("- and %d more" % (len(others) - 25))
        out.append("")
    out += ["### What changed in the data", "", diff_md, "Not covered here: Chummer's rule code (C#), which the data does not show. "
            "The tests that need private characters run in a private repository.", "", "<!-- nightly-state: %s -->" % state]
    body = "\n".join(out)
    if len(body) > MAX_BODY:
        body = body[:MAX_BODY] + "\n\n(cut: the full data diff is in the workflow run's artifacts)\n<!-- nightly-state: %s -->" % state
    open(a.out, "w", encoding="utf-8").write(body)
    open(a.state_out, "w", encoding="utf-8").write(
        "changed=%s\nstate=%s\n" % ("true" if previous and (previous.get("result") != result or previous.get("drift") != str(len(drift))
                                                              or previous.get("failures") != str(len(others))) else "false", state))
    print(state)


def main():
    # Item names can hold characters a Windows console cannot print.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("diff"); d.add_argument("old"); d.add_argument("new"); d.add_argument("--out"); d.set_defaults(fn=cmd_diff)
    c = sub.add_parser("compare")
    c.add_argument("--base", required=True); c.add_argument("--other", required=True)
    c.add_argument("--public"); c.add_argument("--private"); c.set_defaults(fn=cmd_compare)
    r = sub.add_parser("report")
    r.add_argument("--tag", required=True); r.add_argument("--zip", required=True); r.add_argument("--pin-data", required=True)
    r.add_argument("--results", required=True); r.add_argument("--previous-state"); r.add_argument("--out", required=True)
    r.add_argument("--diff-out"); r.add_argument("--state-out", required=True); r.set_defaults(fn=cmd_report)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
