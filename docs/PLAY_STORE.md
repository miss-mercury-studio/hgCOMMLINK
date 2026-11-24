# Google Play: beta metadata draft

A working draft for a **closed beta** on Google Play. Nothing here has been submitted. Google's rules and form wording change, so check each field against the current Play Console when you enter it. This is not legal advice.

## Decisions made

- **Game data:** the Play edition ships **no Chummer game data**. The player adds it from their own Chummer folder on first run (see [RELEASING.md](RELEASING.md), "Two editions"). The GitHub APK keeps bundling it. This lowers the takedown risk below; it does not remove it, since the title, description and in-app wording still refer to the game.
- **Signing key:** import the existing key into Play App Signing (steps under "Setting up the closed beta"), so Play and GitHub installs share one signature.
- **Contact email:** contact@missmercury.studio.
- **Account:** personal, so the closed test needs the minimum number of testers (12 for 14 days at the time of writing; check the current rule).

## Background to those decisions

1. **The Shadowrun name and the bundled game data.** *Shadowrun* is a registered trademark, and the rules text in Chummer's data files is copyrighted by the rights holders (Catalyst Game Labs / Topps). Chummer distributes that data as a free fan tool and the app ships it unmodified. Google Play's intellectual-property policy lets rights holders request takedowns, and an app can be removed for using a trademark in its title or implying it is official. Options: (a) keep the store title free of the trademark and say "unofficial fan tool" in the description (the draft below does this); (b) ask the rights holder for permission before publishing; (c) stay on GitHub releases only. Pick one deliberately; the draft assumes (a) and still carries risk.
2. **Signing key.** Play re-signs apps with its own key ("Play App Signing"). If you let Play create a new key, an app installed from Play and one installed from the GitHub APK have **different signatures** and cannot update each other (the same problem as debug versus release). To keep one signature across both, choose "use the same key as for other distribution" at enrollment and import the existing key from `C:\Users\kross\hgcommlink-signing\` (Play's documentation covers the export step). Decide this at enrollment: it cannot be changed afterwards.
3. **Who the developer is.** A personal developer account created recently must run a closed test with a minimum number of testers (12, opted in for 14 continuous days at the time of writing) before it can apply for production. Check the current requirement, and whether Miss Mercury Studio should be an organization account instead (which has different verification).
4. **Privacy policy URL.** Play requires one. The policy for this studio is kept on the `miss-mercury-studio-notices` account; use its published URL. It must say what the app does, which is below.
5. **Minimum Android version.** The app needs Android 12 (API 31) or newer, which excludes some older phones. State it in the description.

## App facts (from the code)

| | |
|---|---|
| Package name | `studio.missmercury.hgcommlink` (permanent once published) |
| Current version | 0.1.2 (version code 102; Play needs each upload's code to be higher) |
| Android | minSdk 31, targetSdk 36 |
| Build to upload | `./gradlew :app:bundlePlayRelease` makes `app/build/outputs/bundle/playRelease/app-play-release.aab` (about 9.6 MB, no game data), signed with the release key. Play wants the `.aab`, not the `.apk`. The release workflow also builds it and keeps it as a run artifact. |
| Permissions | **None.** No network, no storage, no contacts. Characters and the player's Chummer folder are opened through the system file and folder pickers. |
| Ads, purchases, accounts | None |
| Data leaves the phone | Only if the player chooses to share a character or a crash report |
| Open source | GPL-3 (a port of Chummer 5a). The source is public on GitHub. |

## Store listing

**App name** (30 characters max): `hgCOMMLINK: SR5 Characters` (26)

**Short description** (80 max):
`Build and manage 5th Edition tabletop RPG characters. Opens Chummer files.` (74)

**Full description** (4000 max):

```
hgCOMMLINK is an unofficial, free fan tool for creating and managing Shadowrun 5th Edition characters on your phone. It reads and writes Chummer 5 character files (.chum5), so characters move between this app and desktop Chummer without losing anything, and it aims to produce the same numbers Chummer does.

GAME DATA
hgCOMMLINK does not include Shadowrun's rules data. On first run, a Get Chummer button opens the free Chummer 5a download page in your browser; download the release zip it names, then choose that zip in the app (no unzipping). The files are copied into the app and the app itself downloads and sends nothing. Menu, Game data shows which Chummer version is in use.

WHAT IT DOES
• Open, edit and save .chum5 characters, with desktop-only data kept intact
• A character sheet built for one hand in portrait: stats, skills, magic and resonance, gear, ware, life and qualities
• Create characters with the Karma, Priority and Sum-to-Ten methods, with a live ledger for karma, points and nuyen and a review before you finish
• Critters, spirits, shapeshifters and A.I.s as characters
• Career advancement with a karma and nuyen log and undo: attributes, skills, skill groups, qualities, spells, adept powers, complex forms, martial arts, initiation, gear, weapons, armor, cyberware and vehicles
• House rules: per-character settings profiles and Chummer's custom data folders
• Import with "open with", share-to or the file picker, and share characters out

