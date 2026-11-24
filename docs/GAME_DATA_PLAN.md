# Game data for the Play edition: plan

Status: plan only, nothing here is built yet except the first-run folder import (commit `d59788a`, branch `play-edition`).

The Google Play edition ships without Chummer's game data (see [RELEASING.md](RELEASING.md), "Two editions"). This plan is "tier 1": make getting the data as easy as it can be **without the app touching the network**, and make the data's version visible everywhere it matters. The player brings the files; the app never downloads them. Tier 2 (in-app download of pinned, hashed files) is deliberately out of scope until after the beta, because it needs the INTERNET permission and changes the privacy policy, the listing and the Data Safety answers.

## What we found

| Fact | Detail |
|---|---|
| What the engine is checked against | `chummer-pin.txt` = `dcdede6` = Chummer's nightly build **Nightly-v5.226.248** (2026-11-20). All 193 core tests pass on it. |
| What players can easily get | The newest *stable* release, **5.226.0** (`Chummer5.226.0.zip`, 44 MB). It is also what Chummer's own site points people to. |
| Do they differ? | Yes. 45 data files changed between them and 5.226.0 has no `drugs.xml`. Run against the 5.226.0 data and fixtures, **192 of 193 core tests pass**. The one failure is `PowerEditorTest.powersMatchTheFixtures` ("improvements regressed: 11"): adept power improvements differ for 11 powers. |
| Release zip layout | Flat: `data/`, `lang/`, `customdata/` at the zip root (no `Chummer/` folder), plus `changelog.txt` and the program files. The nightly zip (`Chummer.Nightly.zip`, 45 MB, one per nightly tag) has not been opened yet; check it has the same layout. |
| Version information in the files | `changelog.txt` at the zip root starts `Future Build 227`, then `Build 226` (the version is the first `Build N` after the "Future" heading). It is not in the source repo's root, and there is no version marker inside the data XML. So the **only reliable identity of a data set is a fingerprint of its files**. |
| Repo size | The chummer5a repo is about 600 MB, so "Download ZIP" of the repository is the wrong thing to tell players. (The setup screen text is already corrected to the release zip.) |

A note on how this was measured: a first run against a partial copy of 5.226.0 reported 19 failures. Those were missing test files (`Chummer.Tests/TestFiles`), not data differences, and are not a finding. The 1-of-193 result above used a complete copy.

## Phase 0: fix the engine mismatch (on `master`, before anything else)

**Done (uncommitted on `master`).** The cause was not the 11 powers: the test allows 10 known misses on the pinned data, and 5.226.0's data adds exactly one more. Chummer changed how `Master Archer` is written between builds (5.226.0: `<weaponcategorydice><category><name>Bows</name><value>1</value></category>`; the pin: `<weaponcategorydice><name>Bows</name><bonus>1</bonus>`), and the engine only read the newer form. `BonusHandlers.kt` now reads both. All 193 core tests pass on the pinned data and on 5.226.0. `tools/data_sets.py` fingerprints a data set and runs the tests against a release zip; `chummer-supported.txt` lists both sets, and the release workflow fails if the pin is not on it. Not done: a CI job that runs the tests on the stable release too (the workflow only runs the change log test today), so the second data set is checked by running the tool by hand.

The original steps, for reference:

Goal: the engine passes against the **latest stable release data as well as the pin**, so the "Get Chummer" link can point at the stable release that everyone already knows.

1. Triage `PowerEditorTest.powersMatchTheFixtures` on 5.226.0 data: which 11 powers, and whether the engine reads something the older `powers.xml` lacks or computes a bonus differently. Decide per case: engine fix, or a documented difference between the two data versions.
2. Make it pass on both data sets, or, if some difference cannot be reconciled, record it as a known difference and warn on that data set (Phase 3).
3. Add a way to test against a second data set: a CI job (or script) that extracts a release zip's `data`, `lang`, `customdata` and the matching `Chummer.Tests/TestFiles` and runs `:core:test` against it. Only the pinned data is tested in CI today.
4. Record the supported set in a committed file, `chummer-supported.txt`: one line per supported data set, with a name, where it comes from and its fingerprint (Phase 2). Produced by a small script (`tools/data_fingerprint.py`), checked in CI so it cannot drift from the pin.

If Phase 0 shows the 5.226.0 differences are large, fall back to: the link points at the matching **nightly** release instead, and the setup screen says so. Decide after step 1.

## Phase 1: zip import (branch `play-edition`)

