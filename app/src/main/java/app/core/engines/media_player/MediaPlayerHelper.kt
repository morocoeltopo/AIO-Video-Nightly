package app.core.engines.media_player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.core.AIOApp.Companion.INSTANCE
import lib.process.LogHelperUtils

@UnstableApi
class MediaPlayerHelper {
	private val logger = LogHelperUtils.from(javaClass)
	private val mediaPlayer by lazy { initializeMediaPlayer() }
	private val trackSelector by lazy { initPlayerTrackSelector() }
	private val seekIntervalMs = 10000L

	fun initializeMediaPlayer(): ExoPlayer {
		val applicationContext = INSTANCE
		// Renderers factory with FFmpeg extension preference
		val renderersFactory = DefaultRenderersFactory(applicationContext)
			.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
			.forceEnableMediaCodecAsynchronousQueueing()
			.setEnableDecoderFallback(true)
			.setEnableAudioTrackPlaybackParams(true)

		// Build ExoPlayer with renderers factory
		val player = ExoPlayer.Builder(applicationContext, renderersFactory)
			.setTrackSelector(trackSelector)
			.setUseLazyPreparation(true)
			.setHandleAudioBecomingNoisy(true)
			.setSeekForwardIncrementMs(seekIntervalMs)
			.setSeekBackIncrementMs(seekIntervalMs)
			.build().apply {
				setForegroundMode(false)
				setSeekParameters(CLOSEST_SYNC)
			}

		return player
	}

	fun initPlayerTrackSelector(): DefaultTrackSelector {
		val applicationContext = INSTANCE
		return DefaultTrackSelector(applicationContext).apply {
			setParameters(buildUponParameters().setPreferredAudioLanguage("eng"))
		}
	}

	fun getMediaPlayerInstance(): ExoPlayer {
		return mediaPlayer
	}

	fun getTrackSelector(): DefaultTrackSelector {
		return trackSelector
	}
}