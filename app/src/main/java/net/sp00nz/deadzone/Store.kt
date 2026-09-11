package net.sp00nz.deadzone

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File

/**
 * Everything that persists: feeds, episodes, where the audio landed, and how far
 * through it you got.
 *
 * ponytail: raw SQLite, not Room. Room's whole value is compile-checked DAOs, and it
 * buys that with KSP codegen plus three artifacts. The thing this app actually needs
 * from the database — an external-content FTS index over 23k episode rows — is
 * a thing you write by hand in Room anyway.
 */

data class Feed(
    val id: Long,
    val url: String,
    val title: String,
    val image: String?,
    val category: String?,
    val keep: Int,          // auto-download the N newest; 0 = don't
    val fetched: Long,
    val total: Int = 0,
    val unplayed: Int = 0,
    val downloaded: Int = 0,
)

data class Episode(
    val id: Long,
    val feedId: Long,
    val feedTitle: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val published: Long,
    val duration: Int,
    val size: Long,
    val file: String?,      // absolute path once downloaded
    val position: Long,     // resume point, millis
    val playedAt: Long,     // 0 = never finished
    val image: String?,
    val chaptersUrl: String? = null,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
) {
    val downloaded get() = file != null

    /**
     * What the player should open: the local copy if we have it, else the network.
     * A bare filesystem path has no scheme and ExoPlayer will not guess one, so the
     * local case has to become a real file:// URI here.
     */
    val source: String get() = when {
        file == null -> audioUrl
        // A file we adopted in place is already a content:// URI and ExoPlayer takes
        // it as-is; one we downloaded is a bare path and needs a file:// scheme,
        // because ExoPlayer will not guess one.
        file.startsWith("content://") -> file
        else -> Uri.fromFile(File(file)).toString()
    }

    /** True for audio kept as a local show rather than fetched from a feed. */
    val isLocal get() = audioUrl.startsWith("local:")
}

enum class Filter { ALL, UNPLAYED, DOWNLOADED, IN_PROGRESS }

