# J-ink

Jacob's e-ink apps: a collection of helpful apps for e-ink devices.

## Apps

### Android (install on the e-ink device)

| App | File | Package | Size | Description |
|---|---|---|---|---|
| **Font Drop** | [`apps/android/FontDrop.apk`](apps/android/FontDrop.apk) | `dev.jacob.fontdrop` | 25 KB | Lightweight font utility. |
| **Slate** | [`apps/android/Slate.apk`](apps/android/Slate.apk) | `dev.slate.notes` | 3.7 MB | Handwritten notes with low-latency Onyx/Boox pen support. |
| **Folio** | [`apps/android/Folio.apk`](apps/android/Folio.apk) | `dev.slate.reader` | 9.9 MB | Document/PDF reader with Onyx/Boox pen support. |
| **Monotone** | [`apps/android/Monotone.apk`](apps/android/Monotone.apk) | `com.jacob.monotone` | 19 MB | Audiobook player. Version 1.0.3; this build is labeled for the NoteAir6C. |
| **Ferry** | [`apps/android/Ferry.apk`](apps/android/Ferry.apk) | `dev.onyxbox.ferry` | 136 KB | Android side of Ferry: sends files to and from the Windows app over Bluetooth and peer-to-peer. |
| **Lanternwild** | [`apps/android/Lanternwild.apk`](apps/android/Lanternwild.apk) | `dev.onyxbox.lanternwild` | 26 MB | Lantern-themed adventure game with e-ink-friendly visuals. |

Slate and Folio are built for **arm64** devices and include the Onyx pen SDK, so pen features work best on Boox devices.

### Windows (run on a PC)

| App | File | Size | Description |
|---|---|---|---|
| **Ferry** | [`apps/windows/Ferry.exe`](apps/windows/Ferry.exe) | 25 MB | Sends files between Android and Windows over Bluetooth and peer-to-peer (no cable or internet needed). Pair it with `Ferry.apk` on the device. |
| **Lanternwild** | [`apps/windows/Lanternwild.exe`](apps/windows/Lanternwild.exe) | 29 MB | Windows version of Lanternwild (needs the Microsoft Edge WebView2 runtime, which is preinstalled on Windows 10/11). |

### J-ink Tools

Fourteen more apps that share one e-ink UI library. Their APKs are in
[`apps/android/jink-tools/`](apps/android/jink-tools) (checksums are in that folder's README), and
their source is in [`src/jink-tools`](src/jink-tools).

| Group | Apps |
|---|---|
| Print | **Inkprint**: printable PDF templates (planners, note paper, D&D sheets), and prints any PDF/image/text file |
| D&D | **Dice**, **Initiative** (combat tracker), **Character Sheet**, **DM Tools** (NPC, tavern, loot and plot generators) |
| Reading & focus | **Focus** (Pomodoro timer and desk clock), **Read Log** (reading tracker) |
| Writing | **Typewriter** (distraction-free drafts), **Journal** (daily entries with prompts) |
| Games | **Sudoku**, **Solitaire**, **Chess** (vs computer or two players), **Word Search** |
| Files | **Filer**: file manager with copy/move/rename and a zip browser and editor |

## Installing

**Android APKs.** Copy the `.apk` to your device (or download it there), open it, and allow
"Install unknown apps" when prompted. Or install over USB with
[adb](https://developer.android.com/tools/adb):

```sh
adb install apps/android/Slate.apk
```

**Windows EXEs.** Download and run the file. These apps aren't code-signed, so Windows SmartScreen may warn
you. Click **More info → Run anyway**.

## Checksums (SHA-256)

Compare these against your download to make sure the file wasn't corrupted:

```
87923896f445fe5d1b379575916e0bfcbdbe8dd6d1de42381062f61fd4a353d7  apps/android/FontDrop.apk
e34b2b122f25ae7296da2d86a7b90f5095c9cf2ed319aa7aef5fb73c89fd1e37  apps/android/Slate.apk
07200961c265b167fa308e59b617e7b6477a4e66a61ffc8ac8c7c9ce39c87fd6  apps/android/Folio.apk
d2053a8ef09584f60fa956f97cc05d8b191fbdd92a6a00fec7c3ba13d72eaa14  apps/android/Monotone.apk
438288aead47e508bf43b4397399a2f0acd313b9517e29d053fc8250b988f15d  apps/android/Ferry.apk
11d0464bde887a15bf060455ca792d4fa7663ff147a7e0d64afe7791c8c20e25  apps/android/Lanternwild.apk
8646273c3b93eec2a5f250aba376a0cd9217bf4a43063315c42e45ffe76747b0  apps/windows/Ferry.exe
7392f667aee7d1388c365b18479f0cf5ab284169f0ae3a483f92d3951be22266  apps/windows/Lanternwild.exe
```

## Source code

| App | Source | Built with |
|---|---|---|
| Font Drop | [`src/fontdrop`](src/fontdrop) | Android (Java) |
| Slate + Folio | [`src/slate-folio`](src/slate-folio) | Android/Gradle. One project with two flavors (`slate`, `folio`) |
| Ferry | [`src/ferry`](src/ferry) | Android (Kotlin) + Windows (.NET 8). See its [README](src/ferry/README.md) and [PROTOCOL](src/ferry/PROTOCOL.md) |
| Monotone | [`src/monotone`](src/monotone) | Flutter (Android + Windows). See its [README](src/monotone/README.md) |
| J-ink Tools (14 apps) | [`src/jink-tools`](src/jink-tools) | Android (Java), one Gradle project with a module per app. See its [README](src/jink-tools/README.md) |

**Signing keys are not in this repo.** Release builds need your own keystore: Monotone reads it from
`android/key.properties`, and Ferry expects `android/ferry.jks`. Keep your original keystores backed up
somewhere private. Android only installs an update if it's signed with the same key as the installed app.

## Repository layout

```
apps/
  android/   built APKs
    jink-tools/  APKs for the 14 J-ink Tools apps
  windows/   built EXEs
src/         source code for each app
  jink-tools/  the 14 J-ink Tools apps (one Gradle project)
```
