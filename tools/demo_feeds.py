#!/usr/bin/env python3
"""
Serve a library of invented podcasts, for screenshots and manual testing.

Screenshots of a podcast client are a copyright problem: real cover art, real
episode titles and real show notes all belong to somebody, and a repo is a
distribution. So the screenshots in docs/ are taken against this instead — the
shows, the artwork, the episode titles and every word of the show notes here are
made up, and the audio is silence.

    python tools/demo_feeds.py                 # serves on http://0.0.0.0:8765
    python tools/demo_feeds.py --port 9000

From an Android emulator the host is 10.0.2.2, so add feeds as:

    http://10.0.2.2:8765/feed/concrete-island.xml

`--print-feeds` lists every URL, one per line, for scripting.

ponytail: stdlib only. http.server serves, zlib writes the PNGs, struct writes the
WAV. A fixture that needs pip install is a fixture nobody runs.
"""

import argparse
import random
import struct
import sys
import zlib
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

# (slug, title, author, category, episodes, hue) — all invented.
SHOWS = [
    ("concrete-island", "Concrete Island", "Aperture Works", "Arts", 826, (196, 138, 58)),
    ("ledger-lines", "Ledger Lines", "Mainspring Audio", "Business", 355, (58, 122, 120)),
    ("small-claims", "Small Claims", "Third Rail", "Comedy", 300, (188, 82, 74)),
    ("foundry-road", "Foundry Road", "Mainspring Audio", "History", 462, (92, 84, 148)),
    ("sidereal", "Sidereal", "Longwave Collective", "Science", 669, (54, 96, 156)),
    ("kilowatt-hour", "Kilowatt Hour", "Aperture Works", "Science", 268, (176, 116, 44)),
    ("night-bus", "Night Bus", "Third Rail", "Society & Culture", 214, (150, 66, 108)),
    ("the-dry-season", "The Dry Season", "Longwave Collective", "Society & Culture", 96, (168, 104, 52)),
    ("packet-loss", "Packet Loss", "Halfmast Media", "Technology", 180, (62, 132, 96)),
    ("the-slow-lane", "The Slow Lane", "Halfmast Media", "Leisure", 143, (108, 110, 124)),
]

# Title fragments. Deliberately generic and invented — no real episode is in here.
OPENERS = [
    "The", "A", "Notes on the", "Against the", "Everything but the", "After the",
    "Before the", "Something About the", "Return of the", "The Last",
]
ADJECTIVES = [
    "Quiet", "Borrowed", "Unfinished", "Second", "Cheapest", "Longest", "Missing",
    "Accidental", "Reluctant", "Provisional", "Crooked", "Patient", "Loudest",
    "Half-Built", "Well-Lit", "Overdue", "Nearest", "Stubborn",
]
NOUNS = [
    "Bridge", "Ledger", "Harbour", "Foundry", "Signal", "Orchard", "Timetable",
    "Warehouse", "Junction", "Reservoir", "Switchboard", "Terminal", "Tollbooth",
    "Greenhouse", "Causeway", "Watchtower", "Bakery", "Substation", "Lighthouse",
    "Dry Dock", "Quarry", "Roundabout", "Freight Yard", "Aqueduct",
]
BARE = [
    "Everything Is Fine Here", "Two Weeks in the Annexe", "The Cost of Standing Still",
    "Nobody Reads the Minutes", "A Perfectly Ordinary Tuesday", "Held Over for Repairs",
    "The Committee Will Now Adjourn", "We Built It Twice", "One Road In, One Road Out",
    "The Part Nobody Films", "Closing Time at the Depot", "It Was Always Going to Rain",
]

