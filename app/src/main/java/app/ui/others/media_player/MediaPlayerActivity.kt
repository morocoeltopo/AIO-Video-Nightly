package app.ui.others.media_player

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Bitmap
import android.graphics.BitmapFactory.decodeByteArray
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod.getInstance
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_IMMERSIVE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getColor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.Builder
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.settings.AIOSettings
import app.ui.others.media_player.dialogs.MediaInfoHtmlBuilder.buildMediaInfoHtmlString
import app.ui.others.media_player.dialogs.MediaOptionsPopup
import com.aio.R
import com.aio.R.color
import com.aio.R.drawable
import com.aio.R.id
import com.aio.R.layout
import com.aio.R.string
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory.fromRawRes
import com.anggrayudi.storage.file.FileFullPath
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.common.io.Files.getFileExtension
import lib.device.SecureFileUtil.authenticate
import lib.device.ShareUtility
import lib.device.ShareUtility.shareMediaFile
import lib.files.FileSystemUtility.getFileFromUri
import lib.files.FileSystemUtility.isAudio
import lib.files.FileSystemUtility.isVideo
import lib.networks.URLUtility.isValidURL
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.RoundedTimeBar
import lib.ui.ViewUtility
import lib.ui.ViewUtility.blurBitmap
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.matchHeightToTopCutout
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setTextColorKT
import lib.ui.ViewUtility.showView
import lib.ui.ViewUtility.toggleViewVisibility
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@UnstableApi
class MediaPlayerActivity : BaseActivity(), AIOTimerListener, Listener {

	private val logger = LogHelperUtils.from(javaClass)
	private val selfActivityRef = WeakReference(this).get()

	companion object {
		const val INTENT_EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"
		const val INTENT_EXTRA_STREAM_URL = "INTENT_EXTRA_STREAM_URL"
		const val INTENT_EXTRA_STREAM_TITLE = "INTENT_EXTRA_STREAM_TITLE"
		const val INTENT_EXTRA_MEDIA_FILE_PATH = "INTENT_EXTRA_MEDIA_FILE_PATH"
		const val INTENT_EXTRA_SOURCE_ORIGIN = "INTENT_EXTRA_SOURCE_ORIGIN"

		const val SOURCE_FINISHED_DOWNLOADS = 1
		const val SOURCE_PRIVATE_FOLDER = 2
	}

	lateinit var player: ExoPlayer
	lateinit var playerView: PlayerView
	lateinit var trackSelector: DefaultTrackSelector

	lateinit var optionsPopup: MediaOptionsPopup
	lateinit var quickInfoText: TextView
	lateinit var overlayTouchArea: View
	lateinit var nightModeOverlay: View

	lateinit var cutoutPaddingView: View
	lateinit var albumArtView: ImageView
	lateinit var audioVisualizerView: LottieAnimationView
	lateinit var playbackController: View
	lateinit var backButton: View
	lateinit var videoTitleText: TextView
	lateinit var optionsButton: View

	lateinit var currentTimeText: TextView
	lateinit var durationText: TextView
	lateinit var progressBar: RoundedTimeBar

	lateinit var lockButton: View
	lateinit var prevButton: View
	lateinit var playPauseButton: View
	lateinit var nextButton: View
	lateinit var shareButton: View
	lateinit var unlockButton: View

	var areControllersLocked = false
	var playbackPosition: Long = 0L
	var isNightModeEnabled = false

	var invisibleAreaClickCount = 0
	val seekIntervalMs = 10000L

	val quickInfoHandler = Handler(Looper.getMainLooper())
	var quickInfoRunnable: Runnable? = null
	var isPrivateSessionAllowed: Boolean = false
	var hasMadeDecisionOverPrivateAccess: Boolean = false

	override fun onRenderingLayout(): Int {
		setDarkSystemStatusBar()
		setEdgeToEdgeFullscreen()
		applyAutoRotateSetting(enableAutoRotate = true)
		return layout.activity_player_1
	}

	override fun onAfterLayoutRender() {
		initializeActivityViews().let {
			initializePlayer().let {
				setupSwipeSeekGestures(targetView = overlayTouchArea)
				playVideoFromIntent().let {
					hideView(nightModeOverlay, true, 1000)
				}
			}
		}
	}

	override fun onBackPressActivity() {
		if (areControllersLocked) {
			doSomeVibration(20)
			val quickInfoText = getString(string.title_player_is_locked_unlock_first)
			showQuickPlayerInfo(quickInfoText); return
		}; stopAndReleasePlayer()

		hideView(playerView, true, 300).let {
			delay(200, object : OnTaskFinishListener {
				override fun afterDelay() {
					closeActivityWithFadeAnimation(true)
				}
			})
		}
	}

	/**
	 * Resumes player and loads ads when activity resumes.
	 */
	override fun onResumeActivity() {
		resumePlayer()
	}

	/**
	 * Pauses player when activity pauses.
	 */
	override fun onPauseActivity() = pausePlayer()

	/**
	 * Cleans up player resources when activity is destroyed.
	 */
	override fun onDestroy() {
		super.onDestroy()
		stopAndReleasePlayer()
	}

	/**
	 * Application wide timer loop used for refreshing the progress container.
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		updateVideoProgressContainer()
	}

	/**
	 * Handles player state changes.
	 * @param playbackState The new playback state
	 */
	override fun onPlaybackStateChanged(playbackState: Int) {
		if (playbackState == Player.STATE_ENDED) handlePlaybackCompletion()
		else takeIf { isPlayerBufferingOrGettingReady(playbackState) }
			?.let { updateVideoProgressContainer() }
	}

	/**
	 * Checks if player is buffering or ready to play.
	 * @param playbackState Current playback state
	 * @return True if player is buffering or ready
	 */
	private fun isPlayerBufferingOrGettingReady(playbackState: Int): Boolean {
		return playbackState == STATE_READY || playbackState == STATE_BUFFERING
	}

