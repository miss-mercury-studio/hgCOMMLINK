# Releasing hgCOMMLINK

How a version gets from this repo to testers' phones, and how feedback and bug reports come back.

## The short version

1. While you work, add a line for each player-visible change under `## [Unreleased]` in [CHANGELOG.md](../CHANGELOG.md). (Claude does this by itself; see [CLAUDE.md](../CLAUDE.md).)
2. When you want to release, run:
   ```
   python tools/release.py 0.1.2 --dry-run     # check, and show the entries going out
   python tools/release.py 0.1.2               # rename [Unreleased], bump the version, run the change log test, commit, tag
   git push origin master v0.1.2               # or add --push to the line above
   ```
   The script refuses if the tree is dirty, you are not on master, master is behind origin, the tag exists, `[Unreleased]` is empty, or the version is not higher than the current one. Nothing is pushed unless you ask.
3. The push of the tag starts [release.yml](../.github/workflows/release.yml): it builds the signed APK, creates the GitHub Release with the change log section as its notes, and attaches `hgCOMMLINK-x.y.z.apk`. The repo's Discord webhook (see "The Discord announcement" below) sees the new release and posts it to `#releases`; the workflow itself does not talk to Discord.

Doing it by hand is the same three things: rename `[Unreleased]` to `[x.y.z] - date` (and add a fresh empty one above it), set `app.versionName=x.y.z` in [gradle.properties](../gradle.properties), then commit, tag `vx.y.z` and push both.

The workflow refuses to run if the tag, `app.versionName` and the change log disagree, and a unit test fails if the newest change log entry does not match `app.versionName`. So a stale log cannot ship.

**On pull requests**, [changelog-check.yml](../.github/workflows/changelog-check.yml) fails if app or core code changed without a new `[Unreleased]` entry. Add the `no-changelog` label to a pull request that players will not notice (tests, refactors, CI). It does not run on direct pushes to master.

**Version numbers** are SemVer: `0.1.1` is a fix, `0.2.0` is new features, `1.0.0` is "feature complete enough to call it done". Android's internal `versionCode` is worked out from it (`major*10000 + minor*100 + patch`), so it always rises with the version and never needs editing.

**What's new** in the app reads `CHANGELOG.md` straight from the APK, so write each line for a player, not a developer.

## The signing key (read this once)

Android only installs an update over an existing app if both APKs were **signed with the same key**. The key lives in a keystore file. If it is lost or replaced, nobody can update: they would have to uninstall first, which deletes the characters stored inside the app. So:

- **The keystore is permanent.** One key for the life of the app.
- **It must never be committed or shared.** Anyone with the file and passwords can publish "updates" that install over yours. `.gitignore` already blocks `*.jks` and `*.keystore`.
- **It must be backed up.** There is no recovery if it is lost.

Where things are:

| What | Where |
|---|---|
| Keystore file | `C:\Users\kross\hgcommlink-signing\hgcommlink-release.jks` |
| Passwords and alias | `C:\Users\kross\hgcommlink-signing\keystore.properties` (same folder; not in the repo) |
| Used by CI | GitHub repo secrets (below) |
| Key details | RSA 4096, alias `hgcommlink`, valid 100 years |
| Fingerprint (SHA-256) | `88:66:C2:82:8B:3B:CE:B7:60:04:58:D4:14:FE:F4:3F:CB:9A:C6:CF:F0:60:5D:99:7D:5F:82:9A:6B:71:22:BE` |

**To back it up:** copy the whole `hgcommlink-signing` folder into a password manager's secure attachments (or an encrypted drive). Keep it somewhere that is not this PC. Do this now.

**Local release build** (on this PC, no GitHub needed): `./gradlew :app:assembleRelease`. Gradle finds `keystore.properties` itself; the APK lands in `app/build/outputs/apk/release/`. Without the file the build is simply unsigned.

