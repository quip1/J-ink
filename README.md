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

Slate and Folio are built for **arm64** devices and include the Onyx pen SDK, so pen features work best on Boox devices.

### Windows (run on a PC)

| App | File | Size | Description |
|---|---|---|---|
| **Ferry** | [`apps/windows/Ferry.exe`](apps/windows/Ferry.exe) | 25 MB | Sends files between Android and Windows over Bluetooth and peer-to-peer (no cable or internet needed). |

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
8646273c3b93eec2a5f250aba376a0cd9217bf4a43063315c42e45ffe76747b0  apps/windows/Ferry.exe
```

## Repository layout

```
apps/
  android/   built APKs
  windows/   built EXEs
src/         source code for each app (coming soon)
```
