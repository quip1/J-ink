# Ferry — Bluetooth file transfer for Boox, Android and Windows

One tap (or one drag) sends files between your paired devices over Bluetooth.
No Wi-Fi, no cloud, no accounts.

## Setup (once)
1. Pair each device with the others in the normal Bluetooth settings.
2. Install **Ferry.apk** on the Air 6c, Palma 2 Pro and phone; run **Ferry.exe** on the PC.
3. On each Android device open Ferry and tap through the setup buttons it shows:
   Bluetooth, All-files access (so books land in /Books), notifications,
   and "Keep Ferry running" (Boox freezes background apps otherwise).
   On Boox it's also worth locking Ferry in the recent-apps view.
4. The first transfer from a new device asks **Decline / Once / Always** on the receiver.
   Pick Always and that device never asks again.

## Sending
- **Android:** open Ferry → tap a device → Files… or A whole folder…; or use
  **Share → Ferry** from any app, then tap a device.
- **Windows:** drag files onto a device card, click Send files… / Folder…, or
  right-click in Explorer → **Send to → Ferry (Bluetooth)** (turn that on at the bottom of the window).
  Closing the window keeps Ferry in the tray, still receiving.

## Where received files go
| Type | Boox / Android | Windows |
|---|---|---|
| Books (epub, pdf, mobi, azw3, fb2, djvu, cbz…) | /Books | Documents\Books |
| Audiobooks (m4b) | /Audiobooks | Music\Audiobooks |
| Fonts (ttf, otf) | /Fonts | Downloads\Ferry\Fonts |
| Music | /Music | Music |
| Pictures | /Pictures/Ferry | Pictures\Ferry |
| Video | /Movies/Ferry | Videos\Ferry |
| .note | /Documents/Notes | Documents\Ferry\Notes |
| Documents (txt, md, docx, xlsx…) | /Documents | Documents\Ferry |
| Anything else | /Download/Ferry | Downloads\Ferry |

Sent folders keep their structure inside the category (e.g. `Books/Series/…`).
Name clashes become `name (1).ext`. Every file is CRC-checked before it's kept.

## Speed (v2)
Bluetooth only finds the device and does the handshake. Anything over 256 KB then moves over the fastest
route available, picked automatically:
1. **Same network** (home Wi-Fi / PC on Ethernet): direct TCP link. Typically 5–30 MB/s on a Boox.
2. **No shared network** (campus Wi-Fi blocks device-to-device, or no Wi-Fi at all): the receiving Boox opens a
   private Wi-Fi Direct link. Another Android device joins it directly; a Windows PC briefly joins it
   like a normal Wi-Fi network and then goes back to the network it was on.
3. **Bluetooth** as the last resort (~150–250 KB/s).
Wi-Fi traffic is AES-256 encrypted with a key handed over the Bluetooth link.
Ferry switches Bluetooth and Wi-Fi on by itself when a transfer needs them, and turns Wi-Fi back off
afterwards if it was the one that turned it on. (The APK targets Android 9 (API 28) on purpose,
because newer targets aren't allowed to switch radios themselves; Android may warn about that on install.)

## Building
- APK: `cd android && ./gradlew assembleRelease` (needs Android SDK 34; keystore `ferry.jks`, password `ferryferry`).
  Keep that keystore — updates must be signed with it to install over the old version.
- EXE: `cd windows/FerryWin && dotnet publish -c Release` (.NET 8 SDK; builds on Windows or Linux).
- Protocol: see PROTOCOL.md. `test/` holds a Kotlin↔C# harness that runs the real protocol code over TCP.
