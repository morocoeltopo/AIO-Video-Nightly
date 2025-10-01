package app.ui.main.fragments.downloads.intercepter

import android.widget.TextView
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOApp.Companion.youtubeVidParser
import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrl
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoInfo
import app.core.engines.video_parser.parsers.VideoParserUtility.getYtdlpVideoFormatsListWithRetry
import app.ui.main.MotherActivity
import app.ui.others.information.IntentInterceptActivity
import com.aio.R
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.networks.DownloaderUtils
import lib.networks.URLUtility.isValidURL
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.lang.ref.WeakReference

/**
 * Intercepts shared video URLs and processes them to extract downloadable formats,
 * especially using YTDLP (youtube-dl/pytube) supported parsing, and displays
 * a resolution picker dialog if formats are available.
 *
 * @param baseActivity Activity context used to show UI components and dialogs.
 * @param userGivenVideoInfo Optional pre-filled video info (title, description, etc.).
 * @param onOpenBrowser Callback triggered when user opts to open the link externally.
 */
class SharedVideoURLIntercept(
	private val baseActivity: BaseActivity?,
	private val userGivenVideoInfo: VideoInfo? = null,
	private val onOpenBrowser: (() -> Unit?)? = null,
	private val closeActivityOnSuccessfulDownload: Boolean = false
) {

	private val logger = LogHelperUtils.from(javaClass)

	/** Safe reference to activity to avoid memory leaks */
	private var safeBaseActivityRef = WeakReference(baseActivity).get()

	/** Indicates if the intercepting process is currently active */
	private var isInterceptingInProcess: Boolean = false

	/** Indicates whether the current interception was cancelled or finished */
	private var isInterceptingTerminated: Boolean = false

	/** UI dialog to show progress while analyzing video */
	private var waitingDialog: WaitingDialog? = null

	/** Whether to open browser automatically if intercept fails */
	private var shouldOpenBrowserAsFallback = true

	/**
	 * Starts processing the shared video URL.
	 * @param intentUrl The video URL received via intent.
	 * @param shouldOpenBrowserAsFallback Whether to fallback to browser if formats not found.
	 */
	fun interceptIntentURI(intentUrl: String?, shouldOpenBrowserAsFallback: Boolean = true) {
		logger.d("Starting URL interception process")
		this.shouldOpenBrowserAsFallback = shouldOpenBrowserAsFallback

		intentUrl?.let {
			if (!isValidURL(intentUrl)) {
				logger.d("Invalid URL provided: $intentUrl")
				return
			}
			logger.d("Initializing waiting dialog")
			initWaitingMessageDialog()
			startIntercepting(intentUrl)
		}
	}

	/** Initializes the waiting dialog shown during video analysis */
	private fun initWaitingMessageDialog() {
		logger.d("Creating waiting dialog")
		waitingDialog = WaitingDialog(
			isCancelable = false,
			baseActivityInf = safeBaseActivityRef,
			loadingMessage = getText(R.string.title_analyzing_url_please_wait),
			dialogCancelListener = {
				waitingDialog?.let { waitingDialog ->
					logger.d("Waiting dialog cancelled by user")
					waitingDialog.close()
					onCancelInterceptRequested(waitingDialog)
				}
			}
		)
	}

	/**
	 * Updates the dialog message during analysis.
	 * @param updatedMessage Message to display.
	 */
	private fun updateWaitingMessage(updatedMessage: String) {
		logger.d("Updating waiting dialog message: $updatedMessage")
		waitingDialog?.let { waitingDialog ->
			executeOnMainThread {
				waitingDialog.dialogBuilder?.apply {
					val textProgressInfo = view.findViewById<TextView>(R.id.txt_progress_info)
					textProgressInfo.text = updatedMessage
				}
			}
		}
	}

	/**
	 * Begins checking if the given video URL can be analyzed.
	 * @param targetVideoUrl The URL to analyze.
	 */
	private fun startIntercepting(targetVideoUrl: String) {
		logger.d("Starting interception for URL: $targetVideoUrl")
		waitingDialog?.let { waitingDialog ->
			if (isInterceptingInProcess) {
				logger.d("Interception already in progress")
				return
			}

			waitingDialog.show()
			isInterceptingInProcess = true

			ThreadsUtility.executeInBackground(codeBlock = {
				if (isYoutubeUrlDetected(targetVideoUrl)) {
					logger.d("YouTube URL detected with restrictions")
					return@executeInBackground
				}
				if (!isYtdlpSupportedUrl(targetVideoUrl)) {
					logger.d("URL not supported by yt-dlp: $targetVideoUrl")
					ThreadsUtility.executeOnMain {
						waitingDialog.close()
						safeBaseActivityRef?.doSomeVibration(50)
						showToast(
							activity = safeBaseActivityRef,
							msgId = R.string.text_unsupported_video_link
						)
						openInBuiltInBrowser(targetVideoUrl)
					}; return@executeInBackground
				} else {
					logger.d("Starting video URL analysis")
					startAnalyzingVideoUrl(targetVideoUrl)
				}
			})
		}
	}

	/**
	 * Parses the video URL and shows resolution picker if successful.
	 * @param videoUrl The target video URL.
	 */
	private fun startAnalyzingVideoUrl(videoUrl: String) {
		logger.d("Analyzing video URL: $videoUrl")
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			val ytStreamInfo = if (isYouTubeUrl(videoUrl)) {
				logger.d("YouTube URL detected, getting stream info")
				youtubeVidParser.getStreamInfo(filterYoutubeUrlWithoutPlaylist(videoUrl))
			} else null

			val videoCookie = userGivenVideoInfo?.videoCookie
			val videoTitle = if (ytStreamInfo?.name.isNullOrEmpty() == false)
				ytStreamInfo.name else userGivenVideoInfo?.videoTitle

			val videoDescription = if (ytStreamInfo?.description?.content.isNullOrEmpty() == false)
				ytStreamInfo.description?.content else userGivenVideoInfo?.videoDescription

			val videoThumbnailUrl = if (ytStreamInfo?.thumbnails.isNullOrEmpty() == false)
				ytStreamInfo.thumbnails[0].url else userGivenVideoInfo?.videoThumbnailUrl

			val videoUrlReferer = userGivenVideoInfo?.videoUrlReferer
			val videoThumbnailByReferer = userGivenVideoInfo?.videoThumbnailByReferer
			val videoFormats = if (isYouTubeUrl(videoUrl) && IS_ULTIMATE_VERSION_UNLOCKED) {
				logger.d("Getting YouTube video resolutions")
				getYoutubeVideoResolutions(ytStreamInfo)
			} else {
				logger.d("Getting yt-dlp video formats")
				ArrayList(getYtdlpVideoFormatsListWithRetry(videoUrl, videoCookie))
			}

			val videoInfo = VideoInfo(
				videoUrl = videoUrl,
				videoTitle = videoTitle,
				videoDescription = videoDescription,
				videoThumbnailUrl = videoThumbnailUrl,
				videoUrlReferer = videoUrlReferer,
				videoThumbnailByReferer = videoThumbnailByReferer ?: false,
				videoCookie = videoCookie,
				videoFormats = videoFormats
			)

			executeOnMainThread {
				logger.d("Video analysis completed, showing results")
				waitingDialog?.let { waitingDialog ->
					waitingDialog.close()
					isInterceptingInProcess = false

					if (!isInterceptingTerminated) {
						if (videoInfo.videoFormats.isEmpty()) {
							logger.d("No video formats found")
							if (shouldOpenBrowserAsFallback) openInBuiltInBrowser(videoUrl)
							else showToast(
								activity = safeBaseActivityRef,
								msgId = R.string.title_no_video_found
							)

						} else {
							logger.d("Showing resolution picker with ${videoInfo.videoFormats.size} formats")
							val activityClosingCondition = closeActivityOnSuccessfulDownload
							VideoResolutionPicker(
								baseActivity = safeBaseActivityRef,
								videoInfo = videoInfo,
								onDialogClose = { safelyCloseBaseActivity() },
								closeActivityOnSuccessfulDownload = activityClosingCondition,
								errorCallBack = {
									if (shouldOpenBrowserAsFallback) showOpeningInBrowserPrompt(videoUrl)
									else onOpenBrowser?.invoke()
								}
							).show()
						}
					}
				}
			}
		}
	}

	/**
	 * Retrieves a list of available YouTube video resolutions (and audio-only option) from a [StreamInfo] object.
	 * If no stream data is provided (null), returns a default list of resolutions.
	 *
	 * @param ytStreamInfo The [StreamInfo] object containing YouTube stream data. Can be null.
	 * @return A list of [VideoFormat] objects representing available resolutions/audio options.
	 *         Each [VideoFormat] includes:
	 *         - `formatId`: Identifier (uses app package name).
	 *         - `formatResolution`: Resolution (e.g., "720p") or "Audio" for audio-only.
	 *         - `formatFileSize`: Placeholder text (replace with actual size if available).
	 */
	private fun getYoutubeVideoResolutions(ytStreamInfo: StreamInfo?): ArrayList<VideoFormat> {
		logger.d("Getting YouTube video resolutions")
		// Case 1: No stream info provided → return default resolutions
		if (ytStreamInfo == null) {
			logger.d("No stream info, returning default resolutions")
			return arrayListOf(
				"Audio", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p"
			).map { resolution ->
				VideoFormat(
					formatId = INSTANCE.packageName,
					formatResolution = resolution,
					formatFileSize = getText(R.string.title_unknown)
				)
			}.let { ArrayList(it) }
		}
		// Case 2: Stream info available → extract actual resolutions
		else {
			logger.d("Processing stream info for resolutions")
			val formats = mutableListOf<VideoFormat>()

			// Add audio-only option (always first)
			ytStreamInfo.audioStreams
				.maxByOrNull { it.bitrate }?.let { bestStream ->
					val contentLength = bestStream.itagItem?.contentLength
					formats.add(
						VideoFormat(
							formatId = INSTANCE.packageName,
							formatResolution = "Audio",
							formatFileSize = if (contentLength != null && contentLength > 0L == true)
								DownloaderUtils.getHumanReadableFormat(contentLength) else getText(
								R.string.title_unknown
							)
						)
					)
				}

			// Process video streams:
			// 1. Filter streams with valid bitrate.
			// 2. Group by height (resolution).
			// 3. Select the stream with highest bitrate per resolution.
			ytStreamInfo.videoOnlyStreams
				.filter { it.bitrate > 0 }
				.groupBy { it.height }
				.forEach { (_, streams) ->
					streams.maxByOrNull { it.bitrate }?.let { bestStream ->
						val contentLength = bestStream.itagItem?.contentLength
						formats.add(
							VideoFormat(
								formatId = INSTANCE.packageName,
								formatResolution = "${bestStream.height}p",
								formatFileSize = if (contentLength != null && contentLength > 0L == true)
									DownloaderUtils.getHumanReadableFormat(contentLength) else getText(
									R.string.title_unknown
								)
							)
						)
					}
				}

			logger.d("Found ${formats.size} video formats")
			return ArrayList(formats)
		}
	}

	/**
	 * Shows a fallback prompt to open link in browser when no video format is found.
	 */
	private fun showOpeningInBrowserPrompt(videoUrl: String) {
		logger.d("Showing browser fallback prompt")
		val msgResId = R.string.text_error_failed_to_fetch_video_format
		val buttonTextResId = R.string.title_open_link_in_browser
		showMessageDialog(
			baseActivityInf = safeBaseActivityRef,
			isNegativeButtonVisible = false,
			messageTextViewCustomize = { it.setText(msgResId) },
			positiveButtonTextCustomize = {
				it.setText(buttonTextResId)
				it.setLeftSideDrawable(R.drawable.ic_button_open_v2)
			}
		)?.apply {
			setOnClickForPositiveButton {
				close(); openInBuiltInBrowser(videoUrl)
			}
		}
	}

	/**
	 * Tries to open the URL using the app's internal browser.
	 */
	private fun openInBuiltInBrowser(urlFromIntent: String) {
		logger.d("Opening URL in built-in browser: $urlFromIntent")
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			try {
				if (safeBaseActivityRef is MotherActivity) {
					val fileUrl: String = urlFromIntent
					val browserFragment = safeBaseActivityRef.browserFragment
					val browserFragmentBody = browserFragment?.browserFragmentBody
					val webviewEngine = browserFragmentBody?.webviewEngine ?: return

					if (fileUrl.isNotEmpty() && isValidURL(fileUrl)) {
						safeBaseActivityRef.sideNavigation?.addNewBrowsingTab(
							fileUrl,
							webviewEngine
						)
						safeBaseActivityRef.openBrowserFragment()
					} else showInvalidUrlToast()
				} else onOpenBrowser?.let { it() }
			} catch (error: Exception) {
				logger.d("Error opening in built-in browser: ${error.message}")
				error.printStackTrace()
				openInSystemBrowser(urlFromIntent)
			}
		}
	}

	/**
	 * Opens the URL using the default system browser.
	 */
	private fun openInSystemBrowser(urlFromIntent: String) {
		logger.d("Opening URL in system browser: $urlFromIntent")
		openLinkInSystemBrowser(urlFromIntent, safeBaseActivityRef) {
			showToast(
				activity = safeBaseActivityRef,
				msgId = R.string.title_failed_open_the_video
			)
		}
	}

	/** Shows a toast indicating invalid or malformed URL. */
	private fun showInvalidUrlToast() {
		logger.d("Showing invalid URL toast")
		showToast(
			activity = safeBaseActivityRef,
			msgId = R.string.title_invalid_url
		)
	}

	/**
	 * Handles cases where YouTube URL is detected but download isn't allowed.
	 * @return true if URL is blocked due to YouTube restrictions.
	 */
	private fun isYoutubeUrlDetected(urlFromIntent: String): Boolean {
		logger.d("Checking for YouTube URL restrictions")
		if (IS_ULTIMATE_VERSION_UNLOCKED) return false
		if (isYouTubeUrl(urlFromIntent)) {
			executeOnMainThread {
				waitingDialog?.let { waitingDialog ->
					delay(200, object : OnTaskFinishListener {
						override fun afterDelay() {
							waitingDialog.close()
							logger.d("Showing YouTube restrictions dialog")
							val titleResId = R.string.title_content_download_policy
							val msgResId = R.string.text_not_support_youtube_download
							val positiveButtonResId = R.string.title_content_policy
							val positiveButtonImgResId = R.drawable.ic_button_arrow_next
							R.string.title_cancel
							R.drawable.ic_button_cancel
							showMessageDialog(
								baseActivityInf = safeBaseActivityRef,
								isTitleVisible = true,
								isNegativeButtonVisible = false,
								titleTextViewCustomize = { it.setText(titleResId) },
								messageTextViewCustomize = { it.setText(msgResId) },
								positiveButtonTextCustomize = {
									it.setText(positiveButtonResId)
									it.setLeftSideDrawable(positiveButtonImgResId)
								}
							)?.apply {
								setOnClickForPositiveButton {
									close()
									//Todo: open terms and condition url
								}
							}
						}
					})
				}
			}; return true
		}; return false
	}

	/**
	 * Cancels the ongoing interception when user dismisses the dialog.
	 */
	private fun onCancelInterceptRequested(waitingDialog: WaitingDialog) {
		logger.d("Interception cancelled by user")
		waitingDialog.dialogBuilder?.let { dialogBuilder ->
			if (dialogBuilder.isShowing) {
				dialogBuilder.close()
				safelyCloseBaseActivity()
			}
		}
		isInterceptingInProcess = false
		isInterceptingTerminated = true
	}

	/**
	 * Safely close the base activity only if activity is of IntentInterceptActivity.
	 */
	private fun safelyCloseBaseActivity() {
		logger.d("Safely closing base activity")
		executeOnMainThread {
			try {
				// Special handling for IntentInterceptActivity
				val condition = safeBaseActivityRef is IntentInterceptActivity
				if (condition) safeBaseActivityRef?.finish()
			} catch (error: Exception) {
				logger.e("Error closing activity: ${error.message}", error)
			}
		}
	}
}