YOUR DATA STAYS WITH YOU
The app asks for no permissions and has no account, ads or tracking. Characters are ordinary files in app storage or a folder you choose. If the app crashes, it only offers to share a report; nothing is sent unless you choose to.

BETA
This is an early version and is not feature-complete. Please report problems and ideas from the menu (Report a problem, Send feedback) or on GitHub. Some rules are still being checked against Chummer.

Requires Android 12 or newer, in portrait on a phone.

hgCOMMLINK is not affiliated with, endorsed by or sponsored by Catalyst Game Labs, Topps, or the makers of Chummer. Shadowrun is a trademark of its respective owner. Uses the data files of the open-source Chummer 5a project, which you supply. Source code: github.com/miss-mercury-studio/hgCOMMLINK
```

**Category:** Tools (alternative: Entertainment). **Tags:** pick from Play's list: tabletop / role-playing tools if offered.
**Contact email:** contact@missmercury.studio (shown publicly). **Website:** `https://github.com/miss-mercury-studio/hgCOMMLINK`.

## Beta release notes ("What's new", 500 max)

```
First beta. Skill groups now show their rank at once and lock like Chummer's when their skills differ. Buying a first skill rank no longer moves the row. Starting nuyen from your priority shows right away. New: if the app crashes it offers to report it, plus Report a problem and Send feedback in the menu. Menu > What's new has the full change log.
```
(349 characters)

## Graphics to prepare

| Asset | Spec | Status |
|---|---|---|
| App icon | 512 x 512 PNG, 32-bit, no transparency needed | Launcher icon exists in the app; export a 512 version |
| Feature graphic | 1024 x 500 PNG or JPG | To make |
| Phone screenshots | At least 2, up to 8; 16:9 to 9:16, each side 320 to 3840 px | Capture from the phone (1080 x 2340 works) |

Suggested screenshots, in order: the character list; a sheet's Stats tab; the Skill tab with a group locked; the New Character dialog with priorities; a creation ledger (karma, nuyen); the Gear tab; What's new. Use a made-up character, not a real one.

## App content forms (answers to expect)

- **Privacy policy:** the URL above.
- **Ads:** no.
- **App access:** all functionality available without login.
- **Content rating (IARC questionnaire):** answer for the app's own content. It is a character-sheet tool; its bundled game text describes weapons, drugs, magic and violence in a tabletop RPG. Answer honestly and let the questionnaire set the rating.
- **Target audience:** not for children; choose 13+ or older and no "designed for children".
- **Data safety:** collects no data; shares no data. The app reads a folder the player picks with the system folder picker and copies it into its own storage; it has no network access. Crash reports and shared characters are only produced by an explicit player action and sent by the player (browser, share sheet), not by the app. No data is encrypted in transit because none is transmitted by the app. Nothing to delete on a server.
- **Permissions declarations, financial, health, news, government:** none apply.

## Setting up the closed beta

1. Create the developer account and the app (default language English, app, free).
2. Enroll in Play App Signing and **import the existing key** (decide before the first upload; it cannot be changed). In the console choose to upload a key exported from a Java keystore, download `pepk.jar` and the encryption public key it offers, and in the `hgcommlink-signing` folder run `java -jar pepk.jar --keystore=hgcommlink-release.jks --alias=hgcommlink --output=output.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem` (it asks for the passwords). Upload `output.zip`, then check that the SHA-256 the console shows for the app signing certificate matches the fingerprint in [RELEASING.md](RELEASING.md). Back the keystore folder up off this PC first.
3. Testing, Closed testing, create a track named "Beta". Upload the Play-edition `.aab` (`bundlePlayRelease`).
4. Add testers: an email list or a Google Group, and share the opt-in link (it is only visible to listed testers).
5. Fill in the store listing and the forms above. They are required before a beta can roll out.
6. Roll out to the track. Reviews and Android vitals (crashes and freezes) appear in Play Console; file real ones as issues with the `from-play` label (see [FEEDBACK.md](FEEDBACK.md)).

## Release process

- The release workflow builds both editions on a version tag. The Play bundle is kept as a workflow artifact; upload it by hand to Play at first. Automating the upload needs a Play service account and is a later step.
- Keep `app.versionName` and the change log as they are. The version code is derived from the version, so it always rises, and both editions share it.
- The GitHub APK and the Play build share one version number, one package id and, with the key import above, one signature.
- Testers need Chummer's data on the phone. Say so in the invitation.
