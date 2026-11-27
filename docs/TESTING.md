# Testing and CI

Every way the tests run, and every GitHub workflow. For the release steps themselves see [RELEASING.md](RELEASING.md); for the game data support list see [GAME_DATA_PLAN.md](GAME_DATA_PLAN.md).

## What is tested

All automated tests are in `core` (pure Kotlin/JVM, JUnit 4, under `core/src/test`). They run on the desktop and need no device. The `app` module has no automated tests; the Android UI is checked by hand on a phone (see "Builds and the phone" in [CLAUDE.md](../CLAUDE.md)).

The tests fall into a few kinds:

| Kind | Examples | What it checks |
|---|---|---|
| Golden | `GoldenAttributeTest`, `GoldenEssenceTest`, `GoldenArmorTest`, `GoldenWeaponsTest`, `GoldenExport*Test` | Recomputes values in reference characters and compares them with the numbers Chummer stored or printed. This is the accuracy measure. |
| Round trip | `RoundTripTest` | Loads and saves characters without losing anything. |
| Editor and rules | `*EditorTest`, `CreationPurchasesTest`, `CareerAdvancementTest`, `SkillsTest`, and others in `core/.../engine` | Behavior of the engine and the editing operations, usually on a character from the fixtures. |
| Data drift | `DataSetDriftTest` | Guards against Chummer changing its data format. Fails on a new kind of `<bonus>` node (recorded in `core/src/test/resources/bonus-shapes.txt`) or a catalog that comes out empty. Needs only Chummer's public data. |
| Game data | `GameDataTest` | The data under test is listed in `chummer-supported.txt`. This always fails on a nightly, by design. |
| Change log | `ChangelogTest` | The change log parses, and its newest entry matches `app.versionName`. A stale log cannot ship. |
| Crash reports | `CrashReportTest`, `CrashReportGameDataTest` | The crash report text. |

## Test inputs

Tests read three outside inputs. Gradle passes them as system properties (see `core/build.gradle.kts`).

