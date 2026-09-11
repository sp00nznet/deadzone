package net.sp00nz.deadzone

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { LIBRARY, LATEST, SEARCH, HISTORY }

/**
 * All the state and every action, in one place.
 *
 * ponytail: no DI, no repository layer, no navigation library. One view model, a
 * handful of `by mutableStateOf`, and back is a when-block. Octoquill's `Vm` shape,
 * because it held up.
 */
class Vm(app: Application) : AndroidViewModel(app) {

    val store = Store(app)
    val settings = Settings(app)

    var tab by mutableStateOf(Tab.LIBRARY)
    var openFeed by mutableStateOf<Feed?>(null)
    var openEpisode by mutableStateOf<Episode?>(null)
    var showPlayer by mutableStateOf(false)
    var showSettings by mutableStateOf(false)

    var feeds by mutableStateOf<List<Feed>>(emptyList())
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var filter by mutableStateOf(Filter.ALL)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Episode>>(emptyList())
    var history by mutableStateOf<List<Episode>>(emptyList())

    var syncing by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var downloading by mutableStateOf<Map<Long, Float>>(emptyMap())

    // Directory search, folder import, statistics, chapters, transcript.
    var findingShows by mutableStateOf(false)
    var discovered by mutableStateOf<List<Found>>(emptyList())
    var searchingDirectory by mutableStateOf(false)
    var importing by mutableStateOf<ImportProgress?>(null)
    var showStats by mutableStateOf(false)
    var byShow by mutableStateOf<List<Store.ShowTime>>(emptyList())
    var byMonth by mutableStateOf<List<Store.MonthTime>>(emptyList())
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
    var cues by mutableStateOf<List<Cue>>(emptyList())
    var showTranscript by mutableStateOf(false)

    var nowPlaying by mutableStateOf<Episode?>(null)
    var playing by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var sleepMinutes by mutableStateOf(0)

    private var controller: MediaController? = null

