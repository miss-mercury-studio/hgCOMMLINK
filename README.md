# hgCOMMLINK

A Shadowrun 5th Edition character app for Android. It creates and manages characters on a phone and aims to match the numbers [Chummer 5a](https://github.com/chummer5a/chummer5a) produces on the desktop.

> **Status: early development (v0.1.0).** It builds and runs on a Galaxy S24 Ultra, but it is not feature-complete and has not been released. See [docs/PLAN.md](docs/PLAN.md) for exactly what works and what does not.

## What it does

- Opens and saves Chummer 5 `.chum5` character files. The XML is edited in place and unknown content is kept, so desktop-only data is not lost. Desktop Chummer 5.226 opens files the app writes.
- Shows a character sheet built for one-handed portrait use: stats, skills, magic and resonance, gear, ware, life and qualities, with a bottom tab bar.
- **Creates characters** with the Karma, Priority and Sum-to-Ten build methods, with a live karma, points and nuyen ledger and a review step before finishing. Also creates critters, spirits, shapeshifters and A.I.s.
- **Advances characters in career mode**: karma and nuyen expense log with undo, and purchases of attributes, skills, qualities, spells, adept powers, complex forms, martial arts, initiation, gear, weapons, armor and cyberware.
- Applies house rules: per-character settings profiles and Chummer's `customdata` folders (including `amend_*` files).
- Imports through "open with", share-to, or the system file picker, and shares characters out. Files live in app storage or a folder you choose.

## Scope

| In | Out |
|---|---|
| Shadowrun 5e (SR5) | Other editions |
| Character creation and career advancement | Cloud sync, ChummerHub / ChummerNet |
| Phone, portrait | Tablet and landscape layouts |
| English | Other languages (for now) |

## How it works

The app does not rewrite Chummer's rules from scratch. It ships Chummer's XML game data unmodified and interprets it with a smaller Kotlin engine (a registry of handlers for `<bonus>` nodes, plus ports of the calculation rules). Accuracy is measured with golden tests that recompute values in 35 reference characters and compare them with the numbers Chummer stored or printed.

| Module | Contents |
|---|---|
| `core` | Pure Kotlin/JVM, no Android dependencies: XML and data pack, character model, improvements and calculation engines, editors, ledgers. Tests live here. |
| `app` | Jetpack Compose UI, file handling and storage. |
| `tools` | Generators, for example `gen_bonus_table.py`, which builds the simple bonus handlers from Chummer's source. |

## Building

Requirements: JDK 17, the Android SDK (compileSdk 36, minSdk 31), and a local checkout of [chummer5a](https://github.com/chummer5a/chummer5a). The build copies Chummer's `data`, `customdata` and `lang/en-us.xml` into the APK, and the reference characters used by the tests are not in this repository.

1. Clone chummer5a next to this repo.
2. Point the build at it, either by editing `chummer.src` in `gradle.properties` or on the command line:
   ```
   ./gradlew -Pchummer.src=/path/to/chummer5a :app:assembleDebug
   ```
3. Run the core tests (desktop, no device needed):
   ```
   ./gradlew -Pchummer.src=/path/to/chummer5a :core:test
   ```
   Some tests also read example characters through the `chummer.reference` property and skip or fail without them.

Install the debug APK on a device, then open or import a `.chum5` file, or create a new character from the character list.

Your own `.chum5` files are never committed (see `.gitignore`).

## Project documents

- [docs/PLAN.md](docs/PLAN.md): goals, decisions, milestones, findings about Chummer's rules, outstanding items and the progress log.

## License and credits

This project is a port of behavior and uses data from Chummer 5a, which is licensed under the GNU GPL v3. This project is therefore GPL-3 as well. Chummer's copyright notices apply to the game data it ships.

Shadowrun is a trademark of The Topps Company, Inc. This is an unofficial fan project, not affiliated with or endorsed by Chummer, Catalyst Game Labs or Topps.
