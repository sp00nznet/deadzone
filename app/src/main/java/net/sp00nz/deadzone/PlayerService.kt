package net.sp00nz.deadzone

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/**
 * Playback lives in a service, not the activity, so audio keeps going with the screen
 * off and the lock screen / bluetooth buttons get real controls.
 *
 * It is a MediaLibraryService rather than a plain MediaSessionService so that Android
 * Auto has something to browse. The session was most of that work already; what this
 * adds is a tree — Continue / Downloaded / Queue / Shows — served from the same
 * database every screen reads.
 */
class PlayerService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var store: Store

    // Browsing reads the database, and a car asks for a whole list at once. Doing
    // that on the caller's main thread is an ANR waiting for a big library.
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val player = ExoPlayer.Builder(this)
            // true = also take audio focus, so we duck for navigation and pause for calls.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            // Yanking the headphones out should stop the podcast, not broadcast it.
            .setHandleAudioBecomingNoisy(true)
            // Podcast convention, not music convention: back a little, forward a lot.
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        session = MediaLibrarySession.Builder(this, player, Tree()).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused should end it; while playing, keep going.
        val player = session?.player
        if (player == null || !player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        io.shutdown()
        store.close()
        super.onDestroy()
    }

    private inner class Tree : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Deadzone"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.submit<
            LibraryResult<ImmutableList<MediaItem>>
            >({
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    folder(CONTINUE, "Continue"),
                    folder(DOWNLOADED, "Downloaded"),
                    folder(QUEUE, "Queue"),
                    folder(SHOWS, "Shows"),
                )
                // Driving is exactly when you want the thing you were part way
                // through, so it is the first row rather than buried under a show.
                parentId == CONTINUE ->
                    store.episodes(null, Filter.IN_PROGRESS, limit = 50).map(::track)
                parentId == DOWNLOADED ->
                    store.episodes(null, Filter.DOWNLOADED, limit = 200).map(::track)
                parentId == QUEUE -> store.queue().map(::track)
                parentId == SHOWS -> store.feeds().map { folder("$SHOW/${it.id}", it.title, it.image) }
                parentId.startsWith("$SHOW/") -> {
                    val id = parentId.removePrefix("$SHOW/").toLongOrNull()
                    if (id == null) emptyList()
                    // Unplayed, because a car is not where you scroll a back catalogue.
                    else store.episodes(id, Filter.UNPLAYED, limit = 100).map(::track)
                }
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }, io)

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.submit<
            LibraryResult<MediaItem>
            >({
            val e = episodeOf(mediaId)
            if (e == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            else LibraryResult.ofItem(track(e), null)
        }, io)

        /**
         * Items handed back from the browse tree carry an id but the controller may
         * strip the URI, so anything about to be played is re-resolved here. This is
         * also where a resume position gets applied, which is why Auto picking up
         * mid-episode works at all.
         */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.submit<MutableList<MediaItem>>({
            mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item
                else episodeOf(item.mediaId)?.let(::track)
            }.toMutableList()
        }, io)

        private fun episodeOf(mediaId: String): Episode? =
            mediaId.removePrefix("$EPISODE/").toLongOrNull()?.let(store::episode)
    }

    private fun folder(id: String, title: String, art: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(art?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun track(e: Episode) = MediaItem.Builder()
        .setMediaId("$EPISODE/${e.id}")
        .setUri(e.source)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(e.title)
                .setArtist(e.feedTitle)
                .setArtworkUri(e.image?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build()
        )
        .build()

    private companion object {
        const val ROOT = "root"
        const val CONTINUE = "continue"
        const val DOWNLOADED = "downloaded"
        const val QUEUE = "queue"
        const val SHOWS = "shows"
        const val SHOW = "show"
        const val EPISODE = "ep"
    }
}
