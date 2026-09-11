# Setup

## Build requirements

- JDK 17+ (the CI uses Temurin 17; a local 21 is fine)
- Android SDK with platform 36 and build-tools
- `local.properties` with `sdk.dir` pointing at your SDK — Android Studio writes this
  for you on first open; by hand, note it is a **properties file**, so backslashes on
  Windows must be escaped (`sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`)

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests:

```bash
./gradlew testDebugUnitTest
```

## Releases

CI builds on every push and attaches a signed APK to a release when you push a `v*`
tag:

```bash
git tag v0.2.0 && git push --tags
```

Signing uses two repository secrets. Without them the workflow still produces an APK,
but signed with a throwaway key — which installs fine and then refuses to upgrade over
any previous build, so set them before the first release anyone keeps:

| Secret | |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w0 deadzone.keystore` |
| `ANDROID_KEYSTORE_PASS` | the keystore password |

Generate the keystore once and **do not commit it** (`*.keystore` is gitignored):

```bash
keytool -genkeypair -keystore deadzone.keystore -alias deadzone \
  -keyalg RSA -keysize 2048 -validity 10000
```

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging. The
workflow reads `versionName` out of that file to name the APK.

## Feeds

Add one by pasting its RSS URL, or import an OPML file — folders in the OPML become
categories in the library, and **Export** writes the same shape back out.

### Paid feeds are credentials

A Patreon (or Supporting Cast, or Supercast) feed URL looks like:

```
https://www.patreon.com/rss/yourshow?auth=LONGRANDOMTOKEN
```

That `auth=` token is a bearer credential. Anyone who has the URL can read the paid
feed as you, indefinitely, without your account. Treat it like a password:

- `seed/`, `*.opml` and `feeds.local.*` are in `.gitignore`. Keep them that way.
- Do not paste one into an issue, a log, or a screenshot.
- If one leaks, regenerate it from the publisher's site — changing your password does
  not rotate it.

Deadzone stores feed URLs in its own database, in app-private storage. Release builds
are not debuggable, so `adb` cannot read them off the device; debug builds are, which
is why CI ships the release variant.

## Adopting audio you already have

There is no reason to download a collection you already have. Two routes, same result.

### Import a folder (from the phone)

**Settings → Import a folder…** (or **Library → Import folder**) opens the system
directory picker. Pick a folder and Deadzone walks it, including subfolders.

Two things happen to what it finds:

- Audio whose filename matches an episode of a feed you subscribe to is **attached to
  that episode** — it counts as downloaded, keeps its resume position, and shows up
  under that show.
- Everything else is **kept as a local show**, named after its album tag if it has one
  and its folder if it doesn't, with the title and length read from the file. This is
  the normal case for a plain `Music` folder, and it means you can adopt audio for
  shows you have never subscribed to.

Subscribing first makes matching better, but it is not required and nothing is
discarded either way. No network is needed at all.

Whatever the picker can reach works:

| Source | How |
|---|---|
| An NFS or SMB share | Mount it with any client app that exposes a `DocumentsProvider` (CIFS Documents Provider and most NFS clients do), then pick the folder |
| A USB-OTG drive | Plug it in and pick it |
| An SD card | Pick it |
| Downloads, or anything local | Pick it |

**Deadzone speaks none of those protocols itself**, deliberately. Android's storage
access framework already exposes every mount as a pickable folder, so supporting the
framework supports all of them for no protocol code and no root.

**Audio already on the phone is linked, not copied.** Importing `/Music` does not
duplicate it; the library points at the files where they are. Audio reached through
any other provider — a network share, a cloud mount — **is** copied onto the phone,
because a file left on a share is a file you cannot hear in a tunnel. Mount, import,
unmount.

Deadzone will never delete audio it did not put there. Auto-delete, unsubscribing and
"delete download" all skip linked files; they only remove copies the app made
itself.

An interrupted import is safe: audio is written to `NAME.part` and only renamed once
it is whole, so nothing half-copied is ever marked as on the device. Re-run the import
and it picks up what is missing.

### Push from a desktop (over adb)

When the collection is on a machine the phone cannot mount, `tools/sideload.py` does
the same matching on the desktop and pushes over adb.

```bash
# See what would match, touch nothing
python tools/sideload.py --feed https://example.com/feed.xml \
                         --dir "/media/podcasts/Some Show" --dry-run

# Do it
python tools/sideload.py --feed https://example.com/feed.xml \
                         --dir "/media/podcasts/Some Show"

# Or a whole library at once, matching folders to feeds by name
python tools/sideload.py --opml seed/feeds.local.opml --root /media/podcasts
```

Then, in the app: **Settings → Adopt sideloaded files**.

Requirements: Python 3.8+ (standard library only), `adb` on `PATH`, USB debugging on.
No root, and it works against a release build — files go to the app's own external
directory, which adb can write as the user.

### How the matching works

Local files are named by whatever ripped them, not by the publisher:
`0007 - 7 The Quarry (Part 1).mp3` has to find `Ep 7: The Quarry (Part 1)`. Both routes
strip the leading index, drop filler words like "episode", flatten punctuation and
accents, and score what is left.

Three rules exist because the failure they prevent is silent — the wrong audio
attached to the right title, which looks fine until you press play:

- **The number after "Part" is kept.** Part 1 and Part 2 are different episodes.
- **Matching on a number alone never counts.** Every show has an "Episode 12".
- **The best match wins the episode**, not the first file to ask for it.

Against a 617-file / 636-entry show it matched 616. If yours does worse, lower the bar
on the desktop script:

```bash
python tools/sideload.py ... --threshold 0.5 --dry-run
```

Always `--dry-run` first. It prints every `file → episode` pairing it intends to make.

### What the desktop script writes

A `sideload.tsv` of feed URL, guid and device path, pushed alongside the audio.
**Adopt sideloaded files** reads it and sets the `file` column on the matching
episodes. Entries whose file did not actually arrive are skipped rather than marking
an episode downloaded that then fails to play.

Remote filenames are a sha1 of the episode guid, so re-running the script overwrites
the same files instead of pushing a second copy of everything.

## Running against a fixture

`tools/demo_feeds.py` serves ten invented podcasts — generated artwork, invented
episode titles, original show notes, silent audio. Every screenshot in this repo is
taken against it, so that none of them reproduce anyone else's cover art or copy.

```bash
python tools/demo_feeds.py                 # serves on :8765
python tools/demo_feeds.py --print-feeds   # the URLs to paste into the app
```

From an emulator the host is `10.0.2.2`. Reaching it over plain HTTP is permitted
**only in debug builds** — `app/src/debug/` carries a network security config scoped
to `10.0.2.2` and localhost, and the release APK keeps Android's default of refusing
cleartext everywhere. If you ever need a real `http://` feed to work in a release
build, that is a deliberate decision to make then, not a default to inherit.

## Finding shows

**Search → Find new shows** queries the iTunes directory. No account and no API key:
Podcast Index returns much the same feeds but wants a signed key per request, which is
not worth making anyone register for.

If a show is not in the directory, paste its RSS URL instead.
