# Testing

## The automated part

```bash
./gradlew testDebugUnitTest
```

Twenty-six tests, all against the two pure files — `Feed.kt` and `Match.kt` — and CI
will not build an APK if they fail.

Those two get the tests because they are the only code here that can quietly ruin a
library rather than crash. A date that silently returns `0` drops a show's entire back
catalogue into 1970 and you would not notice for weeks; an enclosure the parser misses
makes an episode simply not exist. Both are invisible failures, so they are the ones
worth pinning down:

| | |
|---|---|
| RSS with iTunes extensions | Title, artwork, category, guid, enclosure size, `MM:SS` duration |
| An `<item>` with no audio | Must be dropped — it is a blog post in the feed, not an episode |
| Atom `<entry>` | `<link rel="enclosure">` instead of `<enclosure>` |
| Channel artwork | `<image><url>` as well as `<itunes:image href>` |
| Eight date shapes | Including a single-digit day, a missing seconds field, and the `UT` zone, all of which strict RFC 1123 rejects |
| Durations | Seconds, `M:SS`, `H:MM:SS`, and junk like `"about an hour"` |
| OPML round trip | Folders become categories, a feed outside a folder does not inherit one, and `&` in a title survives |
| An external entity | Must never resolve (see below) |
| Search input | `don't`, `-NATO`, `St. Anne` are searches, not `MATCH` syntax errors |
| `podcast:` tags | Chapters and transcripts come off the item; VTT beats HTML when a show publishes both |
| VTT and SRT | Index lines, decimal commas, missing blank lines, `MM:SS` with no hours |

And against `Match.kt`, which decides what audio gets attached to which episode:

| | |
|---|---|
| Ripper filenames | `0007 - 7 The Quarry (Part 1)` finds `Ep 7: The Quarry (Part 1)` |
| Part 1 vs Part 2 | Must stay different episodes |
| A number alone | Never a match — every show has an "Episode 12" |
| Allocation order | The best match wins an episode, not the first file to ask |
| One episode, one file | Two files can never claim the same episode |
| Unrelated audio | Ringtones and voicemails match nothing |
| Accents and case | `CAFE BELLEFEUILLE` finds `Café Bellefeuille` |

## The part the tests could not have caught

Four bugs in this app were invisible to a green unit-test run and only appeared when
the APK was installed on an Android 14 emulator and driven through the actual UI. They
are worth recording because they are all the same *kind* of bug — the JVM the tests run
on is not the runtime the app runs on.

**1. `no such module: fts5`.** The schema used FTS5. Android's bundled SQLite does not
compile in the fts5 module — not on API 26, not on API 34, not anywhere. The app
crashed on first launch, in `Store.onCreate`. Unit tests never open a database, so
nothing caught it. Now FTS4, which is universally present.

**2. XXE hardening that crashed every feed.** `setFeature("…/disallow-doctype-decl")`
is honoured by the JVM's Xerces and *throws `SAXNotRecognizedException`* on Android's
Expat. The security test passed on the JVM while, on the device, every single feed
failed to parse — 10 of 10, with the feature URL as the error message. The fix attempts
each feature independently and, more importantly, installs an `EntityResolver` that
returns nothing, which is portable. The test now asserts the *outcome* (the entity does
not resolve) rather than the mechanism (an exception), because the mechanism legitimately
differs by platform.

**3. `LIMIT f.keep` does not parse.** The auto-download query used a correlated outer
column in a subquery's `LIMIT`. SQLite rejects that outright — `no such column: f.keep`
— so the background sync worker threw and returned `RETRY` forever, silently. It now
counts newer siblings instead, which also avoids window functions (SQLite 3.25, newer
than minSdk 26).

**4. A failed OPML read took the app down.** An unreadable URI from a picker threw
straight out of a coroutine. Now caught and reported.

The lesson is not "write more unit tests". It is that a JVM unit test cannot tell you
anything about SQLite's build flags or Expat's feature set, and the only thing that can
is running it.

### And one the tests did catch

Writing `Match.kt` reused the desktop script's rule of treating "part" *and the number
after it* as filler. A test asking what `0007 - 7 The Quarry (Part 1)` normalises to
failed, which is how it came out that Part 1 and Part 2 collapse to the same string —
so an import would attach one of them to both. Two-parters are common enough that the
collection this was built for has plenty of them. `tools/sideload.py` had
carried the same bug since it was written; both are fixed, and re-running the desktop
script against a real 617-file show still matches 616.

## Manual checks

Against the `tools/demo_feeds.py` fixture — 10 invented shows, 3,513 episodes — on an
Android 14 emulator:

- [x] Add a feed by URL — title, artwork and category arrive
- [x] Import OPML — folders become category headers
- [x] Refresh 10 feeds — 4,031 episodes, no duplicates on a second refresh
- [x] Search `lighthouse` — 117 matches across the library, show notes included
- [x] Fast scroller — 826 episodes, two and a half years in one drag, month bubble tracks
- [x] Play — streams, mini player docks, lock-screen controls appear
- [x] Download — chip flips to **On device**, plays from the file afterwards
- [x] Resume — position survives, shown as `0:24 in` on the row
- [x] History — finished episodes listed newest first with the date
- [x] **Aeroplane mode, cold start** — library, episode lists and search all work; 130
      matches for `reservoir` with the radio off

Then again for 0.2.0:

- [x] Schema migration v1 → v2 over a live 4,000-episode database — three columns
      added, every resume position and downloaded file still linked
- [x] Find new shows → a real query → Add → the feed arrives with artwork and episodes
      (a feed whose URL had been guessed wrong by hand earlier)
- [x] Statistics — 11 episodes, 11h 22m, broken down across 8 shows and by month
- [x] Folder import — five ripper-named files in a picked folder matched to five
      different shows
- [x] Chapters and a transcript render, and the active chapter tracks playback
- [x] Playback still works after the MediaLibraryService change, and resumed from the
      stored position

## The fixture

`tools/demo_feeds.py` serves ten invented podcasts over HTTP — generated cover art,
invented episode titles, original show notes, and silent WAV audio whose length
matches what the feed advertises. Everything above was checked against it.

It exists for two reasons. The screenshots in this repo would otherwise reproduce
real cover art and real show-note copy, which is somebody else's work and a repo is a
distribution. And a fixture makes a manual pass repeatable — the same library, the
same episode counts, every time.

```bash
python tools/demo_feeds.py
python tools/demo_feeds.py --print-feeds   # emulator-facing URLs
```

Reaching it needs cleartext HTTP, which is permitted only in debug builds
(`app/src/debug/`), scoped to `10.0.2.2` and localhost. The release APK keeps
Android's default of refusing cleartext everywhere.

### Worth checking by hand before a release

Things no test here covers:

- Kill the app mid-download, reopen, download again — it must resume from the `.part`
  file, not restart, and the finished file must play end to end
- Play with the screen locked for ten minutes, and over bluetooth
- A feed that 404s or times out must not stop the other nine from refreshing
- Auto-delete: mark something played, set 7 days, move the clock forward
- Android Auto: the browse tree is not covered by anything here. It needs the Desktop
  Head Unit, or a car. Check Continue, Downloaded, Queue and Shows each populate, and
  that starting an episode from the car resumes where you left off
- Folder import from a genuinely large mount — the walk and the copy are both
  untested above a handful of files