# Original prose. Varied lengths so the show-notes pane looks like a real one.
NOTES = [
    "A small decision, made badly, that took thirty years to undo. We walk the route "
    "it left behind and talk to the people who still have to drive it every morning.",
    "Everyone agreed the plan was sensible. Everyone signed off. Nothing was built. "
    "This week: what happens in the gap between a decision and a thing existing.",
    "Part of a continuing series on infrastructure that outlived the reason it was "
    "built. Expect maps, expect arguments, expect at least one very long tunnel.",
    "We asked a simple question and got eleven different answers, all of them "
    "confident and four of them load-bearing. An episode about being sure.",
    "Recorded on location, badly, in high wind. Worth it. A conversation about what "
    "gets kept when there is no budget to keep anything.",
    "The shortest episode we have made, about the longest wait anyone we interviewed "
    "has had to endure. Some names have been changed.",
    "A follow-up to something we got wrong last year. We go back, we check, and we "
    "explain how the mistake got past four people who should have caught it.",
    "Two hours of tape cut down to thirty-five minutes, most of it one person "
    "explaining a thing they have explained a thousand times and still find funny.",
]


def rng(slug, n=0):
    return random.Random(f"{slug}:{n}")


def episode_title(slug, n):
    r = rng(slug, n)
    shape = r.random()
    if shape < 0.15:
        return r.choice(BARE)
    if shape < 0.30:
        # A two-parter, so the screenshots exercise the matcher's Part 1 / Part 2 case.
        return f"{r.choice(OPENERS)} {r.choice(NOUNS)} (Part {r.choice([1, 2])})"
    if shape < 0.6:
        return f"{r.choice(OPENERS)} {r.choice(ADJECTIVES)} {r.choice(NOUNS)}"
    return f"{r.choice(ADJECTIVES)} {r.choice(NOUNS)}"


def cover(rgb, seed):
    """A 600x600 PNG: flat ground, one offset block, one bar. No font needed."""
    w = h = 600
    r0, g0, b0 = rgb
    light = (min(r0 + 60, 255), min(g0 + 55, 255), min(b0 + 50, 255))
    dark = (max(r0 - 45, 0), max(g0 - 45, 0), max(b0 - 45, 0))
    rr = random.Random(seed)
    bx, by = rr.randint(60, 260), rr.randint(60, 260)
    bw, bh = rr.randint(150, 280), rr.randint(150, 280)
    bar_y = rr.randint(420, 500)

    rows = []
    for y in range(h):
        row = bytearray()
        in_bar = bar_y <= y < bar_y + 46
        for x in range(w):
            if in_bar and 60 <= x < w - 60:
                c = dark
            elif bx <= x < bx + bw and by <= y < by + bh:
                c = light
            else:
                c = rgb
            row += bytes(c)
        rows.append(bytes(row))

    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def silence(seconds, rate=8000):
    """A valid 8-bit mono WAV of nothing. Real enough for a player to time and scrub."""
    n = int(seconds * rate)
    header = (
        b"RIFF" + struct.pack("<I", 36 + n) + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate, 1, 8)
        + b"data" + struct.pack("<I", n)
    )
    return header + b"\x80" * n


def feed(slug, title, author, category, count, base):
    """An RSS 2.0 feed with the iTunes and podcast-namespace tags the app reads."""
    now = datetime.now(timezone.utc)
    items = []
    for n in range(count, 0, -1):
        r = rng(slug, n)
        published = now - timedelta(days=(count - n) * 7, hours=r.randint(0, 20))
        duration = r.randint(9, 96) * 60 + r.randint(0, 59)
        note = NOTES[n % len(NOTES)]
        # A couple of shows publish chapters and a transcript, so those screens
        # have something to show.
        extra = ""
        if slug in ("packet-loss", "sidereal") and n % 3 == 0:
            extra = (
                f'<podcast:chapters url="{base}/chapters/{slug}/{n}.json" '
                f'type="application/json+chapters"/>'
                f'<podcast:transcript url="{base}/transcript/{slug}/{n}.vtt" type="text/vtt"/>'
            )
        items.append(
            f"""  <item>
    <title>{escape(f'{n}: {episode_title(slug, n)}')}</title>
    <guid isPermaLink="false">{slug}-{n:05d}</guid>
    <pubDate>{published.strftime('%a, %d %b %Y %H:%M:%S +0000')}</pubDate>
    <itunes:duration>{duration // 3600}:{duration % 3600 // 60:02d}:{duration % 60:02d}</itunes:duration>
    <description>{escape(note)}</description>
    <enclosure url="{base}/audio/{slug}/{n}/{duration}.wav" length="{8000 * duration}" type="audio/wav"/>
    {extra}
  </item>"""
        )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"
     xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:podcast="https://podcastindex.org/namespace/1.0">
