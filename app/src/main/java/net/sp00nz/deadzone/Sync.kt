package net.sp00nz.deadzone

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Getting bytes off the internet and onto the phone. */

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    // Feeds and enclosures route through tracking prefixes that bounce between
    // schemes; without this a redirect from podtrac just fails.
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("deadzone", Context.MODE_PRIVATE)
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", true)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var autoDeleteDays: Int
        get() = p.getInt("autoDeleteDays", 30)
        set(v) = p.edit().putInt("autoDeleteDays", v).apply()
    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) = p.edit().putFloat("speed", v).apply()
    /** Reaching the end is rarely exact; anything past this counts as finished. */
    var completeAt: Float
        get() = p.getFloat("completeAt", 0.95f)
        set(v) = p.edit().putFloat("completeAt", v).apply()
}

class SyncResult(val newEpisodes: Int, val failed: List<String>)

/**
 * Refresh one feed. Sends the etag we were given last time, so an unchanged feed
 * costs a 304 and nothing else — which is the difference between a cheap hourly
 * refresh across 38 feeds and an expensive one.
 */
suspend fun refresh(store: Store, feed: Feed): Int = withContext(Dispatchers.IO) {
    val (etag, modified) = store.conditional(feed.id)
    val req = Request.Builder().url(feed.url)
        .header("User-Agent", UA)
        .apply {
            etag?.let { header("If-None-Match", it) }
            modified?.let { header("If-Modified-Since", it) }
        }
        .build()

    http.newCall(req).execute().use { res ->
        if (res.code == 304) return@withContext 0
        if (!res.isSuccessful) error("HTTP ${res.code}")
        val body = res.body?.string().orEmpty()
        if (body.isBlank()) error("empty feed")
        store.merge(
            feed.id, parseFeed(body),
            res.header("ETag"), res.header("Last-Modified"),
        )
    }
}

suspend fun refreshAll(store: Store): SyncResult {
    var added = 0
    val failed = mutableListOf<String>()
    for (f in store.feeds()) {
        try {
            added += refresh(store, f)
        } catch (e: Exception) {
            // One dead feed must not stop the other 37 from updating.
            failed += "${f.title}: ${e.message}"
        }
    }
    return SyncResult(added, failed)
}

/**
 * Download an episode, resuming a partial file if one is there. Episodes are often
 * 80MB+ and phone connections drop; starting from zero every time is how you never
 * finish one on a train.
 */
suspend fun downloadEpisode(
    store: Store,
    episode: Episode,
    onProgress: (Float) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    val target = store.fileFor(episode)
    val part = File(target.path + ".part")
    val have = if (part.exists()) part.length() else 0L

    val req = Request.Builder().url(episode.audioUrl)
        .header("User-Agent", UA)
        .apply { if (have > 0) header("Range", "bytes=$have-") }
        .build()

    http.newCall(req).execute().use { res ->
        if (!res.isSuccessful) error("HTTP ${res.code}")
        val body = res.body ?: error("no body")
        // 206 means it honoured the Range and we append. Anything else (including a
        // 200 from a server that ignored it) means we are getting the whole file
        // again, so the partial has to go or we'd splice a duplicate prefix in.
        val append = res.code == 206
        if (!append && part.exists()) part.delete()

        val total = body.contentLength().let { if (it > 0) it + (if (append) have else 0) else episode.size }
        var written = if (append) have else 0L

        java.io.FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (total > 0) onProgress(written.toFloat() / total)
                }
            }
        }
    }

    // Only now is it a playable file. Renaming last means a killed download can never
    // leave a truncated episode that looks complete.
    if (target.exists()) target.delete()
    check(part.renameTo(target)) { "could not finalise ${target.name}" }
    store.setFile(episode.id, target.path)
    target
}

/** One result from a podcast directory search. */
data class Found(
    val title: String,
    val author: String,
    val feedUrl: String,
    val image: String?,
    val episodes: Int,
)

/**
 * Search the iTunes directory.
 *
 * ponytail: iTunes, not Podcast Index. Both are free and return the same feeds;
 * Podcast Index wants a signed API key per request, and a discovery box is not worth
 * making somebody register for. No key, no account, no secret to keep out of the repo.
 */
suspend fun searchPodcasts(term: String): List<Found> = withContext(Dispatchers.IO) {
    if (term.isBlank()) return@withContext emptyList()
    val url = "https://itunes.apple.com/search?media=podcast&limit=25&term=" +
        java.net.URLEncoder.encode(term.trim(), "UTF-8")
    val body = get(url) ?: return@withContext emptyList()
    val results = org.json.JSONObject(body).optJSONArray("results")
        ?: return@withContext emptyList()
    (0 until results.length()).mapNotNull { i ->
        val o = results.optJSONObject(i) ?: return@mapNotNull null
        val feed = o.optString("feedUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Found(
            title = o.optString("collectionName").ifBlank { feed },
            author = o.optString("artistName"),
            feedUrl = feed,
            image = o.optString("artworkUrl600").takeIf { it.isNotBlank() }
                ?: o.optString("artworkUrl100").takeIf { it.isNotBlank() },
            episodes = o.optInt("trackCount"),
        )
    }
}

data class Chapter(val startMs: Long, val title: String, val image: String?)

/** The podcast-namespace chapters document: a JSON array of start times and titles. */
suspend fun fetchChapters(url: String): List<Chapter> = withContext(Dispatchers.IO) {
    val body = get(url) ?: return@withContext emptyList()
    val arr = org.json.JSONObject(body).optJSONArray("chapters")
        ?: return@withContext emptyList()
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title").ifBlank { return@mapNotNull null }
        Chapter(
            // startTime is seconds, and fractional in plenty of feeds.
            startMs = (o.optDouble("startTime", 0.0) * 1000).toLong(),
            title = title,
            image = o.optString("img").takeIf { it.isNotBlank() },
        )
    }
}

suspend fun fetchTranscript(url: String): List<Cue> = withContext(Dispatchers.IO) {
    parseCues(get(url).orEmpty())
}

/** A plain GET that returns null rather than throwing — all three callers are optional extras. */
private fun get(url: String): String? = runCatching {
    http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
        .execute().use { if (it.isSuccessful) it.body?.string() else null }
}.getOrNull()

private const val UA = "Deadzone/0.1 (+https://github.com/sp00nznet/deadzone)"

// ---- background ----

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val settings = Settings(applicationContext)
        return try {
            refreshAll(store)
            for (e in store.pendingDownloads()) {
                try {
                    downloadEpisode(store, e)
                } catch (_: Exception) {
                    // The next run picks it up, resuming from the .part file.
                }
            }
            store.sweep(settings.autoDeleteDays)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            store.close()
        }
    }

    companion object {
        fun schedule(ctx: Context, wifiOnly: Boolean) {
            val work = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
            // REPLACE, so flipping the wifi-only switch actually re-applies the constraint.
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.UPDATE, work
            )
        }
    }
}