- The picker accepts a **zip** or a **folder**. Two buttons on the setup screen: "Choose zip" and "Choose folder". (Not alternatives to each other: the zip is the main path, the folder is for people who already have Chummer unpacked.)
- Read the zip as a stream through the content resolver; no unzipping to storage first. From the stream take: `data/*.xml` (top level only), `lang/en-us.xml`, `customdata/**` (not `.xsd`), and `changelog.txt` (for the label). Find these under the zip root **or** under one folder level (so a repo-style `Chummer/data/...` also works).
- Keep the existing safety: copy into a staging folder, swap in at the end, delete the staging folder on failure.
- Put the zip-reading and layout detection in `:core` as plain `java.io` code so it is unit-testable on the JVM; the Android layer only supplies the `InputStream`s.
- Clear errors for the likely mistakes: not a zip; a zip with no `data` folder (for example the source zip of the repo, or a custom data pack); a zip that is huge (stop early rather than read 600 MB; say what to download instead); out of storage.
- Show progress (entries copied) and keep the work off the main thread (already so). Survive rotation and the app being backgrounded.

## Phase 2: game data version

One small type, `GameDataVersion`, used everywhere:

- `fingerprint`: SHA-256 over the sorted list of `data/*.xml` names and each file's SHA-256, with carriage returns removed from file contents first (so Windows and Linux checkouts and the release zip agree). Same algorithm in Kotlin (app and core) and Python (tool).
- `label`: looked up from `chummer-supported.txt` by fingerprint (for example "Chummer 5.226.0"). If the fingerprint is not known, use the `changelog.txt` build ("Chummer build 226, not checked") or "Unknown Chummer data".
- `status`: **Supported** (fingerprint in the supported list) or **Unchecked** (anything else).
- `source`: bundled, zip or folder, and the import date.
- Saved with the data (`chummer-data/.version`), recomputed when the data changes. The GitHub edition builds its value in at compile time from the pin.

## Phase 3: wire the version through the app

| Where | What it shows |
|---|---|
| Setup screen | After an import: "Added Chummer 5.226.0. Checked with this app." or the warning. Before: which version to download. |
| Menu | A "Game data" row: version, status, date, and "Update game data". Present in both editions (read-only on GitHub). |
| What's new / boot line | "Game data: Chummer 5.226.0" under the app version. |
| Crash reports, Report a problem, Send feedback | The data version label and the first 8 characters of the fingerprint, next to the app version and edition. Also in the GitHub issue templates as a prefilled field. |
| Opening a character | When the data is **Unchecked**, the sheet's existing warning banner says numbers may differ from Chummer's. When a `.chum5` was saved by a newer Chummer than the data (its stored app version, if the file has one; check this in the fixtures first), say the data may be older than the character. |
| Cache keys | The "amended" cache and the loaded data pack are keyed by fingerprint, so an update never serves stale numbers. |
| Docs and Play listing | State which Chummer version to download, and that other versions may give different numbers. |

## Phase 4: "Get Chummer" button and a smoother first run

- A "Get Chummer" button on the setup screen opens the stable release page in the browser (`ACTION_VIEW`, so the app needs no network permission). The link is a constant tied to the supported list.
- When the player returns, the app offers the picker already opened at Downloads (initial folder hint). Remember that a download is in progress so the first screen says "Choose the file you downloaded" instead of starting over.
- No "Open with" or share-to for zips (decided: not offering it).
- Stretch, if cheap: after a successful import, offer to delete nothing and just say the zip can now be removed from Downloads (the app never deletes the player's files).

## Phase 5: tests, docs, release

- Unit tests in `:core` (JVM): fingerprint equals the Python tool's output on the pinned data and on 5.226.0 (strip-CR case included); zip layouts (flat, nested, no data, wrong zip, truncated zip); label lookup; status.
- On the phone (debug build, clear storage first): zip import from Downloads, folder import, a bad zip, an update over existing data, an unsupported version, rotation mid-import, low storage.
- Release workflow: a step that fails if `chummer-supported.txt` does not list the pin's fingerprint.
- Update [PLAY_STORE.md](PLAY_STORE.md) (listing text names the version; Data Safety wording), [RELEASING.md](RELEASING.md), [FEEDBACK.md](FEEDBACK.md), the change log, and the tester invitation.

## Order of work

1. `master`: Phase 0 (engine mismatch). Merge `play-edition` after it, or rebase it onto master.
2. `play-edition`: Phases 1, 2, 3, 4 (the version type is needed by 1's import result and by 3, so 2 comes first in practice).
3. Phase 5 alongside, then a phone test before the beta build is uploaded.

## Open questions

- Which stable release do we name as "supported" if Chummer ships 5.227 during the beta? Proposal: the supported list gains an entry only after the core tests pass on it.
- If Phase 0 can't reconcile the powers difference, do we point players at the nightly instead (less familiar, changes daily) or support the stable release with a visible known-difference note?
- Should the GitHub edition also show the Game data row? Proposed yes (read-only): it makes bug reports from both editions comparable.
