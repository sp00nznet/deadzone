# Roadmap

## Done — 0.1.0

Feeds (RSS + Atom), OPML in and out, categories, episode lists, full-text search,
resumable downloads, auto-download per feed, background sync on unmetered networks,
auto-delete of finished downloads, background playback with hardware controls, resume
positions, play history, sleep timer, playback speed, a drag rail for long lists, and
sideloading an existing collection from a desktop.

## Done — 0.2.0

**Folder import.** Point the app at any folder the system picker can reach — a mounted
NFS or SMB share, a USB-OTG drive, an SD card — and it matches the audio to your feeds
and copies it in. No protocol code and no root, because every mount is already a
`DocumentsProvider`.

**Find new shows.** The iTunes directory, in the Search tab. No account, no API key.

**Statistics.** Hours per show and per month. A query over `played_at`, which was
already a real timestamp on every finished episode.

**Android Auto.** A browse tree — Continue, Downloaded, Queue, Shows — served from the
same database every screen reads.

**Chapters and transcripts.** `podcast:chapters` and `podcast:transcript`, when a feed
publishes them. Tap a chapter or a line to seek there; the active one is highlighted.

## Next

**Queue UI.** The table has `queue_pos`, `enqueue`/`dequeue` work, and finishing an
episode already pulls the next queued one. What is missing is a screen to see and
reorder it.

**Download progress outside the app.** Progress shows on the row while the app is
open. A foreground-service notification would make a big auto-download batch legible
when it isn't.

**Skip silence.** `skipSilenceEnabled` is an ExoPlayer API rather than a `Player` one,
so reaching it through the media session needs a custom session command. Worth doing
the next time anything else needs one.

**Per-feed playback speed.** One show at 1.8x and another at 1.0x is the common case;
the setting is currently global.

**A foreground service for big imports.** Importing a folder currently holds the app
open. The `.part` rule makes interrupting it safe rather than corrupting, but a
23,000-file import wants a notification and the freedom to background itself.

## Maybe

**Transcript search.** The cues are already parsed and the database already has an FTS
index. Searching *inside* episodes rather than only their show notes is mostly a
question of how much index a phone should carry.

**Playlists.** The queue covers "listen to these next". Named, saved lists are a
different thing, and nobody has wanted one yet.

**A sync server.** Positions following you between devices. Nothing here assumes a
second device, and adding one means running something. Folder import covers the
related-but-different case — getting a big collection onto the phone in the first
place — without a server at all.

## Not planned

**A streaming-first mode.** Streaming works, but the app is built around the audio
being on the device. Something that assumes a network is a different app.

**Accounts.** There is no server, and there is not going to be one by accident.

## Debt

Deliberate shortcuts are marked `ponytail:` in the source. To list them:

```bash
grep -rn "ponytail:" app/src tools
```

Each one names what was skipped and what would trigger doing it properly.
