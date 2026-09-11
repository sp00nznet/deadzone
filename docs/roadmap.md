# Roadmap

## Done — 0.1.0

Feeds (RSS + Atom), OPML in and out, categories, episode lists, full-text search,
resumable downloads, auto-download per feed, background sync on unmetered networks,
auto-delete of finished downloads, background playback with hardware controls, resume
positions, play history, sleep timer, playback speed, a drag rail for long lists, and
sideloading an existing collection.

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

## Maybe

**Chapters and transcripts.** Podcast-namespace `<podcast:chapters>` and
`<podcast:transcript>`. A second parser and a second UI, for tags that few of the feeds
in use here actually publish. Worth it when one you listen to does.

**Feed discovery.** Podcast Index has a free API. Adding a feed is currently pasting a
URL or importing OPML, which is fine when you already know what you want.

**Statistics.** `played_at` is already a real timestamp on every finished episode, so
"hours listened this month, by show" is a query, not a feature.

**Android Auto.** The media session is most of the work; it needs a browse tree.

**A sync server.** Positions following you between devices. Nothing here assumes a
second device yet, and adding one means running something.

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
