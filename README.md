<h1>Deadzone</h1>

**Your whole podcast hoard, on your phone, with no signal.**

Subscribe to RSS feeds, keep the audio on the device, and listen in a basement, on a
plane, or three miles up a trail with no bars. Nothing in the app waits on a network
— not the library, not the episode list, not search across every show note you have.

<table>
<tr>
<td align="center"><img src="docs/screenshots/12-offline-cold-start.png" width="185"><br><sub>Cold start, no signal at all</sub></td>
<td align="center"><img src="docs/screenshots/11-offline-search.png" width="185"><br><sub>4,031 episodes, searched offline</sub></td>
<td align="center"><img src="docs/screenshots/01-library.png" width="185"><br><sub>Grouped, with what's unplayed</sub></td>
<td align="center"><img src="docs/screenshots/05-fastscroll.png" width="185"><br><sub>825 episodes in one drag</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/06-player.png" width="185"><br><sub>Speed, sleep timer, show notes</sub></td>
<td align="center"><img src="docs/screenshots/08-actions.png" width="185"><br><sub>On device, and where you got to</sub></td>
<td align="center"><img src="docs/screenshots/09-history.png" width="185"><br><sub>What you finished, and when</sub></td>
<td align="center"><img src="docs/screenshots/10-settings.png" width="185"><br><sub>Wi-Fi only, auto-delete, adopt</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/13-discover.png" width="185"><br><sub>Find shows, no API key</sub></td>
<td align="center"><img src="docs/screenshots/14-stats.png" width="185"><br><sub>Hours by show and month</sub></td>
<td align="center"><img src="docs/screenshots/15-folder-import.png" width="185"><br><sub>A mounted share, adopted</sub></td>
<td align="center"><img src="docs/screenshots/03-search.png" width="185"><br><sub>Show notes are searched too</sub></td>
</tr>
</table>

---

## The two ideas

**1. The database is the app.** Every screen reads from SQLite and nothing else. The
network never draws a pixel — it only writes rows. So there is no offline *mode* to
enter, because there was never an online one; aeroplane mode changes nothing about
what any screen does. Sync becomes a background job that mutates a table, and it is
free to fail, repeatedly, without a single screen noticing.

**2. A file you already own and a file the app downloaded are the same row.** Point
Deadzone at a folder — a mounted NFS or SMB share, a USB drive, an SD card — and it
matches the audio in it to entries in your feeds by title and adopts it. From then on
the app cannot tell the difference, and does not need to: same column, same playback
path, same auto-delete rules. A back catalogue you already have never gets downloaded
twice.

Notably, **Deadzone speaks no network filesystem at all.** Android's file picker
already reaches anything mounted, so "mount the share however you like, then point the
app at the folder" works for NFS, SMB, USB-OTG and an SD card alike, for no protocol
code. Mount it, import it, unmount it — the audio is on the phone now.

## What it does

| | |
|---|---|
| **Works with no network** | Library, episodes, show notes, search, playback of anything on the device. Cold start included. |
| **Searches everything** | Full-text over every title and every show note, on the phone. 4,031 episodes return in under a second. |
| **Handles a big collection** | A drag rail on any list over 40 rows, showing the month you're passing through. 825 episodes is one gesture. |
| **Remembers where you were** | Resume position per episode, saved every five seconds. Finished episodes land in History with the date you finished them. |
| **Fetches by itself** | Keep the newest *N* per feed, on unmetered networks only, resuming part-downloads rather than restarting them. |
| **Cleans up after itself** | Finished downloads older than 7 / 30 / 90 days are deleted; the feed entry and your history stay. |
| **Imports and exports OPML** | Bring a subscription list in whole, and take it out again. Folders become categories. |
| **Adopts a folder you already have** | Any folder the file picker can reach, matched to your feeds by title and copied in. |
| **Finds new shows** | Searches the iTunes directory. No account, no API key, nothing to register for. |
| **Counts what you listened to** | Hours per show and per month, from play history that was already being recorded. |
| **Works in the car** | An Android Auto browse tree: Continue, Downloaded, Queue, and every show. |
| **Shows chapters and transcripts** | When a feed publishes them — tap a chapter or a transcript line to seek there. |

Plus the things a podcast app is not allowed to get wrong: background playback with
the screen off, lock-screen and bluetooth controls, pausing when the headphones come
out, back-15 / forward-30, playback speed, and a sleep timer.

## Start

**[Download the latest APK](https://github.com/sp00nznet/deadzone/releases/latest)** —
signed release build, Android 8.0+. Or build it:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Paste an RSS URL, or hit **Import OPML** and bring your whole list at once. Set
**auto-download** per feed from the ⋮ menu and it fills itself in over Wi-Fi from then
on.

Already have the audio? **Import folder** in the library adopts anything the file
picker can reach — see [docs/setup.md → Adopting audio you already have](docs/setup.md#adopting-audio-you-already-have).

## Docs

- **[design.md](docs/design.md)** — how it works, and what was traded away
- **[setup.md](docs/setup.md)** — building, signing, feeds, and adopting a collection
- **[roadmap.md](docs/roadmap.md)** — what is next
- **[testing.md](docs/testing.md)** — the checks, and the bugs only a real device found

## Layout

| File | |
|---|---|
| `Feed.kt` | RSS, Atom, OPML and transcript parsing. Pure, and unit-tested |
| `Match.kt` | Working out which episode a file on disk is. Pure, and unit-tested |
| `Store.kt` | SQLite — feeds, episodes, positions, history, full-text search, statistics |
| `Sync.kt` | Fetching, resumable downloads, directory search, the background job |
| `Import.kt` | Walking a picked folder and adopting what is in it |
| `PlayerService.kt` | The media session, and the Android Auto browse tree |
| `MainActivity.kt` | `Vm` — all state, the screen stack, every action |
| `Screens.kt` | The UI |
| `tools/sideload.py` | The same adoption from a desktop, over adb. Stdlib only |

Eight Kotlin files. No dependency injection, no navigation library, no repository
layer, no ORM.

## A note on feed URLs

Nothing in this repo contains a feed URL, and nothing should. A paid feed's URL
(Patreon and friends) carries an `auth=` token that is a bearer credential — anyone
holding it can read your paid subscription as you. `seed/` and `*.opml` are in
`.gitignore` for that reason. Keep your list local, or export it to somewhere private.

## Licence

MIT — see [LICENSE](LICENSE).
