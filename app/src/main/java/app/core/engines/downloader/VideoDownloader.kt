package app.core.engines.downloader

import android.annotation.SuppressLint
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.internalDataFolder
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.cleanYtdlpLoggingSting
import app.core.engines.video_parser.parsers.VideoFormatsUtils.formatDownloadSpeedForYtDlp
import app.core.engines.video_parser.parsers.VideoFormatsUtils.isValidSpeedFormat
import app.core.engines.video_parser.parsers.VideoParserUtility.getSanitizedTitle
import app.core.engines.video_parser.parsers.VideoParserUtility.getVideoTitleFromURL
import com.aio.R
import com.anggrayudi.storage.file.getAbsolutePath
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import lib.device.AppVersionUtility.versionName
import lib.device.DateTimeUtils.calculateTime
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSystemUtility.findFileStartingWith
import lib.files.FileSystemUtility.isFileNameValid
import lib.files.FileSystemUtility.isVideoByName
import lib.files.FileSystemUtility.sanitizeFileNameExtreme
import lib.networks.DownloaderUtils.getAudioPlaybackTimeIfAvailable
import lib.networks.DownloaderUtils.getFormattedPercentage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.DownloaderUtils.renameIfDownloadFileExistsWithSameName
import lib.networks.DownloaderUtils.updateSmartCatalogDownloadDir
import lib.networks.DownloaderUtils.validateExistedDownloadedFileName
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.isInternetConnected
import lib.networks.URLUtilityKT.isUrlExpired
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.AudioPlayerUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.texts.CommonTextUtils.capitalizeWords
import lib.texts.CommonTextUtils.generateRandomString
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A class that handles video downloads using youtube-dl/yt-dlp engine.
 * Supports social media downloads, adaptive bitrate streaming, and progress tracking.
 * Implements [DownloadTaskInf] interface for download task management.
 */
class VideoDownloader(override val downloadDataModel: DownloadDataModel) : DownloadTaskInf {

	// Configuration from the download model
	private val downloadDataModelConfig = downloadDataModel.globalSettings

	// Destination file where the download will be saved
	private var destinationFile = downloadDataModel.getDestinationFile()

	// Timer for retrying downloads when network issues occur
	private var retryingDownloadTimer: CountDownTimer? = null

	// Listener for download status updates
	override var statusListener: DownloadTaskListener? = null

	// Flag to track if WebView is currently loading (for cookie extraction)
	private var isWebViewLoading = false

	/**
	 * Initiates the download task by performing necessary setup in a background thread.
	 * - Initializes the download data model.
	 * - Sets up a download timer.
	 * - Updates the download status.
	 */
	override fun initiate() {
		executeInBackground {
			initDownloadDataModel()
			initDownloadTaskTimer()
			updateDownloadStatus()
		}
	}

	/**
	 * Starts the download process.
	 * - Updates the status to 'preparing'.
	 * - Configures the download model based on settings.
	 * - Creates an empty destination file.
	 * - Checks if the download is from a social media URL and chooses the appropriate method to proceed.
	 */
	override fun startDownload() {
		updateDownloadStatus(getText(R.string.title_preparing_download), DOWNLOADING)
		configureDownloadModel()
		createEmptyDestinationFile()
		if (isSocialMediaUrl(downloadDataModel.fileURL)) startSocialMediaDownload()
		else startRegularDownload()
	}

