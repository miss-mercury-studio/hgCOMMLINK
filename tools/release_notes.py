"""Print the CHANGELOG.md section for one version, as GitHub release notes. Usage: release_notes.py 0.1.1"""
import re
import sys

version = sys.argv[1]
text = open("CHANGELOG.md", encoding="utf-8").read()
m = re.search(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
if not m:
    sys.exit(f"CHANGELOG.md has no section for {version}")
print(m.group(1).strip())
