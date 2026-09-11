# Design

How Deadzone works, and what was traded away to keep it small.

## The shape

Eight Kotlin files, one Gradle module, one database.

```
Feed.kt           RSS, Atom, OPML and VTT/SRT parsing. Pure — no Android imports.
Match.kt          Which episode is this file? Pure — no Android imports.
Store.kt          SQLite: feeds, episodes, positions, history, search, statistics.
Sync.kt           OkHttp fetch, resumable downloads, directory search, the job.
Import.kt         Walking a picked folder and adopting the audio in it.
PlayerService.kt  Media3 library session. Playback, hardware controls, Android Auto.
MainActivity.kt   Vm — all state, every action.
Screens.kt        The UI.
```

Plus two stdlib-only Python tools: `sideload.py` (adopt a collection from a
desktop over adb) and `demo_feeds.py` (invented podcasts to screenshot and test
against, so nothing here redistributes anyone else's artwork or copy).

There is no dependency injection, no navigation library, no repository layer, no
ORM, and no `domain/` package. The screen you are on is a field on `Vm`. Back is a
`when` block.

## The database is the app

Every screen reads from SQLite and nothing else. The network never draws a pixel —
it only writes rows. That single rule is what makes "works offline" true rather than
aspirational: there is no offline mode to enter, because there was never an online
one. Aeroplane mode changes nothing about what the UI does.

Sync is therefore a background job that mutates the database, and downloads are a
background job that fills in a `file` column. Both can fail, repeatedly, without any
screen noticing.

### Schema

Two tables. `feed` holds the subscription plus the conditional-GET state (`etag`,
`modified`). `episode` holds everything else, keyed `UNIQUE(feed_id, guid)` — the
guid the publisher chose, so a re-published item updates in place instead of
duplicating.

Three columns on `episode` are ours and never overwritten by a refresh:

| | |
|---|---|
| `file` | the local path once the audio is on the phone |
| `position` | resume point in milliseconds |
| `played_at` | when you finished it; `0` means you haven't |

The merge is an `ON CONFLICT ... DO UPDATE` that touches only the publisher's fields.
A show that rewrites its whole back catalogue (they do this) cannot reset your
history.

### Search

`ep_fts` is an FTS4 external-content table over `title` and `description`.

FTS4 rather than FTS5 is not a preference. **Android's bundled SQLite does not
compile in the fts5 module at all** — `CREATE VIRTUAL TABLE ... USING fts5` throws
`no such module: fts5` on a stock API 34 device, and there is no version of Android
where it is guaranteed present. Getting FTS5 means shipping your own SQLite. FTS4 is
everywhere and does the job; the only thing given up is `bm25()` ranking, and for
podcast search "newest matching episode first" is the better order anyway.

External
content means the index references `episode` rows rather than storing a second copy
of every show note — at 23,000 episodes that is the difference between a database
you can keep on a phone and one you can't.

The update trigger is `AFTER UPDATE OF title, description`, not a bare `AFTER
UPDATE`. Playback writes `position` every five seconds; without the column list,
every one of those writes would delete and reinsert an FTS row.

`MATCH` is a query language, so a typed apostrophe or leading dash is a
*syntax error*, not zero results. `ftsQuery()` splits on everything that is not a
letter or a digit, so no token can mean anything to the parser, and appends `*` so
it works as you type.

## Downloads resume

Episodes are routinely 80MB and phone connections are not. `downloadEpisode()`
writes to `NAME.part`, sends `Range: bytes=N-` when a partial is already there, and
only renames to the final path once the stream ends. Two consequences worth stating:

- A killed download never leaves a truncated file that looks complete, because the
  file does not exist under its real name until it is whole.
- A server that ignores `Range` and replies `200` gets detected (we check for `206`),
  and the partial is discarded rather than having a duplicate prefix spliced into it.

## Feeds are hostile input

A podcast feed is XML from a stranger. Two defences:

1. **DTDs are refused.** `disallow-doctype-decl` is on, so a feed cannot declare an
   external entity and read files off the phone. There is a test for this.
2. **Dates are parsed leniently but never guessed.** Eight patterns are tried; if
   none match, `published` is `0` and the episode sorts last. The strict RFC 1123
   parser rejects a single-digit day, a missing seconds field, and the `UT` zone —
   all of which are in feeds that have been running since 2005. Silently returning 0
   for those would drop a show's entire back catalogue into 1970.

## Adopting audio you already have

The interesting case is a collection that already exists somewhere. There are two
routes in, and they end at the same place: the `file` column on an episode row.

### From the phone: a folder

**Import folder** opens the system directory picker. Whatever that picker can reach,
Deadzone can import: an NFS or SMB share mounted by a client app, a USB-OTG drive, an
SD card, the Downloads folder.

**Deadzone implements no network filesystem, and should not.** Every mount is already
a `DocumentsProvider`, so supporting the framework supports all of them at once, for
no protocol code and no root. Mount the share, import, unmount — the audio is local
from then on, which is the whole point.

The walk uses `DocumentsContract` and one cursor per directory rather than
`DocumentFile`, which issues a separate content-provider query per file for its name
and type. Over 23,000 files on a network mount that is thousands of round trips
against tens.

Files are **copied in**, not linked. A file left on the share is a file you cannot
hear in a tunnel, and hearing it in a tunnel is the entire app.

### From a desktop: adb

`tools/sideload.py` does the same matching on a computer and pushes over adb, writing
a manifest of feed URL, guid and path that **Adopt sideloaded files** in Settings
reads. Useful when the collection is on a machine the phone cannot mount.

### The matching

Local audio is named by whatever ripped it, not by the publisher:
`0007 - 7 The Quarry (Part 1).mp3` has to find `Ep 7: The Quarry (Part 1)`. `Match.kt`
strips the leading index (rippers often write it twice), drops filler words, flattens
case and accents, and scores token overlap.

Three rules earn their place, because the failure they prevent is silent — the wrong
audio attached to the right title, which looks fine until you press play:

1. **Numbers are kept.** "Part 1" and "Part 2" are different episodes, and a matcher
   that treats the digit as filler makes them the same string.
2. **A number alone is never a match.** Every show has an "Episode 12". Since files
   are matched against the whole library rather than one feed, agreeing on a digit is
   not evidence; a shared *word* is required first.
3. **Episodes are allocated best-score-first, not file-order.** Otherwise a mediocre
   early match takes the episode a later file matches exactly, and one wrong pairing
   cascades into ten.

Against a real 617-file show it matches 616.

After adoption there is no difference between an episode you imported and one the app
downloaded: same column, same playback path, same auto-delete rules. The app has no
idea which is which, and does not need to.

## Playback

`PlayerService` is a `MediaSessionService` because that is the only way to keep audio
running with the screen off and get lock-screen, bluetooth and Android Auto controls.
The activity talks to it through a `MediaController`, which means playback survives
the activity being destroyed.

Two podcast-specific settings on the ExoPlayer: `AUDIO_CONTENT_TYPE_SPEECH`, and
asymmetric seek increments (back 15s, forward 30s) rather than the music default.

An episode is marked played at 95% rather than at the end, because outros, ad reads
and trailing silence mean almost nobody reaches the last sample.

### Android Auto

Being a `MediaLibraryService` rather than a plain `MediaSessionService` is what gives
the car something to browse. The tree comes from the same database every screen reads:
**Continue** (started but unfinished), **Downloaded**, **Queue**, and **Shows** → each
feed → its unplayed episodes.

Two things there are deliberate. Browsing runs on a background executor, because a car
asks for a whole list at once and serving that on the caller's main thread is an ANR
waiting for a large library. And `onAddMediaItems` re-resolves every item by id before
playback, because a controller may strip the URI off a browse item — that hook is also
where the resume position is applied, which is why picking up mid-episode works in the
car at all.

### Chapters and transcripts

`podcast:chapters` is a JSON document of start times; `podcast:transcript` is VTT or
SRT. Both are fetched when an episode starts, and both are optional — a feed that
publishes neither simply shows neither. Tapping a chapter or a transcript line seeks
there, and the active one is highlighted from the position that was already ticking.

When a show publishes the same transcript three ways, VTT wins: it is the one with
timings, and timings are the only reason to show a transcript in a player rather than
in a browser.

### Statistics

Hours per show and per month. This is a query, not a feature — `played_at` has been a
real timestamp on every finished episode since the first version, so nothing had to be
recorded to enable it.

## What was traded away

| Not built | Why | When to add it |
|---|---|---|
| Room | Compile-checked DAOs cost KSP codegen and three artifacts; the FTS index would be hand-written SQL either way | If the schema grows past two tables |
| Paging 3 | `LIMIT/OFFSET` with an index covers 23k rows, and the fast scroller makes position, not page size, the problem | If a single feed exceeds ~50k episodes |
| Skip silence | `skipSilenceEnabled` is an ExoPlayer API, not a `Player` one; exposing it through the session needs a custom command | Alongside any other custom session command |
| Podcast Index | The same feeds as iTunes, but it wants a signed API key per request, and a search box is not worth making someone register for | If iTunes' catalogue proves too narrow |
| An NFS or SMB client | The system picker already reaches every mount, so one protocol would buy less than the framework does | Ideally never |
| A sync server | Positions following you between devices. Nothing here assumes a second device, and adding one means running something | If a second device appears |
| A foreground service for imports | A large import holds the app open; the `.part` rule already makes interrupting it safe | If importing tens of thousands of files becomes routine |

Every shortcut taken deliberately is marked with a `ponytail:` comment in the source.