**Check a signature:** `apksigner verify --print-certs app-release.apk` (in the Android SDK's `build-tools`). The SHA-256 must match the fingerprint above.

**Debug and release are two separate apps.** The debug build has its own id (`studio.missmercury.hgcommlink.debug`) and shows as "hgCOMMLINK (debug)" in the launcher, with a `-debug` version. It is signed with Android Studio's debug key, the release with the permanent key, and Android treats them as different apps, so they install **side by side**: use `installDebug` all day, and install a release APK whenever you want to test one, with no uninstalling either way. Each has its own characters, backups and folder permission, so point the debug app at a test folder (or the same folder, if you are careful about editing the same character in both).

Commands that name the app need the right id: `adb shell am force-stop studio.missmercury.hgcommlink.debug`, `adb shell run-as studio.missmercury.hgcommlink.debug ...` (only the debug build allows `run-as`), `adb logcat -b crash -d`.

**Phones that still have the old debug build** (installed before this change, id `studio.missmercury.hgcommlink`, debug-signed) must uninstall it once before the release installs. See "Switching from the old debug build" below.

### Switching from the old debug build (one time)

Testers who installed a debug build before 0.1.1 see "App not installed" when they try the release APK. Android refuses an update signed with a different key. They uninstall the old app first, then install the release, then use the menu, "Choose a shared folder", and pick the same folder as before to bring their characters back. Characters kept in app storage (no shared folder chosen) are deleted by the uninstall, so share them out of the app first. The game data is inside the APK and needs no export.

### GitHub secrets the workflow needs

Repo, Settings, Secrets and variables, Actions, "New repository secret":

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_B64` | The keystore file, base64-encoded: `base64 -w0 hgcommlink-release.jks` |
| `RELEASE_STORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `RELEASE_KEY_ALIAS` | `hgcommlink` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

## Chummer's data

The APK ships Chummer's game data, so CI checks out [chummer5a](https://github.com/chummer5a/chummer5a) at the commit in [chummer-pin.txt](../chummer-pin.txt). Releases use that commit, not whatever upstream is today, so a build is repeatable. To take newer game data: update the pin, run the tests locally against that checkout, and note it in the change log.

[chummer-supported.txt](../chummer-supported.txt) lists the Chummer data sets the engine is checked against (by fingerprint), and the release workflow fails if the pinned data is not on it. Players of the Play edition bring their own data, usually Chummer's latest stable release, so test that too:

```
python tools/data_sets.py test --zip Chummer5.226.0.zip     # runs :core:test on that release's data (needs ../chummer5a at the pin for the test characters)
python tools/data_sets.py fingerprint Chummer5.226.0.zip    # the line to add to chummer-supported.txt once the tests pass
```

Chummer changes its data format between builds (for example how `weaponcategorydice` is written), so a new release can need engine changes before it is listed.

## Getting the APK to testers

- Public repo: testers open the repo's **Releases** page on their phone and tap the `.apk` (Android asks to allow installs from the browser the first time).
- **Obtainium** (a free app) can watch the repo's releases and install updates automatically. Point it at the repo URL. Recommended for regular testers.
- Each release is also announced in Discord `#releases` by the repo webhook, with the notes and a link.

## The Discord announcement

Discord posts come from a **GitHub repo webhook**, not from the release workflow, and there is no Discord secret in Actions. Setup, once:

1. Discord: `#releases` channel settings, Integrations, Webhooks, New webhook, Copy Webhook URL.
2. GitHub: repo Settings, Webhooks, Add webhook:
   - Payload URL: the Discord URL with `/github` added on the end (`https://discord.com/api/webhooks/<id>/<token>/github`).
   - Content type: `application/json`.
   - Secret: leave empty (Discord does not use one).
   - Events: "Let me select individual events", tick only **Releases**.
3. Treat the Discord URL like a password: it lets anyone post to the channel. If it leaks, delete the webhook in Discord and add a new one.

A new webhook only sends a test "ping", which Discord accepts but does not show. A post appears when a **release** is published.

**Resending an announcement.** Redelivering an old delivery in GitHub replays the stored payload, with its old links (after the move to the org it still pointed at the old owner). To send a fresh one with current links, unpublish and publish the release again:
```
gh release edit vX.Y.Z --draft=true
gh release edit vX.Y.Z --draft=false
```
The tag, notes and APK are untouched. Check the webhook's Recent Deliveries tab: `published` should be 204.

## Discord and GitHub: where feedback goes

| Channel | Use |
|---|---|
| `#releases` | Read-only. Filled by the webhook. |
| `#feedback` | Ideas and "this is confusing". Anything. |
| `#bug-reports` | A forum channel. Pin the template below. |

Pinned template for `#bug-reports`:

```
App version (menu > What's new > Installed):
Phone and Android version:
What you did, from opening the app:
What you expected:
What happened:
Screenshot if you have one:
```

Triage: a bug that reproduces becomes a GitHub Issue (the repo has a bug and a feedback form under [.github/ISSUE_TEMPLATE](../.github/ISSUE_TEMPLATE)), labelled `from-discord`, with a link back to the thread. Fixed bugs go into the change log under `### Fixed` with a plain-language line, so testers see it in What's new and in `#releases`. Close the Discord thread when the release that fixes it ships.

## Related docs

- [FEEDBACK.md](FEEDBACK.md): how crash reports, app feedback, Discord, GitHub and Google Play reports all become GitHub issues.
- [PLAY_STORE.md](PLAY_STORE.md): a draft of the Google Play closed beta (listing text, forms, decisions to make first).

## Not built yet

- An in-app "update available" check.
- Automatic upload of the app bundle to Google Play.
- Capturing app freezes (only crashes are caught by the app; Play Console shows freezes once the app is on Play).