    init {
        val token = SessionToken(app, ComponentName(app, PlayerService::class.java))
        MediaController.Builder(app, token).buildAsync().also { future ->
            future.addListener({
                controller = future.get().apply {
                    setPlaybackSpeed(settings.speed)
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playing = isPlaying
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) finishCurrent()
                        }
                    })
                }
            }, MoreExecutors.directExecutor())
        }
        reload()
        SyncWorker.schedule(app, settings.wifiOnly)
        tick()
    }

    /** One second is fine for a scrubber; the DB write is throttled well below that. */
    private fun tick() = viewModelScope.launch {
        var sinceSave = 0
        while (true) {
            delay(1000)
            val c = controller ?: continue
            position = c.currentPosition
            duration = if (c.duration > 0) c.duration else (nowPlaying?.duration ?: 0) * 1000L
            val ep = nowPlaying
            if (c.isPlaying && ep != null && ++sinceSave >= 5) {
                sinceSave = 0
                withContext(Dispatchers.IO) { store.setPosition(ep.id, position) }
                if (duration > 0 && position >= duration * settings.completeAt) finishCurrent()
            }
        }
    }

    fun reload() = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) { store.feeds() }
        feeds = f
        val fid = openFeed?.id
        episodes = withContext(Dispatchers.IO) { store.episodes(fid, filter) }
        history = withContext(Dispatchers.IO) { store.history() }
        openFeed?.let { cur -> openFeed = f.firstOrNull { it.id == cur.id } ?: cur }
        // The player sheet renders from this value rather than re-reading, so
        // without refreshing it a finished download leaves the sheet still
        // offering "Download" while the row behind it already says "on device".
        nowPlaying?.let { cur ->
            nowPlaying = withContext(Dispatchers.IO) { store.episode(cur.id) } ?: cur
        }
    }

    // ---- feeds ----

    fun addFeed(url: String) = viewModelScope.launch {
        val clean = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        status = "Fetching…"
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val id = store.addFeed(clean)
                refresh(store, store.feed(id)!!)
            }
        }
        status = ok.fold({ "Added" }, { "Could not read that feed: ${it.message}" })
        reload()
    }

    fun removeFeed(feed: Feed) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.removeFeed(feed.id) }
        openFeed = null
        reload()
    }

    fun setKeep(feed: Feed, keep: Int) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.setKeep(feed.id, keep) }
        reload()
    }

    fun sync() = viewModelScope.launch {
        if (syncing) return@launch
        syncing = true
        status = "Refreshing ${feeds.size} feeds…"
        val r = withContext(Dispatchers.IO) { refreshAll(store) }
        syncing = false
        // A bare failure count is useless when something systemic is wrong, so say
        // what the first one actually said.
        status = when {
            r.failed.isNotEmpty() -> "${r.failed.size} of ${feeds.size} failed — ${r.failed.first()}"
            r.newEpisodes == 0 -> "Up to date"
            else -> "${r.newEpisodes} new episodes"
        }
        reload()
    }

    // ---- OPML ----

    /**
     * The URI comes from a file picker or another app's share sheet, so it can be
     * unreadable, revoked, or not OPML at all. None of those may take the app down.
     */
    fun importOpml(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val xml = app.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("could not open that file")
                parseOpml(xml).onEach { e -> store.addFeed(e.url, e.title, e.category) }.size
            }
        }
        status = r.fold(
            { if (it == 0) "No feeds in that file" else "Imported $it feeds — refreshing…" },
            { "Could not read that OPML: ${it.message}" },
        )
        reload()
        if (r.getOrDefault(0) > 0) sync()
    }

    fun exportOpml(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val xml = writeOpml(feeds.map { OpmlEntry(it.title, it.url, it.category) })
                app.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }
                    ?: error("could not write there")
            }
        }
        status = r.fold({ "Exported ${feeds.size} feeds" }, { "Export failed: ${it.message}" })
    }

    // ---- search ----

    fun search(q: String) = viewModelScope.launch {
        query = q
        results = if (q.isBlank()) emptyList()
        else withContext(Dispatchers.IO) { store.search(q) }
    }

    /** The iTunes directory, for feeds you do not already have the URL for. */
    fun discover(term: String) = viewModelScope.launch {
        query = term
        if (term.isBlank()) { discovered = emptyList(); return@launch }
        searchingDirectory = true
        discovered = runCatching { searchPodcasts(term) }.getOrDefault(emptyList())
        searchingDirectory = false
        if (discovered.isEmpty()) status = "Nothing in the directory for that"
    }

    fun subscribe(found: Found) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.addFeed(found.feedUrl, found.title) }
        status = "Added ${found.title}"
        reload()
        sync()
    }

    // ---- folder import ----

    /**
     * Adopt a folder of audio you already have — a mounted NFS or SMB share, a USB
     * drive, an SD card, anything the system file picker can reach.
     */
    fun importFolder(tree: Uri) = viewModelScope.launch {
        if (importing != null) return@launch
        // The settings sheet sits above the snackbar host, so anything reported while
        // it is open is painted underneath it and looks like nothing happened.
        showSettings = false
        importing = ImportProgress(0, 0, "Scanning…")
        val r = runCatching {
            importFolder(getApplication(), store, tree) { p -> importing = p }
        }
        importing = null
        status = r.fold(
            {
                val kept = it.matched + it.adopted
                when {
                    it.found == 0 -> "No audio files in that folder"
                    kept == 0 -> "Found ${it.found} files but could not read any of them"
                    else -> buildString {
                        append("Added $kept of ${it.found}")
                        if (it.matched > 0) append(" · ${it.matched} matched a feed")
                        if (it.adopted > 0) append(" · ${it.adopted} as local shows")
                        if (it.failed > 0) append(" · ${it.failed} failed")
                    }
                }
            },
            { "Import failed: ${it.message}" },
        )
        reload()
    }

    // ---- statistics ----

    fun openStats() = viewModelScope.launch {
        byShow = withContext(Dispatchers.IO) { store.timeByShow() }
        byMonth = withContext(Dispatchers.IO) { store.timeByMonth() }
        showStats = true
    }

    // ---- downloads ----

    fun download(e: Episode) = viewModelScope.launch {
        downloading = downloading + (e.id to 0f)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                downloadEpisode(store, e) { p -> downloading = downloading + (e.id to p) }
            }
        }
        downloading = downloading - e.id
        if (ok.isFailure) status = "Download failed: ${ok.exceptionOrNull()?.message}"
        reload()
    }

    fun adoptSideloaded() = viewModelScope.launch {
        // Same reason as importFolder: the result has to be visible.
        showSettings = false
        val n = withContext(Dispatchers.IO) { store.adoptSideloaded() }
        status = if (n == 0) "No sideload.tsv found — run tools/sideload.py first"
                 else "Adopted $n episodes already on the phone"
        reload()
    }

    fun deleteDownload(e: Episode) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.deleteFile(e.id) }
        reload()
    }

    // ---- playback ----

    fun play(e: Episode) {
        val c = controller ?: return
        nowPlaying = e
        c.setMediaItem(
            MediaItem.Builder()
                .setUri(e.source)
                .setMediaId(e.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.title)
                        .setArtist(e.feedTitle)
                        .setArtworkUri((e.image ?: feeds.firstOrNull { it.id == e.feedId }?.image)?.let(Uri::parse))
                        .build()
                )
                .build(),
            e.position,
        )
        c.playWhenReady = true
        c.prepare()
        showPlayer = true
        loadExtras(e)
    }

    /** Chapters and a transcript, if the feed published them. Neither is common. */
    private fun loadExtras(e: Episode) = viewModelScope.launch {
        chapters = emptyList(); cues = emptyList(); showTranscript = false
        e.chaptersUrl?.let { chapters = runCatching { fetchChapters(it) }.getOrDefault(emptyList()) }
        e.transcriptUrl?.let { cues = runCatching { fetchTranscript(it) }.getOrDefault(emptyList()) }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skip(deltaMs: Long) = controller?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }

    fun setSpeed(v: Float) {
        settings.speed = v
        controller?.setPlaybackSpeed(v)
    }

    fun markPlayed(e: Episode, played: Boolean = true) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.setPlayed(e.id, played) }
        reload()
    }

    /** Reached the end, or close enough. Mark it and take the next thing in the queue. */
    private fun finishCurrent() {
        val ep = nowPlaying ?: return
        markPlayed(ep)
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { store.queue().firstOrNull() }
            if (next != null) play(next) else { nowPlaying = null; showPlayer = false }
        }
    }

    fun enqueue(e: Episode) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.enqueue(e.id) }
        status = "Queued"
    }

    fun sleepTimer(minutes: Int) {
        sleepMinutes = minutes
        if (minutes <= 0) return
        viewModelScope.launch {
            val target = minutes
            delay(minutes * 60_000L)
            // Only fire if nobody changed the timer while we were asleep.
            if (sleepMinutes == target) {
                controller?.pause()
                sleepMinutes = 0
                status = "Sleep timer ended"
            }
        }
    }

    override fun onCleared() {
        controller?.release()
        store.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read once here, not inside setContent — a recomposition would re-import.
        val opml = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            val vm: Vm = viewModel()
            LaunchedEffect(opml) { opml?.let(vm::importOpml) }
            App(vm)
        }
    }
}