class Store(private val ctx: Context) : SQLiteOpenHelper(ctx, "deadzone.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE feed(
                 id INTEGER PRIMARY KEY,
                 url TEXT NOT NULL UNIQUE,
                 title TEXT NOT NULL,
                 image TEXT,
                 category TEXT,
                 keep INTEGER NOT NULL DEFAULT 0,
                 fetched INTEGER NOT NULL DEFAULT 0,
                 etag TEXT,
                 modified TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE episode(
                 id INTEGER PRIMARY KEY,
                 feed_id INTEGER NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
                 guid TEXT NOT NULL,
                 title TEXT NOT NULL,
                 description TEXT NOT NULL DEFAULT '',
                 audio_url TEXT NOT NULL,
                 published INTEGER NOT NULL DEFAULT 0,
                 duration INTEGER NOT NULL DEFAULT 0,
                 size INTEGER NOT NULL DEFAULT 0,
                 image TEXT,
                 chapters_url TEXT,
                 transcript_url TEXT,
                 transcript_type TEXT,
                 file TEXT,
                 position INTEGER NOT NULL DEFAULT 0,
                 played_at INTEGER NOT NULL DEFAULT 0,
                 queue_pos INTEGER,
                 UNIQUE(feed_id, guid))"""
        )
        // Scrolling a feed is always "newest first for one feed", and the Latest
        // screen is "newest first across all of them". Two indexes, both covering.
        db.execSQL("CREATE INDEX ep_feed_pub ON episode(feed_id, published DESC)")
        db.execSQL("CREATE INDEX ep_pub ON episode(published DESC)")
        db.execSQL("CREATE INDEX ep_played ON episode(played_at DESC) WHERE played_at > 0")

        // FTS4, not FTS5: Android's bundled SQLite does not compile in the fts5
        // module at all — "no such module: fts5" on a stock API 34 device. External
        // content still works here, so the index references episode rows rather than
        // storing a second copy of every show note. At 23k episodes that is the
        // difference between a database you can keep on a phone and one you can't.
        db.execSQL(
            "CREATE VIRTUAL TABLE ep_fts USING fts4(title, description, " +
                "content=\"episode\", tokenize=unicode61)"
        )
        db.execSQL(
            """CREATE TRIGGER ep_ai AFTER INSERT ON episode BEGIN
                 INSERT INTO ep_fts(docid, title, description)
                 VALUES (new.id, new.title, new.description);
               END"""
        )
        db.execSQL(
            """CREATE TRIGGER ep_ad AFTER DELETE ON episode BEGIN
                 DELETE FROM ep_fts WHERE docid = old.id;
               END"""
        )
        // Only on the text columns. A plain AFTER UPDATE would reindex on every
        // position save, which happens every few seconds while something is playing.
        db.execSQL(
            """CREATE TRIGGER ep_au AFTER UPDATE OF title, description ON episode BEGIN
                 DELETE FROM ep_fts WHERE docid = old.id;
                 INSERT INTO ep_fts(docid, title, description)
                 VALUES (new.id, new.title, new.description);
               END"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Additive only. Dropping the database would take every resume position and
        // every downloaded file's link with it, which is not a thing to do for a
        // couple of new columns.
        if (old < 2) {
            for (col in listOf("chapters_url", "transcript_url", "transcript_type")) {
                db.execSQL("ALTER TABLE episode ADD COLUMN $col TEXT")
            }
        }
    }

    // ---- feeds ----

    fun feeds(): List<Feed> = readableDatabase.rawQuery(
        """SELECT f.*,
                  (SELECT COUNT(*) FROM episode e WHERE e.feed_id = f.id) total,
                  (SELECT COUNT(*) FROM episode e WHERE e.feed_id = f.id AND e.played_at = 0) unplayed,
                  (SELECT COUNT(*) FROM episode e WHERE e.feed_id = f.id AND e.file IS NOT NULL) dl
             FROM feed f ORDER BY f.title COLLATE NOCASE""", null
    ).use { it.map(::feedOf) }

    fun feed(id: Long): Feed? = readableDatabase
        .rawQuery("SELECT *, 0 total, 0 unplayed, 0 dl FROM feed WHERE id = ?", arrayOf("$id"))
        .use { if (it.moveToFirst()) feedOf(it) else null }

    /** Returns the feed id whether it was new or already subscribed. */
    fun addFeed(url: String, title: String = url, category: String? = null): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM feed WHERE url = ?", arrayOf(url)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insert("feed", null, ContentValues().apply {
            put("url", url); put("title", title); put("category", category)
        })
    }

    fun removeFeed(id: Long) {
        // Take our own downloads with it, or they are orphaned on disk forever —
        // but never touch audio adopted in place, which is the user's own file.
        readableDatabase.rawQuery(
            "SELECT file FROM episode WHERE feed_id = ? AND file IS NOT NULL", arrayOf("$id")
        ).use { c -> while (c.moveToNext()) c.getString(0).let { if (owns(it)) File(it).delete() } }
        writableDatabase.delete("feed", "id = ?", arrayOf("$id"))
    }

    fun setKeep(id: Long, keep: Int) = update("feed", id, "keep" to keep)

    fun conditional(id: Long): Pair<String?, String?> = readableDatabase
        .rawQuery("SELECT etag, modified FROM feed WHERE id = ?", arrayOf("$id"))
        .use { if (it.moveToFirst()) it.str(0) to it.str(1) else null to null }

    /** Writes a refreshed feed in one transaction; returns how many episodes are new. */
    fun merge(feedId: Long, parsed: ParsedFeed, etag: String?, modified: String?): Int {
        val db = writableDatabase
        // ponytail: count before and after rather than inspecting each insert —
        // SQLiteDatabase.lastChangeCount() is API 30 and minSdk here is 26.
        val before = countIn(feedId)
        db.beginTransaction()
        try {
            db.update("feed", ContentValues().apply {
                if (parsed.title.isNotBlank()) put("title", parsed.title)
                parsed.image?.let { put("image", it) }
                parsed.category?.let { put("category", it) }
                put("fetched", System.currentTimeMillis())
                put("etag", etag); put("modified", modified)
            }, "id = ?", arrayOf("$feedId"))

            for (item in parsed.items) {
                // ON CONFLICT updates only the feed's own fields. file, position and
                // played_at are ours, and a re-publish must never reset them.
                db.execSQL(
                    """INSERT INTO episode
                         (feed_id, guid, title, description, audio_url, published,
                          duration, size, image, chapters_url, transcript_url, transcript_type)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(feed_id, guid) DO UPDATE SET
                         title = excluded.title, description = excluded.description,
                         audio_url = excluded.audio_url, published = excluded.published,
                         duration = excluded.duration, size = excluded.size,
                         image = COALESCE(excluded.image, episode.image),
                         chapters_url = excluded.chapters_url,
                         transcript_url = excluded.transcript_url,
                         transcript_type = excluded.transcript_type""",
                    arrayOf<Any?>(
                        feedId, item.guid, item.title, item.description, item.audioUrl,
                        item.published, item.duration, item.size, item.image,
                        item.chaptersUrl, item.transcriptUrl, item.transcriptType,
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return countIn(feedId) - before
    }

    private fun countIn(feedId: Long): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM episode WHERE feed_id = ?", arrayOf("$feedId"))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- episodes ----

    fun episodes(feedId: Long?, filter: Filter, limit: Int = 500, offset: Int = 0): List<Episode> {
        val where = buildList {
            if (feedId != null) add("e.feed_id = $feedId")
            when (filter) {
                Filter.ALL -> {}
                Filter.UNPLAYED -> add("e.played_at = 0")
                Filter.DOWNLOADED -> add("e.file IS NOT NULL")
                Filter.IN_PROGRESS -> add("e.position > 0 AND e.played_at = 0")
            }
        }.joinToString(" AND ").ifEmpty { "1" }
        return readableDatabase.rawQuery(
            "$SELECT WHERE $where ORDER BY e.published DESC LIMIT ? OFFSET ?",
            arrayOf("$limit", "$offset")
        ).use { it.map(::episodeOf) }
    }

    fun episode(id: Long): Episode? = readableDatabase
        .rawQuery("$SELECT WHERE e.id = ?", arrayOf("$id"))
        .use { if (it.moveToFirst()) episodeOf(it) else null }

    /** Most recently finished first — the "you played this, on this date" list. */
    fun history(limit: Int = 300): List<Episode> = readableDatabase.rawQuery(
        "$SELECT WHERE e.played_at > 0 ORDER BY e.played_at DESC LIMIT ?", arrayOf("$limit")
    ).use { it.map(::episodeOf) }

    fun queue(): List<Episode> = readableDatabase
        .rawQuery("$SELECT WHERE e.queue_pos IS NOT NULL ORDER BY e.queue_pos", null)
        .use { it.map(::episodeOf) }

    fun search(q: String, limit: Int = 200): List<Episode> {
        val match = ftsQuery(q) ?: return emptyList()
        return readableDatabase.rawQuery(
            """SELECT e.*, f.title feed_title FROM ep_fts
                 JOIN episode e ON e.id = ep_fts.docid
                 JOIN feed f ON f.id = e.feed_id
                WHERE ep_fts MATCH ? ORDER BY e.published DESC LIMIT ?""",
            arrayOf(match, "$limit")
        ).use { it.map(::episodeOf) }
    }

    /**
     * The newest `keep` episodes per feed that aren't on disk yet — what to auto-fetch.
     *
     * "Is this row in the newest N of its feed" is counted, not limited: SQLite will
     * not resolve an outer column inside a subquery's LIMIT (`LIMIT f.keep` fails to
     * even parse), and window functions need SQLite 3.25, which minSdk 26 predates.
     * Counting the newer siblings works everywhere and rides the (feed_id, published)
     * index.
     */
    fun pendingDownloads(): List<Episode> = readableDatabase.rawQuery(
        """$SELECT WHERE e.file IS NULL AND f.keep > 0 AND e.played_at = 0
             AND (SELECT COUNT(*) FROM episode x
                   WHERE x.feed_id = e.feed_id AND x.published > e.published) < f.keep
           ORDER BY e.published DESC""", null
    ).use { it.map(::episodeOf) }

    fun setPosition(id: Long, ms: Long) = update("episode", id, "position" to ms)

    fun setPlayed(id: Long, played: Boolean) {
        writableDatabase.execSQL(
            "UPDATE episode SET played_at = ?, position = 0, queue_pos = NULL WHERE id = ?",
            arrayOf(if (played) System.currentTimeMillis() else 0L, id)
        )
    }

    fun enqueue(id: Long) = writableDatabase.execSQL(
        "UPDATE episode SET queue_pos = COALESCE((SELECT MAX(queue_pos) FROM episode), 0) + 1 " +
            "WHERE id = ? AND queue_pos IS NULL", arrayOf(id)
    )

    fun dequeue(id: Long) = update("episode", id, "queue_pos" to null)

    // ---- files ----

    /** Where an episode's audio lives. One directory per feed, so it stays browsable. */
    fun fileFor(e: Episode): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, safe(e.feedTitle))
        dir.mkdirs()
        val ext = e.audioUrl.substringBefore('?').substringAfterLast('.', "mp3").take(4)
        return File(dir, "${e.id}-${safe(e.title).take(60)}.$ext")
    }

    fun setFile(id: Long, path: String?) = update("episode", id, "file" to path)

    fun deleteFile(id: Long) {
        episode(id)?.file?.let { if (owns(it)) File(it).delete() }
        setFile(id, null)
    }

    /**
     * Whether the app put this file where it is, and may therefore remove it.
     *
     * Audio adopted in place belongs to the user — it is their Music folder, not our
     * cache. Deleting it because an episode was tidied up would destroy files the app
     * never owned, so every delete path checks this first.
     */
    private fun owns(path: String): Boolean {
        if (path.startsWith("content://")) return false
        val root = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
        return File(path).absolutePath.startsWith(root)
    }

    /** Reclaims space: finished episodes whose audio has been sitting around too long. */
    fun sweep(olderThanDays: Int): Int {
        if (olderThanDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        var n = 0
        // Only our own downloads expire. A local show is the user's library, not a
        // cache, and quietly unlinking it after 30 days would be a data loss.
        readableDatabase.rawQuery(
            "SELECT id, file FROM episode WHERE file IS NOT NULL AND played_at > 0 " +
                "AND played_at < ?", arrayOf("$cutoff")
        ).use { c ->
            while (c.moveToNext()) if (owns(c.getString(1))) { deleteFile(c.getLong(0)); n++ }
        }
        return n
    }

    /**
     * Link audio that tools/sideload.py pushed over adb to the episodes it belongs to.
     * The manifest is feedUrl 	 guid 	 path, and the guid is what the feed itself
     * published — so a file adopted this way behaves exactly like a downloaded one.
     */
    fun adoptSideloaded(): Int {
        val dir = ctx.getExternalFilesDir(null) ?: return 0
        val manifest = File(dir, "sideload.tsv")
        if (!manifest.exists()) return 0
        var n = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            manifest.forEachLine { line ->
                val parts = line.split('	')
                if (parts.size != 3) return@forEachLine
                val (url, guid, path) = parts
                // A manifest entry whose file never made it across would otherwise
                // mark the episode downloaded and then fail to play.
                if (!File(path).exists()) return@forEachLine
                db.rawQuery(
                    "SELECT e.id FROM episode e JOIN feed f ON f.id = e.feed_id " +
                        "WHERE e.guid = ? AND f.url = ?", arrayOf(guid, url)
                ).use { c ->
                    if (c.moveToFirst()) {
                        db.execSQL(
                            "UPDATE episode SET file = ? WHERE id = ?",
                            arrayOf<Any?>(path, c.getLong(0))
                        )
                        n++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun bytesOnDisk(): Long = readableDatabase
        .rawQuery("SELECT COALESCE(SUM(size), 0) FROM episode WHERE file IS NOT NULL", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // ---- local shows ----

    /** A show that is not a feed: audio adopted from a folder. Never refreshed. */
    fun localFeed(name: String): Long =
        addFeed("$LOCAL$name", name, "On this phone")

    fun addLocalEpisode(
        feedId: Long,
        file: String,
        title: String,
        published: Long,
        duration: Int,
        size: Long,
    ) {
        // guid is the stored location, so re-importing the same folder updates the
        // row instead of stacking a second copy of every episode.
        writableDatabase.execSQL(
            """INSERT INTO episode
                 (feed_id, guid, title, description, audio_url, published, duration, size, file)
               VALUES (?,?,?,'',?,?,?,?,?)
               ON CONFLICT(feed_id, guid) DO UPDATE SET
                 title = excluded.title, duration = excluded.duration,
                 size = excluded.size, file = excluded.file""",
            arrayOf<Any?>(
                feedId, file, title, "$LOCAL$file", published, duration, size, file,
            )
        )
    }

    /** Where copied local audio goes, when it had to be copied at all. */
    fun localDir(show: String): File =
        File(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "local"), safe(show))
            .also { it.mkdirs() }

    // ---- folder import ----

    /**
     * Every episode we do not already have audio for, as (id, title).
     *
     * ponytail: matched against the whole library rather than per-feed. Working out
     * which feed a folder belongs to is a second guess that can also be wrong, and
     * the matcher's inverted index does not care about the extra rows. Episode titles
     * generic enough to collide across shows ("Episode 12") normalise to nothing and
     * are skipped rather than mismatched.
     */
    fun unmatchedEpisodes(): List<Candidate<Long>> = readableDatabase.rawQuery(
        "SELECT id, title FROM episode WHERE file IS NULL", null
    ).use { c ->
        val out = ArrayList<Candidate<Long>>(c.count)
        while (c.moveToNext()) out += Candidate(c.getString(1), c.getLong(0))
        out
    }

    // ---- statistics ----

    data class ShowTime(val title: String, val finished: Int, val seconds: Long)
    data class MonthTime(val month: String, val finished: Int, val seconds: Long)

    /**
     * played_at has been a real timestamp on every finished episode since v1, so this
     * is a query rather than a feature — nothing had to be recorded to enable it.
     */
    fun timeByShow(): List<ShowTime> = readableDatabase.rawQuery(
        """SELECT f.title, COUNT(*) n, COALESCE(SUM(e.duration), 0) secs
             FROM episode e JOIN feed f ON f.id = e.feed_id
            WHERE e.played_at > 0
            GROUP BY f.id ORDER BY secs DESC""", null
    ).use { c ->
        val out = ArrayList<ShowTime>()
        while (c.moveToNext()) out += ShowTime(c.getString(0), c.getInt(1), c.getLong(2))
        out
    }

    fun timeByMonth(limit: Int = 12): List<MonthTime> = readableDatabase.rawQuery(
        """SELECT strftime('%Y-%m', played_at / 1000, 'unixepoch') m,
                  COUNT(*) n, COALESCE(SUM(duration), 0) secs
             FROM episode WHERE played_at > 0
            GROUP BY m ORDER BY m DESC LIMIT ?""", arrayOf("$limit")
    ).use { c ->
        val out = ArrayList<MonthTime>()
        while (c.moveToNext()) out += MonthTime(c.getString(0), c.getInt(1), c.getLong(2))
        out
    }

    private fun update(table: String, id: Long, vararg cols: Pair<String, Any?>) {
        writableDatabase.update(table, ContentValues().apply {
            for ((k, v) in cols) when (v) {
                null -> putNull(k)
                is Int -> put(k, v)
                is Long -> put(k, v)
                else -> put(k, v.toString())
            }
        }, "id = ?", arrayOf("$id"))
    }
}

/**
 * MATCH is its own query language, so a typed apostrophe, dash or quote is a syntax
 * *error* rather than zero results. Splitting on everything that is not a letter or a
 * digit leaves only tokens that cannot mean anything to the parser, which is why no
 * escaping is needed after it. The trailing * makes it work as you type.
 */
fun ftsQuery(raw: String): String? {
    val tokens = raw.trim().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") + "*"
}

// COALESCE, not e.image: most feeds set artwork once on the channel and never per
// episode, so reading e.image alone leaves every row and the player with a
// placeholder. Doing it here fixes every screen at once, the car included.
const val LOCAL = "local:"

private const val SELECT =
    "SELECT e.*, COALESCE(e.image, f.image) image, f.title feed_title " +
        "FROM episode e JOIN feed f ON f.id = e.feed_id"

private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "podcast" }

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
private fun Cursor.str(name: String): String? = str(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

private fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += f(this)
    return out
}

private fun feedOf(c: Cursor) = Feed(
    id = c.long("id"), url = c.getString(c.getColumnIndexOrThrow("url")),
    title = c.getString(c.getColumnIndexOrThrow("title")), image = c.str("image"),
    category = c.str("category"), keep = c.int("keep"), fetched = c.long("fetched"),
    total = c.int("total"), unplayed = c.int("unplayed"), downloaded = c.int("dl"),
)

private fun episodeOf(c: Cursor) = Episode(
    id = c.long("id"), feedId = c.long("feed_id"),
    feedTitle = c.getString(c.getColumnIndexOrThrow("feed_title")),
    title = c.getString(c.getColumnIndexOrThrow("title")),
    description = c.getString(c.getColumnIndexOrThrow("description")),
    audioUrl = c.getString(c.getColumnIndexOrThrow("audio_url")),
    published = c.long("published"), duration = c.int("duration"), size = c.long("size"),
    file = c.str("file"), position = c.long("position"), playedAt = c.long("played_at"),
    image = c.str("image"), chaptersUrl = c.str("chapters_url"),
    transcriptUrl = c.str("transcript_url"), transcriptType = c.str("transcript_type"),
)
