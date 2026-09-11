#!/usr/bin/env python3
"""
Adopt an audio collection you already have, instead of downloading it twice.

Given a folder of episodes on disk and the RSS feed they came from, this matches
each file to an entry in the feed, pushes it to the phone over adb, and writes a
manifest Deadzone reads to link the two. Settings -> "Adopt sideloaded files".

    python tools/sideload.py --opml seed/feeds.local.opml --root /media/podcasts
    python tools/sideload.py --feed https://example.com/feed.xml --dir "/media/podcasts/Show"
    python tools/sideload.py --opml seed/feeds.local.opml --root /media/podcasts --dry-run

Nothing here needs the app to be a debug build and nothing needs root: files land
in the app's own external directory, which adb can write to as the user.

ponytail: stdlib only. urllib fetches, ElementTree parses, difflib matches. The
one job that actually needed a library — fuzzy title matching — is in difflib.
"""

import argparse
import difflib
import hashlib
import re
import subprocess
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "net.sp00nz.deadzone"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files"
AUDIO = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".m4b"}
UA = "Deadzone-sideload/0.1"

# Episode titles carry emoji and smart quotes, and the default Windows console
# codepage raises on them mid-run — after the files have already been pushed.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

# Local files are named by the ripper, not the publisher: "0007 - 7 The Quarry (Part
# 1).mp3" has to match "Ep 7: The Quarry (Part 1)". Stripping the leading index and
# the punctuation is what makes the two comparable at all.
LEADING_INDEX = re.compile(r"^\s*\d{1,5}\s*[-._]\s*(?:\d{1,5}\s*[-._:]?\s*)?")
# The filler word goes; the number after it does NOT. "(Part 1)" and "(Part 2)"
# are different episodes, and swallowing the digit makes them one string,
# silently pairing the wrong audio with the wrong entry.
NOISE = re.compile(r"\b(ep(isode)?|pt|part|no)\b\.?", re.I)


def normalise(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    s = LEADING_INDEX.sub("", s)
    s = NOISE.sub(" ", s)
    return re.sub(r"[^a-z0-9 ]+", " ", s.lower()).strip()


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def feed_episodes(url: str):
    """[(guid, title)] for everything in the feed that has audio attached."""
    root = ET.fromstring(fetch(url))
    out = []
    for item in root.iter():
        if item.tag.rsplit("}", 1)[-1] not in ("item", "entry"):
            continue
        title = guid = audio = None
        for child in item:
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "title":
                title = (child.text or "").strip()
            elif tag in ("guid", "id") and not guid:
                guid = (child.text or "").strip()
            elif tag == "enclosure":
                audio = child.get("url")
            elif tag == "link" and child.get("rel") == "enclosure":
                audio = child.get("href")
        if audio and title:
            out.append((guid or audio, title))
    return out


def opml_feeds(path: Path):
    """[(title, url)] — the same OPML the app imports."""
    root = ET.fromstring(path.read_bytes())
    return [
        (o.get("text") or o.get("title") or o.get("xmlUrl"), o.get("xmlUrl"))
        for o in root.iter("outline")
        if o.get("xmlUrl")
    ]


def pick_dir(root: Path, show: str):
    """Find the folder for a show when the folder name is a squashed version of it."""
    want = normalise(show).replace(" ", "")
    best, score = None, 0.0
    for d in root.iterdir():
        if not d.is_dir():
            continue
        s = difflib.SequenceMatcher(None, want, normalise(d.name).replace(" ", "")).ratio()
        if s > score:
            best, score = d, s
    return best if score >= 0.6 else None


def adb(*args, dry=False):
    if dry:
        print("  adb", " ".join(str(a) for a in args))
        return 0
    return subprocess.run(["adb", *[str(a) for a in args]], capture_output=True).returncode


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--opml", type=Path, help="OPML file of feeds to match against")
    src.add_argument("--feed", help="a single RSS URL")
    ap.add_argument("--root", type=Path, help="folder of per-show folders (with --opml)")
    ap.add_argument("--dir", type=Path, help="one show's folder (with --feed)")
    ap.add_argument("--threshold", type=float, default=0.62,
                    help="title similarity needed to call it a match (0-1)")
    ap.add_argument("--dry-run", action="store_true", help="match and report, push nothing")
    args = ap.parse_args()

    if args.opml and not args.root:
        ap.error("--opml needs --root")
    if args.feed and not args.dir:
        ap.error("--feed needs --dir")

    if not args.dry_run and adb("get-state") != 0:
        sys.exit("No device. Plug the phone in, enable USB debugging, and accept the prompt.")

    if args.opml:
        shows = opml_feeds(args.opml)
    else:
        shows = [(args.dir.name, args.feed)]

    manifest, pushed, skipped = [], 0, 0

    for show, url in shows:
        folder = args.dir if args.dir else pick_dir(args.root, show)
        if folder is None or not folder.is_dir():
            print(f"- {show}: no folder found, skipping")
            continue

        try:
            episodes = feed_episodes(url)
        except Exception as e:
            print(f"- {show}: could not read feed ({e})")
            continue

        files = sorted(p for p in folder.iterdir() if p.suffix.lower() in AUDIO)
        print(f"\n{show}: {len(files)} files, {len(episodes)} feed entries")

        titles = [normalise(t) for _, t in episodes]
        used = set()
        for f in files:
            key = normalise(f.stem)
            # close_matches over the *remaining* titles, so two files can't both
            # claim the same episode and leave a real one unmatched.
            pool = [t for i, t in enumerate(titles) if i not in used]
            hit = difflib.get_close_matches(key, pool, n=1, cutoff=args.threshold)
            if not hit:
                skipped += 1
                continue
            # Shows repeat titles ("Bonus"), so walk past the ones already claimed.
            idx = titles.index(hit[0])
            while idx in used:
                try:
                    idx = titles.index(hit[0], idx + 1)
                except ValueError:
                    idx = -1
                    break
            if idx < 0:
                skipped += 1
                continue
            used.add(idx)

            guid = episodes[idx][0]
            # sha1, not hash() — Python randomises hash() per process, so the same
            # episode would get a new filename on every run and push a second copy.
            remote_name = hashlib.sha1(guid.encode()).hexdigest()[:16] + f.suffix.lower()
            dest = f"{REMOTE}/{folder.name}/{remote_name}"
            if adb("push", f, dest, dry=args.dry_run) == 0:
                manifest.append(f"{url}\t{guid}\t{dest}")
                pushed += 1
                print(f"  ok  {f.name}  ->  {episodes[idx][1][:60]}")
            else:
                print(f"  FAIL push {f.name}")

    if not manifest:
        sys.exit("\nNothing matched. Try --threshold 0.5, or check --root points at the right place.")

    out = Path("sideload.tsv")
    out.write_text("\n".join(manifest) + "\n", encoding="utf-8")
    if not args.dry_run:
        adb("push", out, f"{REMOTE}/sideload.tsv")
    print(f"\n{pushed} pushed, {skipped} unmatched. Manifest at {out}")
    print('Now open Deadzone -> Settings -> "Adopt sideloaded files".')


if __name__ == "__main__":
    main()
