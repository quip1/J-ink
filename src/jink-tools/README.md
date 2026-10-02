# J-ink Tools

Twenty-three small Android apps for e-ink devices, in one Gradle project. They share an e-ink UI library
(`common/`), and each app builds its own APK. Like Font Drop, Slate and Folio, they're plain Java on
the Android framework: no AndroidX, no other dependencies. The one exception is Scribe, which builds
whisper.cpp and llama.cpp into the app so its AI runs on the device (see [On-device AI](#on-device-ai-scribe)).

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
| Files | **Filer** | `filer` | File manager: browse, rename, copy, move (pick files, go to the destination, tap Paste), delete, share, open with other apps. Zip files: create, browse inside, extract, add, rename and remove entries. Built-in text editor; tap an APK to install it. |
| Everyday | **Lists** | `lists` | To-do lists and checklists. Ticked items sink to the bottom; untick all to reuse a packing or grocery list. Share text to it to make a list. |
| Everyday | **Habits** | `habits` | Tick daily habits; streaks, the last 7 days at a glance, and a monthly calendar per habit. |
| Everyday | **Calc** | `calc` | Big-button calculator (brackets, powers, %, √, history) and a unit converter (length, weight, temperature, volume, speed, area, data, time). |
| Everyday | **Timer** | `timer` | Stopwatch with laps, and any number of named countdowns that beep even when the app is closed. |
| Reading & listening | **Speak** | `speak` | Reads text aloud with your device's voice: paste, open a file, or share text to it. Highlights the current sentence; tap any sentence to jump there. |
| Reading & listening | **Feeds** | `feeds` | RSS/Atom reader. Saves each article as clean text when it updates, so you can read offline a page at a time (volume keys). Add a feed or any website. |
| Reading & listening | **Weather** | `weather` | Current weather, next hours and the week, from Open-Meteo (free, no account). Several places, °C or °F. |
| Study | **Flashcards** | `flashcards` | Spaced-repetition decks (like Anki): cards you know come back less often. Import cards from CSV/TSV. |
| AI (on-device) | **Scribe** | `scribe` | Records lectures, meetings or notes, then transcribes them with Whisper, summarises them and answers questions about them with a small language model, all on the device. Reads transcripts and summaries aloud. Also transcribes audio shared from other apps. |

## On-device AI (Scribe)

Scribe runs two open-source AI engines inside the app, so recordings never leave the device:

- **[whisper.cpp](https://github.com/ggml-org/whisper.cpp)** turns speech into text (OpenAI's
  Whisper models). Module `aiwhisper`.
- **[llama.cpp](https://github.com/ggml-org/llama.cpp)** runs a small chat model (Qwen2.5 Instruct)
  for summaries and questions. Module `aillama`.

Both are C++ libraries. Each module has a small JNI bridge (`src/main/cpp/*_jni.cpp`) and a Java
class (`Whisper`, `Llama`) that the app calls. The C++ source isn't in this repo: on the first build,
`native-sources.gradle` downloads pinned releases, checks their SHA-256 and unpacks them into
`third_party/` (git-ignored). The two libraries ship different versions of their shared math library
(ggml), so each is linked into its own `.so` with its symbols hidden, and they can't clash.

**Models** aren't in the APK either. On first use the Models screen downloads one speech model and
one language model from Hugging Face into the app's folder (`Android/data/dev.jacob.scribe/files/models`).
Downloads resume if interrupted. You can also import your own `.bin` (Whisper) or `.gguf` (llama.cpp)
file.

| Model | Size | Notes |
|---|---|---|
| Whisper base | 60 MB | Fast; fine for clear speech |
| Whisper small | 190 MB | Good default for lectures and meetings |
| Whisper medium | 540 MB | Most accurate, slowest |
| Qwen2.5 0.5B | 400 MB | Quick, simple summaries |
| Qwen2.5 1.5B | 1.1 GB | Good default |
| Qwen2.5 3B | 2.1 GB | Best answers; needs about 3 GB free RAM |

**Speed.** The Boox Note Air 6C has a Qualcomm Dragonwing Q6690 (6 GB RAM). Scribe
runs the models on the CPU with ARM's dot-product instructions, using 4 threads. Long recordings are
transcribed in 10-minute windows, and long transcripts are summarised part by part and then merged,
so they fit in memory and in the model's context. The chip's Hexagon NPU isn't used yet; ggml has an
experimental Hexagon backend that may make that possible later.

**Building** Scribe needs the Android NDK and CMake 3.22 (install both from Android Studio's SDK
Manager). It's built for 64-bit ARM (`arm64-v8a`) only, which every current Boox device uses. Because
Scribe's Java depends on the two libraries, check it with `./check.sh aiwhisper aillama scribe`.

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
