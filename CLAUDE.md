# hgCOMMLINK: notes for Claude

Android app (Kotlin, Compose) plus a pure-Kotlin `core` engine that reproduces Chummer 5a's Shadowrun 5e numbers. See [docs/PLAN.md](docs/PLAN.md) for status and [docs/RELEASING.md](docs/RELEASING.md) for the release process.

## Change log (always)

[CHANGELOG.md](CHANGELOG.md) is shown to players in the app (menu, What's new) and becomes the GitHub release notes and the Discord post.

- When a change is **visible to a player** (a fix, a new feature, changed behavior, a rules correction), add one line for it under `## [Unreleased]` in the same piece of work, under `### Added`, `### Changed` or `### Fixed`. Do not wait to be asked.
- Write it for a player, not a developer: what they will notice, in plain words. No class names or file paths. Say who reported a bug only if the user wants that.
- Do not log developer-only changes (tests, build, refactors, docs, CI, the debug app id).
- Never invent a version heading. Versions are created only by `tools/release.py`.

## Releases (never without asking)

- Do **not** bump `app.versionName`, create a `v*` tag, or push a tag on your own. Each release is the user's decision.
- When the user asks for a release, run `python tools/release.py X.Y.Z --dry-run` first and show the entries going out, then run it without `--dry-run`, and only add `--push` if the user says to push. The script checks the tree, the branch, that `[Unreleased]` has entries, and runs the change log test.
- Never put the signing key, its passwords, or the Discord webhook URL in the repo, in output, or in commit messages. The key lives in `C:\Users\kross\hgcommlink-signing\` and in GitHub secrets.

## Builds and the phone

- Debug and release are separate apps: debug is `studio.missmercury.hgcommlink.debug` ("hgCOMMLINK (debug)"), release is `studio.missmercury.hgcommlink`. There are two editions (flavors): `github` bundles Chummer's game data, `play` does not (the player adds it from their own Chummer folder). Use `installGithubDebug` day to day; `installPlayDebug` has the same id, so it replaces the github debug app (keeping its data). `installDebug` never touches the release app. Use the `.debug` id with `adb shell am`, `run-as` and `logcat`.
- Never uninstall an app on the user's phone: that deletes characters kept in app storage.
- The phone is reached over wireless debugging (the IP and port change; ask for them if `adb devices` is empty). It must be unlocked to drive the UI.
- `:core:test` needs the `chummer.src` checkout and the reference characters. CI runs only the change log test.
