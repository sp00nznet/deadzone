package net.sp00nz.deadzone

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Playback lives in a service, not the activity, so audio keeps going with the screen
 * off and the lock screen / bluetooth buttons / Android Auto get real controls. This
 * is the whole reason media3-session is a dependency.
 */
class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
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
            // Podcast convention, not media convention: back a little, forward a lot.
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        session = MediaSession.Builder(this, player).build()
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
        super.onDestroy()
    }
}
