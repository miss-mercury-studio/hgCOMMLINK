# Changelog

All notable changes to hgCOMMLINK. The newest release is first. The app shows this file under **What's new**, so keep entries short and written for players, not developers.

Format: `## [version] - date`, then `### Added`, `### Changed` or `### Fixed`, then one `-` line per change. Put work in progress under `## [Unreleased]`; it is not shown in the app until it is given a version.

## [Unreleased]

### Added
- **Use another Chummer version.** The app still comes with Chummer's latest stable game data, so it works as soon as it is installed. If you would rather use the exact version you know, open Game data in the menu, tap Get Chummer to open its download page, then Choose zip (no unzipping needed) or Choose folder. You can go back to the version that came with the app at any time. Your characters are not touched.
- **Game data in the menu shows what you are using.** It names the Chummer version, including the exact version and commit for a nightly or other release you add, and marks it **Verified** (the app has been tested against that data) or **Unverified** (it has not). Data you chose yourself is tagged **Nightly**, **Stable** or **Custom** (in the menu, on the Game data sheet and in About). On an unverified version, character sheets show a warning that numbers may differ from Chummer's.

## [0.1.5] - 2026-11-24

### Added
- **Each weapon has its own sheet, with proper clip and ammunition handling.** Tap a gun on the Gear tab and choose Manage. You can see every clip it has (its own, plus one for each Spare Clip), what is loaded in each and how full it is, switch which clip the weapon fires from, load a clip with the ammunition and number of rounds you choose, top up a partly used clip, unload it, and spend rounds as you fire. The damage and AP shown follow the clip you are firing from, so switching from regular ammo to APDS changes them straight away. Accessories, selling and removing are in the same sheet.
- **Weapon accessories are listed in the weapon sheet, and you can manage them.** Each accessory is a card showing its mount, rating and any clip slots it adds. You can switch off one that adds to the weapon's figures, such as accuracy or recoil compensation (it stops counting but stays fitted), sell it in career for a share of its price, or take it back in creation. Parts that come with the weapon can only be switched off.
- **Spare Clips now give the weapon extra clip slots.** Buy one and the weapon gains an empty clip you can load and switch to; selling or removing it gives back the rounds in it.
- **Weapons show the ammunition loaded in them.** Each gun on the Gear tab shows what is in its active clip and how many rounds (for example Loaded: APDS ×42), or Empty when it has none. The damage and AP shown already include that ammunition's bonus.
- **Cyberware and bioware can be edited while you create a character.** Tap an item on the Ware tab to change its grade or rating without buying it again. The sheet shows the price and essence before and after, only the difference in nuyen is spent, and it refuses a change you cannot afford or that is over the availability limit.
- **Cyberware and bioware can be upgraded in career mode.** The Ware tab shows an **Upgrade** button on each item that has a better grade, for example Standard to Alphaware. It charges only the difference in price (plug-ins fitted to the item are upgraded too), the essence you get back shows at once, and the button is dimmed when you cannot afford it. The log has an undo that puts the old grade and the nuyen back.

### Changed
- **Long character names no longer get cut off in the title bar.** When the full title does not fit, it shortens to `> name` so the name shows in full.
- **The Gear tab is easier to read.** Armor, weapons, vehicles and gear are now grouped under their own headings, each item is a card with its figures as tags (a vehicle's handling, speed and body, a weapon's mode, accuracy and recoil), and the buttons match the Ware tab. Gear cards are compact, so a long kit takes less scrolling.
- **The Ware tab is easier to read.** Each item is now a card, with cyberware and bioware grouped under their own headings, grade and rating shown as tags, plug-ins listed inside the item they belong to, and the Upgrade, Plug-in, Sell and Remove buttons all the same size.

### Fixed
- **Deleting a character's file while it is open no longer causes odd behaviour.** If the file is deleted or moved outside the app (with a file manager or a sync app) while the character is open, the app now tells you when you come back to it and offers to save it again as a new file or close it. Before, your next change could fail with a confusing message, or quietly bring the file back.
- **The app now returns to the character and tab you were on.** If Android closes hgCOMMLINK while you are in another app, such as a browser, opening it again takes you straight back to your character and the tab you had open, without the boot logo, instead of dropping you on the character list. Backing out to the list still starts you on the list next time.
- **Weapon accessories are only offered for weapons they fit.** A Spare Clip made for one gun, or a clip for a gun with a cylinder, no longer appears on every weapon, and a part that does not fit cannot be bought. This follows Chummer's own rules.
- **Flechette weapons now use the right figures with flechette ammunition.** A weapon that already fires flechettes (a slivergun, for example) gets nothing extra from Flechette Rounds, and Stick-n-Shock Flechette Rounds lower its damage by 2 and its AP by 5, as Chummer does. Before, they were treated like ammunition for an ordinary gun.
- **The character list now updates when you go back to it.** Finishing a character and starting career mode, or any change to a character, shows on its card straight away instead of after a manual refresh.
- **Numbers and buttons no longer get cut off on smaller screens or with a larger font.** The nuyen box now always shows how much is left, bottom buttons such as New, Critter and Import shrink to fit instead of losing letters, panel titles no longer break apart, armor and gear names no longer run into their values, and the tab bar scrolls sideways when the tabs do not fit.
- **Creating a new character works again.** In the 0.1.4 download, New character failed with "the new character skeleton is missing". The built-in template was left out of the app, so no new characters could be made.

