# Releasing hgCOMMLINK

How a version gets from this repo to testers' phones, and how feedback and bug reports come back.

## The short version

1. Fix things. Add a line for each user-visible change under `## [Unreleased]` in [CHANGELOG.md](../CHANGELOG.md).
2. When ready to release, rename `[Unreleased]` to `[x.y.z] - YYYY-MM-DD` (and add a fresh empty `[Unreleased]` above it).
3. Set `app.versionName=x.y.z` in [gradle.properties](../gradle.properties).
4. Commit, then tag and push:
   ```
   git tag vx.y.z
   git push origin master vx.y.z
   ```
5. GitHub Actions ([release.yml](../.github/workflows/release.yml)) builds the signed APK, creates the GitHub Release with the change log section as its notes, attaches `hgCOMMLINK-x.y.z.apk`, and posts the notes to Discord `#releases` if the webhook is set.

The workflow refuses to run if the tag, `app.versionName` and the change log disagree, and a unit test fails if the newest change log entry does not match `app.versionName`. So a stale log cannot ship.

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

**Debug builds are signed with a different key** (Android Studio's debug key). A debug build and a release build cannot update each other. That is why the first release-signed install on a phone that has a debug build needs an uninstall: export characters first (share them out of the app), or just keep using debug builds on your own phone.

### GitHub secrets the workflow needs

Repo, Settings, Secrets and variables, Actions, "New repository secret":

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_B64` | The keystore file, base64-encoded: `base64 -w0 hgcommlink-release.jks` |
| `RELEASE_STORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `RELEASE_KEY_ALIAS` | `hgcommlink` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` from `keystore.properties` |
| `DISCORD_RELEASES_WEBHOOK` | Optional. The `#releases` channel's webhook URL (Channel settings, Integrations, Webhooks, New webhook, Copy URL). Treat it like a password. |

## Chummer's data

The APK ships Chummer's game data, so CI checks out [chummer5a](https://github.com/chummer5a/chummer5a) at the commit in [chummer-pin.txt](../chummer-pin.txt). Releases use that commit, not whatever upstream is today, so a build is repeatable. To take newer game data: update the pin, run the tests locally against that checkout, and note it in the change log.

## Getting the APK to testers

- Public repo: testers open the repo's **Releases** page on their phone and tap the `.apk` (Android asks to allow installs from the browser the first time).
- **Obtainium** (a free app) can watch the repo's releases and install updates automatically. Point it at the repo URL. Recommended for regular testers.
- Each release is also announced in Discord `#releases` with the notes and a link.

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

## Not built yet

- A crash handler that saves the last stack trace and offers "Share crash report" on the next launch, so testers do not need logcat.
- A "Send feedback" menu item that opens a prefilled issue with the version and device.
- An in-app "update available" check.
