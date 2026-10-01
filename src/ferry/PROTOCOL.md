# Ferry wire protocol v2

Transport: Bluetooth Classic RFCOMM, secure (bonded) link.
Service UUID: `6f0a7c2e-4b1d-4e8a-9c35-f3a1d0b7e2c9`, service name `Ferry`.

## Framing

A control frame is a 4-byte big-endian length followed by that many bytes of
UTF-8 JSON (max 65536). File contents are sent as raw bytes between a `file`
frame and its `fend` frame; the receiver knows how many to read from `size`.

## Session

```
client                                   server
  hello {v,id,name,os,purpose}  ──────▶
                                ◀──────  hello {v,id,name,os,trusted}
  (purpose "probe": client closes here)

  offer {count,total,files:[{path,size}]} ─▶
                                ◀──────  accept | reject {reason}
  per file:
    file {path,size}            ──────▶
    <size raw bytes>            ──────▶
    fend {crc32}                ──────▶
                                ◀──────  saved {as} | error {reason}
  done                          ──────▶
                                ◀──────  bye
```

- `id` is a random UUID generated once per install; it is what "trusted" refers to.
- `path` uses `/` separators and may contain folders (when a folder is sent).
  Receivers strip `..`, drive letters and characters their filesystem rejects.
- `crc32` is the standard CRC-32 (zlib/IEEE) of the file bytes as an unsigned integer.
- A trusted client's offer is accepted immediately. An unknown client's offer
  makes the receiver ask its user (Accept once / Always trust / Decline, 60 s timeout).
- Files are written to `<name>.ferrypart` and renamed once the CRC matches;
  existing names get ` (1)`, ` (2)`, … appended.

## v2: Wi-Fi handoff

Bluetooth only carries the handshake. The client's `hello` includes `total` (bytes it plans to send);
the server may wake its Wi-Fi for big transfers, then replies with a session `token`, a 32-byte AES `key`
(base64), its `lan` endpoints `[{ip,port}]` and `canHost` (can it start a Wi-Fi Direct group).

Client route choice (transfers ≥ 256 KB):
1. **LAN** — race TCP connects to every `lan` endpoint (2.5 s). First socket to send `attach {token}` and get
   `attached` back wins.
2. **Wi-Fi Direct** (≥ 4 MB, server `canHost`) — send `p2p_req` over Bluetooth; server starts a WPA2
   Wi-Fi Direct group and replies `p2p {ssid,pass,ip,port}` (or `p2p_fail {reason}`). Android clients join via
   WifiP2pManager; Windows joins it like a normal Wi-Fi network and switches back afterwards. Then attach as in 1.
3. **Bluetooth** — send `offer` on the Bluetooth link as in v1.

After `attached`, both directions of the TCP stream are AES-256-CTR encrypted (client→server IV = 'C' then
15 zero bytes, server→client IV = 'S' then zeros; 128-bit big-endian counter) and the session continues
from `offer` exactly as over Bluetooth. Tokens are single-use and expire after 2 minutes.

## Placement ("smart by type")

| Category    | Extensions                                                   | Android (Boox)        | Windows                         |
|-------------|--------------------------------------------------------------|-----------------------|---------------------------------|
| Books       | epub pdf mobi azw azw3 fb2 djvu cbz cbr chm                  | /Books                | Documents\Books                 |
| Audiobooks  | m4b aax                                                       | /Audiobooks           | Music\Audiobooks                |
| Music       | mp3 flac m4a ogg opus wav aac wma                             | /Music                | Music                           |
| Pictures    | jpg jpeg png gif webp heic bmp tif tiff svg                   | /Pictures/Ferry       | Pictures\Ferry                  |
| Video       | mp4 mkv mov avi webm m4v                                      | /Movies/Ferry         | Videos\Ferry                    |
| Fonts       | ttf otf ttc woff woff2                                        | /Fonts                | Downloads\Ferry\Fonts           |
| Notes       | note                                                          | /Documents/Notes      | Documents\Ferry\Notes           |
| Documents   | txt md doc docx odt rtf xls xlsx ods csv ppt pptx odp json    | /Documents            | Documents\Ferry                 |
| Other       | everything else (apk, zip, …)                                 | /Download/Ferry       | Downloads\Ferry                 |