	/**
	 * Cancels the ongoing download and updates the status accordingly.
	 *
	 * @param cancelReason A reason string for the cancellation. If empty, shows 'Paused' as default message.
	 */
	override fun cancelDownload(cancelReason: String) {
		try {
			closeYTDLProgress()
			val statusMessage = cancelReason.ifEmpty { getText(R.string.title_paused) }
			updateDownloadStatus(statusMessage, CLOSE)
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Initializes the `downloadDataModel` with default values and resets temporary state.
	 */
	private fun initDownloadDataModel() {
		downloadDataModel.status = CLOSE
		downloadDataModel.isRunning = false
		downloadDataModel.isWaitingForNetwork = false
		downloadDataModel.totalConnectionRetries = 0
		downloadDataModel.statusInfo = getText(R.string.text_waiting_to_join)
		initBasicDownloadModelInfo()
		downloadDataModel.updateInStorage()
	}

	/**
	 * Starts a repeating timer that periodically checks network availability.
	 * If waiting for network, it attempts to restart the download every 5 seconds.
	 */
	private fun initDownloadTaskTimer() {
		executeOnMainThread {
			retryingDownloadTimer = object : CountDownTimer((1000 * 60), 5000) {
				override fun onTick(millisUntilFinished: Long) {
					if (downloadDataModel.isWaitingForNetwork) {
						executeInBackground(::restartDownload)
					}
				}

				override fun onFinish() {
					if (downloadDataModel.isWaitingForNetwork) start()
				}
			}
		}
	}

	/**
	 * Applies all necessary configurations to the download model:
	 * - YTDLP settings
	 * - Auto-resume
	 * - Auto-remove
	 * - Part range allocation
	 */
	private fun configureDownloadModel() {
		configureDownloadModelForYTDLP()
		configureDownloadAutoResumeSettings()
		configureDownloadAutoRemoveSettings()
		configureDownloadPartRange()
	}

	/**
	 * Prepares and configures the downloadDataModel for execution via yt-dlp.
	 *
	 * Workflow:
	 * - Ensures smart category directory setup and handles filename collisions.
	 * - Generates a sanitized, unique temporary filename for yt-dlp downloads.
	 * - Assigns execution command for yt-dlp with the selected video format.
	 * - Resets error flags/messages and persists updated state.
	 */
	private fun configureDownloadModelForYTDLP() {
		val videoFormat = downloadDataModel.videoFormat!!

		// Ensure download directory and filename setup if not processed
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			updateSmartCatalogDownloadDir(downloadDataModel)
			renameIfDownloadFileExistsWithSameName(downloadDataModel)

			val internalDirPath = internalDataFolder.getAbsolutePath(INSTANCE)

			// Generate a unique random filename in the internal directory
			var randomFileName: String
			do {
				randomFileName = sanitizeFileNameExtreme(generateRandomString(10))
			} while (File(internalDirPath, randomFileName).exists())

			// Validate filename against existing downloads
			val sanitizedTempName = validateExistedDownloadedFileName(internalDirPath, randomFileName)

			// Assign temp yt-dlp destination file
			val ytTempDownloadFile = File(internalDirPath, sanitizedTempName)
			downloadDataModel.tempYtdlpDestinationFilePath = ytTempDownloadFile.absolutePath

			// Set final destination file
			destinationFile = downloadDataModel.getDestinationFile()
			downloadDataModel.isSmartCategoryDirProcessed = true
		}

		// Configure yt-dlp command for the selected format
		downloadDataModel.executionCommand = getYtdlpExecutionCommand(videoFormat)

		// Reset error state
		downloadDataModel.isYtdlpHavingProblem = false
		downloadDataModel.ytdlpProblemMsg = ""

		// Persist updated state
		downloadDataModel.updateInStorage()

		println("yt-dlp execution command set to: ${downloadDataModel.executionCommand}")
	}

	/**
	 * Extracts the vertical resolution (height in pixels) from a resolution string.
	 *
	 * Supported formats:
	 * - "1280x720", "1920×1080" (using 'x' or '×' as a separator)
	 * - "720p", "1080P"
	 * - "1280px720p", "1920Px1080P"
	 * - "720" (a standalone number)
	 *
	 * @param resolutionStr The resolution string to parse.
	 * @return The extracted height as an Int, or null if no valid resolution found.
	 */
	private fun extractResolutionNumber(resolutionStr: String): Int? {
		val patterns = listOf(
			// Pattern 1: 1280x720 or 1920×1080 (with × symbol)
			Regex("""(\d{3,4})[xX×](\d{3,4})"""),
			// Pattern 2: 720p or 1080P
			Regex("""(\d{3,4})[pP]"""),
			// Pattern 3: 1280px720p or 1920Px1080P
			Regex("""(\d{3,4})[pPxX×](\d{3,4})[pP]"""),
			// Pattern 4: Standalone number
			Regex("""^(\d{3,4})$""")
		)

		for (regex in patterns) {
			val match = regex.find(resolutionStr) ?: continue
			// Return the last numeric group (height)
			return match.groupValues.last {
				it.isNotEmpty() && it.all(Char::isDigit)
			}.toIntOrNull()
		}

		return null
	}

	/**
	 * Initializes the [downloadDataModel] with core metadata and default flags
	 * required before starting a YTDLP download.
	 *
	 * What this method does:
	 * - Prevents re-initialization if already initialized.
	 * - Resolves the target filename and applies sanitization if needed.
	 * - Ensures the video title is present (extracts from URL if missing).
	 * - Determines output file extension (`.mp3` for audio-only, `.mp4` otherwise).
	 * - Assigns the video URL and site referrer.
	 * - Applies default download flags (resume support, threading, etc.).
	 * - Records the download start timestamp.
	 */
	private fun initBasicDownloadModelInfo() {
		// Skip if the model has already been initialized
		if (downloadDataModel.isBasicYtdlpModelInitialized) return

		val videoInfo = downloadDataModel.videoInfo!!
		val videoFormat = downloadDataModel.videoFormat!!

		// Determine file extension based on whether it's audio-only or not
		val formatResolution = videoFormat.formatResolution.lowercase()
		val fileExtension: String =
			if (formatResolution.contains("audio", ignoreCase = true)) "mp3" else "mp4"

		// Ensure video title is not empty: fallback to URL-derived title if missing
		if (videoInfo.videoTitle.isNullOrEmpty()) {
			val titleFromURL = getVideoTitleFromURL(videoInfo.videoUrl)
			videoInfo.videoTitle =
				titleFromURL.ifEmpty(::madeUpTitleFromSelectedVideoFormat)
		}

		// Assign essential fields: URL, referrer, filename
		downloadDataModel.fileURL = videoInfo.videoUrl
		downloadDataModel.siteReferrer = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
		downloadDataModel.fileName =
			"${getSanitizedTitle(videoInfo, videoFormat)}.$fileExtension"

		// If filename is invalid, regenerate with strict sanitization
		if (!isFileNameValid(downloadDataModel.fileName)) {
			downloadDataModel.fileName =
				"${getSanitizedTitle(videoInfo, videoFormat, true)}.$fileExtension"
		}

		// Set baseline download flags
		downloadDataModel.isUnknownFileSize = false
		downloadDataModel.isMultiThreadSupported = false
		downloadDataModel.isResumeSupported = true
		downloadDataModel.globalSettings.downloadDefaultThreadConnections = 1

		// Record download start timestamp (both raw and human-readable formats)
		System.currentTimeMillis().apply {
			downloadDataModel.startTimeDate = this
			downloadDataModel.startTimeDateInFormat = millisToDateTimeString(this)
		}

		// Mark initialization as complete
		downloadDataModel.isBasicYtdlpModelInitialized = true
	}

	/**
	 * Generates a fallback title string when the video has no valid title.
	 *
	 * The constructed title uses technical details from the selected format
	 * and the base domain of the video URL. This ensures the resulting title
	 * is unique enough for identification, even if not user-friendly.
	 *
	 * Output format:
	 *   <formatId>_<resolution>_<videoCodec>_<baseDomain>
	 *
	 * Example:
	 *   "137_1080p_avc1_youtube.com"
	 *
	 * @return A machine-generated title string derived from format metadata.
	 */
	private fun madeUpTitleFromSelectedVideoFormat(): String {
		val videoFormat = downloadDataModel.videoFormat!!

		// Build a synthetic title by concatenating format details + domain
		val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"${capitalizeWords(getBaseDomain(downloadDataModel.videoInfo!!.videoUrl))}" +
				"Downloaded_By_AIO_v${versionName}_"

		return madeUpTitle
	}

	/**
	 * Builds the execution command string for YTDLP based on the selected video format.
	 *
	 * Command logic:
	 * 1. **Special "dynamic" format case** (when formatId == app's packageName):
	 *    - If the format comes from a social media site:
	 *        → Always request best video up to 2400p + best audio
	 *        → Fallback: best[height<=2400] / best
	 *    - Otherwise (normal sites):
	 *        → Use the resolution number (if extracted) to build a height filter
	 *          (e.g., bestvideo[height<=1080]+bestaudio).
	 *        → If no resolution number is available, fallback to generic `bestvideo+bestaudio/best`.
	 *        → For YouTube links specifically:
	 *            - If the format resolution is audio-only, force "bestaudio".
	 *            - Otherwise, use the common pattern.
	 *
	 * 2. **Normal format case** (when formatId != app's packageName):
	 *    - Just return the `formatId` directly.
	 *
	 * This method ensures that the generated YTDLP command adapts dynamically
	 * based on resolution, source type (YouTube vs. others), and audio-only cases.
	 *
	 * @param videoFormat The selected video format metadata (id, resolution, etc.).
	 * @return A valid YTDLP command string for format selection.
	 */
	private fun getYtdlpExecutionCommand(videoFormat: VideoFormat): String {
		val packageName = INSTANCE.packageName
		val resolutionNumber = extractResolutionNumber(videoFormat.formatResolution)

		return if (videoFormat.formatId == packageName) {
			// Case 1: Dynamic handling if formatId matches package name
			if (videoFormat.isFromSocialMedia) {
				// Social media sites → always cap resolution at 2400p
				"bestvideo[height<=2400]+bestaudio/best[height<=2400]/best"
			} else {
				// Build a resolution-specific pattern, or fallback if not available
				val commonPattern = if (resolutionNumber != null) {
					"bestvideo[height<=$resolutionNumber]+bestaudio/best[height<=$resolutionNumber]/best"
				} else {
					"bestvideo+bestaudio/best"
				}

				// Special handling for YouTube
				if (isYouTubeUrl(downloadDataModel.fileURL)) {
					val isAudio = videoFormat.formatResolution.contains("audio", true)
					if (isAudio) "bestaudio" else commonPattern
				} else {
					commonPattern
				}
			}
		} else {
			// Case 2: Normal formats → just return the formatId
			videoFormat.formatId
		}
	}

	/**
	 * Configures the auto-resume behavior for a download task.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - If auto-resume is **disabled** in the config, forcefully sets the maximum
	 *   allowed resume error count to `0`.
	 *
	 * Purpose:
	 * Ensures that downloads won't automatically retry/resume when the user
	 * has explicitly disabled auto-resume functionality.
	 */
	private fun configureDownloadAutoResumeSettings() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			if (!downloadDataModelConfig.downloadAutoResume)
				downloadDataModelConfig.downloadAutoResumeMaxErrors = 0
		}
	}

	/**
	 * Configures the auto-remove behavior for completed downloads.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - If auto-remove is **disabled** in the config, resets the
	 *   "remove after N days" timer to `0`.
	 *
	 * Purpose:
	 * Ensures that completed downloads remain in storage indefinitely
	 * unless the user has explicitly enabled auto-removal.
	 */
	private fun configureDownloadAutoRemoveSettings() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			if (!downloadDataModelConfig.downloadAutoRemoveTasks)
				downloadDataModelConfig.downloadAutoRemoveTaskAfterNDays = 0
		}
	}

	/**
	 * Initializes the download part-ranges for multi-threaded downloading.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - Creates arrays to track:
	 *   1. Bytes downloaded per thread (`partsDownloadedByte`).
	 *   2. Progress percentage per thread (`partProgressPercentage`).
	 *
	 * Purpose:
	 * Allows accurate tracking and coordination of multiple threads
	 * working in parallel on different parts of the same file.
	 */
	private fun configureDownloadPartRange() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			val numberOfThreads = downloadDataModelConfig.downloadDefaultThreadConnections
			downloadDataModel.partsDownloadedByte = LongArray(numberOfThreads)
			downloadDataModel.partProgressPercentage = IntArray(numberOfThreads)
		}
	}

	/**
	 * Updates the overall download progress.
	 *
	 * Synchronized to avoid race conditions between multiple threads.
	 *
	 * Steps performed:
	 * 1. Recalculate the current download progress and update the model.
	 * 2. Check the network connection and attempt a retry if needed.
	 * 3. Update the download's status (active, paused, failed, etc.).
	 * 4. Persist the latest state to storage (so progress isn't lost).
	 *
	 * Purpose:
	 * Keeps the download model consistent, reliable, and crash-safe during execution.
	 */
	@Synchronized
	private fun updateDownloadProgress() {
		calculateProgressAndModifyDownloadModel()
		checkNetworkConnectionAndRetryDownload()
		updateDownloadStatus()
	}

	/**
	 * Updates the status of the download and notifies the registered listener (if any).
	 *
	 * @param statusInfo Optional description of the new status.
	 * @param status Numeric status code indicating the current download state.
	 */
	override fun updateDownloadStatus(statusInfo: String?, status: Int) {
		// Update status info if provided
		if (!statusInfo.isNullOrEmpty()) downloadDataModel.statusInfo = statusInfo

		// Update model flags and status
		val classRef = this@VideoDownloader
		downloadDataModel.status = status
		downloadDataModel.isRunning = (status == DOWNLOADING)
		downloadDataModel.isComplete = (status == COMPLETE)

		// Persist updated status
		downloadDataModel.updateInStorage()

		// Notify UI/listener on the main thread
		executeOnMainThread {
			statusListener?.onStatusUpdate(classRef)
		}
	}

	/**
	 * Recalculates progress metrics (time, bytes, percentage),
	 * updates the model, and persists the result.
	 */
	private fun calculateProgressAndModifyDownloadModel() {
		calculateTotalDownloadedTime()     // Update time spent
		calculateDownloadedBytes()         // Update downloaded size
		calculateDownloadPercentage()      // Update completion %
		updateLastModificationDate()       // Update last modified timestamp

		// Save updated model state
		downloadDataModel.updateInStorage()
	}

	/**
	 * Checks network connectivity and resumes download if possible.
	 * Handles waiting state, retry logic, and process execution.
	 */
	private fun checkNetworkConnectionAndRetryDownload() {
		// Close YTDL if connection is not valid
		if (!verifyNetworkConnection()) closeYTDLProgress()

		// If waiting for network, check again
		if (downloadDataModel.isWaitingForNetwork) {
			if (isNetworkAvailable() && isInternetConnected()) {
				// If WiFi-only is enabled, ensure WiFi is active
				if (downloadDataModelConfig.downloadWifiOnly && !isWifiEnabled()) return

				// Resume download once network is ready
				downloadDataModel.isWaitingForNetwork = false
				updateDownloadStatus(getText(R.string.title_started_downloading))
				executeDownloadProcess()
			}
		}
	}

	/**
	 * Forcefully terminates any active YTDL process
	 * related to the current download task.
	 */
	private fun closeYTDLProgress() {
		getInstance().destroyProcessById(downloadDataModel.id.toString())
		println("YTDL progress closed for download task ID: ${downloadDataModel.id}")
	}

	/**
	 * Validates current network conditions.
	 *
	 * @return true if network is available and WiFi-only preference is respected.
	 */
	private fun verifyNetworkConnection(): Boolean {
		val isWifiOnly = downloadDataModelConfig.downloadWifiOnly
		return !(!isNetworkAvailable() || (isWifiOnly && !isWifiEnabled()))
	}

	/**
	 * Tracks the total download duration (excluding wait time)
	 * and updates the formatted time string in the model.
	 */
	private fun calculateTotalDownloadedTime() {
		// Increase time only if not waiting for network
		if (!downloadDataModel.isWaitingForNetwork)
			downloadDataModel.timeSpentInMilliSec += 500

		// Convert time to formatted string
		val timeSpentMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		val format = calculateTime(timeSpentMillis, getText(R.string.text_spent))
		downloadDataModel.timeSpentInFormat = format
	}

	/**
	 * Updates the downloaded byte count and size format by scanning
	 * temporary YTDLP part files. Also updates part progress.
	 */
	private fun calculateDownloadedBytes() {
		try {
			val ytTempDownloadFile = downloadDataModel.tempYtdlpDestinationFilePath
			val ytTempFileNamePrefix = File(ytTempDownloadFile).name
			val internalDir = internalDataFolder

			// Look for YTDLP-generated part files matching the temp prefix
			internalDir.listFiles()
				.filter { file ->
					file.isFile &&
							file.name!!.startsWith(ytTempFileNamePrefix) &&
							file.name!!.endsWith(".part")
				}
				.forEach { file ->
					try {
						// Update byte count from part file size
						downloadDataModel.downloadedByte = file.length()
						downloadDataModel.fileSize = downloadDataModel.downloadedByte
					} catch (error: Exception) {
						error.printStackTrace()
					}
				}

			// Update human-readable format and progress tracking
			val downloadedByte = downloadDataModel.downloadedByte
			downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadedByte)
			downloadDataModel.partsDownloadedByte[0] = downloadedByte

		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Calculates the overall download progress percentage.
	 * Updates both the numeric percentage and the formatted string.
	 */
	private fun calculateDownloadPercentage() {
		try {
			val totalProcess = downloadDataModel.progressPercentage
			downloadDataModel.partProgressPercentage[0] = totalProcess.toInt()
			downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Updates the download model's last modification timestamp
	 * and converts it into a formatted date-time string.
	 */
	private fun updateLastModificationDate() {
		downloadDataModel.lastModifiedTimeDate = System.currentTimeMillis()
		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
	}

	/**
	 * Creates an empty destination file for the download if it doesn't already exist.
	 * This file acts as a placeholder and ensures disk access is working correctly.
	 *
	 * If any I/O error occurs, the download is marked as failed and cancelled.
	 */
	private fun createEmptyDestinationFile() {
		if (downloadDataModel.isDeleted) return
		if (downloadDataModel.downloadedByte < 1) {
			try {
				if (destinationFile.exists()) return
				RandomAccessFile(destinationFile, "rw").setLength(108)
			} catch (error: IOException) {
				error.printStackTrace()
				downloadDataModel.totalConnectionRetries++
				downloadDataModel.isFailedToAccessFile = true
				cancelDownload(getText(R.string.title_download_io_failed))
			}
		}
	}

	/**
	 * Starts the download process for social media content.
	 * This typically involves checking if the download is initiated from a browser.
	 *
	 * If not browser-based, it triggers a regular download; otherwise,
	 * it resumes the process in a background thread.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private fun startSocialMediaDownload() {
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie)
		if (!downloadDataModel.isDownloadFromBrowser) {
			downloadDataModel.isDownloadFromBrowser = false
			downloadDataModel.updateInStorage()
			startRegularDownload()
		} else {
			executeInBackground(codeBlock = {
				downloadDataModel.isDownloadFromBrowser = false
				downloadDataModel.updateInStorage()
				executeDownloadProcess()
			})
		}
	}

	/**
	 * Initiates the standard download procedure, typically used when no browser involvement is needed.
	 * This method supports retries up to two times, falling back to direct execution on failure.
	 *
	 * It loads the download URL in a WebView to fetch cookies or session data if needed,
	 * and then triggers the actual download execution once the page finishes loading.
	 *
	 * @param retryCount Indicates how many times this method has been retried so far.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private fun startRegularDownload(retryCount: Int = 0) {
		// If max retries reached → fallback to direct process
		if (retryCount >= 2) {
			executeInBackground { executeDownloadProcess() }
			return
		}

		// Update status before initializing WebView
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie)

		Handler(Looper.getMainLooper()).post {
			val webView = WebView(INSTANCE).apply {
				settings.javaScriptEnabled = true

				// Handle page load success / failure
				webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView?, url: String?) {
						if (isWebViewLoading) {
							handlePageLoaded(url)
							destroy()
						}
					}

					@Deprecated("Deprecated in Java")
					override fun onReceivedError(
						view: WebView?, errorCode: Int,
						description: String?, failingUrl: String?
					) = retryOrFail(retryCount)
				}

				// Handle page title event as a backup signal for load complete
				webChromeClient = object : WebChromeClient() {
					override fun onReceivedTitle(view: WebView?, title: String?) {
						super.onReceivedTitle(view, title)
						if (isWebViewLoading) {
							handlePageLoaded(url)
							destroy()
						}
					}
				}

				// Start loading the target URL
				loadUrl(downloadDataModel.fileURL)
				isWebViewLoading = true
			}

			// Timeout safeguard → destroy and retry if still stuck
			Handler(Looper.getMainLooper()).postDelayed({
				if (isWebViewLoading) {
					webView.destroy()
					retryOrFail(retryCount)
				}
			}, 10_000)
		}
	}

	/**
	 * Called when the WebView has finished loading the target page.
	 * Extracts cookies from the loaded URL and saves them into the data model.
	 *
	 * Triggers the actual download process in the background once cookies are obtained.
	 *
	 * @param url The URL that was loaded in the WebView.
	 */
	private fun handlePageLoaded(url: String?) {
		// Mark WebView as no longer loading
		isWebViewLoading = false

		// Try to capture cookies from the loaded URL
		val cookies = CookieManager.getInstance().getCookie(url)
		cookies?.let {
			downloadDataModel.apply {
				videoInfo?.videoCookie = cookies
				siteCookieString = cookies
				updateInStorage()
				saveCookiesIfAvailable(shouldOverride = true) // Persist cookies for reuse
			}
		}

		// Continue with actual download in background
		executeInBackground {
			executeDownloadProcess()
		}
	}

	/**
	 * Handles retrying of the WebView-based download initialization.
	 * If retry count is within limit, restarts the download using the regular path.
	 *
	 * @param currentRetry The number of retries that have already been attempted.
	 */
	private fun retryOrFail(currentRetry: Int) {
		isWebViewLoading = false
		Handler(Looper.getMainLooper()).post {
			startRegularDownload(currentRetry + 1)
		}
	}

	/**
	 * Executes the actual download command using yt-dlp.
	 * Builds and runs the YoutubeDLRequest with various custom options.
	 * Handles progress updates, cookie injection, retry logic, and success/failure cases.
	 */
	private fun executeDownloadProcess() {
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_connecting_to_the_server)

		val response: YoutubeDLResponse?
		try {
			var lastUpdateTime = System.currentTimeMillis()
			val urlWithoutPlaylist = filterYoutubeUrlWithoutPlaylist(downloadDataModel.fileURL)
			val request = YoutubeDLRequest(urlWithoutPlaylist)

			// Add standard options
			request.addOption("--continue")
			request.addOption("-f", downloadDataModel.executionCommand)
			request.addOption("-o", downloadDataModel.tempYtdlpDestinationFilePath)
			request.addOption("--playlist-items", "1")
			request.addOption("--user-agent", downloadDataModelConfig.downloadHttpUserAgent)
			request.addOption("--retries", downloadDataModelConfig.downloadAutoResumeMaxErrors)
			request.addOption("--socket-timeout", downloadDataModelConfig.downloadMaxHttpReadingTimeout)
			request.addOption("--concurrent-fragments", 10)
			request.addOption("--fragment-retries", 10)
			request.addOption("--no-check-certificate")
			request.addOption("--force-ipv4")
			request.addOption("--source-address", "0.0.0.0")
			if (isVideoByName(downloadDataModel.fileName)) {
				request.addOption("--merge-output-format", "mp4")
			}

			// Add cookie support if available
			downloadDataModel.getCookieFilePathIfAvailable()?.let {
				val cookieFile = File(it)
				if (cookieFile.exists() && cookieFile.canWrite()) {
					println("Cookie File Found=${cookieFile.absolutePath}")
					request.addOption("--cookies", cookieFile.absolutePath)
				}
			}

			// Add download speed throttling if configured
			if (downloadDataModelConfig.downloadMaxNetworkSpeed > 0) {
				val downloadMaxNetworkSpeed = downloadDataModelConfig.downloadMaxNetworkSpeed
				val ytDlpSpeedLimit = formatDownloadSpeedForYtDlp(downloadMaxNetworkSpeed)
				if (isValidSpeedFormat(ytDlpSpeedLimit)) {
					request.addOption("--limit-rate", ytDlpSpeedLimit)
					println("ytDlpSpeedLimit=$ytDlpSpeedLimit")
				}
			}

			// Execute yt-dlp request with progress updates
			response = getInstance().execute(
				request = request,
				processId = downloadDataModel.id.toString()
			) { progress, _, status ->
				if (progress > 0) downloadDataModel.progressPercentage = progress.toLong()
				downloadDataModel.tempYtdlpStatusInfo = cleanYtdlpLoggingSting(status)
				println(downloadDataModel.tempYtdlpStatusInfo)

				val currentTime = System.currentTimeMillis()
				if (currentTime - lastUpdateTime >= 500) {
					lastUpdateTime = currentTime
					updateDownloadProgress()
				}
			}

			// Handle yt-dlp completion or failure
			if (response.exitCode != 0) onYtdlpDownloadFailed(response.out) else {
				moveToUserSelectedDestination()

				if (downloadDataModelConfig.downloadPlayNotificationSound)
					AudioPlayerUtils(INSTANCE).play(R.raw.sound_download_finished)

				downloadDataModel.isRunning = false
				downloadDataModel.isComplete = true
				updateDownloadStatus(getText(R.string.text_completed), COMPLETE)
				println("Download status updated to COMPLETE.")
				println("Download completed successfully in ${response.elapsedTime} ms.")
			}
		} catch (error: Exception) {
			error.printStackTrace()

			if (isRetryingAllowed()) {
				onYtdlpDownloadFailed(error.message)
			} else {
				cancelDownload()
				updateDownloadStatus(getText(R.string.title_paused), CLOSE)
			}
		}
	}

	/**
	 * Handles failures during a YTDLP download attempt.
	 * Determines the failure type (critical error, expired link, missing file, etc.),
	 * and either cancels, retries, or restarts the download accordingly.
	 *
	 * @param response Optional error message returned from YTDLP.
	 */
	private fun onYtdlpDownloadFailed(response: String? = null) {
		executeInBackground(codeBlock = {
			if (response != null && isCriticalErrorFound(response)) {
				// Critical error handling
				if (downloadDataModel.isYtdlpHavingProblem) {
					val pausedMsg = downloadDataModel.ytdlpProblemMsg.ifEmpty {
						getText(R.string.title_paused)
					}
					cancelDownload(pausedMsg) // Pause due to persistent YTDLP issues
					return@executeInBackground
				}

				if (downloadDataModel.isFileUrlExpired) {
					cancelDownload(getText(R.string.title_link_expired)) // Expired link
					return@executeInBackground
				}

				if (downloadDataModel.isDestinationFileNotExisted) {
					cancelDownload(getText(R.string.title_file_deleted_paused)) // Target file deleted
					return@executeInBackground
				}
			} else if (!response.isNullOrEmpty()) {
				// Generic failure with error response
				updateDownloadStatus(getText(R.string.title_download_failed))
				cancelDownload(getText(R.string.title_download_failed))

			} else {
				// No response → retry logic
				restartDownload()
				retryingDownloadTimer?.start()
			}

			// Always persist the latest state
			downloadDataModel.updateInStorage()

			// Clean up destination file if marked deleted
			if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
				if (destinationFile.exists()) destinationFile.delete()
			}
		})
	}

	/**
	 * Checks if the download has failed due to any critical issues.
	 * Updates appropriate flags in the downloadDataModel and persists the state.
	 *
	 * @param response The error message or output received from yt-dlp.
	 * @return `true` if a critical error is detected, otherwise `false`.
	 */
	private fun isCriticalErrorFound(response: String): Boolean {
		// Case 1: YTDLP server/internal issue
		if (isYtdlHavingProblem(response)) {
			downloadDataModel.isYtdlpHavingProblem = true

			// Set problem message if not already set
			if (downloadDataModel.ytdlpProblemMsg.isEmpty()) {
				val msgString = getText(R.string.title_paused_server_problem)
				downloadDataModel.ytdlpProblemMsg = msgString
			}

			downloadDataModel.updateInStorage()
			return true
		}

		// Case 2: Expired file URL
		if (isUrlExpired(downloadDataModel.fileURL)) {
			downloadDataModel.isFileUrlExpired = true
			return true
		}

		// Case 3: Destination file missing/deleted
		if (!destinationFile.exists()) {
			downloadDataModel.isDestinationFileNotExisted = true
			return true
		}

		// No critical issues found
		return false
	}

	/**
	 * Identifies if the given yt-dlp response contains any known fatal issues.
	 * Sets a human-readable message to `ytdlpProblemMsg` accordingly.
	 *
	 * @param response The raw response string from yt-dlp.
	 * @return `true` if a known yt-dlp problem is detected, otherwise `false`.
	 */
	private fun isYtdlHavingProblem(response: String): Boolean {
		return when {
			// Login required or rate limit hit
			response.contains("rate-limit reached or login required", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				true
			}

			// Content not available
			response.contains("Requested content is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg =
					getText(R.string.title_paused_content_not_available)
				true
			}

			// Requested format not available
			response.contains("Requested format is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg =
					getText(R.string.title_paused_ytdlp_format_not_found)
				true
			}

			// Restricted video (region or login required)
			response.contains("Restricted Video", ignoreCase = true) ||
					response.contains("--cookies for the authentication", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				true
			}

			// Network / ISP ban issues
			response.contains("Connection reset by peer", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_site_banned_in_your_area)
				true
			}

			// Generic yt-dlp exception
			response.contains("YoutubeDLException", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = INSTANCE.getString(
					R.string.title_server_issue,
					getText(R.string.title_download_failed)
				)
				true
			}

			else -> false
		}
	}

	/**
	 * Attempts to restart the download process if retrying is allowed.
	 * Handles different network conditions (no network, no Wi-Fi, no internet)
	 * by updating the model state and waiting until conditions improve.
	 *
	 * - If conditions are satisfied, the download process is resumed.
	 * - Increments retry counter for tracking connection attempts.
	 */
	private fun restartDownload() {
		if (!isRetryingAllowed()) return

		// Case 1: No network available at all
		if (!isNetworkAvailable()) {
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_network))
			println("Network not available. Waiting for network.")
			return
		}

		// Case 2: Wi-Fi required but not enabled
		if (downloadDataModelConfig.downloadWifiOnly && !isWifiEnabled()) {
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_wifi))
			println("Wi-Fi is not enabled. Waiting for Wi-Fi.")
			return
		}

		// Case 3: Network available but no internet access
		if (!isInternetConnected()) {
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_internet))
			println("Internet connection is not available. Waiting for internet.")
			return
		}

		// Case 4: Network conditions are now favorable → restart the download
		if (downloadDataModel.isWaitingForNetwork) {
			downloadDataModel.isWaitingForNetwork = false
			updateDownloadStatus(getText(R.string.title_started_downloading))
			println("Network or Wi-Fi is now available. Restarting download.")
			executeDownloadProcess()
		}

		// Track retry attempts
		downloadDataModel.totalConnectionRetries++
	}

	/**
	 * Determines whether the download should be retried based on retry count and status.
	 *
	 * @return `true` if retrying is allowed, otherwise `false`.
	 */
	private fun isRetryingAllowed(): Boolean {
		val maxErrorAllowed = downloadDataModelConfig.downloadAutoResumeMaxErrors
		val retryAllowed = downloadDataModel.isRunning &&
				downloadDataModel.totalConnectionRetries < maxErrorAllowed
		return retryAllowed
	}

	/**
	 * Moves the downloaded temporary file into the user-selected destination folder.
	 *
	 * - Finds the temp file generated by yt-dlp.
	 * - Copies it to the destination file, handling overwrites.
	 * - Updates metadata (file size, progress, formatted sizes).
	 * - On failure, applies fallback strategies like renaming and retrying.
	 */
	private fun moveToUserSelectedDestination() {
		val inputFile = findFileStartingWith(
			internalDir = File(internalDataFolder.getAbsolutePath(INSTANCE)),
			namePrefix = File(downloadDataModel.tempYtdlpDestinationFilePath).name
		)

		inputFile?.let { tempFile ->
			try {
				// Copy temp file to final destination
				val outputFile = downloadDataModel.getDestinationFile()
				tempFile.copyTo(outputFile, overwrite = true)
				tempFile.delete()

				// Update metadata with final file stats
				downloadDataModel.fileSize = outputFile.length()
				downloadDataModel.fileSizeInFormat =
					getHumanReadableFormat(downloadDataModel.fileSize)

				downloadDataModel.downloadedByte = downloadDataModel.fileSize
				downloadDataModel.downloadedByteInFormat =
					getHumanReadableFormat(downloadDataModel.downloadedByte)

				downloadDataModel.progressPercentage = 100
				downloadDataModel.partsDownloadedByte[0] = downloadDataModel.downloadedByte
				downloadDataModel.partProgressPercentage[0] = 100

			} catch (error: Exception) {
				error.printStackTrace()

				// Rollback & attempt recovery
				val outputFile = downloadDataModel.getDestinationFile()
				outputFile.renameTo(File(tempFile.name)) // revert rename attempt
				outputFile.delete()

				// Fix invalid filenames & retry copying
				val currentName = downloadDataModel.fileName
				downloadDataModel.fileName = sanitizeFileNameExtreme(currentName)
				renameIfDownloadFileExistsWithSameName(downloadDataModel)
				copyFileToUserDestination(tempFile)
			}
		}
	}

	/**
	 * Copies a given file to the user-selected download destination.
	 *
	 * Workflow:
	 * - Copies the file to the final output location.
	 * - Deletes the original temp file.
	 * - Updates metadata (file size, cached thumbnail, playback duration).
	 * - Cleans up temporary cookie files if present.
	 * - Persists changes in storage.
	 *
	 * @param sourceFile The file to be copied into the destination folder.
	 */
	private fun copyFileToUserDestination(sourceFile: File) {
		val outputFile = downloadDataModel.getDestinationFile()

		// Copy to destination & remove temp source
		sourceFile.copyTo(outputFile, overwrite = true)
		sourceFile.delete()

		// Update file size and clear cached thumbnail
		downloadDataModel.fileSize = outputFile.length()
		downloadDataModel.clearCachedThumbnailFile()

		// Update media playback duration if available
		downloadDataModel.mediaFilePlaybackDuration =
			getAudioPlaybackTimeIfAvailable(downloadDataModel)

		// Delete temp cookie file if it exists
		downloadDataModel.videoInfo?.videoCookieTempPath?.let { cookiePath ->
			val tempCookieFile = File(cookiePath)
			if (tempCookieFile.isFile && tempCookieFile.exists()) {
				tempCookieFile.delete()
			}
		}

		// Save updated metadata
		downloadDataModel.updateInStorage()
	}

}