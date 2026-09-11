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
git tag v0.1.0 && git push --tags
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

## Sideloading

If you already have a podcast collection on a drive, there is no reason to download it
a second time. `tools/sideload.py` matches your files to entries in the real feed and
pushes them to the phone.

```bash
# See what would match, touch nothing
python tools/sideload.py --feed https://feeds.megaphone.fm/darknetdiaries \
                         --dir "X:/Podcasts/DarknetDiaries" --dry-run

# Do it
python tools/sideload.py --feed https://feeds.megaphone.fm/darknetdiaries \
                         --dir "X:/Podcasts/DarknetDiaries"

# Or a whole library at once, matching folders to feeds by name
python tools/sideload.py --opml seed/feeds.local.opml --root X:/Podcasts
```

Then, in the app: **Settings → Adopt sideloaded files**.

Requirements: Python 3.8+ (standard library only), `adb` on `PATH`, USB debugging on.
No root, and it works against a release build — files go to the app's own external
directory, which adb can write as the user.

### How the matching works

Local files are named by whatever ripped them, not by the publisher:
`0007 - 7 Manfred (Part 1).mp3` has to find `Ep 7: Manfred (Part 1)`. The script
strips the leading index, drops filler words like "episode" and "part", flattens
punctuation and accents, and then uses `difflib` to pick the closest remaining feed
entry. Each feed entry can only be claimed once, so two similar filenames cannot both
take the same episode and leave a real one unmatched.

Against a 617-file / 636-entry show it matched 616. If yours does worse, lower the bar:

```bash
python tools/sideload.py ... --threshold 0.5 --dry-run
```

Always `--dry-run` first. It prints every `file → episode` pairing it intends to make.

### What it writes

A `sideload.tsv` of `feedUrl <TAB> guid <TAB> devicePath`, pushed alongside the audio.
**Adopt sideloaded files** reads it and sets the `file` column on the matching
episodes. Entries whose file did not actually arrive are skipped rather than marking
an episode downloaded that then fails to play.

Remote filenames are a sha1 of the episode guid, so re-running the script overwrites
the same files instead of pushing a second copy of everything.
