# Chummer 5e for Android: Plan

Living document. Update the status checkboxes, the decisions log and the open questions as work proceeds.
Last updated: 2026-11-21 (M1 done; M2 started)

## Goal

An Android app (Kotlin, Compose) for creating and managing Shadowrun 5e characters on the go, built to match Chummer 5a's behavior and to follow Chummer's ongoing development.

## Decisions

| Date | Decision |
|---|---|
| 2026-11-21 | License GPL-3 (Chummer5a is GPL-3, so a port must be too). Keep copyright notices and publish source. |
| 2026-11-21 | Edition: SR5 only for now. |
| 2026-11-21 | Scope: character creation and career advancement. Not just a viewer. |
| 2026-11-21 | Device: phone only, targeting Galaxy S24 Ultra (portrait). |
| 2026-11-21 | Cloud features tabled. Support convenient file management instead (Storage Access Framework, share, "open with"). |
| 2026-11-21 | ChummerHub / ChummerNet is out of scope. |
| 2026-11-21 | Approach: data-driven interpreter. Ship Chummer's XML data unmodified, and build a smaller Kotlin engine that interprets `<bonus>` nodes through a handler registry. |

## References

| What | Where |
|---|---|
| Chummer source (read-only reference) | `C:\Users\kross\Documents\CODE\chummer5a` |
| Example career character | `C:\Users\kross\Documents\CODE\reference\Viper_career.chum5`, with its settings and custom data in `reference\Viper_career\` (see the README there) |
| 34 more fixture characters | `chummer5a\Chummer.Tests\TestFiles\*.chum5` |
| Previous Android project (toolchain to reuse) | `C:\Users\kross\Documents\CODE\hg-glow` (Kotlin 2.2.20, AGP 8.13, Gradle version catalog) |
| Game data | `chummer5a\Chummer\data\*.xml` (about 96 files, 7.7 MB), `chummer5a\Chummer\lang\en-us.xml` |

## What we learned about Chummer

- Data is declarative XML. Entries have a GUID `<id>`, source book and page, and often a `<bonus>` block. Characters reference entries by GUID (`<sourceid>`).
- The rules engine is the hard part: about 75k lines in `Backend/Equipment`, 30k in Skills and Attributes, 22k in Improvements. `ImprovementType` has 328 members.
- `<bonus>` nodes use 246 distinct node types across 7,878 uses. The top 10 cover 62% of uses, the top 30 cover 83%, and the top 80 cover 95%. Ship about 30 handlers first, add the long tail by priority, and log unhandled nodes instead of crashing.
- Prerequisites use `<required>` (1,110), `<forbidden>` (433), `<oneof>` (959) and `<allof>` (269). **Correction (M0):** these are not XPath against the character XML. They are a custom mini-language of about 83 node types (`quality`, `metatype`, `skill`, `cyberware`, `weapondetails`, `magenabled`, ...), evaluated by a 3.6k-line `SelectionShared.cs` (`TestNodeRequirementsAsync`, about 60 `case` branches). We port it as a rule-by-rule evaluator over our character model.
- Formulas in data fields (`ess`, `cost`, `avail`, `capacity`, `weight`) are small arithmetic expressions. About 2,900 are non-numeric, and the vocabulary is `Rating`/`R`, `F` (force), `Body`, `FixedValues(...)`, `Variable(...)`, `Parent Cost` and a few vehicle stats. A small evaluator (`Expr.kt`) handles the plain arithmetic form. Function forms are still to do.
- **A saved `.chum5` stores Chummer's applied improvements** (`<improvements>`, 85 in Viper), each pointing back to its source item by GUID, plus calculated values (`totalvalue`, `totaless`, `calculatedvalues`). So viewing, and recalculating from stored improvements, does not need the full `<bonus>` interpreter. The interpreter is needed for creating or changing items, which is M4 onward.
- Four build methods: Karma, Priority, SumtoTen, LifeModule. About 170 per-character settings affect calculations. `customdata` folders hold house-rule overrides.
- `.chum5` is plain XML and stores Chummer's calculated values. That makes a golden test: load, recompute, diff against the stored values.
- Quirks seen so far: `essenceatspecialstart` is .NET `decimal.MinValue` (`-79228162514264337593543950335`). The file embeds a base64 mugshot (Viper is 2.3 MB, mostly that and the expense log).
- Largest catalogs: gear 1,601, qualities 803, weapons 787, vehicles 611, cyberware 381, spells 363, bioware 222.

## Settings and custom data (learned from the Viper campaign files)

- A character stores only a settings **GUID** (`<settings>`), not the settings. Viper's GUID matches `reference/Viper_career/settings/San Francisco Showdown.xml`. Chummer's older fixtures say `default.xml`. We need a settings loader that finds the right file (Chummer's own `data/settings.xml` holds the built-in ones; user-made ones live in the user's settings folder, which is what Viper's file is).
- Settings change calculations. Viper's differ from the defaults in limb count (4, not 6), excluded limb slots (`torso,skull`), lift limit `{STR} * 15`, carry limit `{STR} * 10`, max skill ratings, banned ware grades, build method and points. Several settings are **expressions** with `{Name}` variables, for example `({INTUnaug} + {LOGUnaug}) * 2` and `{Karma} * 2000 + {PriorityNuyen}`, so `Expr` needs `{Name}` variable support.
- Custom data is a folder with a `manifest.xml` (GUID, version) plus `custom_*.xml` files that add entries and `amend_*.xml` files that change existing ones. The settings list the folders as `GUID>version` with an `order` and an `enabled` flag. Viper uses 7 folders: 6 ship inside Chummer's `Chummer/customdata`, one (San Francisco Showdown) is campaign-specific.
- **Amend files use XPath.** Each entry has `xpathfilter="name = 'Pistols' and count(specs/spec[. = 'Machine Pistols']) = 0"` and an `amendoperation` (`remove`, `addnode`, `removenode`, ...). These are XPath 1.0, which Android supports (`javax.xml.xpath`). So the data pack needs an amend layer that applies these over the base data, in order. (This is separate from prerequisites, which are not XPath.)

## Architecture

- **`core` module:** pure Kotlin/JVM with no Android dependencies. Holds the XML parser, data pack, character model, improvements engine and validators. Runs on the desktop. Golden tests live here.
- **`app` module:** Compose UI, file handling, Room cache for the list and search. Portrait, one-handed use, bottom sheets, search-first catalog pickers.
- **Data pack:** Chummer XML unmodified, plus the English language file. Parse into indexed stores and cache the result. Fallback if parsing is too slow: build a binary or SQLite cache at install time.
- **Character storage:** `.chum5` files in a user-visible folder through the Storage Access Framework. XML is the source of truth. Room holds only a cache.
- **Unknown XML is preserved** on import and export so desktop-only features are not deleted.
- **Update path:** a `sync-chummer` script pulls upstream `data/` and `lang/`, runs the golden tests, and reports divergences. Pin the upstream commit per release. Read `appversion` and `minimumappversion` from imported files and warn when a file is newer than the engine supports.
- **Overrides** go in a separate layer, like Chummer's `customdata`. Never edit upstream data files.

## Milestones

Sizes are relative: S, M, L, XL.

### M0: Spike (S)
Answers the questions that could change the design.
- [x] Repo set up (git, Gradle, `core` and `app` modules, toolchain from hg-glow)
- [x] Parse Viper and resolve `sourceid` GUIDs against the data pack (299/299)
- [x] ~~Verify `required`/`forbidden` evaluate via XPath~~ Not XPath, see the correction above. Instead: surveyed the rule language (about 83 node types).
- [x] Measure data pack parse time and memory on the S24 Ultra. Results below. Decision: keep the DOM loader for now; revisit caching in M1/M7.
- [x] First-pass calculations diffed against Viper's stored values (see findings below)
- [x] Write up findings; revise this plan

M0 findings (all in `core/src/test`, run with `./gradlew :core:test`):
- **On-device (S24 Ultra, Android, debug build, `:app` benchmark screen):** the 43-file, 9,041-entry data pack loads in about 830 ms on a cold first run and about 390 ms on warm runs. Viper loads and resolves 299/299 ids in about 50-60 ms. One pack holds roughly 80 MB of Java heap, because it keeps the full DOM. Copying the 7 MB of data out of the APK took 84 ms (one-off). This is acceptable for the spike but wasteful; the likely improvements are loading only the files a screen needs, or indexing into compact structures, and doing both off the main thread behind a splash or placeholder.
- Viper: attributes 13/14 match. The formula is `metatype min + base + karma + enabled Attribute improvement aug`. ESS is the exception, since it is derived from essence.
- Viper: karma and nuyen expense-log sums equal the stored `karma` (5) and `nuyen` (6678) exactly.
- Viper: essence matches the stored 2.30 only if metagenic bioware (categories Exotic Metagenetics and Transgenics, such as Narco and Synaptic Acceleration) is excluded. Where Chummer encodes that rule is still to be found (M2).
- All 34 Chummer fixture characters parse. 613/613 `sourceid` references resolve, including files saved by older 5.202.x versions against current data.
- Across the fixtures, 390/441 attributes match. The misses cluster on AGI/STR (likely cyberlimbs), MAG/MAGAdept (likely initiation or magic rules) and RES. These are M2 work.
- 31 of the 34 fixtures use the **SumtoTen** build method, and only 3 use Priority. Viper is Karma. So SumtoTen is better covered by test data than Priority, which affects M4 and M5 ordering.
- 12 of 34 fixtures have a karma log that does not equal the stored karma. Most of these look like chargen files with no log, but this is unchecked.

### M1: Core model and I/O (M)
- [x] Data access behind a `GameData` interface (`DataPack` is the current DOM-backed implementation), so storage can change later
- [x] Lossless `.chum5` import and export: the DOM is read and written back, so unknown XML is preserved
- [x] Round-trip test over all 35 fixtures: content identical after read, write, read, and a second write is byte-stable. Files are not byte-identical to Chummer's own output (XML declaration and self-closing-tag formatting differ), which Chummer does not mind.
- [x] DTD and entity declarations are refused on import (shared files are untrusted). Checked on the raw bytes, because Android's XML parser does not support the usual parser feature.
- [x] Android file handling: import from the system picker, "open with" and "share to" for `.chum5`, share out through a FileProvider, delete, and a choice of app storage or a shared folder (system folder picker, access kept across restarts)
- [x] Character list and a read-only detail screen (summary and stored attribute totals)
- [ ] Verify on the phone: share out, delete, shared-folder choice, and the list with several characters. Only "open with" and the detail screen have been checked so far.
- [ ] Mugshot thumbnails in the list (the image is in the file, base64)

Notes:
- Compose and AndroidX versions are pinned to what builds with AGP 8.13 and compileSdk 36 (Compose BOM 2026.03.01). Current releases need AGP 9.1 and compileSdk 37; upgrading the toolchain is a separate decision.
- The list parses each file's DOM to read its summary (about 60 ms for Viper) and caches by modified time and size. Fine for now; revisit if lists get large.

### M2: Calculation engine (L), in progress
Engine code is in `core/src/main/kotlin/chummer/core/engine/`. Each calculation has a golden test that compares against values Chummer stored in the 35 reference characters, with a ratchet: the test fails if the number of misses grows, and prints when it shrinks.
- [x] Improvement model (`Improvement`) and Chummer's summing rules (`ImprovementSet`): enabled and unconditional filtering, `addToRating`, unique-name precedence (`precedence0`, `precedence-1`, `precedence1`), custom improvements
- [x] Attributes (`AttributeEngine`): minimum and maximum modifiers, `Attributelevel` free base, attribute modifiers, augmented maximum, armor-encumbrance floor, cyberlimb averaging for AGI and STR (customization and enhancement children, bonus cap, Redliner, limb defaults of 3). **Golden: 440/454 match** (was 403 before the engine).
- [x] Essence (`EssenceEngine`): metatype maximum, penalties, per-item cost from formula and grade, additive and multiplicative multipliers, Suite, discounts, cyberware, bioware and geneware multiplier improvements, children that add to the parent, Prototype Transhuman, rounding. **Golden: 31/35 match.** The Narco and Synaptic Acceleration exclusion found in M0 turned out to be a normal rule (geneware multiplier), not a special case.
- [x] Skills (`SkillEngine`), first version: rating (base + karma + free levels + group points + rating modifiers), rating maximum from settings plus improvements, dice pool (attribute + rating + modifiers + wound and sustaining), default pool (attribute - 1 unless the penalty is removed), disabled skills, no-default rules, knowledge skill attributes by type. Skill data (linked attribute, group, default) comes from the game data by the skill's `suid`. **No stored golden exists for skills**, so the tests check wiring and hand-derived values (Viper's 23 rated skills, default pools, disabled magic skills) and survey all 35 characters: 2,396 skills resolve to game data, none exceed their maximum, none have a negative pool. Not covered yet: skillsofts and activesofts, conditional improvements, specialization bonuses, broken skill groups, swapped attributes, wound modifier from current damage (passed in, defaults to 0).
- [x] Skills verified against Chummer's own print XML for Viper (`reference/exports/Viper_career.xml`): all 78 skills match on rating, rating maximum, linked attribute, skill group, default flag and dice pool (`GoldenExportSkillsTest`, attribute totals taken from the export). Fixes from this: knowledge skills never default, and a knowledge skill with no type uses LOG.
- [x] Derived stats (`DerivedStats`): physical, mental, social and astral limits, initiative score and dice, physical and stun condition monitors, overflow, threshold offsets, lift and carry (from the settings expressions), composure, judge intentions, memory, surprise, lift and carry pool, fatigue resist, dodge, direct mana resist, natural recovery. **Golden: 32 of 32 match** in `GoldenExportDerivedTest`, which replays the export's state (see below).
- [x] Armor: `ArmorEngine` ports the total armor rule (best non-stacking armor plus `+` accessories up to STR, ware and per-armor improvements) and armor encumbrance. Matches the export for armor, fire, cold, electricity, acid and falling with the Social set worn, and Chummer's stored AGI/REA penalty for the Runner set (`GoldenArmorTest`). The "Custom Fit (Stack)" mod is not read yet.
- [ ] Bonus handler registry: armor mods' `<bonus>` nodes still come from the saved file's stored improvements. Creating them from the data (needed for new items in M4) is not built; the Social set only worked because its improvements were already stored.
- [x] Resistance pools: judge intentions, fatigue, radiation, sonic, toxins and pathogens by vector, addiction, and all spell defences (31 values) match the export; `DerivedStats.resistances()`. Toxin and pathogen immunity is not handled.
- [ ] Still to do from this group: movement (needs the metatype movement string, cyberleg setting and encumbrance), matrix initiative (needs the active commlink's Data Processing, so it waits for gear parsing), astral initiative, wound modifiers (need current damage)
- [ ] Improvement handler registry for item creation (about 30 `<bonus>` node handlers)
- [x] Character settings (`CharacterSettings`): resolves a character's settings GUID against the user's settings folder, then Chummer's built-in `data/settings.xml`; a legacy name such as `default.xml` falls back to the first built-in profile. Reads simple values, books, custom data refs, banned grades, redliner exclusions and karma costs; expression settings evaluate through `Expr` (which now accepts `{Name}` variables); `toRuleSettings()` feeds the engines (limb count, excluded slots, cyberlimb cap, essence decimals and rounding).
- [x] Data amend layer (`DataAmender`, `CustomData`, `CustomDataLocator`): a port of Chummer's `AmendNodeChildren` and its `override_`, `custom_`, `amend_` loading order. Custom folders are found by manifest GUID. Tests on Viper's campaign: settings resolve, all 7 folders found (6 from Chummer, 1 campaign), the amends remove and add the expected entries, and all 299 of Viper's `sourceid`s still resolve in the amended data. Not ported: re-creating missing parent nodes when recursing into nodes absent from the base data.
- [ ] Remaining golden misses (below)

Findings from the amend layer (from reading Chummer's source and running our port; not yet seen in Chummer itself):
- **`amend_weapon.xml` is never applied.** Chummer only pairs a custom file with a base file when its name ends in `_` plus the base file's name, so `amend_weapon.xml` does not match `weapons.xml`. The campaign's change to a weapon's damage has no effect. Probably a typo for `amend_weapons.xml`.
- **`amendoperation="removenode"` is not an operation Chummer knows.** An unknown operation falls through to the default rules, which here means "replace every matching node". In our port, Automatics' five specializations all become `Machine Pistols`, instead of one being removed, and Pistols does gain Machine Pistols. If that matches Chummer, it is a bug in the campaign data that we must reproduce. To confirm: open Viper in Chummer with this settings profile and look at the Automatics specializations.
- **Cost:** loading with 7 custom folders takes about 1.7 s against 0.15 s without, on a warm desktop JVM (XPath over the whole DOM for every amend). On the phone this needs a cache keyed on the data version, settings and custom folders, so the amended data is built once.
- Fixtures that name `default.xml` do not change when given their settings, because the fallback is the standard profile; their real settings were never recorded.

**The Viper export is a different state from the saved file.** The file has the five drug groups (Kamikaze, Betameth, Cram, Novacoke, Psyche) off and armor encumbrance applied; the export has Kamikaze on, a different armor set equipped, and a CHA of 7 where the file gives 4. **User feedback and a screenshot of the Improvements tab: Kamikaze was switched on, and an ungrouped custom improvement named "Spell" (Attribute CHA, rating 1, augmented 3) was active**; it is a temporary improvement the user keeps for planning ahead, and it is not in the saved file (Chummer showed unsaved changes). **User feedback: the saved state has the Runner Armor set equipped and the export has the Social Situations set** (equipment groups chosen in the character menu), which accounts for the missing armor encumbrance. `reference/exports/README.md` records this. The derived-stat test replays that state, so it checks the calculations, not the state.

Open golden misses, with what is known:
- Attributes, Davis Jones (10): saved by Chummer 5.183, which stores values differently. Probably out of scope; may need an old-format reader.
- Attributes, Bastion and Blindfire (4): cyberlimb averaging is one too low. Numbers fit a character with 5 limbs instead of 6, but no `AddLimb` improvement or setting explains it.
- Essence, Bastion and Monomax (differ by 0.04 and 0.02), Fuzzy-chargen (1.745 stored, which looks like a "do not round essence" setting), SCSi (off by 1.8). Settings did not change these. Not investigated further.
- Condition monitors and lift/carry limits can only be checked against Viper; the older fixtures do not store them. A fuller golden source would be Chummer's own XML export of a few characters.

### M3: Read-only character sheet (M)
- [x] First slice on the phone: `Sheet` (core) calculates everything shown; `SheetScreen` (app) has Overview (attributes, essence, initiative, limits, condition, armor and soak, dice pools, lift/carry), Skills (rated first, then unrated, then knowledge) and Qualities tabs. Game data ships unmodified in the APK (Gradle copies `Chummer/data/*.xml`, unpacked on first run). Seen working on the S24 Ultra with Viper.
- [x] Custom data on the device: Chummer's own `customdata` folders ship in the APK (2.3 MB); the user's loose `manifest.xml`, `amend_*`, `custom_*` and `override_*` files in the characters folder become one more custom data folder, found by its manifest GUID. Viper opens with all 7 folders applied and no warning. Files are copied only when changed, so the loaded pack is reused between opens.
- [x] Amended-data cache: each data file that custom data changes is saved to disk after amending (`DataPack.load(..., cacheDir)`), named by a fingerprint of the base file and the custom files that affect it, and read back next time; the oldest entries beyond 60 are deleted. On the S24 Ultra, opening Viper with 7 custom data folders: 6.7 s the first time (XPath amending is slow on Android), 0.7 s afterwards, also after a restart. `AmendedCacheTest` checks the cached data equals a fresh amend entry for entry. Possible later: build the cache for Chummer's own folders at build time.
- [ ] Custom data from subfolders of the characters folder is not read (only loose files); fine for now.
- [~] Mobile layout design, first pass (built, **not yet seen on the phone**: the phone locked before it could be installed and checked): the system action bar is off (custom theme), freeing about 170 px; the header is one line with the karma left in bold, problems in red and the settings warning collapsed to one tappable line; the Overview leads with one card of the in-play numbers (initiative, physical and stun condition, armor, soak, essence, plus overflow, encumbrance and elemental armor in small text), then attributes (read-only: four to a row; creation: two to a row with a minus and plus beside each value), limits, and dice pools collapsed by default; the creation karma card is collapsed with "remaining of budget" as its summary; the Skills tab has a search box and tighter rows with smaller steppers. To check on the phone: no overlap or clipping, buttons easy to hit, and the collapsed sections behave.
- [ ] Mobile layout design, later: tap a number to see how it is made up (for example a dice pool or armor), denser gear and ware rows, a quick-reference mode for play, landscape and the tablet case are not planned.
- [x] Sections added from stored values: Gear (armor by equipment set, weapons, vehicles, gear), Ware (cyberware and bioware with essence from `EssenceEngine`, nested), Life (contacts, lifestyle, martial arts). Weapon figures are calculated by `WeaponEngine` (damage, AP, accuracy, RC, concealment, ammo capacity): all 120 checks (15 weapons x 8 fields) match Chummer's export. Not covered: dice pool, wireless bonuses, fire modes changed by accessories, underbarrel inheritance, flechette ammo, availability and cost.
- [ ] Still missing: spells, adept powers, complex forms, programs and other magic/resonance sections (Viper is mundane, so these need a magical fixture), drugs, the expense log, notes, mugshot, matrix attributes of commlinks, vehicle details, weapon totals.
- [x] Settings profiles: the app copies `.xml` settings files found beside the characters into its own folder when a character is opened and uses the one matching the character's GUID. Custom data (amend files) is still not applied on the device.
- [ ] Complete character display matching Chummer's numbers

### M4: Creation, Karma build (XL)
Build order decision: **Karma build first**, then Sum-to-Ten (M5), then Priority. Karma build has no allocation tables, only a karma budget and per-item karma costs from the character's settings, so it gives the creation framework (a ledger of what has been bought, validation, writing a valid file) with the least rule surface. Sum-to-Ten and Priority then add point allocation on the same ledger. The fixtures are mostly Sum-to-Ten (31 of 35), so M5 follows directly.

Approach: creation edits a character document (the same DOM we read) and every change goes through the improvement and engine layers already built, so the sheet recalculates live. The first step is knowing exactly what a new Chummer file contains.
- [x] **Template received** (`reference/New_Empty`, `New_Spent`, `New_Quality`, see `reference/README.md`). Findings: a new file is a fixed skeleton plus data-derived parts (14 attribute rows from the metatype, 59 active skills and 11 groups from `skills.xml`, the settings' book list, version stamps and a settings hash); the stored `karma` is 0 and the creation budget is **computed live** by the creation form (`CalculateBPandRefreshBPDisplays`, about 1000 lines of `CharacterCreate.cs`), so the ledger has to be ported, not read. The editor's `addQuality` already reproduces New_Quality exactly (only difference was the new `notesColor` value, now fixed).
- [x] Create a new character (Karma build): `NewCharacter.createKarma` builds the file from a skeleton (Chummer's own empty character with the data-derived parts emptied, `core/src/main/resources/chummer/new-character-karma.chum5`) and generates the metatype fields, 14 attribute rows with the metatype's limits, 59 active skills and 11 skill groups from `skills.xml`, the book list, custom data names and calculated values. For Human with your settings it equals Chummer's `New_Empty.chum5` apart from random GUIDs (`NewCharacterTemplateTest`); every base metatype generates a file the sheet reads, with minimums following the metatype (Ork BOD 4, STR 3). In the app: menu, New character, name, street name, metatype, and a settings profile with the Karma build method; saved to the characters folder and opened. Tested on the phone with an Ork (created, shown, deleted). Not verified: opening a generated file in desktop Chummer; metatype variants (Nartaki and so on) are not supported; `settingshashcode` is left empty for new characters (Chummer uses it to notice changed settings); A.I., Naga, Pixie and shapeshifters are listed but their special rules are not handled.
- [x] Karma ledger (`KarmaLedger`), ported from Chummer's creation form: budget (800 unless the settings say otherwise), metatype cost times the settings' multiplier, attributes (cost of each step is its new rating times the attribute karma cost, plus the karma-cost improvements), active skills (new-skill price for the first rating, then improve price times each rating, plus specializations), qualities the user chose (bp times the quality cost, negatives give karma back, free metatype qualities not counted) and contacts beyond the free points. Shown on the sheet for Karma characters in creation (header and an Overview card). It lists what it does not count yet (skill group karma, knowledge skills, spells, foci, complex forms, programs, martial arts, initiation, critter powers, adept powers, metamagics, mentor spirits, nuyen bought with karma, group contacts), in which case remaining is an upper bound. **Confirmed against Chummer for one file**: the app-made Ork shows Karma Remaining 583 in Chummer 5.226 (budget 700, Metatype 50, Attributes 50, Active Skills 8, Positive Qualities 9), exactly what the ledger gives. The budget is the `buildpoints` setting (700 in your profile; the ledger first read a non-existent `buildkarma` and used 800). The reference files give 700, 688, 680, 610 and 495 (computed, not read from Chummer).
- [x] Buying attributes and skills with karma: `CharacterEditor.changeAttribute` and `changeSkill` (+1 or -1), stopping at the attribute's metatype maximum and the settings' creation skill maximum (5 in your profile), refusing to give back points that were not bought; the attribute row's stored total and the stored condition monitor values are rewritten as Chummer does. Golden tests: buying BOD and Pistols on `New_Empty` gives exactly `New_Spent`, and REA +3 and WIL +4 on `empty_troll` gives exactly `troll_spent`; giving the points back restores the empty file. In the app, creation characters show a minus and plus under each attribute and on each active skill, saving after every tap and updating karma left live. Verified on the phone with an Ork: INT 1 to 3 cost 25 and Archery 1 cost 2 (750 to 723).
- [x] Creation checks: the ledger reports problems (overspent; positive or negative quality karma over the settings' `qualitykarmalimit`, 50 in your profile), the sheet shows them in red and shows the 9/50 style counters on the Karma card and the Qualities tab, and adding a quality that would pass the limit is refused with the reason (`CharacterEditor.qualityLimitProblem`). Matches Chummer's "Positive Qualities 9/50" for the app-made Ork. Other quality prerequisites are not checked.
- [ ] Knowledge skills, languages and skill groups: costs read from Chummer's source (knowledge: triangle of rating steps times the improve price, first level at the new-skill price; groups: triangle of group steps, new-group price for the first step) but the free knowledge points (`knowledgepointsexpression`, shown as "4 of 4" in Chummer), the native language, specialization points and how group karma raises member skills need a Chummer sample to check. Waiting for `reference/New_Skills.chum5` (see the instructions given to the user).
- [ ] Still to buy in creation: skill groups, knowledge skills and languages, specializations, special attributes (MAG, RES, EDG limits), contacts, spells, adept powers, complex forms, gear and nuyen. The attribute stepper allows going over the karma budget (the ledger then shows a negative); Chummer's finishing step is the place to refuse that.
- [ ] Skills and skill groups, specializations, knowledge and language skills, with karma costs.
- [ ] Qualities with prerequisites (the prerequisite language, about 83 node types) and karma limits.
- [~] The improvement handler registry (`BonusHandlers`): turns an item's `<bonus>` node into improvements. 144 simple nodes (one improvement of a fixed type, with or without a value) are generated from Chummer's source by `tools/gen_bonus_table.py` (re-run on Chummer updates); about 40 more are written by hand (armor family with precedence/group, specific attribute, condition monitor, tabs, skills, skill groups, limit modifiers, movement, lifestyle cost, select-text and select-restricted, karma cost modifiers). Covers 135 of the 246 node types in Chummer's data. `BonusHandlerFixtureTest` replays every item in the 35 fixtures through the handlers (bonus node read from the game data, as creation will) and compares with the improvements Chummer stored: 468 of 680 items match exactly. The rest: 151 unhandled nodes (mostly choice-driven: select skill, weapon, tradition, mentor spirit, unlock skills, add gear/qualities/limbs/contacts) and mismatches that are old-version drift (the fixtures were mostly saved by Chummer 5.202, which created different improvements or used older text and data; for example `DisableQuality` only exists from 5.226). Not verified against a newer file except Viper.
- [x] Character editing, first slice: `CharacterEditor` adds a quality from the game data (writing the `<quality>` element in Chummer's layout and its improvements through `BonusHandlers`) and removes one with its improvements. `CharacterEditorTest` re-adds each of Viper's 10 qualities and compares with what Chummer wrote (2 known differences: one quality renamed in newer data, one saved by an older version without its text prompt); adding Toughness changes soak by exactly 1, survives a write and read, and removing it restores the numbers. In the app (Qualities tab): search all 761 qualities, add (with a text prompt for the 94 that ask for one), remove with confirmation; the file is saved and the sheet recalculated; the first save of each file in a session keeps a timestamped backup in the app's private folder. Verified on the S24 Ultra on a copy of Viper (soak 29 to 30).
- [ ] Not in the editing slice yet: prerequisites (`required`/`forbidden`) and karma limits are not checked; no karma or nuyen bookkeeping (adding a quality does not spend karma); qualities with levels; the 165 qualities whose bonuses use a node type without a handler are added but those bonuses are missing (flagged in the picker). Saved files differ from Chummer's byte for byte (plain newlines and `<x/>` instead of Windows newlines and `<x />`), which is the same XML; checking that desktop Chummer opens an edited file is still to do.
- [ ] Choice-driven and item-adding nodes (`select*`, `unlockskills`, `add*`): need the creation UI to supply the choice; `BonusContext.choice` is the hook.
- [ ] Gear, weapons, armor, cyberware and bioware with availability, cost and essence rules; nuyen budget and restricted gear.
- [ ] Spells, adept powers, complex forms, programs, and magic/resonance basics.
- [ ] Validation of creation limits, and the "finish creation" step that moves a character to career mode.

### M5: Creation, Priority build (L)
- [ ] Priority table and free allotments
- [ ] Magic and resonance paths

### M6: Career advancement (L)
- [ ] Karma and nuyen expense log, with undo
- [ ] Improvement costs
- [ ] Created-versus-career mode
- [ ] Viper's expense log as the test case

### M7: Long tail and polish (XL, ongoing)
- [ ] Remaining improvement types, by priority
- [ ] Vehicles and drones, matrix, contacts, lifestyles
- [ ] House-rule `customdata`
- [ ] Data-pack updates without an app release
- [ ] Sheet export as HTML or PDF
- [ ] SumtoTen and LifeModule build methods (optional)

## Risks

1. **Engine parity:** about 20 years of accumulated rules behavior. M2 is the measure: attributes 440/454, essence 31/35, skills 78/78 and derived stats 32/32 against Chummer's numbers for Viper. The golden tests keep progress measurable, but creation and bonus handling (M4) are still ahead.
2. **Checking against Chummer needs its own output.** Saved files store few computed values (no skill totals, limits or armor). Only Viper has a full Chummer print XML. Every other character is checked against stored values or hand-derived numbers until exported. Mitigation: export more characters (Bastion, Blindfire, SCSi, Monomax, Fuzzy-chargen, one magic character).
3. **Creation validation (M4 and M5):** the most complex rules. Mirror Chummer's source behavior and check each rule against fixtures.
4. **Phone usability:** Chummer's UI is dense and desktop-oriented. We need a distinct mobile design, not a port.
5. **Data drift:** Chummer is under active development (last upstream commit seen: 2026-11-20). Mitigation: the sync script and a pinned upstream commit.
6. **Data load cost on the phone:** the base data loads in about 0.4 s and 80 MB heap on the S24 Ultra; with 7 custom data folders applied the desktop time is about ten times higher. Needs a cache of the amended data (not built yet).
7. **Old-format files:** Chummer 5.183 files (Davis Jones) store values differently and miss all checks. May need a separate reader.
8. **Campaign data quirks must be reproduced:** `amend_weapon.xml` is never applied, and `removenode` acts as replace. Both come from reading Chummer's source, not from seeing Chummer do it.

## Open questions

- Build-method order: Viper is Karma, but most fixtures are SumtoTen. Decide the order for M4 and M5 after M2.
- Mobile design: sketch the main screens first, or propose a layout once M2 gives real data.
- Languages: English only at first? Chummer ships de, fr and ja language files.
- Which source books are enabled by default? (Viper uses 18 books, including non-core ones.)
- Do you want to export more characters from Chummer (list in the risks above)? Each one closes golden gaps.
- Phone check of M1 features not yet tried: share out, delete, list with several characters.

## Progress log

- 2026-11-21: Reviewed Chummer source and data, Viper and hg-glow. Agreed scope and wrote this plan. No code yet.
- 2026-11-21: Started M0 in `hgCOMMLINK` (confirmed as the project root). Created the Gradle project with a `core` JVM module: DOM XML helpers, `DataPack`, `CharacterFile`, `Expr`, and `SpikeTest` and `FixtureSurveyTest`. Found that prerequisites are not XPath. Remaining for M0: the `app` module and on-device measurement.
- 2026-11-21: Finished M0. Added the `:app` module (benchmark screen, data bundled into the APK for the spike only) and ran it on the S24 Ultra over wireless debugging. Load times and memory are in the M0 findings.
- 2026-11-21: M1 built. Added `GameData`, `CharacterIo` (lossless read and write, DTD refusal), `CharacterFile.summary()`, and the Compose app: list, detail, import, "open with", share, delete, folder choice. First device test failed on import because Android's parser rejected the DTD-hardening feature; fixed by checking the prolog bytes instead. Viper now imports and displays on the S24 Ultra.
- 2026-11-21: M2 started. Added the improvement model and Chummer's summing rules, the attribute engine (including cyberlimbs) and the essence engine, each with a ratcheting golden test. Attribute goldens went from 403 to 440 of 454, essence is 31 of 35. Noted that Chummer's C# duplicates every method as sync and async plus locking, so ports read the logic only.
- 2026-11-21: Received Viper's settings file and its San Francisco Showdown custom data. Filed them under `reference/Viper_career/` with a README mapping every GUID. Confirmed the settings GUID matches, and that 6 of the 7 custom data folders are Chummer's own. Learned that amend files use XPath and that settings contain expressions; planned a settings loader and an amend layer in M2.
- 2026-11-21: Built the settings loader and the data amend layer, with tests against Viper's campaign files. Found that `amend_weapon.xml` is never applied and that `removenode` is an unknown operation that behaves as replace. Amended loading is about 10x slower than plain loading, so the phone will need a cache.
- 2026-11-21: Skills engine added. Fixed the data pack indexing the all-zero GUID (a placeholder row in the lifestyle data) as a real entry. Skills have no stored totals in saved files, so they are the first calculation with nothing from Chummer to compare against.
- 2026-11-21: Received Chummer's print XML for Viper. All 78 skills match; added `DerivedStats` and it matches on all 32 derived values. Discovered the export reflects a different state than the saved file (Kamikaze on, no armor encumbrance, CHA +3); the Improvements tab shows the CHA change is a custom improvement named "Spell" (+3), and only Kamikaze is on.
- 2026-11-21: Armor engine added and checked both ways: the Social set against the export (6 values) and the Runner set's encumbrance against the saved file. Learned that armor-mod improvements are never switched off when armor is unequipped (Chummer keys them by the armor id, not the mod id), which the engine reproduces by only reading each improvement's stored enabled flag.
- 2026-11-21: Resistance pools added to `DerivedStats` and checked against the export: 63 of 63 derived values now match. Matrix initiative is deferred until gear (commlinks) is read.
- 2026-11-21: M3 started. Added `Sheet` and the three-tab sheet screen; bundled Chummer's data into the APK. First device run crashed on an empty lift/carry expression (built-in settings profiles do not define one; Chummer's code defaults are now used) and a JVM test now covers the built-in-settings path.
- 2026-11-21: Sheet gained Gear, Ware and Life tabs, and the app now reads settings profiles from the user's characters folder. The folder also held the amend files; they are copied but not used yet.
- 2026-11-21: Custom data applied on the device. Measured the cost: 6.7 s cold, 0.6 s warm for Viper with 7 folders. Found that re-copying the files on each open invalidated the loaded pack; now unchanged files are skipped.
- 2026-11-21: Added the on-disk cache of amended data: cold open of Viper 6.7 s to 0.7 s after the first.
- 2026-11-21: Weapon totals added and checked against the export (120/120). Found the export used a different loaded ammo slot for the Ingram than the saved file, another unsaved-state difference; the test replays it.
- 2026-11-21: Surveyed the fixtures (31 Sum-to-Ten, 3 Priority, 1 Karma) and wrote the M4 breakdown. Decided Karma build first for the simplest framework; needs a fresh Chummer-made template file to write valid new characters.
- 2026-11-21: Improvement handlers started: generated table plus hand-written handlers, checked against 680 stored items (468 exact). Learned that `selecttext` creates a Text improvement, limit modifiers use the item name as unique name, and that old fixtures differ from current data and lang text.
- 2026-11-21: Added `CharacterEditor`, the quality catalog, and add/remove quality on the Qualities tab with save and backup. First end-to-end edit working on the phone.
- 2026-11-21: Received the fresh Karma-build files and the settings file. The editor matches Chummer on the quality file; the empty and spent files differ in exactly the four expected values.
- 2026-11-21: New-character creation for the Karma build, matching Chummer's empty character exactly for Human; menu entry and dialog in the app.
- 2026-11-21: Troll reference files added; generation now matches Chummer's empty Troll (metatype bonus, free quality, own unarmed-weapon ids, reach handler). Added the karma ledger and a Karma card on the sheet; its figures for the five reference files are computed, not yet confirmed in Chummer.
- 2026-11-21: Buying attributes and skills with karma works end to end, matching both golden pairs exactly; steppers in the app for creation characters.
- 2026-11-21: Wrote two sample files made by the app (`reference/app_made/`: a new Ork with purchases and a quality, and Viper with Toughness added) for the user to open in desktop Chummer. Test `writeSamplesToTryInDesktopChummer` regenerates them into `core/build/samples/`. Waiting for the result: it is the last unverified link in writing files.
- 2026-11-21: **Desktop Chummer 5.226 opens files written by the app.** A new Ork with purchases and a quality opened in creation mode with the right attributes, skills, qualities and karma (583 remaining, matching the ledger once the budget was read from `buildpoints`); Viper with Toughness added opened in career mode with Toughness listed and damage resistance pool 30 (was 29). No errors, and no settings-changed prompt. Chummer also showed the Ork's free Low-Light Vision, the 9/50 quality limit and 4 free knowledge skill points ((INT+LOG) x 2 from the settings).
- 2026-11-21: Quality limit and overspend checks added (verified against Chummer's 9/50 for the app-made Ork). Knowledge skills and skill groups wait for a Chummer sample file.
- 2026-11-21: Design pass written (theme without the action bar, compact header, vitals card, two-column attribute tiles with steppers in creation, collapsible karma and pools, skill search). Not yet checked on the phone, which locked.
