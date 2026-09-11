# Design

How Deadzone works, and what was traded away to keep it small.

## The shape

Six Kotlin files, one Gradle module, one database.

```
Feed.kt           RSS, Atom and OPML parsing. Pure — no Android imports.
Store.kt          SQLite: feeds, episodes, positions, history, full-text search.
Sync.kt           OkHttp fetch, resumable downloads, the WorkManager job, settings.
PlayerService.kt  Media3 session service. Playback, lock screen, bluetooth.
MainActivity.kt   Vm — all state, every action.
Screens.kt        The UI.
```

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

## Sideloading

The interesting case is a collection you already have. `tools/sideload.py` reads the
feed, fuzzy-matches local filenames to episode titles with `difflib` (local files are
named by whatever ripped them — `0007 - 7 Manfred (Part 1).mp3` has to find
`Ep 7: Manfred (Part 1)`), pushes each file to the app's external directory over adb,
and writes a `feedUrl \t guid \t path` manifest.

**Adopt sideloaded files** in Settings reads that manifest and sets `file` on the
matching rows. After that there is no difference at all between an episode you
sideloaded and one the app downloaded — same column, same playback path, same
auto-delete rules. The app has no idea which is which, and does not need to.

Files land in `/sdcard/Android/data/net.sp00nz.deadzone/files/`, which adb can write
as the user. No root, and no debug build required.

## Playback

`PlayerService` is a `MediaSessionService` because that is the only way to keep audio
running with the screen off and get lock-screen, bluetooth and Android Auto controls.
The activity talks to it through a `MediaController`, which means playback survives
the activity being destroyed.

Two podcast-specific settings on the ExoPlayer: `AUDIO_CONTENT_TYPE_SPEECH`, and
asymmetric seek increments (back 15s, forward 30s) rather than the music default.

An episode is marked played at 95% rather than at the end, because outros, ad reads
and trailing silence mean almost nobody reaches the last sample.

## What was traded away

| Not built | Why | When to add it |
|---|---|---|
| Room | Compile-checked DAOs cost KSP codegen and three artifacts; the FTS index would be hand-written SQL either way | If the schema grows past two tables |
| Paging 3 | `LIMIT/OFFSET` with an index covers 23k rows, and the fast scroller makes position, not page size, the problem | If a single feed exceeds ~50k episodes |
| Skip silence | `skipSilenceEnabled` is an ExoPlayer API, not a `Player` one; exposing it through the session needs a custom command | Alongside any other custom session command |
| Podcast Index search | Adding a feed is pasting a URL or importing OPML; discovery is a different app | If adding feeds by hand becomes the friction |
| Chapters, transcripts | Podcast-namespace tags, a second parser and a second UI | When a feed you actually listen to publishes them |
| A sync server | There is no second device in the picture yet | If playback position needs to follow you between devices |

Every shortcut taken deliberately is marked with a `ponytail:` comment in the source.