| Property | What it is | Where it is set |
|---|---|---|
| `chummer.src` | A checkout of [chummer5a](https://github.com/chummer5a/chummer5a): its `Chummer/data` and its public test characters (`Chummer.Tests/TestFiles`). | `gradle.properties` locally, `-Pchummer.src=...` in CI. CI uses the commit in `chummer-pin.txt`. |
| `chummer.reference` | Private reference characters (such as `Viper_career.chum5` and its settings). Not in this repo. | `gradle.properties`, local only. |
| `chummer.submitted` | Optional folder of characters from bug reports and testers, kept outside the repo. One folder per character, with its settings profile and custom data beside it. Empty when not set. Layout is in `Fixtures.kt`. | `-Pchummer.submitted=...`. Changing the folder reruns the tests. |

`Fixtures.characters()` is the upstream characters, plus the Viper reference character if present, plus any submitted ones. A submitted character with a missing settings profile or custom data folder fails and says what to add. Upstream and reference characters fall back to Chummer's first built-in profile instead.

## Testing modes

### 1. Full local run (the normal one)

```
./gradlew :core:test
```

Uses `chummer.src` and `chummer.reference` from `gradle.properties` (the pinned Chummer data). This is everything. About 40 test classes read the private reference characters and cannot run anywhere else.

### 2. Public only

```
./gradlew -Pchummer.src=/path/to/chummer5a -Pchummer.publicOnly=true :core:test
```

Leaves out every test class whose source mentions `chummer.reference`. The list is found by reading the sources, so a new test is sorted automatically and nobody keeps a list. This is what the Tests workflow runs, and it needs no secrets. It is a subset: passing here does not mean the full suite passes.

### 3. One test or class

```
./gradlew :core:test --tests chummer.core.engine.DataSetDriftTest
```

Standard Gradle filtering. Used by the Chummer release watch and while working on one area.

### 4. Submitted characters

```
./gradlew -Pchummer.submitted=C:/path/to/submitted :core:test
```

Adds characters from bug reports to the golden and round-trip tests. Use it to reproduce a report and to keep the fix covered.

### 5. Another Chummer data set

```
python tools/data_sets.py test --zip Chummer5.226.0.zip [--checkout ../chummer5a] [-- <gradle args>]
```

Builds a folder holding that release's `data`, `lang` and `customdata` plus the pinned checkout's test characters, and runs `:core:test --continue` against it. Players can import their own Chummer data, so the engine has to read the versions we tell them to use. Needs `../chummer5a` at the pin. Extra Gradle arguments after `--` are passed through, for example `-Pchummer.publicOnly=true`.

Related commands in the same script:

| Command | Does |
|---|---|
| `fingerprint PATH` | Prints the data fingerprint for a release zip, a Chummer folder or a data folder. This is the value in `chummer-supported.txt`. |
| `check` | Fails unless `chummer-supported.txt` lists the pinned data. Runs in the release workflow. |
| `gate` | Full core tests on every line of `chummer-supported.txt`. Takes several minutes per set and needs the private reference characters. `tools/release.py` runs it before tagging. |

### 6. Updating the bonus shapes

```
./gradlew -Pchummer.updateShapes=true :core:test --tests chummer.core.engine.DataSetDriftTest
```

Adds the open data set's bonus shapes to `bonus-shapes.txt` instead of failing. Run it once per data set, and only after making sure `BonusHandlers` reads the new shapes. It merges, so earlier sets are kept.

### 7. Change log test only

```
./gradlew -Pchummer.src=/path/to/chummer5a :core:test --tests "chummer.core.ChangelogTest"
```

The only test the release workflow runs, because that run does not have the private characters.

### 8. Release gate

`python tools/release.py X.Y.Z --dry-run` shows what would happen. Without `--dry-run` it runs the data set gate (mode 5's `gate`) and the change log test before it commits and tags. `--skip-data-tests` exists for emergencies only. Never run a release without being asked; see [RELEASING.md](RELEASING.md).

### Where the private tests run

CI cannot read the private reference characters, so the tests that need them run on the developer's machine (mode 1 and the gate). The nightly watch also mentions a private fixtures repository that runs those tests against a nightly; `nightly_watch.py compare` produces its report (the public text names only tests and counts, the private text also lists per-character lines).

## CI workflows

All are in `.github/workflows`. Tests need Java 17 (Temurin) and a clone of chummer5a at the pin, which each workflow sets up.

| Workflow | Runs when | What it does |
|---|---|---|
| [tests.yml](../.github/workflows/tests.yml) **Tests** | Push to master, pull requests to master, manual | Public-only core tests (mode 2). No secrets, so it is safe on fork pull requests. Cancels an older run on the same ref. On failure it uploads the HTML test report (`core-test-report`, kept 7 days). |
| [changelog-check.yml](../.github/workflows/changelog-check.yml) **Change log check** | Pull requests to master (opened, updated, labeled) | If `app/src/main` or `core/src/main` changed, `CHANGELOG.md` must gain a `- ` line. Add the `no-changelog` label for a change players will not notice (tests, refactors, CI). It does not run on direct pushes. |
| [release.yml](../.github/workflows/release.yml) **Release** | Push of a `v*` tag | Checks the tag equals `app.versionName` and builds release notes from the change log; checks the pinned data is on `chummer-supported.txt`; runs `ChangelogTest`; builds the signed APK and app bundle with the secrets in GitHub; uploads the bundle as the `play-bundle-X.Y.Z` artifact (30 days, uploaded to Play by hand); creates the GitHub release with the APK. |
| [chummer-watch.yml](../.github/workflows/chummer-watch.yml) **Watch Chummer releases** | Mondays 06:17 UTC, manual | If Chummer's newest stable release is not on `chummer-supported.txt`, runs `DataSetDriftTest` on its data and opens an issue with the result, the fingerprint and the steps to support it. One issue per release. Nothing changes for players until a person lists it. |
| [chummer-nightly-watch.yml](../.github/workflows/chummer-nightly-watch.yml) **Watch Chummer nightlies** | Daily 03:40 UTC, manual (with a `force` option) | Finds Chummer's newest `Nightly-*` prerelease. If it was not checked already, runs the public tests on its data (mode 5 with `publicOnly`), and updates one rolling issue, "Chummer nightly status" (label `chummer-nightly`): drift and public test results, and what the nightly's data changes since the pin. It comments only when the result changes, and uploads the full data diff (`nightly-data-diff`, 30 days). A failing test is reported, not a workflow failure. A nightly is never added to `chummer-supported.txt`, and `GameDataTest`'s supported-list check is an expected failure there. |
| [debug-build.yml](../.github/workflows/debug-build.yml) **Debug build** | Push to a `claude/**` branch, manual | Runs the public-only core tests (mode 2) and builds the debug APK, kept 7 days (`build-report` holds the test report if it failed). No secrets. Signed with the runner's own debug key, so it cannot be installed over a debug app built on the PC; never uninstall that app to make room, it holds characters. |
| [triage.yml](../.github/workflows/triage.yml) **Triage** | New issue | Adds the `needs-triage` label. Filter the Issues tab with `label:needs-triage`. See [FEEDBACK.md](FEEDBACK.md). |

## Which check answers which question

| Question | Run |
|---|---|
| Did I break the engine? | Mode 1 (full). Mode 2 if the reference characters are not available. |
| Is the change log valid? | Mode 7, or the Change log check on the pull request. |
| Does a new Chummer release work? | Mode 5, then `fingerprint` and add the line to `chummer-supported.txt`. |
| Did Chummer change its data format? | `DataSetDriftTest` (mode 3 on the new data). The Monday watch does this for you. |
| What will the next Chummer release change? | The nightly status issue. |
| Can I ship? | `python tools/release.py X.Y.Z --dry-run`. |