## [0.1.4] - 2026-11-24

### Added
- **The character list reloads when you return to the app.** Files added to a shared folder while the app was in the background now appear without tapping Refresh, and the list stays where you scrolled.
- **Lifestyles can be added.** The Life tab has a **+ Lifestyle** button: pick a lifestyle and the number of months, and the cost comes out of your nuyen. Lifestyles can be removed again, and in career the expense log can undo the purchase.

### Changed
- **The built-in game data is now Chummer's stable release (5.226.0)** instead of a nightly build, so it matches what you get from Chummer's download page.

## [0.1.3] - 2026-11-24

### Added
- **Crash reports.** If the app closes unexpectedly, the next time you open it a sheet shows what happened and lets you report it on GitHub or share it anywhere. It contains the app version, your phone and the error, never your characters, and nothing is sent unless you choose to.
- **Report a problem** and **Send feedback** in the menu, which open a pre-filled form on GitHub.

### Fixed
- **Exceptional Attribute on Magic.** Spending the special attribute point to take Magic from 6 to 7 now shows 7 on the stats and skills screens, not just in the attribute editor. Other attributes raised past their normal maximum are fixed the same way (#1).

## [0.1.2] - 2026-11-24

### Fixed
- **Skill group ranks show at once.** A group you raise with points now shows its new rank in the group list straight away, instead of only on the main skills page.
- **Skill groups lock the way Chummer's do.** When a skill in a group is bought on its own so the skills differ, the group is marked "locked" and cannot be raised, and its karma stops counting toward the skills, as in Chummer.
- **Buying a first rank no longer moves the skill.** The row stays where it is while you buy ranks, so you can keep tapping +. Skills you have not rated at the start sit under "Other skills" until you reopen the tab.
- **Starting nuyen in Priority and Sum-to-Ten creation.** The nuyen from your resources priority now shows and can be spent as soon as the character is made. Before, it only appeared after you changed the karma-to-nuyen setting once.

## [0.1.1] - 2026-11-24

First release built and signed by the release process.

### Added
- **What's new** in the menu: this change log, back to the first build.
- Release builds are signed with a permanent key, so later versions install over this one without losing characters.

### Fixed
- **Skills with no name.** Characters saved by Chummer often store only a skill's id, so their active skills showed as blank rows. Names now come from the game data.
- **Crash when scrolling a character's skill list.** A blank or repeated skill name made the list fail ("Key was already used"). Reported by a tester.

## [0.1.0] - 2026-11-22

The first working build, tested on a Galaxy S24 Ultra. Not released.

### Added
- **Open, edit and save Chummer 5 characters** (`.chum5`) with nothing lost: desktop-only data is kept, and Chummer 5.226 opens the files the app writes. Open with, share to and the file picker all work.
- **Character sheet** for one hand in portrait: stats, skills, magic and resonance, gear, ware, life and qualities, with a bottom tab bar and a damage card with wound modifiers, recovery and overflow.
- **Create characters** with the Karma, Priority and Sum-to-Ten methods. A live ledger tracks karma, points and nuyen, and a review step warns about anything unspent before you finish. Finishing rolls the starting nuyen.
- **Metatypes and metavariants**, plus critters, spirits, shapeshifters, metasapients and A.I.s as characters.
- **Career advancement:** a karma and nuyen log with undo. Buy attributes, skills, skill groups, knowledge skills, specializations, qualities (and buy off negatives), spells, adept powers, complex forms, martial arts and techniques, initiation and submersion with metamagics and echoes, and contacts.
- **Magic and resonance:** spells, rituals, foci and bonding, mentor spirits and their choices, summoned and bound spirits, sprites, enchantments and enhancements at a grade.
- **Gear, ware and vehicles:** weapons with accessories, armor and armor mods, ammo loading and unloading, cyberware and bioware with essence, cyberlimb upgrades, lifestyles, vehicles and drones, selling with a share of the cost.
- **A.I. programs**, critter powers, and A.I. condition monitors and recovery.
- **House rules:** per-character settings profiles and Chummer's custom data folders, including `amend_` files, with a cache so they open quickly.
- Pickers for everything above, with search and filters.

### Changed
- The game data is Chummer's own, shipped unmodified and read by a smaller Kotlin rules engine. Attributes, skills, derived stats, resistances, armor and weapons were checked against Chummer's own output for the reference characters.
