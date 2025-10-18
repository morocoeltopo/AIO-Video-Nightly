package app.ui.others.media_player

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.Builder
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.PlayerNotificationManager.BitmapCallback
import androidx.media3.ui.PlayerNotificationManager.MediaDescriptionAdapter
import com.aio.R
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference

@UnstableApi
open class MediaPlaybackService() : MediaSessionService() {
	private val logger = LogHelperUtils.from(javaClass)
	private val safePlaybackService = WeakReference(this).get()
	private var mediaSession: MediaSession? = null
	private var mediaPlayer: ExoPlayer? = null
	private val notificationManager: PlayerNotificationManager? = null

	override fun onCreate() {
		super.onCreate()
		safePlaybackService?.let { playbackService ->
			mediaPlayer = ExoPlayer.Builder(playbackService).build()
			mediaPlayer?.let {
				mediaSession = Builder(playbackService, it).build()
				it.setHandleAudioBecomingNoisy(true)
				setupPlaybackNotification(playbackService)
				notificationManager?.setPlayer(mediaPlayer)
			}
		}
	}

	override fun onGetSession(controllerInfo: ControllerInfo): MediaSession? {
		return mediaSession
	}

	override fun onDestroy() {
		mediaSession?.let { session -> mediaPlayer?.release(); session.release() }
		mediaSession = null
		super.onDestroy()
	}

	private fun setupPlaybackNotification(mediaPlaybackService: MediaPlaybackService) {
		PlayerNotificationManager.Builder(
			/* context = */ mediaPlaybackService,
			/* notificationId = */ 11212,
			/* channelId = */ "Media_Playback_Channel"
		).setMediaDescriptionAdapter(object : MediaDescriptionAdapter {
			override fun getCurrentContentTitle(player: Player): CharSequence {
				return player.mediaMetadata.title ?: getString(R.string.title_now_playing)
			}

			override fun createCurrentContentIntent(player: Player): PendingIntent? {
				val intent = Intent(mediaPlaybackService, MediaPlayerActivity::class.java)
				return PendingIntent.getActivity(
					/* context = */ mediaPlaybackService,
					/* requestCode = */ 0,
					/* intent = */ intent,
					/* flags = */ FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
				)
			}

			override fun getCurrentContentText(player: Player): CharSequence? {
				return player.mediaMetadata.artist
			}

			override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? = null
		}).build()
	}
}