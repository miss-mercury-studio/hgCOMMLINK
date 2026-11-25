# Changelog

All notable changes to hgCOMMLINK. The newest release is first. The app shows this file under **What's new**, so keep entries to one short line, written for players, not developers.

Format: `## [version] - date`, then `### Added`, `### Changed` or `### Fixed`, then one `-` line per change. Put work in progress under `## [Unreleased]

### Added
- **Use another Chummer version.** Menu > Game data: Get Chummer, then Choose zip or folder. You can go back to the built-in version any time.
- **Game data shows what you are using.** Version, nightly or stable, and Verified or Unverified. Unverified data adds a warning on character sheets.
- **Warning when desktop Chummer won't open a character.** Shown on the sheet, and sharing asks first.
- **Gear with plug-ins has its own sheet.** Tap a gear item with parts inside it, like a Fake SIN with licenses or a drug with a grade, to see what is in it, switch a part on or off, sell or remove it, or plug in more.
- **Merge gear stacks.** Identical gear listed on separate lines, such as ammo, gets a Merge button that combines the quantities.
- **Armor sets.** Make named sets on Gear, tap one to add or remove armor, and wear or take off the whole set at once.

### Changed
- **Sell at any percent.** Selling now has a percent box that starts at 50 and goes from 0 to 9999.99 with plus and minus buttons, like desktop Chummer. Before, you could only pick 100, 75, 50 or 25.

### Fixed
- **Fake Licenses ask which license.** Buying one into a Fake SIN asks which license it is for, with Chummer's list to pick from, and keeps the answer. Before, every bought license had no name.
- **Shorter item cards.** A card with many of the same part inside, like a Fake SIN with eight licenses, now says "Fake License x8". The item's sheet lists them all.
- **A refused gear purchase says why.** It no longer says "Not enough nuyen" when the real problem is availability or a price that cannot be worked out.
- **Buttons keep their size.** "Cancel" and "Buy" on the buy dialogs could stay tiny after the dialog slid in.
- **Armor mod list only shows mods that fit.** Built-in mods like Custom Fit and mods made for one particular armor no longer show for every armor.
- **Buying ammo again adds to your existing stack,** including ammo kept inside armor or other gear. Ammo now asks which weapon type it is for, so it joins the right stack. Before, every purchase made a new line. Undoing a purchase takes back only what it added.
- **Gear shows what it is for.** Ammo, licenses and similar items now show their type, like "Regular Ammo (Heavy Pistols)", so separate stacks are no longer mistaken for duplicates.
- **Armor can be worn and taken off.** Tap an armor on Gear to wear it or take it off, switch, sell or remove its mods and installed gear, or sell the armor.
- **An A.I. can no longer be given cyberware or bioware.** Desktop Chummer hung loading one. The Ware tab is hidden for an A.I.

## [0.1.5] - 2026-11-24

### Added
- **Weapon sheet.** Tap a gun on Gear, then Manage: see, load, switch, top up and unload its clips, and spend rounds as you fire.
- **Weapon accessories can be managed.** Switch one off, sell it in career, or take it back in creation.
- **Spare Clips add clip slots.** Selling or removing one gives back its rounds.
- **Weapons show their loaded ammo.** Damage and AP include its bonus.
- **Edit cyberware and bioware in creation.** Change grade or rating without buying again.
- **Upgrade cyberware and bioware in career.** Pay only the difference; the log can undo it.

### Changed
- **Long character names fit the title bar.**
- **Gear and Ware tabs are easier to read.** Items are cards under their own headings.

### Fixed
- **Deleting a character's file while it is open is handled.** The app offers to save it again or close it.
- **The app returns to your character and tab** after Android closes it in the background.
- **Accessories are only offered for weapons they fit.**
- **Flechette weapons use the right figures** with flechette ammunition.
- **The character list updates when you go back to it.**
- **Nothing gets cut off** on small screens or with a larger font.
- **New character works again.** It failed in 0.1.4.

## [0.1.4] - 2026-11-24

### Added
- **The character list reloads when you return to the app.**
- **Lifestyles can be added and removed.** The cost comes out of your nuyen.

### Changed
- **Built-in game data is now Chummer's stable release (5.226.0).**

## [0.1.3] - 2026-11-24

### Added
- **Crash reports.** After a crash you can report it on GitHub or share it. It never contains your characters.
- **Report a problem** and **Send feedback** in the menu.

### Fixed
- **Exceptional Attribute on Magic** now shows 7 everywhere, and other attributes raised past their maximum too (#1).

## [0.1.2] - 2026-11-24

### Fixed
- **Skill group ranks show at once.**
- **Skill groups lock the way Chummer's do.**
- **Buying a first rank no longer moves the skill.**
- **Starting nuyen** is spendable as soon as the character is made.

## [0.1.1] - 2026-11-24

First release built and signed by the release process.

### Added
- **What's new** in the menu.
- Releases are signed with a permanent key, so updates keep your characters.

### Fixed
- **Skills with no name** now show their names.
- **Crash when scrolling the skill list.**

## [0.1.0] - 2026-11-22

The first working build. Not released.

### Added
- **Open, edit and save Chummer 5 characters** with nothing lost.
- **Character sheet** for one hand: stats, skills, magic, gear, ware, life and qualities.
- **Create characters** with Karma, Priority and Sum-to-Ten, with a live ledger and a review step.
- **Metatypes, critters, spirits, shapeshifters, metasapients and A.I.s.**
- **Career advancement** with a karma and nuyen log and undo.
- **Magic and resonance:** spells, foci, spirits, sprites, initiation and submersion.
- **Gear, ware and vehicles,** with essence and selling.
- **House rules:** per-character settings and custom data folders.

### Changed
- The game data is Chummer's own, read by a smaller Kotlin rules engine.