<channel>
  <title>{escape(title)}</title>
  <itunes:author>{escape(author)}</itunes:author>
  <itunes:category text="{escape(category)}"/>
  <itunes:image href="{base}/art/{slug}.png"/>
  <description>{escape(f'{title} is not a real podcast. It exists so that screenshots of this app contain nobody else’s work.')}</description>
{chr(10).join(items)}
</channel>
</rss>
"""


def chapters(slug, n):
    r = rng(slug, n)
    out, t = [], 0
    for i in range(r.randint(4, 7)):
        out.append(f'{{"startTime":{t},"title":"{escape(episode_title(slug, n * 100 + i))}"}}')
        t += r.randint(240, 900)
    return '{"version":"1.2.0","chapters":[' + ",".join(out) + "]}"


def transcript(slug, n):
    r = rng(slug, n)
    lines = ["WEBVTT", ""]
    t = 0
    for i in range(24):
        start, end = t, t + r.randint(3, 9)
        fmt = lambda s: f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}.000"
        lines += [f"{fmt(start)} --> {fmt(end)}", r.choice(NOTES).split(". ")[0] + ".", ""]
        t = end
    return "\n".join(lines)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        base = f"http://{self.headers.get('Host', 'localhost')}"
        path = self.path.split("?")[0]
        try:
            if path.startswith("/feed/"):
                slug = path[len("/feed/"):].removesuffix(".xml")
                s = next(x for x in SHOWS if x[0] == slug)
                self.send(feed(s[0], s[1], s[2], s[3], s[4], base).encode(), "application/rss+xml")
            elif path.startswith("/art/"):
                slug = path[len("/art/"):].removesuffix(".png")
                s = next(x for x in SHOWS if x[0] == slug)
                self.send(cover(s[5], slug), "image/png")
            elif path.startswith("/audio/"):
                # .../<slug>/<n>/<seconds>.wav — the length is in the URL so the file
                # really is as long as the feed says it is.
                secs = int(path.removesuffix(".wav").rsplit("/", 1)[1])
                self.send(silence(min(secs, 7200)), "audio/wav")
            elif path.startswith("/chapters/"):
                slug, n = path[len("/chapters/"):].removesuffix(".json").split("/")
                self.send(chapters(slug, int(n)).encode(), "application/json")
            elif path.startswith("/transcript/"):
                slug, n = path[len("/transcript/"):].removesuffix(".vtt").split("/")
                self.send(transcript(slug, int(n)).encode(), "text/vtt")
            elif path == "/":
                body = "\n".join(f"{base}/feed/{s[0]}.xml" for s in SHOWS)
                self.send(body.encode(), "text/plain")
            else:
                self.send_error(404)
        except StopIteration:
            self.send_error(404)

    def send(self, body, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        # Every request regenerates, so a cached 304 would hide edits to this file.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass  # one line per episode request is not useful


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--print-feeds", action="store_true",
                    help="print the emulator-facing feed URLs and exit")
    args = ap.parse_args()

    if args.print_feeds:
        for s in SHOWS:
            print(f"http://10.0.2.2:{args.port}/feed/{s[0]}.xml")
        return

    total = sum(s[4] for s in SHOWS)
    print(f"{len(SHOWS)} invented shows, {total} episodes, on port {args.port}")
    print(f"From an Android emulator: http://10.0.2.2:{args.port}/feed/<slug>.xml")
    try:
        ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
