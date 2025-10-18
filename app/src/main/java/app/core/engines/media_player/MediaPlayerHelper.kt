package app.core.engines.media_player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.core.AIOApp.Companion.INSTANCE
import lib.process.LogHelperUtils

/**
 * Helper class responsible for creating, configuring, and managing the Media3 ExoPlayer instance.
 *
 * Provides control functions to manage playback state (play, pause, seek, release, etc.),
 * ensuring a consistent and centralized handling of player lifecycle.
 */
@UnstableApi
class MediaPlayerHelper {

	private val logger = LogHelperUtils.from(javaClass)

	/** Lazily initialized ExoPlayer instance. */
	private val mediaPlayer by lazy { initializeMediaPlayer() }

	/** Lazily initialized track selector for managing available media tracks. */
	private val trackSelector by lazy { initPlayerTrackSelector() }

	/** Default seek interval for fast-forward and rewind (in milliseconds). */
	private val seekIntervalMs = 10_000L

	/**
	 * Initializes and configures ExoPlayer instance.
	 *
	 * @return A fully configured [ExoPlayer] instance ready for playback.
	 */
	fun initializeMediaPlayer(): ExoPlayer {
		val context = INSTANCE
		logger.d("Initializing ExoPlayer with FFmpeg extensions and custom configuration.")

		val renderersFactory = DefaultRenderersFactory(context)
			.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
			.forceEnableMediaCodecAsynchronousQueueing()
			.setEnableDecoderFallback(true)
			.setEnableAudioTrackPlaybackParams(true)

		val player = ExoPlayer.Builder(context, renderersFactory)
			.setTrackSelector(trackSelector)
			.setUseLazyPreparation(true)
			.setHandleAudioBecomingNoisy(true)
			.setSeekForwardIncrementMs(seekIntervalMs)
			.setSeekBackIncrementMs(seekIntervalMs)
			.build().apply {
				setForegroundMode(false)
				setSeekParameters(CLOSEST_SYNC)
				addListener(object : Player.Listener {
					override fun onPlaybackStateChanged(playbackState: Int) {
						logger.d("Player state changed: $playbackState")
					}

					override fun onPlayerError(error: PlaybackException) {
						logger.e("Player encountered an error: ${error.message}", error)
					}
				})
			}

		logger.d("ExoPlayer initialized successfully with seek interval: $seekIntervalMs ms")
		return player
	}

	/**
	 * Initializes the [DefaultTrackSelector] with a preferred audio language.
	 */
	fun initPlayerTrackSelector(): DefaultTrackSelector {
		val context = INSTANCE
		logger.d("Initializing DefaultTrackSelector with preferred audio language 'eng'.")
		return DefaultTrackSelector(context).apply {
			setParameters(buildUponParameters().setPreferredAudioLanguage("eng"))
		}.also {
			logger.d("TrackSelector initialized successfully.")
		}
	}

	/** Returns the current [ExoPlayer] instance. */
	fun getMediaPlayerInstance(): ExoPlayer = mediaPlayer

	/** Returns the [DefaultTrackSelector] instance. */
	fun getTrackSelector(): DefaultTrackSelector = trackSelector

	/** Starts or resumes playback. */
	fun play() {
		logger.d("Attempting to start playback.")
		mediaPlayer.playWhenReady = true
	}

	/** Pauses playback if active. */
	fun pause() {
		if (mediaPlayer.isPlaying) {
			logger.d("Pausing playback.")
			mediaPlayer.pause()
		} else {
			logger.d("Pause ignored — player not currently playing.")
		}
	}

	/** Stops playback and resets position to the start. */
	fun stop() {
		logger.d("Stopping playback and resetting position.")
		mediaPlayer.stop()
		mediaPlayer.seekTo(0)
	}

	/** Seeks forward by [seekIntervalMs]. */
	fun seekForward() {
		val newPosition = (mediaPlayer.currentPosition + seekIntervalMs)
			.coerceAtMost(mediaPlayer.duration)
		logger.d("Seeking forward to $newPosition ms.")
		mediaPlayer.seekTo(newPosition)
	}

	/** Seeks backward by [seekIntervalMs]. */
	fun seekBackward() {
		val newPosition = (mediaPlayer.currentPosition - seekIntervalMs)
			.coerceIn(0, mediaPlayer.duration)
		logger.d("Seeking backward to $newPosition ms.")
		mediaPlayer.seekTo(newPosition)
	}

	/** Seeks to a specific position in milliseconds. */
	fun seekTo(positionMs: Long) {
		logger.d("Seeking to position: $positionMs ms.")
		mediaPlayer.seekTo(positionMs)
	}

	/** Releases all player resources and cleans up memory. */
	fun release() {
		logger.d("Releasing ExoPlayer resources.")
		try {
			mediaPlayer.release()
			logger.i("ExoPlayer released successfully.")
		} catch (error: Exception) {
			logger.e("Error releasing ExoPlayer: ${error.message}", error)
		}
	}

	/** Returns true if the player is currently playing. */
	fun isPlaying(): Boolean {
		val playing = mediaPlayer.isPlaying
		logger.d("isPlaying() -> $playing")
		return playing
	}

	/** Returns current playback position in milliseconds. */
	fun getCurrentPosition(): Long {
		val position = mediaPlayer.currentPosition
		logger.d("Current playback position: $position ms")
		return position
	}

	/** Returns total media duration in milliseconds. */
	fun getDuration(): Long {
		val duration = mediaPlayer.duration
		logger.d("Media duration: $duration ms")
		return duration
	}
}