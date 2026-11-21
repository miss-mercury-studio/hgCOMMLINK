# Chummer 5e for Android: Plan

Living document. Update the status checkboxes, the decisions log and the open questions as work proceeds.
Last updated: 2026-11-21 (M0 complete)

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
| Example career character | `C:\Users\kross\Documents\CODE\reference\Viper_career.chum5` |
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
- [x] Repo set up (git, Gradle, `core` module, toolchain from hg-glow). The `app` module is not created yet.
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
- [ ] Character model and data loader with indexes
- [ ] Lossless `.chum5` import and export, with unknown XML preserved
- [ ] Round-trip test over all 35 fixtures
- [ ] Android file handling: open, save, share, "open with", visible folder
- [ ] Character list screen

### M2: Calculation engine (L)
- [ ] Attributes, skills, limits, initiative, condition monitors, essence, armor, derived stats
- [ ] Improvement handler registry with about 30 handlers, and logging of unhandled nodes
- [ ] Golden tests against the 35 fixtures, with a divergence report
- [ ] Character settings (build method, house-rule flags, `customdata`)

### M3: Read-only character sheet (M)
- [ ] Mobile layout design (before building)
- [ ] Complete character display matching Chummer's numbers

### M4: Creation, Karma build (XL)
- [ ] Metatype, attributes, skills, qualities
- [ ] Gear, weapons, armor, cyberware and bioware, with availability and cost rules
- [ ] Spells, adept powers, complex forms and other magic or resonance basics
- [ ] Validation of creation limits

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

1. **Engine parity:** about 20 years of accumulated rules behavior. M2 shows the real size. The golden tests keep it measurable.
2. **Creation validation (M4 and M5):** the most complex rules. Mirror Chummer's source behavior and check each rule against fixtures.
3. **Phone usability:** Chummer's UI is dense and desktop-oriented. We need a distinct mobile design, not a port.
4. **Data drift:** Chummer is under active development (last upstream commit seen: 2026-11-20). Mitigation: the sync script and a pinned upstream commit.
5. **Data load cost:** 7.7 MB of XML on a phone. M0 measures it.

## Open questions

- Build-method order: Viper is Karma, but most fixtures are SumtoTen. Decide the order for M4 and M5 after M2.
- Mobile design: sketch the main screens first, or propose a layout once M2 gives real data.
- Languages: English only at first? Chummer ships de, fr and ja language files.
- Which source books are enabled by default? (Viper uses 18 books, including non-core ones.)

## Progress log

- 2026-11-21: Reviewed Chummer source and data, Viper and hg-glow. Agreed scope and wrote this plan. No code yet.
- 2026-11-21: Started M0 in `hgCOMMLINK` (confirmed as the project root). Created the Gradle project with a `core` JVM module: DOM XML helpers, `DataPack`, `CharacterFile`, `Expr`, and `SpikeTest` and `FixtureSurveyTest`. Found that prerequisites are not XPath. Remaining for M0: the `app` module and on-device measurement.
- 2026-11-21: Finished M0. Added the `:app` module (benchmark screen, data bundled into the APK for the spike only) and ran it on the S24 Ultra over wireless debugging. Load times and memory are in the M0 findings.