	/**
	 * Handles configuration changes (e.g., orientation changes).
	 * @param newConfig The new configuration
	 */
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		if (newConfig.orientation == ORIENTATION_PORTRAIT) handlePortraitMode()
		else if (newConfig.orientation == ORIENTATION_LANDSCAPE) handleLandscapeMode()
	}

	/**
	 * Handles player errors.
	 * @param error The playback error
	 */
	override fun onPlayerError(error: PlaybackException) {
		logger.e("onPlayerError:", error)
		showMessageDialog(
			baseActivityInf = selfActivityRef,
			titleText = getString(string.title_couldnt_play_media),
			isTitleVisible = true,
			isCancelable = true,
			isNegativeButtonVisible = false,
			titleTextViewCustomize = {
				it.setTextColor(
					resources.getColor(
						color.color_error,
						theme
					)
				)
			},
			messageTextViewCustomize = { it.setText(string.text_error_media_cant_be_played) },
			positiveButtonTextCustomize = {
				it.text = getString(string.title_exit_the_player)
				it.setLeftSideDrawable(drawable.ic_button_exit)
			}, negativeButtonText = getString(string.title_delete_file),
			negativeButtonTextCustomize = { it.setLeftSideDrawable(drawable.ic_button_delete) }
		)?.let { dialogBuilder ->
			dialogBuilder.setOnClickForNegativeButton { dialogBuilder.close(); deleteMediaFile() }
			dialogBuilder.setOnClickForPositiveButton { dialogBuilder.close(); closeActivityWithFadeAnimation() }
		}
	}

	/**
	 * Checks if currently playing a streaming video.
	 * @return True if streaming video is playing
	 */
	fun isStreamingVideoPlaying(): Boolean {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			intent.getParcelableExtra(INTENT_EXTRA_STREAM_URL, Uri::class.java)?.let { return true }
		else intent.getStringExtra(INTENT_EXTRA_STREAM_URL)?.let { if (isValidURL(it)) return true }
		return false
	}

	/**
	 * Handles play/pause state changes.
	 * @param isPlaying True if player is currently playing
	 */
	override fun onIsPlayingChanged(isPlaying: Boolean) {
		selfActivityRef?.let { safeActivityRef ->
			let {
				if (isPlaying) aioTimer.register(safeActivityRef)
				else aioTimer.unregister(safeActivityRef)
			}.apply {
				val incomingVideoTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
				if (incomingVideoTitle.isNullOrEmpty()) refreshCurrentVideoTitle()
				updateIconOfVideoPlayPauseButton(isPlaying)
			}
		}
	}

	/**
	 * Sets dark theme for system bars.
	 */
	fun setDarkSystemStatusBar() {
		setSystemBarsColors(
			statusBarColorResId = color.color_pure_black,
			navigationBarColorResId = color.color_pure_black,
			isLightStatusBar = false,
			isLightNavigationBar = false
		)
	}

	/**
	 * Handles landscape mode changes.
	 */
	fun handleLandscapeMode() {
		cutoutPaddingView.visibility = GONE
		delay(timeInMile = 400, listener = object : OnTaskFinishListener {
			override fun afterDelay() = hideEntirePlaybackControllers()
		})
	}

	/**
	 * Handles portrait mode changes.
	 */
	fun handlePortraitMode() {
		cutoutPaddingView.visibility = VISIBLE
		delay(timeInMile = 400, listener = object : OnTaskFinishListener {
			override fun afterDelay() = hideEntirePlaybackControllers()
		})
	}

	fun showQuickPlayerInfo(msgText: String) {
		quickInfoText.apply { visibility = VISIBLE; text = msgText }
		quickInfoRunnable?.let { quickInfoHandler.removeCallbacks(it) }
		quickInfoRunnable = Runnable { quickInfoText.apply { visibility = GONE; text = "" } }
		quickInfoHandler.postDelayed(quickInfoRunnable!!, 1500)
	}

	private fun initializeActivityViews() {
		selfActivityRef?.let { _ ->
			// Initialize ExoPlayer view and other UI components
			playerView = findViewById(id.video_player_view)
			quickInfoText = findViewById(id.txt_video_quick_info)
			nightModeOverlay = findViewById(id.night_mode_invisible_area)
			nightModeOverlay.visibility = GONE
			overlayTouchArea = findViewById(id.invisible_touch_area)

			// Set up device cutout handling
			cutoutPaddingView = findViewById(id.device_cutout_padding_view)
			cutoutPaddingView.matchHeightToTopCutout()

			// Initialize playback controls
			playbackController = findViewById(id.container_player_controller)
			playbackController.visibility = GONE

			// Initialize album art & visualizer holder for audio files
			albumArtView = findViewById(id.img_audio_album_art)
			audioVisualizerView = findViewById(id.anim_audio_visualizing)
			fromRawRes(INSTANCE, R.raw.animation_audio_visualizing_v1)
				.addListener { audioVisualizerView.setComposition(it) }

			// Set up action bar buttons
			backButton = findViewById(id.btn_actionbar_back)
			backButton.apply { setOnClickListener { onBackPressActivity() } }

			videoTitleText = findViewById(id.txt_video_file_name)
			videoTitleText.apply {
				isSelected = true; text = extractDownloadModelFromIntent()?.fileName ?: ""
			}

			optionsButton = findViewById(id.btn_actionbar_option)
			optionsButton.apply { setOnClickListener { showOptionMenuPopup() } }

			// Initialize progress display
			currentTimeText = findViewById(id.txt_video_progress_timer)
			progressBar = findViewById(id.video_progress_bar)
			progressBar.apply { addListener(createScrubberSeekListener()) }

			durationText = findViewById(id.txt_video_duration)

			// Initialize control buttons
			lockButton = findViewById(id.btn_video_controllers_lock)
			lockButton.apply { setOnClickListener { lockEntirePlaybackControllers() } }

			prevButton = findViewById(id.btn_video_previous)
			prevButton.apply { setOnClickListener { playPreviousMedia() } }

			playPauseButton = findViewById(id.btn_video_play_pause_toggle)
			playPauseButton.apply { setOnClickListener { toggleVideoPlayback() } }

			nextButton = findViewById(id.btn_video_next)
			nextButton.apply { setOnClickListener { playNextMedia() } }

			shareButton = findViewById(id.btn_video_file_share)
			shareButton.apply { setOnClickListener { shareMediaFile() } }

			unlockButton = findViewById(id.btn_video_unlock_overlay)
			unlockButton.apply { setOnClickListener { unlockEntirePlaybackControllers() } }
		}
	}

	private fun initializePlayer() {
		selfActivityRef?.let { activityRef ->
			// Renderers factory with FFmpeg extension preference
			val renderersFactory = DefaultRenderersFactory(activityRef)
				.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
				.forceEnableMediaCodecAsynchronousQueueing()
				.setEnableDecoderFallback(true)
				.setEnableAudioTrackPlaybackParams(true)

			// Track selector (set preferred audio language)
			trackSelector = DefaultTrackSelector(activityRef).apply {
				setParameters(buildUponParameters().setPreferredAudioLanguage("eng"))
			}

			// Build ExoPlayer with renderers factory
			// (FFmpeg will be automatically picked for unsupported codecs)
			player = ExoPlayer.Builder(activityRef, renderersFactory)
				.setTrackSelector(trackSelector)
				.setUseLazyPreparation(true)
				.setHandleAudioBecomingNoisy(true)
				.setSeekForwardIncrementMs(seekIntervalMs)
				.setSeekBackIncrementMs(seekIntervalMs)
				.build().apply {
					addListener(activityRef)
					setForegroundMode(false)
					setSeekParameters(CLOSEST_SYNC)
				}

			// Attach player to PlayerView
			playerView.player = player

			// Configure subtitles
			playerView.subtitleView?.apply {
				setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
				setApplyEmbeddedStyles(true)
				setApplyEmbeddedFontSizes(false)
				setCues(emptyList())
				setStyle(
					CaptionStyleCompat(
						getColor(activityRef, color.transparent_white),
						getColor(activityRef, color.transparent),
						getColor(activityRef, color.transparent),
						EDGE_TYPE_NONE,
						getColor(activityRef, color.color_primary_variant),
						Typeface.DEFAULT_BOLD
					)
				)
			}
		}
	}

	private fun createScrubberSeekListener(): VideoScrubberListener {
		return object : VideoScrubberListener() {
			override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit

			override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
				if (!canceled) player.seekTo(position)
			}
		}
	}

	private fun refreshCurrentVideoTitle() {
		val mediaItem = player.currentMediaItem
		val mediaUriString = mediaItem?.localConfiguration?.uri.toString()
		if (mediaUriString.isEmpty()) return

		val videoFileName = mediaUriString.toUri().lastPathSegment ?: return
		applyVideoTitle(videoFileName)
	}

	private fun applyVideoTitle(videoName: CharSequence) {
		::videoTitleText.isInitialized.takeIf { it }.let {
			if (videoTitleText.text != videoName) videoTitleText.text = videoName
		}
	}

	private fun getPlaybackPosition(): Long = player.currentPosition

	private fun getVideoDuration(): Long = player.duration

	private fun setupSwipeSeekGestures(targetView: View) {
		var isUserSeeking = false
		var startSeekPosition = 0L
		var wasPlayingBeforeSeek = false
		var isFingerScrolling = false
		var isLongPressTriggered = false
		val longPressDelay = 500L
		val longPressHandler = Handler(Looper.getMainLooper())

		// Set up gesture detector for seeking
		val gestureDetector = GestureDetector(
			targetView.context,
			object : GestureDetector.SimpleOnGestureListener() {
				override fun onDown(e: MotionEvent): Boolean {
					isFingerScrolling = false
					isLongPressTriggered = false
					longPressHandler.postDelayed({
						if (!isFingerScrolling && !isUserSeeking) {
							wasPlayingBeforeSeek = player.isPlaying
							if (wasPlayingBeforeSeek) {
								player.pause()
								isLongPressTriggered = true
							}
						}
					}, longPressDelay)
					return true
				}

				override fun onScroll(
					e1: MotionEvent?, e2: MotionEvent,
					distanceX: Float, distanceY: Float,
				): Boolean {
					longPressHandler.removeCallbacksAndMessages(null)
					if (!isFingerScrolling) {
						isFingerScrolling = true
						wasPlayingBeforeSeek = player.isPlaying
						if (wasPlayingBeforeSeek) player.pause()
						isUserSeeking = true
						startSeekPosition = getPlaybackPosition()
					}

					val duration = getVideoDuration()
					if (isUserSeeking && e1 != null && duration > 0) {
						val deltaX = e2.x - e1.x
						val threshold = 120

						if (abs(deltaX) > threshold && abs(distanceX) > abs(distanceY)) {
							val seekOffset = ((deltaX / targetView.width) * 25000).toLong()
							val newSeekPosition =
								(startSeekPosition + seekOffset).coerceIn(0, getVideoDuration())
							progressBar.setPosition(newSeekPosition)
							player.seekTo(newSeekPosition)
							showQuickPlayerInfo(formatPlaybackTime(newSeekPosition))
							return true
						}
					}
					return false
				}

				override fun onSingleTapUp(e: MotionEvent): Boolean {
					toggleVisibilityOfPlaybackController(shouldTogglePlayback = false)
					return false
				}

				override fun onDoubleTap(e: MotionEvent): Boolean {
					toggleVideoPlayback(); return true
				}
			})

		// Set touch listener to handle gestures
		targetView.setOnTouchListener { touchedView, event ->
			gestureDetector.onTouchEvent(event)
			when (event.action) {
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					longPressHandler.removeCallbacksAndMessages(null)
					if (isUserSeeking) {
						isUserSeeking = false
						if (wasPlayingBeforeSeek) player.play()
					} else if (isLongPressTriggered) {
						isLongPressTriggered = false
						player.play()
					}
					touchedView.performClick()
				}
			}
			true
		}
	}

	fun formatPlaybackTime(milliseconds: Long): String {
		val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
		val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
		return String.format(Locale.US, "%02d:%02d", minutes, seconds)
	}

	private fun handlePlaybackCompletion() {
		if (isStreamingVideoPlaying()) return

		val currentMediaItem = player.currentMediaItem
		val mediaUri = currentMediaItem?.localConfiguration?.uri.toString()
		if (mediaUri.isEmpty()) return

		val downloadedMediaList = getAllMediaRelatedDownloadDataModels()
		val matchedMediaIndex = getFirstMatchingModelIndex(mediaUri, downloadedMediaList)
		if (matchedMediaIndex == -1) return
		playbackPosition = 0
		playDownloadedVideo(downloadedMediaList[matchedMediaIndex])
	}

	private fun playVideoFromIntent() {
		extractDownloadModelFromIntent()?.let { playDownloadedVideo(it) } ?: run {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				intent.getParcelableExtra(INTENT_EXTRA_STREAM_URL, Uri::class.java)
					?.let { startStreamingPlayback(it); return }
			} else intent.getStringExtra(INTENT_EXTRA_STREAM_URL)?.let {
				streamVideoFromUrl(it)
			}
		}
	}

	private fun startStreamingPlayback(fileUri: Uri) {
		val videoTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
		if (!videoTitle.isNullOrEmpty()) applyVideoTitle(videoName = videoTitle)
		else getFileFromUri(fileUri)?.let { applyVideoTitle(videoName = it.name) }

		val mediaItem = MediaItem.fromUri(fileUri)
		player.setMediaItem(mediaItem)
		player.prepare()
		resumePlayer()

		val downloadModel = extractDownloadModelFromIntent()
		val audioFile = downloadModel?.getDestinationDocumentFile()
		showAlbumArtIfAudio(audioFile)
	}

	private fun extractDownloadModelFromIntent(): DownloadDataModel? {
		intent?.let { incomingIntent ->
			val downloadModelID = incomingIntent.getIntExtra(DOWNLOAD_MODEL_ID_KEY, -1)
			if (downloadModelID > -1) downloadSystem.finishedDownloadDataModels
				.find { it.id == downloadModelID }?.let { return it }
		}; return null
	}

	private fun playDownloadedVideo(downloadModel: DownloadDataModel) {
		val videoFile = fromFile(File("${downloadModel.fileDirectory}/${downloadModel.fileName}"))
		if (videoFile.exists()) playVideoFile(mediaFile = videoFile, subtitleFile = null)
		else showQuickPlayerInfo(msgText = getString(string.title_media_file_not_existed))
	}

	private fun playVideoFile(mediaFile: DocumentFile, subtitleFile: DocumentFile?) {
		mediaFile.canWrite().let {
			stopAndReleasePlayer()
			initializePlayer()

			subtitleFile?.let {
				val mediaWithSubtitles = combineMediaFileWithSubtitle(
					mediaFile = mediaFile, subtitleFile = subtitleFile)
				player.setMediaItem(mediaWithSubtitles)
					.apply { prepareMediaAndPlay(mediaFile) }
			} ?: run {
				val mediaItem = createMediaItemFromUri(mediaFile.uri)
				player.setMediaItem(mediaItem)
					.apply { prepareMediaAndPlay(mediaFile) }
			}
		}
	}

	private fun streamVideoFromUrl(fileUrl: String) {
		selfActivityRef?.let { activityRef ->
			if (!isValidURL(fileUrl)) {
				getMessageDialog(
					baseActivityInf = activityRef,
					isCancelable = false,
					titleText = getString(string.title_invalid_streaming_link),
					isNegativeButtonVisible = false,
					isTitleVisible = true,
					titleTextViewCustomize = {
						val color = resources.getColor(color.color_error, theme)
						it.setTextColor(color)
					},
					messageTextViewCustomize = {
						val messageString = getString(string.text_streaming_link_invalid)
						it.text = messageString
					},
					positiveButtonText = getString(string.title_exit_the_player),
					positiveButtonTextCustomize = { it.setLeftSideDrawable(drawable.ic_button_exit) },
					dialogBuilderCustomize = { dialogBuilder ->
						dialogBuilder.setOnClickForPositiveButton {
							dialogBuilder.close()
							closeActivityWithFadeAnimation()
						}
					}
				)?.show()
				return
			}

			val streamTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
			if (!streamTitle.isNullOrEmpty()) {
				applyVideoTitle(videoName = streamTitle)
			} else {
				updateTitleFromUrl(fileUrl)
			}

			val mediaItem = MediaItem.fromUri(fileUrl)
			player.setMediaItem(mediaItem)
			player.prepare()
			resumePlayer()

			val downloadModel = extractDownloadModelFromIntent()
			val audioFile = downloadModel?.getDestinationDocumentFile()
			showAlbumArtIfAudio(audioFile)
		}
	}

	private fun updateTitleFromUrl(fileUrl: String) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val modelId = intent.getIntExtra(INTENT_EXTRA_DOWNLOAD_ID, -1)
			downloadSystem.activeDownloadDataModels.firstOrNull { it.id == modelId }?.let {
				executeOnMainThread { videoTitleText.text = it.fileName }
			} ?: run {
				getFileInfoFromSever(URL(fileUrl)).let { fileInfo ->
					if (fileInfo.fileName.isNotEmpty()) executeOnMainThread {
						videoTitleText.text = fileInfo.fileName
					}
				}
			}
		})
	}

	/**
	 * Generates media item from URI.
	 * @param mediaUri The media URI
	 * @return Configured MediaItem
	 */
	private fun createMediaItemFromUri(mediaUri: Uri): MediaItem =
		Builder().setUri(mediaUri).build()

	/**
	 * Prepares media and starts playback.
	 * @param mediaFile The media file
	 */
	private fun prepareMediaAndPlay(mediaFile: DocumentFile) {
		player.prepare(); resumePlayer(); showAlbumArtIfAudio(mediaFile)
	}

	/**
	 * Combines media file with subtitle.
	 * @param mediaFile The media file
	 * @param subtitleFile The subtitle file
	 * @return Combined MediaItem
	 */
	private fun combineMediaFileWithSubtitle(
		mediaFile: DocumentFile,
		subtitleFile: DocumentFile
	): MediaItem {
		val listOfSubtitles = listOf(
			SubtitleConfiguration.Builder(subtitleFile.uri)
				.setMimeType(MimeTypes.APPLICATION_SUBRIP)
				.setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
		); return Builder().setUri(mediaFile.uri).setSubtitleConfigurations(listOfSubtitles).build()
	}

	/**
	 * Checks if player has active subtitles.
	 * @param player The ExoPlayer instance
	 * @return True if subtitles are active
	 */
	private fun hasSubtitles(player: ExoPlayer): Boolean {
		val trackGroups = player.currentTracks.groups
		for (trackGroup in trackGroups)
			if (trackGroup.type == C.TRACK_TYPE_TEXT && trackGroup.isSelected)
				return true; return false
	}

	/**
	 * Shows album art for audio files.
	 * @param mediaFile The media file
	 */
	private fun showAlbumArtIfAudio(mediaFile: DocumentFile?) {
		selfActivityRef?.let { safeActivityRef ->
			mediaFile?.let {
				val retriever = MediaMetadataRetriever()
				val file = mediaFile.getAbsolutePath(safeActivityRef)
				retriever.setDataSource(file)
				val width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)
				val height = retriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)

				if (width == null && height == null) {
					showDefaultAudioAlbumArt(mediaFile); return
				}

				if (!isAudio(mediaFile)) {
					hideView(audioVisualizerView, true)
					hideView(albumArtView, true)
					return
				}; showDefaultAudioAlbumArt(mediaFile)
			}
		}
	}

	/**
	 * Shows default album art for audio files.
	 * @param mediaFile The audio file
	 */
	private fun showDefaultAudioAlbumArt(mediaFile: DocumentFile) {
		selfActivityRef?.let { safeActivityRef ->
			showView(audioVisualizerView, true)
			showView(albumArtView, true)
			setAlbumArt(mediaFile.getAbsolutePath(safeActivityRef), albumArtView)
		}
	}

	/**
	 * Sets album art for audio file.
	 * @param audioFilePath Path to audio file
	 * @param imageView ImageView to display album art
	 */
	private fun setAlbumArt(audioFilePath: String?, imageView: ImageView) {
		val retriever = MediaMetadataRetriever()
		try {
			retriever.setDataSource(audioFilePath)
			val art = retriever.embeddedPicture
			if (art != null) {
				val albumArtBitmap = decodeByteArray(art, 0, art.size)
				showBlurredBitmap(imageView, albumArtBitmap)
			} else {
				getCurrentPlayingDownloadModel()?.let { downloadDataModel ->
					if (downloadDataModel.thumbPath.isNotEmpty()) {
						val bitmapFile = File(downloadDataModel.thumbPath)
						val originalBitmap = ViewUtility.getBitmapFromFile(bitmapFile)
						originalBitmap?.let { showBlurredBitmap(imageView, originalBitmap) } ?: run {
							val thumbImageUri = Uri.fromFile(bitmapFile)
							imageView.setImageURI(thumbImageUri)
						}
					} else imageView.setImageResource(drawable.image_audio_thumb)
				} ?: run { imageView.setImageResource(drawable.image_audio_thumb) }
			}
		} catch (error: Exception) {
			LogHelperUtils.from(javaClass).e(error)
			imageView.setImageResource(drawable.image_audio_thumb)
		} finally {
			retriever.release()
		}
	}

	/**
	 * Displays a blurred version of the original bitmap in the given ImageView.
	 *
	 * - If the bitmap is portrait (height > width), the original image is shown without blur.
	 * - If the bitmap is landscape, it is first rotated by 90 degrees,
	 *   then blurred with the given radius (default 5f), and finally displayed.
	 * - The blur operation runs in a background thread to avoid blocking the UI.
	 * - Once processing is complete, the result is posted back on the main thread
	 *   to safely update the ImageView.
	 *
	 * @param targetImageView The ImageView where the blurred (or original) bitmap will be displayed
	 * @param originalBitmap The original bitmap to be processed
	 */
	private fun showBlurredBitmap(targetImageView: ImageView, originalBitmap: Bitmap) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val isPortraitImage = originalBitmap.height > originalBitmap.width
			if (isPortraitImage) {
				targetImageView.setImageBitmap(originalBitmap)
			} else {
				rotateBitmap(originalBitmap, 90f).let { rotatedBitmap ->
					val blurredBitmap = blurBitmap(bitmap = rotatedBitmap, radius = 15f)
					ThreadsUtility.executeOnMain {
						targetImageView.setImageBitmap(blurredBitmap)
					}
				}
			}
		})
	}

	/**
	 * Stops and releases player resources.
	 */
	private fun stopAndReleasePlayer() {
		if (!isFinishing && !isDestroyed) {
			player.stop(); player.release()
		}
	}

	/**
	 * Pauses player playback.
	 */
	fun pausePlayer() {
		if (!isFinishing && !isDestroyed) {
			player.playWhenReady = false
			player.playbackState; player.pause()
		}
	}

	/**
	 * Resumes player playback.
	 */
	fun resumePlayer() {
		if (player.isPlaying) return

		player.playWhenReady = true
		player.playbackState; player.play()
		player.seekTo(playbackPosition)
	}

	/**
	 * Toggles between play and pause states.
	 */
	private fun toggleVideoPlayback() {
		// Always pause first
		if (player.isPlaying) player.pause()
		else player.play()
	}

	/**
	 * Plays next media in playlist.
	 */
	private fun playNextMedia() {
		if (!::player.isInitialized) return

		if (isStreamingVideoPlaying()) {
			val infoText = getString(string.title_no_next_item_to_play)
			showQuickPlayerInfo(infoText); return
		}

		val currentMediaUri = player.currentMediaItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		intent.getIntExtra(INTENT_EXTRA_SOURCE_ORIGIN, -2).let { result ->
			if (result == -2) return
			if (result == SOURCE_FINISHED_DOWNLOADS) {
				playNextFromFinishedDownloads(currentMediaUri); return
			}

			if (result == SOURCE_PRIVATE_FOLDER) {
				//todo: implement next playback from the private folder
			}
		}
	}

	/**
	 * Plays next media from finished downloads.
	 * @param currentMediaUri URI of current media
	 */
	private fun playNextFromFinishedDownloads(currentMediaUri: String) {
		val mediaFiles = getAllMediaRelatedDownloadDataModels()
		val matchedIndex = getFirstMatchingModelIndex(currentMediaUri, mediaFiles)
		if (matchedIndex != -1) {
			val nextIndex = matchedIndex + 1
			if (mediaFiles.size > nextIndex) {
				val candidate = mediaFiles[nextIndex]
				val downloadLocation = candidate.globalSettings.defaultDownloadLocation
				val isPrivateVideo = downloadLocation == AIOSettings.PRIVATE_FOLDER
				if (isPrivateVideo && !hasMadeDecisionOverPrivateAccess) {
					getMessageDialog(
						baseActivityInf = selfActivityRef,
						isTitleVisible = true,
						titleText = getText(string.title_private_videos_alert),
						messageTxt = getString(string.text_prompt_skip_or_play_private_video),
						isNegativeButtonVisible = true,
						negativeButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_cancel)
							it.text = getString(string.title_not_now)
						},
						positiveButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_media_play)
							it.text = getString(string.title_unlock_and_play_all)
						}
					)?.apply {
						setOnClickForNegativeButton {
							close()
							isPrivateSessionAllowed = false
							hasMadeDecisionOverPrivateAccess = true
							playNextMedia()
						}

						setOnClickForPositiveButton {
							close()
							authenticate(activity = selfActivityRef, onResult = { isSuccess ->
								if (isSuccess) {
									isPrivateSessionAllowed = true
									hasMadeDecisionOverPrivateAccess = true
									playNextMedia()
								} else {
									selfActivityRef?.doSomeVibration(50)
									showToast(selfActivityRef, msgId = string.title_authentication_failed)
								}
							})
						}
					}?.show()
				} else {
					if (isPrivateSessionAllowed) {
						playDownloadedVideo(candidate)
					} else {
						if (matchedIndex != -1) {
							var nextIndex = matchedIndex + 1
							var nextDownloadDataModel: DownloadDataModel? = null

							while (nextIndex < mediaFiles.size) {
								val candidate = mediaFiles[nextIndex]
								val downloadLocation = candidate.globalSettings.defaultDownloadLocation
								if (downloadLocation != AIOSettings.PRIVATE_FOLDER) {
									nextDownloadDataModel = candidate
									break
								}
								nextIndex++
							}

							if (nextDownloadDataModel != null) {
								playDownloadedVideo(nextDownloadDataModel)
							} else {
								val infoText = getString(string.title_no_next_item_to_play)
								showQuickPlayerInfo(infoText)
							}
						}
					}
				}
			} else {
				val infoText = getString(string.title_no_next_item_to_play)
				showQuickPlayerInfo(infoText)
			}
		}
	}

	/**
	 * Plays previous media in playlist.
	 */
	private fun playPreviousMedia() {
		if (!::player.isInitialized) return

		if (isStreamingVideoPlaying()) {
			val infoText = getString(string.title_no_previous_item_to_play)
			showQuickPlayerInfo(infoText); return
		}

		val currentItem = player.currentMediaItem
		val currentMediaUri = currentItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		intent.getIntExtra(INTENT_EXTRA_SOURCE_ORIGIN, -2).let { result ->
			if (result == -2) return
			if (result == SOURCE_FINISHED_DOWNLOADS) {
				playPreviousFromFinishedDownloads(currentMediaUri)
				return
			}

			@Suppress("ControlFlowWithEmptyBody")
			if (result == SOURCE_PRIVATE_FOLDER) {
				//Todo: implement previous playback from the private folder
			}
		}
	}

	/**
	 * Plays previous media from finished downloads.
	 * @param currentMediaUri URI of current media
	 */
	private fun playPreviousFromFinishedDownloads(currentMediaUri: String) {
		val mediaFiles = getAllMediaRelatedDownloadDataModels()
		val matchedIndex = getFirstMatchingModelIndex(currentMediaUri, mediaFiles)
		if (matchedIndex != -1) {
			val previousIndex = matchedIndex - 1
			if (previousIndex > -1) {
				val candidate = mediaFiles[previousIndex]
				val downloadLocation = candidate.globalSettings.defaultDownloadLocation
				val isPrivateVideo = downloadLocation == AIOSettings.PRIVATE_FOLDER
				if (isPrivateVideo && !hasMadeDecisionOverPrivateAccess) {
					getMessageDialog(
						baseActivityInf = selfActivityRef,
						isTitleVisible = true,
						titleText = getText(string.title_private_videos_alert),
						messageTxt = getString(string.text_prompt_skip_or_play_private_video),
						isNegativeButtonVisible = true,
						negativeButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_cancel)
							it.text = getString(string.title_not_now)
						},
						positiveButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_media_play)
							it.text = getString(string.title_unlock_and_play_all)
						}
					)?.apply {
						setOnClickForNegativeButton {
							close()
							isPrivateSessionAllowed = false
							hasMadeDecisionOverPrivateAccess = true
							playPreviousMedia()
						}

						setOnClickForPositiveButton {
							close()
							authenticate(activity = selfActivityRef, onResult = { isSuccess ->
								if (isSuccess) {
									isPrivateSessionAllowed = true
									hasMadeDecisionOverPrivateAccess = true
									playPreviousMedia()
								} else {
									selfActivityRef?.doSomeVibration(50)
									showToast(selfActivityRef, msgId = string.title_authentication_failed)
								}
							})
						}
					}?.show()
				} else {
					if (isPrivateSessionAllowed) {
						playDownloadedVideo(candidate)
					} else {
						if (matchedIndex != -1) {
							var previousIndex = matchedIndex - 1
							var previousDownloadDataModel: DownloadDataModel? = null

							while (previousIndex >= 0) {
								val candidate = mediaFiles[previousIndex]
								val downloadLocation = candidate.globalSettings.defaultDownloadLocation
								if (downloadLocation != AIOSettings.PRIVATE_FOLDER) {
									previousDownloadDataModel = candidate
									break
								}
								previousIndex--
							}

							if (previousDownloadDataModel != null) {
								playDownloadedVideo(previousDownloadDataModel)
							} else {
								val infoText = getString(string.title_no_previous_item_to_play)
								showQuickPlayerInfo(infoText)
							}
						}
					}
				}
			} else {
				val infoText = getString(string.title_no_previous_item_to_play)
				showQuickPlayerInfo(infoText)
			}
		}
	}

	fun enablePrivateSession() {
		selfActivityRef?.let { playerActivity ->
			if (playerActivity.isPrivateSessionAllowed) return
			authenticate(activity = playerActivity, onResult = { isSuccess ->
				if (isSuccess) {
					playerActivity.isPrivateSessionAllowed = true
					playerActivity.hasMadeDecisionOverPrivateAccess = true
				} else {
					playerActivity.doSomeVibration(50)
					showToast(playerActivity, msgId = string.title_authentication_failed)
				}
			})
		}
	}

	/**
	 * Gets index of first matching model for media URI.
	 * @param mediaUri The media URI to match
	 * @param models List of DownloadDataModels to search
	 * @return Index of matching model or -1 if not found
	 */
	private fun getFirstMatchingModelIndex(mediaUri: String, models: List<DownloadDataModel>): Int {
		val currentMediaFilePath = mediaUri.toUri().path
		val matchedIndex = models.indexOfFirst { model ->
			val modelFilePath = Uri.fromFile(model.getDestinationFile()).path
			modelFilePath == currentMediaFilePath
		}; return matchedIndex
	}

	/**
	 * Gets all media-related download models.
	 * @return List of DownloadDataModels for media files
	 */
	private fun getAllMediaRelatedDownloadDataModels(): List<DownloadDataModel> {
		return downloadSystem.finishedDownloadDataModels
			.filter { model ->
				val modelDestinationFile = model.getDestinationDocumentFile()
				modelDestinationFile.exists() && isMediaFile(modelDestinationFile)
			}.distinctBy { it.getDestinationDocumentFile().uri }
	}

	/**
	 * Checks if file is media (audio or video).
	 * @param modelDestinationFile The file to check
	 * @return True if file is audio or video
	 */
	private fun isMediaFile(modelDestinationFile: DocumentFile): Boolean {
		return (isAudio(modelDestinationFile) || isVideo(modelDestinationFile))
	}

	/**
	 * Updates video progress display.
	 */
	private fun updateVideoProgressContainer() {
		currentTimeText.text = formatPlaybackTime(player.currentPosition)
		val tmpTimer = formatPlaybackTime(player.duration)
		if (!tmpTimer.startsWith("-")) durationText.text = tmpTimer
		else durationText.text = getString(string.title_00_00)

		val duration = player.duration
		val position = player.currentPosition
		val bufferedPosition = player.bufferedPosition
		playbackPosition = position

		progressBar.setDuration(duration)
		progressBar.setPosition(position)
		progressBar.setBufferedPosition(bufferedPosition)
	}

	fun applyAutoRotateSetting(enableAutoRotate: Boolean = true) {
		val newOrientation =
			if (enableAutoRotate) SCREEN_ORIENTATION_UNSPECIFIED
			else SCREEN_ORIENTATION_LOCKED
		requestedOrientation = newOrientation
	}

	/**
	 * Toggles playback controller visibility.
	 * @param shouldTogglePlayback True to toggle playback state
	 */
	private fun toggleVisibilityOfPlaybackController(shouldTogglePlayback: Boolean = true) {
		if (areControllersLocked) {
			toggleViewVisibility(unlockButton, true)
		} else {
			invisibleAreaClickCount++
			if (invisibleAreaClickCount > 1) {
				if (shouldTogglePlayback) toggleVideoPlayback()
				invisibleAreaClickCount = 0
			} else toggleViewVisibility(playbackController, true)

			delay(timeInMile = 300, listener = object : OnTaskFinishListener {
				override fun afterDelay() {
					invisibleAreaClickCount = 0
				}
			})
		}
	}

	/**
	 * Shows playback controllers.
	 */
	private fun visibleEntirePlaybackControllers() {
		showView(shouldAnimate = true, targetView = playbackController)
	}

	/**
	 * Hides playback controllers.
	 */
	private fun hideEntirePlaybackControllers() {
		hideView(shouldAnimate = true, targetView = playbackController)
	}

	/**
	 * Updates play/pause button icon.
	 * @param isPlaying True if player is currently playing
	 */
	private fun updateIconOfVideoPlayPauseButton(isPlaying: Boolean) {
		val iconResId = if (isPlaying) drawable.ic_button_media_pause
		else drawable.ic_button_media_play
		val buttonViewId = id.btn_img_video_play_pause_toggle
		(findViewById<ImageView>(buttonViewId)).setImageResource(iconResId)
		if (isPlaying) audioVisualizerView.resumeAnimation()
		else audioVisualizerView.pauseAnimation()
	}

	/**
	 * Locks playback controllers.
	 */
	private fun lockEntirePlaybackControllers() {
		requestedOrientation = SCREEN_ORIENTATION_LOCKED
		hideEntirePlaybackControllers()
		showView(targetView = unlockButton, shouldAnimate = true)
		areControllersLocked = true

		delay(timeInMile = 1500, listener = object : OnTaskFinishListener {
			override fun afterDelay() {
				hideView(targetView = unlockButton, shouldAnimate = true)
			}
		})
	}

	/**
	 * Unlocks playback controllers.
	 */
	private fun unlockEntirePlaybackControllers() {
		requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
		hideView(targetView = unlockButton, shouldAnimate = true)
		visibleEntirePlaybackControllers()
		areControllersLocked = false
	}

	/**
	 * Shares current media file.
	 */
	fun shareMediaFile() {
		selfActivityRef?.let { safeActivityRef ->
			if (isStreamingVideoPlaying()) {
				showMessageDialog(
					baseActivityInf = safeActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(color.color_error)
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(drawable.ic_button_checked_circle)
						positiveButton.setText(string.title_okay)
					},
					messageTextViewCustomize = { it.setText(string.text_share_stream_media_unavailable) }
				); return
			} else {
				val currentItem = player.currentMediaItem
				val currentMediaUri = currentItem?.localConfiguration?.uri.toString()
				if (currentMediaUri.isEmpty()) {
					invalidMediaFileToast(); return
				}

				downloadSystem.finishedDownloadDataModels.find {
					it.getDestinationFile().path == currentMediaUri.toUri().path
				}?.let { downloadDataModel ->
					shareMediaFile(safeActivityRef, downloadDataModel.getDestinationFile())
					return
				}; invalidMediaFileToast()
			}

			invalidMediaFileToast()
		}
	}

	/**
	 * Shows toast for invalid media file.
	 */
	private fun invalidMediaFileToast() {
		doSomeVibration(timeInMillis = 50)
		showToast(
			activityInf = selfActivityRef,
			msg = getString(string.title_invalid_media_file)
		)
	}

	/**
	 * Opens and syncs subtitle file.
	 */
	fun openAndSyncSubtitle() {
		selfActivityRef?.let { safeActivityRef ->
			getMessageDialog(
				baseActivityInf = safeActivityRef,
				messageTxt = getString(string.text_select_subtitle_from_file_manager),
				positiveButtonText = getString(string.title_select_file)
			)?.let { dialogBuilder ->
				dialogBuilder.positiveButtonView.setOnClickListener {
					dialogBuilder.close(); addExternalSubtitleToPlayer()
				}.apply { dialogBuilder.show() }
			}
		}
	}

	/**
	 * Adds external subtitle to player.
	 */
	private fun addExternalSubtitleToPlayer() {
		try {
			pausePlayer()
			requestToPickSubtitleFile()
			validateSelectedSubtitleFileByUser()
		} catch (error: Exception) {
			error.printStackTrace()
			warnUserAboutSubtitleSelectionFailure()
		}
	}

	/**
	 * Warns user about subtitle selection failure.
	 */
	private fun warnUserAboutSubtitleSelectionFailure() {
		val msgText = getString(string.text_subtitle_file_not_readable)
		showMessageDialog(baseActivityInf = selfActivityRef, messageTxt = msgText)
	}

	/**
	 * Validates user-selected subtitle file.
	 */
	private fun validateSelectedSubtitleFileByUser() {
		scopedStorageHelper?.onFileSelected = { _, files ->
			if (!isFileReadable(files)) {
				val errorMsg = getString(string.title_file_not_readable_permission_issue)
				throw Exception(errorMsg)
			}

			val subtitleFile = files.first()
			val fileExtension = subtitleFile.let {
				subtitleFile.name?.let { fileName -> getFileExtension(fileName) ?: "" }
			}

			if (fileExtension.isNullOrEmpty()) throw Exception(getString(string.title_file_not_readable_permission_issue))
			if (!isSubtitleFileExtension(fileExtension)) showUnsupportedSubtitleFileMessage() else {
				extractDownloadModelFromIntent()?.let { downloadDataModel ->
					val videoFile = downloadDataModel.getDestinationDocumentFile()
					playVideoWithSubtitle(videoFile, subtitleFile)
				}
			}
		}
	}

	/**
	 * Plays video with subtitle.
	 * @param videoFile The video file
	 * @param subtitleFile The subtitle file
	 */
	private fun playVideoWithSubtitle(videoFile: DocumentFile, subtitleFile: DocumentFile) {
		playVideoFile(videoFile, subtitleFile); resumePlayer()
	}

	/**
	 * Shows message about unsupported subtitle file.
	 */
	private fun showUnsupportedSubtitleFileMessage() {
		val msgText = getString(string.text_subtitle_file_not_supported)
		showMessageDialog(baseActivityInf = selfActivityRef, messageTxt = msgText)
	}

	/**
	 * Checks if file extension is for subtitle.
	 * @param fileExt The file extension
	 * @return True if extension is for subtitle file
	 */
	private fun isSubtitleFileExtension(fileExt: String): Boolean {
		var isValid = false
		if (fileExt.isNotEmpty() || fileExt.isBlank()) {
			if (fileExt.lowercase(Locale.getDefault()) == "srt" ||
				fileExt.lowercase(Locale.getDefault()) == "vtt"
			) isValid = true
		}; return isValid
	}

	/**
	 * Checks if file is readable.
	 * @param files List of files to check
	 * @return True if first file is readable
	 */
	private fun isFileReadable(files: List<DocumentFile>): Boolean {
		return files.isNotEmpty() && files.first().canRead()
	}

	/**
	 * Requests user to pick subtitle file.
	 */
	private fun requestToPickSubtitleFile() {
		selfActivityRef?.let { safeActivityRef ->
			val pubicDownloadFolder = INSTANCE.getPublicDownloadDir()?.getAbsolutePath(INSTANCE)
			val defaultAIOFolder = getText(string.text_default_aio_download_folder_path)
			val pathToPick = pubicDownloadFolder ?: defaultAIOFolder
			val initialPath =
				FileFullPath(context = safeActivityRef, fullPath = pathToPick.toString())
			scopedStorageHelper?.openFilePicker(allowMultiple = false, initialPath = initialPath)
		}
	}

	/**
	 * Rewinds video player by 10 seconds.
	 */
	private fun rewindVideoPlayer() {
		if (player.isPlaying) {
			val currentPosition = player.currentPosition
			val newPosition = (currentPosition - 10000).coerceIn(0, player.duration)
			player.seekTo(newPosition)
		}
	}

	/**
	 * Forwards video player by 10 seconds.
	 */
	private fun forwardVideoPlayer() {
		if (player.isPlaying) {
			val currentPosition = player.currentPosition
			val newPosition = (currentPosition + 10000).coerceAtMost(player.duration)
			player.seekTo(newPosition)
		}
	}

	/**
	 * Shows options menu popup.
	 */
	private fun showOptionMenuPopup() {
		if (!::optionsPopup.isInitialized)
			optionsPopup = MediaOptionsPopup(selfActivityRef); optionsPopup.show()
	}

	/**
	 * Toggles fullscreen mode.
	 */
	@Suppress("DEPRECATION")
	private fun toggleFullscreen() {
		selfActivityRef?.let { safeActivityRef ->
			var newUiOptions = safeActivityRef.window.decorView.systemUiVisibility
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_HIDE_NAVIGATION
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_FULLSCREEN
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_IMMERSIVE
			safeActivityRef.window.decorView.systemUiVisibility = newUiOptions
		}
	}

	/**
	 * Deletes current media file.
	 */
	fun deleteMediaFile() {
		if (!::player.isInitialized) return

		val currentMediaUri = player.currentMediaItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		val audioAndVideoFiles = getAllMediaRelatedDownloadDataModels()
		val matchedIndex = getFirstMatchingModelIndex(currentMediaUri, audioAndVideoFiles)

		ThreadsUtility.executeInBackground(codeBlock = {
			playNextOrPreviousFromFinishedDownloads(matchedIndex, audioAndVideoFiles)
			deleteActualMediaFileAndClearDatabase(audioAndVideoFiles, matchedIndex)
		})
	}

	/**
	 * Deletes media file and clears database entry.
	 * @param audioAndVideoFiles List of media files
	 * @param matchedIndex Index of file to delete
	 */
	private fun deleteActualMediaFileAndClearDatabase(
		audioAndVideoFiles: List<DownloadDataModel>, matchedIndex: Int,
	) {
		audioAndVideoFiles[matchedIndex].getDestinationFile().delete()
		val deletedDownloadDataModel = audioAndVideoFiles[matchedIndex]
		downloadSystem.finishedDownloadDataModels.remove(deletedDownloadDataModel)
		downloadSystem.sortFinishedDownloadDataModels()
		deletedDownloadDataModel.deleteModelFromDisk()
		executeOnMainThread {
			showToast(
				activityInf = selfActivityRef,
				msg = getString(string.title_successfully_deleted)
			)
		}
	}

	/**
	 * Plays next or previous media after deletion.
	 * @param matchedIndex Index of deleted file
	 * @param audioAndVideoFiles List of media files
	 */
	private fun playNextOrPreviousFromFinishedDownloads(
		matchedIndex: Int, audioAndVideoFiles: List<DownloadDataModel>,
	) {
		executeOnMainThread {
			if (matchedIndex != -1) {
				if (audioAndVideoFiles.size > (matchedIndex + 1)) {
					playDownloadedVideo(audioAndVideoFiles[(matchedIndex + 1)])
				} else {
					if ((matchedIndex - 1) > -1)
						playDownloadedVideo(audioAndVideoFiles[(matchedIndex - 1)]) else {
						stopAndReleasePlayer(); closeActivityWithSwipeAnimation()
					}
				}
			}
		}
	}

	/**
	 * Gets current playing download model.
	 * @return Current DownloadDataModel or null
	 */
	fun getCurrentPlayingDownloadModel(): DownloadDataModel? {
		selfActivityRef?.let { _ ->
			try {
				if (!::player.isInitialized) return null

				val currentMediaUri =
					player.currentMediaItem?.localConfiguration?.uri.toString()
				if (currentMediaUri.isEmpty()) return null

				val downloadDataModelList = getAllMediaRelatedDownloadDataModels()
				val matchedIndex =
					getFirstMatchingModelIndex(currentMediaUri, downloadDataModelList)
				val currentPlayingDownloadModel = downloadDataModelList[matchedIndex]
				return currentPlayingDownloadModel
			} catch (error: Exception) {
				error.printStackTrace()
				return null
			}
		}; return null
	}

	/**
	 * Opens current media file in external app.
	 */
	fun openMediaFile() {
		selfActivityRef?.let { safeActivityRef ->
			if (isStreamingVideoPlaying()) {
				val msgText = getString(string.text_open_stream_media_unavailable)
				showMessageDialog(
					baseActivityInf = safeActivityRef,
					messageTxt = msgText, isNegativeButtonVisible = false
				); return
			}

			if (!::player.isInitialized) return

			val currentMediaUri =
				player.currentMediaItem?.localConfiguration?.uri.toString()
			if (currentMediaUri.isEmpty()) return

			val mediaFiles = getAllMediaRelatedDownloadDataModels()
			val matchedIndex = getFirstMatchingModelIndex(currentMediaUri, mediaFiles)
			val mediaFile = mediaFiles[matchedIndex].getDestinationFile()
			ShareUtility.openFile(mediaFile, safeActivityRef)
		}
	}

	/**
	 * Opens media file information dialog.
	 */
	fun openMediaFileInfo() {
		selfActivityRef?.let { safeActivityRef ->
			if (isStreamingVideoPlaying()) {
				showMessageDialog(
					baseActivityInf = safeActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(color.color_error)
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(drawable.ic_button_checked_circle)
						positiveButton.setText(string.title_okay)
					},
					messageTextViewCustomize = { it.setText(string.text_video_stream_info_unavailable) }
				); return
			}

			if (!::player.isInitialized) return

			val currentItem = player.currentMediaItem
			val currentMediaUri = currentItem?.localConfiguration?.uri.toString()
			if (currentMediaUri.isEmpty()) return

			val mediaFiles = getAllMediaRelatedDownloadDataModels()
			val matchedIndex = getFirstMatchingModelIndex(currentMediaUri, mediaFiles)
			val downloadModel = mediaFiles[matchedIndex]

			getMessageDialog(
				baseActivityInf = safeActivityRef,
				isCancelable = true,
				isTitleVisible = true,
				titleText = getText(string.title_media_file_info),
				messageTextViewCustomize = {
					it.gravity = Gravity.START
					it.linksClickable = true
					val htmlString = buildMediaInfoHtmlString(downloadModel)
					it.text = fromHtmlStringToSpanned(htmlString)
					it.movementMethod = getInstance()
				},
				positiveButtonText = getString(string.title_okay),
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = { positiveButton: TextView ->
					val drawable = ContextCompat.getDrawable(
						applicationContext,
						drawable.ic_button_checked_circle
					)
					drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
					positiveButton.setCompoundDrawables(drawable, null, null, null)
				}
			)?.show()
		}
	}

	/**
	 * Abstract class for video scrubber listeners.
	 */
	abstract class VideoScrubberListener : TimeBar.OnScrubListener {
		override fun onScrubMove(timeBar: TimeBar, position: Long) {
			//Override this function to implement the function.
		}
	}
}