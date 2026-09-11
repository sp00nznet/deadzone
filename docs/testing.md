# Testing

## The automated part

```bash
./gradlew testDebugUnitTest
```

Eleven tests, all against `Feed.kt`, and CI will not build an APK if they fail.

The parser gets the tests because it is the only code here that can quietly ruin a
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
| Search input | `don't`, `-NATO`, `Mt. Gox` are searches, not `MATCH` syntax errors |

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

## Manual checks

Against a real library of 10 feeds / 4,031 episodes on an Android 14 emulator:

- [x] Add a feed by URL — title, artwork and category arrive
- [x] Import OPML — folders become category headers
- [x] Refresh 10 feeds — 4,031 episodes, no duplicates on a second refresh
- [x] Search `bitcoin` — 7 matches across 3 shows, including show-note-only hits
- [x] Fast scroller — 825 episodes, Sep 2026 → Oct 2023 in one drag, month bubble tracks
- [x] Play — streams, mini player docks, lock-screen controls appear
- [x] Download — chip flips to **On device**, plays from the file afterwards
- [x] Resume — position survives, shown as `0:24 in` on the row
- [x] History — finished episodes listed newest first with the date
- [x] **Aeroplane mode, cold start** — library, episode lists and search all work; 47
      matches for `september` with the radio off

### Worth checking by hand before a release

Things no test here covers:

- Kill the app mid-download, reopen, download again — it must resume from the `.part`
  file, not restart, and the finished file must play end to end
- Play with the screen locked for ten minutes, and over bluetooth
- A feed that 404s or times out must not stop the other nine from refreshing
- Auto-delete: mark something played, set 7 days, move the clock forward
