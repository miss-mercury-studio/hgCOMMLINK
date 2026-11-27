# Changelog

All notable changes to hgCOMMLINK. The newest release is first. The app shows this file under **What's new**, so keep entries to one short line, written for players, not developers.

Format: `## [version] - date`, then `### Added`, `### Changed` or `### Fixed`, then one `-` line per change. Put work in progress under `## [Unreleased]`; it is not shown in the app until it is given a version.

## [Unreleased]

### Added
- **Vehicles and drones have their own sheet.** Tap a vehicle on Gear to see its mods and gear, take off or sell what you added, or sell the whole vehicle.
- **Drone arms and legs take cyberware.** Open the drone, tap + Ware on a limb, and pick from what is made for that limb. It uses the limb's capacity and costs nuyen only, and it belongs to the drone, not your character's essence. Each limb shows its Strength (the drone's Body) and Agility (its Pilot), which Customized Strength and Agility raise up to double. Cyberlimb Optimization asks which skill, and a custom cyberfinger asks what it is for.
- **Vehicle figures include the fitted mods.** Handling, Speed, Acceleration, Armor, Sensor, Pilot, Body and Seats show the totals, and the vehicle sheet lists what changed. Tires, speed and armor upgrades and drone upgrades all count.
- **Buy mods and gear for a vehicle or drone.** Only mods that fit that vehicle are offered, priced from its own figures, with the mod slots they use.

### Changed
- **Rating and quantity have − and + buttons.** In every buy dialog the number is labelled with its range, and you can still type it.
- **Buy dialogs show your nuyen with separators.** ¥117,675, not ¥117675.
- **The armor mod list shows prices.** They used to appear only after you tapped a mod.
- **Weapon accessory prices are shown as money.** A Smartgun System, Internal shows its real price for that weapon instead of "Weapon Cost".

### Fixed
- **Merge no longer touches loaded ammo.** Rounds in a clip were being folded back into the loose stack, which left the clip showing "Empty". Loaded stacks now show as Loaded, and bought ammo never joins them.
- **Removing loaded ammo empties its clip.** The clip used to keep counting the rounds and show "Empty" with a full bar.
- **A clip with no ammo behind its rounds says "Ammo missing".** Tap Unload on it to clear it.
- **Vehicle mods priced from the vehicle's Handling, Speed or Armor count toward your nuyen.** They were being counted as free.
- **Characters saved by very old Chummer versions show the right attributes.** They were showing 1 or 2 too high.
- **Nuyen shows as money.** A balance like 2702.7799999997 now reads ¥2,702.78, with thousands separators.

## [0.2.0] - 2026-11-25

### Added
- **Use another Chummer version.** Menu > Game data: Get Chummer, then Choose zip or folder. You can go back to the built-in version any time.
- **Game data shows what you are using.** Version, nightly or stable, and Verified or Unverified. Unverified data adds a warning on character sheets.
- **Warning when desktop Chummer won't open a character.** Shown on the sheet, and sharing asks first.
- **Armor can be worn and taken off.** Tap an armor on Gear to wear it, switch or sell its mods and gear, or sell it.
- **Armor sets.** Group armor into named sets, and wear or take off a whole set at once.
- **Gear with plug-ins has its own sheet.** Tap a Fake SIN or a drug to see what is in it, switch parts on or off, sell or remove them, or plug in more. A Fake License asks which license it is.
- **Merge gear stacks.** Identical gear on separate lines gets a Merge button.

### Changed
- **Sell at any percent.** The sell box starts at 50 and goes from 0 to 9999.99, like desktop Chummer.

### Fixed
- **Armor mod list only shows mods that fit.** No more Custom Fit or mods made for other armor.
- **Buying ammo adds to your existing stack.** It asks which weapon type, and undo takes back only that purchase.
- **Gear shows what it is for.** Ammo reads like "Regular Ammo (Heavy Pistols)", so stacks are not mistaken for duplicates.
- **A refused gear purchase says why,** such as availability, not just "Not enough nuyen".
- **Buy and Cancel text stays full size.**
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
