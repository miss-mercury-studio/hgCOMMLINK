# Changelog

All notable changes to hgCOMMLINK. The newest release is first. The app shows this file under **What's new**, so keep entries short and written for players, not developers.

Format: `## [version] - date`, then `### Added`, `### Changed` or `### Fixed`, then one `-` line per change. Put work in progress under `## [Unreleased]`; it is not shown in the app until it is given a version.

## [Unreleased]

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
