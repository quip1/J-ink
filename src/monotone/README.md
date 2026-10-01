# Monotone

E-ink-friendly m4b audiobook player for Android and Windows (one Flutter codebase).

## Install
- **Android:** sideload `Monotone.apk`. On first launch, grant "All files access", then pick your library folder.
- **Windows:** push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`) builds
  the Windows app and an APK. Download them from the run's Artifacts. Run `monotone.exe` from the Windows zip
  and keep the DLLs next to it.

## Library layout
    <library folder>/
      Fantasy/            <- shelf "Fantasy"
        Sanderson/book.m4b
      SciFi/book2.m4b     <- shelf "SciFi"
      loose.m4b           <- shelf "Unsorted"

Supported: m4b / m4a (full tags, cover, chapters), plus mp3, ogg, opus, flac and wav (the filename becomes the title).
Covers come from embedded art, or from cover.jpg / folder.jpg / <bookname>.jpg in the same folder.

## Signing (Android)
`android/key.properties` + `android/app/monotone-release.jks` are the release key. **Back them up.**
Every future APK must be signed with this key to install as an update without losing your library state.
Both are git-ignored, so GitHub Actions builds fall back to a debug key (fine for testing, but they won't
update over a release-signed install).

## Build locally
Windows: double-click `BUILD-WINDOWS.bat` (installs Git, VS C++ Build Tools and Flutter on first run).

    flutter pub get
    flutter build apk --release        # Android
    flutter build windows --release    # Windows (needs Visual Studio with the C++ desktop workload)

Windows keys: Space play/pause · ←/→ skip · [ ] chapter · − = speed · B bookmark · P player
