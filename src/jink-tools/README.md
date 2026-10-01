# J-ink Tools

Thirteen small Android apps for e-ink devices, in one Gradle project. They share an e-ink UI library
(`common/`), and each app builds its own APK. Like Font Drop, Slate and Folio, they're plain Java on
the Android framework: no AndroidX, no other dependencies.

Designed for the **Boox Note Air** (tablet layout) and the **Boox Palma** (phone layout). They should
work on any Android 8.0+ device.

| Group | App | Module | What it does |
|---|---|---|---|
| Print | **Inkprint** | `inkprint` | Makes printable/writable PDF templates: lined, dot grid, graph, Cornell notes, daily/weekly/monthly planners, D&D character sheet and session log. Save as PDF (paper sizes plus Note Air and Palma screen-shaped pages) or send to a printer. Also prints any PDF, image or text file, including files shared to it from other apps. |
| D&D | **Dice** | `dice` | Dice expressions (`2d6+3`, `4d6dl1`, `adv+5`, `2d6r2`, `3d6!`, `6x 4d6dl1`), quick-roll buttons with a modifier, saved rolls, history. Flags natural 20s and 1s. |
| D&D | **Initiative** | `initiative` | Combat tracker: turn order, rounds, HP and temp HP, AC, all 5e conditions. Add groups like "3 goblins" with rolled initiative. Survives the app being closed. |
| D&D | **Character Sheet** | `charsheet` | Several characters. Tabs for stats, skills and saves (with proficiency/expertise), combat, spells (slots, DC, attack), gear and notes. Long rest button. All bonuses calculated for you. |
| D&D | **DM Tools** | `dmgen` | Random NPCs, taverns, loot, plot hooks, towns, weather and names (human, elf, dwarf, halfling, orc styles). Keep the ones you like. All tables are original. |
| Reading & focus | **Focus** | `focus` | Pomodoro timer that beeps even when the app is closed, and a big desk clock with a month calendar. Updates once a minute, not every second. |
| Reading & focus | **Read Log** | `readlog` | Books you're reading, want to read and have finished. Time reading sessions, see your pace, time left, streak, and last 7 days. |
| Writing | **Typewriter** | `typewriter` | Distraction-free drafts with autosave, word count, session goal, hide-cursor option and a no-delete "first draft" mode. Import, export and share. |
| Writing | **Journal** | `journal` | One page per day with a daily prompt and optional mood. Calendar of the days you wrote, search, export everything to Markdown. |
| Games | **Sudoku** | `sudoku` | Generated puzzles with exactly one solution, four difficulties, pencil notes, hints, undo, mistake check. |
| Games | **Solitaire** | `solitaire` | Klondike, draw 1 or 3. Tap a card, then where it goes (no dragging); tap twice to send it home. Undo, auto-finish. |
| Games | **Chess** | `chess` | Play the computer (four levels) as either colour, or two players. Full rules, including castling, en passant, promotion, and the draw rules. Move list, undo, flip. |
| Games | **Word Search** | `wordsearch` | Eight themes, three sizes. Tap the first letter of a word, then its last. |

## E-ink rules every app follows

These live in `common/` (`Ui`, `InkActivity`, `Pager`), so new apps get them for free:

- **Black and white, no animations.** No ripples, fades, overscroll glow or window transitions;
  they all turn into ghosting on e-ink.
- **Pages, not scrolling.** Long lists show one page at a time with Prev/Next. **Volume keys turn
  pages** in every list.
- **Redraw only when something changes.** Timers and word counts update when their text
  actually changes (usually once a minute), not every frame or keystroke.
- **A ↻ button** in each header flashes the screen black and white to clear ghosting.
- **Big targets and readable widths.** Buttons are at least 52dp. On the Note Air the content is
  centred and limited to a comfortable width, and text is a size up.

## Building

You need Android Studio (or JDK 17, Gradle 8.7+ and the Android SDK 34).

1. Open the `src/jink-tools` folder in Android Studio.
2. Pick an app in the run configuration dropdown (e.g. `dice`) and press Run, or build every APK:
   ```sh
   gradle assembleRelease
   ```
   APKs land in `<module>/build/outputs/apk/release/`.

Release builds are signed with your machine's debug key, like Slate and Folio. To sign with a real
key, add a `signingConfigs` block to `app.gradle`, and keep the keystore and its password out of this
public repo.

## Checking without the Android SDK

`./check.sh` compiles every module against the real Android 14 framework classes and runs all the
unit tests, with no Android SDK needed. It downloads the framework jar and JUnit from Maven Central
on first run.

```sh
./check.sh              # everything
./check.sh chess dice   # just these modules
```

The game rules, dice parser, character math, timers and generators all live in plain Java classes
with JVM unit tests. For example, the chess move generator is verified against the standard
"perft" counts. `check.sh` doesn't compile resources or build APKs, so use Gradle for that.

## Adding an app

```sh
./new-module.sh myapp     # creates myapp/build.gradle and folders
```

Then add `':myapp'` to `settings.gradle`, write `src/main/AndroidManifest.xml` (copy one from
another app), a launcher icon at `src/main/res/drawable/ic_launcher.xml`, and a `MainActivity`
that extends `dev.jacob.jink.InkActivity`.
