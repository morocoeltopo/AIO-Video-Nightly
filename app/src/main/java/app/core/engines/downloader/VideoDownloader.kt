package app.core.engines.downloader

import android.annotation.SuppressLint
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.downloadSystem
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
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.texts.CommonTextUtils.capitalizeWords
import lib.texts.CommonTextUtils.generateRandomString
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.System.currentTimeMillis

/**
 * A class that handles video downloads using the youtube-dl/yt-dlp engine.
 * Supports downloads from social media platforms, adaptive bitrate streaming (e.g., HLS),
 * and provides mechanisms to track download progress, handle retries, and network interruptions.
 *
 * Implements [DownloadTaskInf] interface for standardized download task management.
 */
class VideoDownloader(override val downloadDataModel: DownloadDataModel) : DownloadTaskInf {

	// Logger utility for debugging and tracking state changes
	private val logger = LogHelperUtils.from(javaClass)

	// Configuration settings loaded from the download model's global settings
	private val downloadDataModelConfig = downloadDataModel.globalSettings

	// Destination file where the download will be saved
	private var destinationFile = downloadDataModel.getDestinationFile()

	// Timer to handle automatic retries if network issues are detected or download stalls
	private var retryingDownloadTimer: CountDownTimer? = null

	// Listener interface to communicate download status updates to UI or other components
	override var statusListener: DownloadTaskListener? = null

	// Flag indicating if a WebView is currently loading, used for cookie extraction
	private var isWebViewLoading = false

	// Timestamp of the last progress update; used to detect stalled downloads
	private var lastUpdateTime = 0L

	/**
	 * Starts the initialization process for the download task.
	 * This method sets up the model, initializes retry mechanisms, and updates initial status.
	 */
	override fun initiate() {
		logger.d("Initiating download task...")
		executeInBackground {
			logger.d("Initializing download data model...")
			initDownloadDataModel()
			logger.d("Setting up download retry timer...")
			initDownloadTaskTimer()
			logger.d("Updating initial download status...")
			updateDownloadStatus()
		}
	}

	/**
	 * Begins the actual download process.
	 * Prepares the environment, configures settings, and decides on the download method
	 * depending on the type of URL.
	 */
	override fun startDownload() {
		logger.d("Starting download process...")
		updateDownloadStatus(getText(R.string.title_preparing_download), DOWNLOADING)
		configureDownloadModel()
		createEmptyDestinationFile()
		if (isSocialMediaUrl(downloadDataModel.fileURL)) {
			logger.d("Detected social media URL; starting social media download...")
			startSocialMediaDownload()
		} else {
			logger.d("Regular URL detected; starting standard download...")
			startRegularDownload()
		}
	}

	/**
	 * Cancels the download operation.
	 * Handles closing of resources and updates the download status.
	 *
	 * @param cancelReason The reason for cancellation. Defaults to 'Paused' if empty.
	 */
	override fun cancelDownload(cancelReason: String) {
		try {
			logger.d("Cancelling download...")
			closeYTDLProgress()
			val statusMessage = cancelReason.ifEmpty { getText(R.string.title_paused) }
			logger.d("Updating status after cancellation: $statusMessage")
			updateDownloadStatus(statusMessage, CLOSE)
		} catch (error: Exception) {
			logger.d("Error during cancellation: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Initializes the download model's state before starting a download.
	 * Resets flags, counters, and status information to ensure a clean start.
	 */
	private fun initDownloadDataModel() {
		logger.d("Initializing download data model fields...")
		downloadDataModel.status = CLOSE
		downloadDataModel.isRunning = false
		downloadDataModel.isWaitingForNetwork = false
		downloadDataModel.totalConnectionRetries = 0
		downloadDataModel.statusInfo = getText(R.string.text_waiting_to_join)
		initBasicDownloadModelInfo()
		logger.d("Saving initial state to storage...")
		downloadDataModel.updateInStorage()
	}

	/**
	 * Starts a repeating timer that periodically monitors the download state.
	 * - If the download is stalled for more than 20 seconds, it attempts a forced restart.
	 * - If waiting for network, it triggers a retry every 5 seconds.
	 */
	private fun initDownloadTaskTimer() {
		logger.d("Initializing download task timer...")
		executeOnMainThread {
			if (retryingDownloadTimer != null) {
				logger.d("Download timer already running, skipping initialization.")
				return@executeOnMainThread
			}
			logger.d("Setting up new CountDownTimer for download retries.")
			retryingDownloadTimer = object : CountDownTimer((1000 * 60), 5000) {
				override fun onTick(millisUntilFinished: Long) {
					logger.d("Timer tick: Checking download state...")
					if (downloadDataModel.isRunning &&
						!downloadDataModel.isComplete &&
						!downloadDataModel.isWaitingForNetwork
					) {
						logger.d("Download is running and not complete.")
						if (lastUpdateTime == 0L) {
							logger.d("Last update time is zero, skipping tick.")
							return
						}

						if (currentTimeMillis() - lastUpdateTime >= (1000 * 5)) {
							if (downloadDataModel.ytdlpProblemMsg.contains("left", true)) {
								logger.d("Download stalled for over 5 seconds, forcing restart...")
								forcedRestartDownload(retryingDownloadTimer)
							}
						}
					} else if (downloadDataModel.isWaitingForNetwork) {
						logger.d("Waiting for network, attempting to restart download...")
						executeInBackground(::restartDownload)
					}
				}

				override fun onFinish() {
					logger.d("Timer finished.")
					if (downloadDataModel.isRunning ||
						downloadDataModel.isWaitingForNetwork ||
						downloadDataModel.isComplete == false) {
						logger.d("Still waiting for network, restarting timer...")
						start()
					}
				}
			}
		}
	}

	/**
	 * Forces a restart of the download if connection retries are below the limit.
	 * - Increments retry counter.
	 * - Cancels the current timer to avoid redundant attempts.
	 * - Triggers a forced resume operation.
	 *
	 * @param retryingDownloadTimer Optional timer that can be cancelled during restart.
	 */
	private fun forcedRestartDownload(retryingDownloadTimer: CountDownTimer? = null) {
		logger.d("Attempting forced restart of the download...")
		executeInBackground(codeBlock = {
			if (downloadDataModel.totalUnresetConnectionRetries < 10) {
				downloadDataModel.totalUnresetConnectionRetries++
				logger.d("Increasing retry count to ${downloadDataModel.totalUnresetConnectionRetries}")
				retryingDownloadTimer?.cancel()
				logger.d("Cancelled the existing retry timer.")
				downloadSystem.forceResumeDownload(downloadDataModel)
				logger.d("Triggered force resume download.")
			} else {
				logger.d("Maximum retry attempts reached, skipping forced restart.")
			}
		})
	}

	/**
	 * Applies all necessary configurations to the download model:
	 * - YTDLP settings for format selection and execution
	 * - Auto-resume behavior on failure
	 * - Auto-remove settings after download completion
	 * - Part range allocation for segmented downloads
	 */
	private fun configureDownloadModel() {
		logger.d("Configuring download model...")
		configureDownloadModelForYTDLP()
		configureDownloadAutoResumeSettings()
		configureDownloadAutoRemoveSettings()
		configureDownloadPartRange()
		logger.d("Download model configuration complete.")
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
		logger.d("Configuring yt-dlp specific settings...")
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d("Selected video format: $videoFormat")

		// Ensure download directory and filename setup if not processed
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			logger.d("Smart category directory not processed. Setting up directories and filenames.")

			updateSmartCatalogDownloadDir(downloadDataModel)
			logger.d("Smart category directory updated.")

			renameIfDownloadFileExistsWithSameName(downloadDataModel)
			logger.d("Checked and renamed file if necessary to avoid collisions.")

			val internalDirPath = internalDataFolder.getAbsolutePath(INSTANCE)
			logger.d("Internal directory path: $internalDirPath")

			// Generate a unique random filename in the internal directory
			var randomFileName: String
			do {
				randomFileName = sanitizeFileNameExtreme(generateRandomString(10))
			} while (File(internalDirPath, randomFileName).exists())
			logger.d("Generated unique random filename: $randomFileName")

			// Validate filename against existing downloads
			val sanitizedTempName =
				validateExistedDownloadedFileName(internalDirPath, randomFileName)
			logger.d("Sanitized filename validated: $sanitizedTempName")

			// Assign temp yt-dlp destination file
			val ytTempDownloadFile = File(internalDirPath, sanitizedTempName)
			downloadDataModel.tempYtdlpDestinationFilePath = ytTempDownloadFile.absolutePath
			logger.d("Temporary yt-dlp file set at: ${ytTempDownloadFile.absolutePath}")

			// Set final destination file
			destinationFile = downloadDataModel.getDestinationFile()
			logger.d("Final destination file set at: ${destinationFile.absolutePath}")

			downloadDataModel.isSmartCategoryDirProcessed = true
			logger.d("Smart category directory processed flag set to true.")
		} else {
			logger.d("Smart category directory already processed, skipping setup.")
		}

		// Configure yt-dlp command for the selected format
		downloadDataModel.executionCommand = getYtdlpExecutionCommand(videoFormat)
		logger.d("yt-dlp execution command configured.")

		// Reset error state
		downloadDataModel.isYtdlpHavingProblem = false
		downloadDataModel.ytdlpProblemMsg = ""
		logger.d("Reset yt-dlp error state.")

		// Persist updated state
		downloadDataModel.updateInStorage()
		logger.d("Download data model updated in storage.")

		// Log the final command
		logger.d("yt-dlp execution command set to: ${downloadDataModel.executionCommand}")
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
		logger.d("Extracting resolution number from: $resolutionStr")
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
			val match = regex.find(resolutionStr)
			if (match != null) {
				// Return the last numeric group (height)
				logger.d("Pattern matched: ${regex.pattern}")
				val resolution = match.groupValues.last {
					it.isNotEmpty() && it.all(Char::isDigit)
				}.toIntOrNull()
				logger.d("Extracted resolution: $resolution")
				return resolution
			}
		}
		logger.d("No valid resolution found.")
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
		logger.d("Initializing basic download model info...")
		if (downloadDataModel.isBasicYtdlpModelInitialized) {
			logger.d("Model already initialized, skipping.")
			return
		}

		val videoInfo = downloadDataModel.videoInfo!!
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d("Video format: ${videoFormat.formatResolution}")

		// Determine file extension based on audio/video type
		val formatResolution = videoFormat.formatResolution.lowercase()
		val fileExtension: String =
			if (formatResolution.contains("audio", ignoreCase = true)) "mp3" else "mp4"
		logger.d("Determined file extension: $fileExtension")

		// Ensure video title is set
		if (videoInfo.videoTitle.isNullOrEmpty()) {
			logger.d("Video title missing, extracting from URL...")
			val titleFromURL = getVideoTitleFromURL(videoInfo.videoUrl)
			videoInfo.videoTitle = titleFromURL.ifEmpty(::madeUpTitleFromSelectedVideoFormat)
			logger.d("Video title set to: ${videoInfo.videoTitle}")
		} else {
			logger.d("Video title already present.")
		}

		// Assign essential fields: URL, referrer, filename
		downloadDataModel.fileURL = videoInfo.videoUrl
		downloadDataModel.siteReferrer = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
		logger.d("File URL set to: ${downloadDataModel.fileURL}")
		logger.d("Site referrer set to: ${downloadDataModel.siteReferrer}")

		// Generate sanitized filename
		downloadDataModel.fileName = "${getSanitizedTitle(videoInfo, videoFormat)}.$fileExtension"
		logger.d("Generated filename: ${downloadDataModel.fileName}")

		// If filename is invalid, regenerate with strict sanitization
		if (!isFileNameValid(downloadDataModel.fileName)) {
			logger.d("Filename invalid, regenerating with stricter sanitization.")
			downloadDataModel.fileName =
				"${getSanitizedTitle(videoInfo, videoFormat, true)}.$fileExtension"
			logger.d("Regenerated filename: ${downloadDataModel.fileName}")
		}

		// Set baseline download flags
		downloadDataModel.isUnknownFileSize = false
		downloadDataModel.isMultiThreadSupported = false
		downloadDataModel.isResumeSupported = true
		downloadDataModel.globalSettings.downloadDefaultThreadConnections = 1
		logger.d("Download flags set: unknownFileSize=false, multiThreadSupported=false, resumeSupported=true")

		// Record download start timestamp (both raw and human-readable formats)
		val currentTime = currentTimeMillis()
		downloadDataModel.startTimeDate = currentTime
		downloadDataModel.startTimeDateInFormat = millisToDateTimeString(currentTime)
		logger.d("Download start time recorded: $currentTime (${downloadDataModel.startTimeDateInFormat})")

		// Mark initialization as complete
		downloadDataModel.isBasicYtdlpModelInitialized = true
		logger.d("Basic download model initialization complete.")
	}

	/**
	 * Generates a fallback title string when the video has no valid title.
	 *
	 * The constructed title uses technical details from the selected format
	 * and the base domain of the video URL. This ensures the resulting title
	 * is unique enough for identification, even if not user-friendly.
	 *
	 * Output format:
	 *   <formatId>_<resolution>_<videoCodec>_<baseDomain>_From_<siteReferrer>_Downloaded_By_AIO_v<version>_
	 *
	 * Example:
	 *   "137_1080p_avc1_Youtube.com_From_youtube.com_Downloaded_By_AIO_v1.0_"
	 *
	 * @return A machine-generated title string derived from format metadata.
	 */
	private fun madeUpTitleFromSelectedVideoFormat(): String {
		logger.d("Generating fallback title from selected video format...")
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d(
			"Video format details - ID: ${videoFormat.formatId}, " +
					"Resolution: ${videoFormat.formatResolution}, " +
					"Codec: ${videoFormat.formatVcodec}"
		)

		val baseDomain = capitalizeWords(getBaseDomain(downloadDataModel.videoInfo!!.videoUrl))
		logger.d("Extracted base domain: $baseDomain")

		val siteReferrer = downloadDataModel.siteReferrer
		logger.d("Site referrer: $siteReferrer")

		// Build a synthetic title by concatenating format details + domain
		val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"$baseDomain" +
				"_From_${siteReferrer}" +
				"_Downloaded_By_AIO_v${versionName}_"

		logger.d("Generated fallback title: $madeUpTitle")
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
		logger.d("Generating yt-dlp execution command for format ID: ${videoFormat.formatId}")
		val packageName = INSTANCE.packageName
		val resolutionNumber = extractResolutionNumber(videoFormat.formatResolution)
		logger.d("Extracted resolution number: $resolutionNumber")

		val command = if (videoFormat.formatId == packageName) {
			logger.d("Format ID matches package name.")
			// Case 1: Dynamic handling if formatId matches package name
			if (videoFormat.isFromSocialMedia) {
				// Social media sites → always cap resolution at 2400p
				logger.d("Video is from social media; using resolution cap at 2400p.")
				"bestvideo[height<=2400]+bestaudio/best[height<=2400]/best"
			} else {
				// Build a resolution-specific pattern, or fallback if not available
				val commonPattern = if (resolutionNumber != null) {
					logger.d("Building resolution-specific pattern with max height $resolutionNumber.")
					"bestvideo[height<=$resolutionNumber]+bestaudio/best[height<=$resolutionNumber]/best"
				} else {
					logger.d("No resolution number available; using fallback pattern.")
					"bestvideo+bestaudio/best"
				}

				// Special handling for YouTube
				if (isYouTubeUrl(downloadDataModel.fileURL)) {
					logger.d("Video URL is from YouTube.")
					val isAudio = videoFormat.formatResolution.contains("audio", ignoreCase = true)
					logger.d("Format is ${if (isAudio) "audio-only" else "video or mixed"}.")
					if (isAudio) "bestaudio" else commonPattern
				} else {
					logger.d("Video URL is not from YouTube; using common pattern.")
					commonPattern
				}
			}
		} else {
			logger.d("Format ID does not match package name; using provided format ID.")
			// Case 2: Normal formats → just return the formatId
			videoFormat.formatId
		}

		logger.d("Generated yt-dlp command: $command")
		return command
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
	 * Updates the current download status and related flags in the download model.
	 *
	 * This method:
	 * - Updates the status message if provided.
	 * - Sets the status code and related flags (`isRunning`, `isComplete`).
	 * - Persists the updated state in storage.
	 * - Notifies any registered listener on the main thread to reflect the status change.
	 *
	 * @param statusInfo Optional status message to display.
	 * @param status The current status code (e.g., DOWNLOADING, COMPLETE).
	 */
	override fun updateDownloadStatus(statusInfo: String?, status: Int) {
		logger.d("Updating download status...")

		// Update status information if a new message is provided
		if (!statusInfo.isNullOrEmpty()) {
			logger.d("Updating status info: $statusInfo")
			downloadDataModel.statusInfo = statusInfo
		}

		// Update model flags and status
		val classRef = this@VideoDownloader
		logger.d("Setting status to $status")
		downloadDataModel.status = status
		downloadDataModel.isRunning = (status == DOWNLOADING)
		logger.d("Download running flag set to ${downloadDataModel.isRunning}")
		downloadDataModel.isComplete = (status == COMPLETE)
		logger.d("Download complete flag set to ${downloadDataModel.isComplete}")

		// Persist the updated state
		downloadDataModel.updateInStorage()
		logger.d("Download data model updated in storage.")

		// Notify UI/listener on the main thread
		executeOnMainThread {
			logger.d("Notifying status listener on main thread.")
			statusListener?.onStatusUpdate(classRef)
		}
	}

	/**
	 * Calculates and updates download progress metrics in the model.
	 *
	 * This method performs the following actions:
	 * - Updates the total time spent downloading.
	 * - Calculates the number of bytes downloaded so far.
	 * - Updates the completion percentage.
	 * - Refreshes the last modification timestamp.
	 * - Persists the updated state in storage.
	 */
	private fun calculateProgressAndModifyDownloadModel() {
		logger.d("Calculating download progress...")

		logger.d("Updating total downloaded time.")
		calculateTotalDownloadedTime()

		logger.d("Updating downloaded bytes.")
		calculateDownloadedBytes()

		logger.d("Updating download completion percentage.")
		calculateDownloadPercentage()

		logger.d("Updating last modification timestamp.")
		updateLastModificationDate()

		logger.d("Saving updated download model state to storage.")
		downloadDataModel.updateInStorage()
	}

	/**
	 * Checks the current network connection and attempts to retry the download if possible.
	 *
	 * Workflow:
	 * - Verifies network connectivity and closes yt-dlp progress if disconnected.
	 * - If waiting for network, it rechecks availability.
	 * - Ensures WiFi-only settings are respected before resuming.
	 * - Updates the download status and restarts the download process when conditions are met.
	 */
	private fun checkNetworkConnectionAndRetryDownload() {
		logger.d("Checking network connection and deciding whether to retry download.")

		// Close yt-dlp progress if the network connection is invalid
		if (!verifyNetworkConnection()) {
			logger.d("Network connection invalid. Closing yt-dlp progress.")
			closeYTDLProgress()
		}

		// If the download is waiting for network, recheck conditions
		if (downloadDataModel.isWaitingForNetwork) {
			logger.d("Download is waiting for network. Rechecking connectivity...")
			if (isNetworkAvailable() && isInternetConnected()) {
				logger.d("Network is available and internet is connected.")

				// Check if WiFi-only mode is enabled and ensure WiFi is active
				if (downloadDataModelConfig.downloadWifiOnly && !isWifiEnabled()) {
					logger.d("WiFi-only mode is enabled but WiFi is not active. Aborting retry.")
					return
				}

				// Resume download as network conditions are satisfied
				logger.d("Network ready. Resuming download.")
				downloadDataModel.isWaitingForNetwork = false
				updateDownloadStatus(getText(R.string.title_started_downloading))
				executeDownloadProcess()
			} else {
				logger.d("Network or internet not available. Cannot resume download.")
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
	 * Calculates and updates the total time spent downloading.
	 *
	 * This method:
	 * - Increments the download time if the download is not waiting for network connectivity.
	 * - Converts the accumulated time into a human-readable formatted string.
	 * - Updates the download model with the latest time metrics.
	 */
	private fun calculateTotalDownloadedTime() {
		logger.d("Calculating total downloaded time.")

		// Increase time only if not waiting for network
		if (!downloadDataModel.isWaitingForNetwork) {
			logger.d("Download is active. Adding 500 milliseconds to time spent.")
			downloadDataModel.timeSpentInMilliSec += 500
		} else {
			logger.d("Download is waiting for network. Time not incremented.")
		}

		// Convert total time spent into a formatted string
		val timeSpentMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		val format = calculateTime(timeSpentMillis, getText(R.string.text_spent))
		logger.d("Formatted time spent: $format")

		// Update the download model
		downloadDataModel.timeSpentInFormat = format
	}

	/**
	 * Calculates the total downloaded bytes by scanning part files generated by yt-dlp.
	 *
	 * This method:
	 * - Searches the internal directory for part files matching the temporary file prefix.
	 * - Updates the downloaded byte count and file size from the matching part file.
	 * - Converts the byte count into a human-readable format.
	 * - Updates the download model with the latest byte metrics.
	 */
	private fun calculateDownloadedBytes() {
		logger.d("Calculating downloaded bytes from part files.")

		try {
			val ytTempDownloadFile = downloadDataModel.tempYtdlpDestinationFilePath
			val ytTempFileNamePrefix = File(ytTempDownloadFile).name
			val internalDir = internalDataFolder

			logger.d("Searching for part files with prefix: " +
					" $ytTempFileNamePrefix in directory: " +
					" ${internalDir.getAbsolutePath(INSTANCE)}")

			// Look for YTDLP-generated part files matching the temp prefix
			internalDir.listFiles()
				.filter { file ->
					file.isFile &&
							file.name!!.startsWith(ytTempFileNamePrefix) &&
							file.name!!.endsWith(".part")
				}
				.forEach { file ->
					try {
						logger.d("Found part file: ${file.name}, size: ${file.length()} bytes")
						// Update byte count from part file size
						downloadDataModel.downloadedByte = file.length()
						downloadDataModel.fileSize = file.length()
					} catch (error: Exception) {
						logger.d("Error reading file ${file.name}: ${error.message}")
						error.printStackTrace()
					}
				}

			// Update human-readable format and progress tracking
			val downloadedByte = downloadDataModel.downloadedByte
			downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadedByte)
			logger.d("Downloaded bytes: $downloadedByte, " +
					"formatted: ${downloadDataModel.downloadedByteInFormat}")

			downloadDataModel.partsDownloadedByte[0] = downloadedByte
			logger.d("Updated partsDownloadedByte array with latest count.")

		} catch (error: Exception) {
			logger.d("Exception occurred while calculating downloaded bytes: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Calculates the overall download progress percentage.
	 * Updates both the numeric percentage and the formatted string in the download model.
	 */
	private fun calculateDownloadPercentage() {
		logger.d("Calculating download percentage.")
		try {
			val totalProcess = downloadDataModel.progressPercentage
			logger.d("Total progress percentage: $totalProcess")

			downloadDataModel.partProgressPercentage[0] = totalProcess.toInt()
			logger.d("Updated part progress percentage: ${downloadDataModel.partProgressPercentage[0]}")

			downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
			logger.d("Formatted progress percentage: ${downloadDataModel.progressPercentageInFormat}")
		} catch (error: Exception) {
			logger.d("Error while calculating download percentage: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Updates the download model's last modification timestamp
	 * and converts it into a formatted date-time string.
	 */
	private fun updateLastModificationDate() {
		logger.d("Updating last modification date.")
		downloadDataModel.lastModifiedTimeDate = currentTimeMillis()
		logger.d("Set lastModifiedTimeDate to ${downloadDataModel.lastModifiedTimeDate}")

		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
		logger.d("Formatted last modification date: ${downloadDataModel.lastModifiedTimeDateInFormat}")
	}

	/**
	 * Creates an empty destination file for the download if it doesn't already exist.
	 * This file acts as a placeholder and ensures disk access is working correctly.
	 *
	 * If any I/O error occurs, the download is marked as failed and cancelled.
	 */
	private fun createEmptyDestinationFile() {
		logger.d("Creating empty destination file if necessary.")

		if (downloadDataModel.isDeleted) {
			logger.d("Download is marked as deleted. Skipping file creation.")
			return
		}

		if (downloadDataModel.downloadedByte < 1) {
			try {
				if (destinationFile.exists()) {
					logger.d("Destination file already exists. No need to create.")
					return
				}
				logger.d("Creating new empty file with size 108 bytes.")
				RandomAccessFile(destinationFile, "rw").setLength(108)
			} catch (error: IOException) {
				logger.d("IOException while creating file: ${error.message}")
				error.printStackTrace()
				downloadDataModel.totalConnectionRetries++
				logger.d("Incremented total connection retries: ${downloadDataModel.totalConnectionRetries}")

				downloadDataModel.isFailedToAccessFile = true
				logger.d("Marked download as failed to access file.")

				cancelDownload(getText(R.string.title_download_io_failed))
				logger.d("Cancelled download due to file I/O error.")
			}
		}
	}

	/**
	 * Starts the download process specifically for social media content.
	 *
	 * Workflow:
	 * - Updates the download status and sets temporary information about fetching cookies.
	 * - Checks if the download is initiated from a browser.
	 *   - If not from a browser, starts a regular download directly.
	 *   - If from a browser, resumes the process in a background thread.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private fun startSocialMediaDownload() {
		logger.d("Starting social media download.")
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie)

		if (!downloadDataModel.isDownloadFromBrowser) {
			logger.d("Download is not from browser. Proceeding with regular download.")
			downloadDataModel.isDownloadFromBrowser = false
			downloadDataModel.updateInStorage()
			startRegularDownload()
		} else {
			logger.d("Download initiated from browser. Resuming in background thread.")
			executeInBackground(codeBlock = {
				downloadDataModel.isDownloadFromBrowser = false
				downloadDataModel.updateInStorage()
				executeDownloadProcess()
			})
		}
	}

	/**
	 * Starts the standard download procedure when browser involvement is unnecessary.
	 *
	 * Workflow:
	 * - Allows up to 2 retry attempts before falling back to direct execution.
	 * - Loads the URL in a WebView to extract cookies or session data.
	 * - Triggers the actual download process after the page finishes loading or if retries are exhausted.
	 *
	 * @param retryCount Number of retry attempts already made.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private fun startRegularDownload(retryCount: Int = 0) {
		logger.d("Starting regular download. Retry count: $retryCount")

		// If max retries reached → fallback to direct execution
		if (retryCount >= 2) {
			logger.d("Max retries reached. Falling back to direct download execution.")
			executeInBackground { executeDownloadProcess() }
			return
		}

		// Update status before initializing WebView
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie)

		Handler(Looper.getMainLooper()).post {
			logger.d("Initializing WebView for URL: ${downloadDataModel.fileURL}")
			val webView = WebView(INSTANCE).apply {
				settings.javaScriptEnabled = true

				// Handle page load success / failure
				webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView?, url: String?) {
						logger.d("WebView page finished loading. URL: $url")
						if (isWebViewLoading) {
							handlePageLoaded(url)
							destroy()
						}
					}

					@Deprecated("Deprecated in Java")
					override fun onReceivedError(
						view: WebView?, errorCode: Int,
						description: String?, failingUrl: String?
					) {
						logger.d("WebView encountered an error: $errorCode, $description")
						retryOrFail(retryCount)
					}
				}

				// Handle page title event as a backup signal for load complete
				webChromeClient = object : WebChromeClient() {
					override fun onReceivedTitle(view: WebView?, title: String?) {
						super.onReceivedTitle(view, title)
						logger.d("WebView received title: $title")
						if (isWebViewLoading) {
							handlePageLoaded(url)
							destroy()
						}
					}
				}

				// Start loading the target URL
				loadUrl(downloadDataModel.fileURL)
				isWebViewLoading = true
				logger.d("WebView started loading.")
			}

			// Timeout safeguard → destroy and retry if still stuck
			Handler(Looper.getMainLooper()).postDelayed({
				if (isWebViewLoading) {
					logger.d("WebView loading timeout. Destroying and retrying.")
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
	 * Workflow:
	 * - Marks WebView loading as complete.
	 * - Retrieves cookies from the given URL using CookieManager.
	 * - Saves the cookies to the data model and persists them if available.
	 * - Continues the download process in the background.
	 *
	 * @param url The URL that was loaded in the WebView.
	 */
	private fun handlePageLoaded(url: String?) {
		logger.d("WebView page loaded for URL: $url")
		isWebViewLoading = false

		// Try to capture cookies from the loaded URL
		val cookies = CookieManager.getInstance().getCookie(url)
		cookies?.let {
			logger.d("Cookies found: $cookies")
			downloadDataModel.apply {
				videoInfo?.videoCookie = cookies
				siteCookieString = cookies
				updateInStorage()

				// Persist cookies for reuse
				saveCookiesIfAvailable(shouldOverride = true)
				logger.d("Cookies saved to data model and storage.")
			}
		}

		logger.d("Starting download process after extracting cookies.")
		executeInBackground {
			executeDownloadProcess()
		}
	}

	/**
	 * Handles retrying the WebView-based download initialization.
	 * If within retry limits, it attempts to restart the download process.
	 *
	 * @param currentRetry The current retry attempt count.
	 */
	private fun retryOrFail(currentRetry: Int) {
		logger.d("Retrying or failing download. Current retry count: $currentRetry")
		isWebViewLoading = false
		Handler(Looper.getMainLooper()).post {
			startRegularDownload(currentRetry + 1)
		}
	}

	/**
	 * Executes the actual download process using yt-dlp.
	 *
	 * Workflow:
	 * - Updates download status and initializes necessary settings.
	 * - Builds yt-dlp request with standard options, cookies, and throttling.
	 * - Starts retry timers and monitors progress.
	 * - Handles success, failure, and retries based on the response.
	 */
	private fun executeDownloadProcess() {
		logger.d("Executing download process.")
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_connecting_to_the_server)
		retryingDownloadTimer?.cancel()

		try {
			lastUpdateTime = currentTimeMillis()
			val urlWithoutPlaylist = filterYoutubeUrlWithoutPlaylist(downloadDataModel.fileURL)
			val request = YoutubeDLRequest(urlWithoutPlaylist)
			logger.d("YT-DLP request initialized for URL: $urlWithoutPlaylist")

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
				logger.d("Added merge output option for mp4 format.")
			}
			if (AIOApp.IS_DEBUG_MODE_ON) {
				request.addOption("-v")
				logger.d("Debug mode enabled for yt-dlp.")
			}

			// Add cookie support if available
			downloadDataModel.getCookieFilePathIfAvailable()?.let {
				val cookieFile = File(it)
				if (cookieFile.exists() && cookieFile.canWrite()) {
					logger.d("Using cookie file: ${cookieFile.absolutePath}")
					request.addOption("--cookies", cookieFile.absolutePath)
				}
			}

			// Add download speed throttling if configured
			if (downloadDataModelConfig.downloadMaxNetworkSpeed > 0) {
				val downloadMaxNetworkSpeed = downloadDataModelConfig.downloadMaxNetworkSpeed
				val ytDlpSpeedLimit = formatDownloadSpeedForYtDlp(downloadMaxNetworkSpeed)
				if (isValidSpeedFormat(ytDlpSpeedLimit)) {
					request.addOption("--limit-rate", ytDlpSpeedLimit)
					logger.d("Download speed limit set to: $ytDlpSpeedLimit")
				}
			}

			// Execute restarting downloading timer
			initDownloadTaskTimer()
			retryingDownloadTimer?.start()

			// Execute yt-dlp request with progress callback
			val response = getInstance().execute(
				request = request,
				processId = downloadDataModel.id.toString()
			) { progress, _, status ->
				if (progress > 0) downloadDataModel.progressPercentage = progress.toLong()
				downloadDataModel.tempYtdlpStatusInfo = cleanYtdlpLoggingSting(status)
				logger.d("Download progress: ${downloadDataModel.progressPercentage}%")
				logger.d("Status message: ${downloadDataModel.tempYtdlpStatusInfo}")

				val currentTime = currentTimeMillis()
				if (currentTime - lastUpdateTime >= 500) {
					lastUpdateTime = currentTime
					updateDownloadProgress()
				}
			}

			// Handle response after yt-dlp finishes
			if (response.exitCode != 0) {
				logger.d("Download failed with exit code ${response.exitCode}")
				onYtdlpDownloadFailed(response.out)
			} else {
				logger.d("Download completed successfully.")
				moveToUserSelectedDestination()

				if (downloadDataModelConfig.downloadPlayNotificationSound) {
					logger.d("Playing download completion sound.")
					AudioPlayerUtils(INSTANCE).play(R.raw.sound_download_finished)
				}

				downloadDataModel.isRunning = false
				downloadDataModel.isComplete = true
				updateDownloadStatus(getText(R.string.text_completed), COMPLETE)
				retryingDownloadTimer?.cancel()

				logger.d("Download status updated to COMPLETE.")
				logger.d("Elapsed time: ${response.elapsedTime} ms")
			}
		} catch (error: Exception) {
			logger.d("Exception during download process: ${error.message}")
			error.printStackTrace()

			if (isRetryingAllowed()) {
				logger.d("Retrying download due to error.")
				onYtdlpDownloadFailed(error.message)
			} else {
				logger.d("Cancelling download due to unrecoverable error.")
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
	/**
	 * Handles yt-dlp download failures by checking for critical errors or retrying the download process.
	 *
	 * Workflow:
	 * - Runs in the background thread to process error response.
	 * - Checks for critical errors like server problems, expired URLs, or missing files.
	 * - Pauses, retries, or cancels the download depending on the nature of the error.
	 * - Persists the updated state and cleans up if needed.
	 *
	 * @param response The error message or output from yt-dlp, if available.
	 */
	private fun onYtdlpDownloadFailed(response: String? = null) {
		logger.d("yt-dlp download failed. Response: $response")
		executeInBackground(codeBlock = {
			if (response != null && isCriticalErrorFound(response)) {
				// Critical error handling
				logger.d("Critical error detected in response.")
				if (downloadDataModel.isYtdlpHavingProblem) {
					val pausedMsg = downloadDataModel.ytdlpProblemMsg.ifEmpty {
						getText(R.string.title_paused)
					}
					logger.d("Pausing download due to persistent yt-dlp problems.")
					cancelDownload(pausedMsg) // Pause due to persistent YTDLP issues
					return@executeInBackground
				}

				if (downloadDataModel.isFileUrlExpired) {
					logger.d("Pausing download due to expired URL.")
					cancelDownload(getText(R.string.title_link_expired)) // Expired link
					return@executeInBackground
				}

				if (downloadDataModel.isDestinationFileNotExisted) {
					logger.d("Pausing download because destination file is missing.")
					cancelDownload(getText(R.string.title_file_deleted_paused)) // Target file deleted
					return@executeInBackground
				}
			} else if (!response.isNullOrEmpty()) {
				// Generic failure with error response
				if (downloadDataModel.totalUnresetConnectionRetries < 10) {
					logger.d("Retrying download, connection retries:" +
							" ${downloadDataModel.totalUnresetConnectionRetries}")
					forcedRestartDownload()
				} else {
					logger.d("Max retries reached, cancelling download.")
					updateDownloadStatus(getText(R.string.title_download_failed))
					cancelDownload(getText(R.string.title_download_failed))
				}
			} else {
				// No response → retry logic
				logger.d("No response, retrying download process.")
				restartDownload()
				retryingDownloadTimer?.start()
			}

			// Always persist the latest state
			logger.d("Updating download model storage.")
			downloadDataModel.updateInStorage()

			// Clean up destination file if marked deleted
			if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
				if (destinationFile.exists()) {
					logger.d("Cleaning up deleted destination file.")
					destinationFile.delete()
				}
			}
		})
	}

	/**
	 * Checks if the yt-dlp download failure is caused by critical issues.
	 * Updates relevant flags in the downloadDataModel and persists them.
	 *
	 * Checks:
	 * - yt-dlp server or internal errors
	 * - Expired download URL
	 * - Destination file missing or deleted
	 *
	 * @param response The error message or output received from yt-dlp.
	 * @return `true` if a critical error is detected, otherwise `false`.
	 */
	private fun isCriticalErrorFound(response: String): Boolean {
		logger.d("Checking for critical error in response.")

		// Case 1: YTDLP server/internal issue
		if (isYtdlHavingProblem(response)) {
			logger.d("Critical error: yt-dlp server problem detected.")
			downloadDataModel.isYtdlpHavingProblem = true

			// Set problem message if not already set
			if (downloadDataModel.ytdlpProblemMsg.isEmpty()) {
				val msgString = getText(R.string.title_paused_server_problem)
				downloadDataModel.ytdlpProblemMsg = msgString
				logger.d("Set problem message: $msgString")
			}

			downloadDataModel.updateInStorage()
			return true
		}

		// Case 2: Expired file URL
		if (isUrlExpired(downloadDataModel.fileURL)) {
			logger.d("Critical error: URL expired.")
			downloadDataModel.isFileUrlExpired = true
			return true
		}

		// Case 3: Destination file missing/deleted
		if (!destinationFile.exists()) {
			logger.d("Critical error: Destination file missing.")
			downloadDataModel.isDestinationFileNotExisted = true
			return true
		}

		// No critical issues found
		logger.d("No critical error found.")
		return false
	}

	/**
	 * Checks if the given yt-dlp response contains any known fatal errors.
	 * Updates the `ytdlpProblemMsg` field in the download model with a user-friendly message.
	 *
	 * @param response The raw response from yt-dlp.
	 * @return `true` if a known problem is detected, `false` otherwise.
	 */
	private fun isYtdlHavingProblem(response: String): Boolean {
		return when {
			// Login required or rate limit error
			response.contains("rate-limit reached or login required", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				logger.d("YTDLP problem detected: rate limit or login required.")
				true
			}

			// Content unavailable
			response.contains("Requested content is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_content_not_available)
				logger.d("YTDLP problem detected: content not available.")
				true
			}

			// Format not available
			response.contains("Requested format is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_ytdlp_format_not_found)
				logger.d("YTDLP problem detected: format not available.")
				true
			}

			// Restricted video due to region or authentication
			response.contains("Restricted Video", ignoreCase = true) ||
					response.contains("--cookies for the authentication", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				logger.d("YTDLP problem detected: restricted video or auth required.")
				true
			}

			// Network or ISP block issues
			response.contains("Connection reset by peer", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_site_banned_in_your_area)
				logger.d("YTDLP problem detected: connection reset or site blocked.")
				true
			}

			// Generic yt-dlp exception
			response.contains("YoutubeDLException", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = INSTANCE.getString(
					R.string.title_server_issue,
					getText(R.string.title_download_failed)
				)
				logger.d("YTDLP problem detected: YoutubeDLException.")
				true
			}

			// No known problem detected
			else -> false
		}
	}

	/**
	 * Attempts to restart the download process if retrying is allowed.
	 * Updates the model's state based on network conditions.
	 * Logs state changes at each step for debugging and traceability.
	 */
	private fun restartDownload() {
		if (!isRetryingAllowed()) return

		// Case 1: No network available at all
		if (!isNetworkAvailable()) {
			// Case: No network connection
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_network))
			logger.d("Network unavailable. Waiting for network.")
			return
		}

		// Case 2: Wi-Fi required but not enabled
		if (downloadDataModelConfig.downloadWifiOnly && !isWifiEnabled()) {
			// Case: Wi-Fi required but not enabled
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_wifi))
			logger.d("Wi-Fi not enabled. Waiting for Wi-Fi.")
			return
		}

		// Case 3: Network available but no internet access
		if (!isInternetConnected()) {
			// Case: No internet access despite network connection
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.text_waiting_for_internet))
			logger.d("Internet unavailable. Waiting for internet.")
			return
		}

		// Case 4: Network conditions are now favorable → restart the download
		if (downloadDataModel.isWaitingForNetwork) {
			// Case: Network is now available, resume download
			downloadDataModel.isWaitingForNetwork = false
			updateDownloadStatus(getText(R.string.title_started_downloading))
			logger.d("Network or Wi-Fi now available. Restarting download.")
			executeDownloadProcess()
		}

		// Increment retry counter for diagnostics
		downloadDataModel.totalConnectionRetries++
		logger.d("Retry attempt #${downloadDataModel.totalConnectionRetries}.")
	}

	/**
	 * Determines if the download can be retried based on current state and retry limits.
	 *
	 * @return `true` if retrying is allowed, `false` otherwise.
	 */
	private fun isRetryingAllowed(): Boolean {
		val maxErrorAllowed = downloadDataModelConfig.downloadAutoResumeMaxErrors
		val retryAllowed = downloadDataModel.isRunning &&
				downloadDataModel.totalConnectionRetries < maxErrorAllowed
		logger.d("Checking if retry is allowed: $retryAllowed " +
				"(retries: ${downloadDataModel.totalConnectionRetries}/$maxErrorAllowed)")
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

		inputFile?.let { ytdlpTempfile ->
			try {
				// Copy temp file to final destination and delete original
				val outputFile = downloadDataModel.getDestinationFile()
				val isMp4ConvertSuccessful = moveMoovAtomToStart(ytdlpTempfile, outputFile)
				if (!isMp4ConvertSuccessful) ytdlpTempfile.copyTo(outputFile, overwrite = true)
				ytdlpTempfile.delete()

				// Update metadata with file stats
				downloadDataModel.fileSize = outputFile.length()
				downloadDataModel.fileSizeInFormat = getHumanReadableFormat(downloadDataModel.fileSize)
				downloadDataModel.downloadedByte = downloadDataModel.fileSize
				downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadDataModel.downloadedByte)
				downloadDataModel.progressPercentage = 100
				downloadDataModel.partsDownloadedByte[0] = downloadDataModel.downloadedByte
				downloadDataModel.partProgressPercentage[0] = 100
				downloadDataModel.updateInStorage()

				logger.d("File successfully moved to destination: ${outputFile.absolutePath}")
			} catch (error: Exception) {
				error.printStackTrace()
				logger.d("Error while moving file: ${error.message}. Attempting recovery.")

				// Attempt recovery by reverting rename and retrying
				val outputFile = downloadDataModel.getDestinationFile()
				outputFile.renameTo(File(ytdlpTempfile.name)) // revert rename attempt
				outputFile.delete()

				// Fix invalid filenames & retry copying
				val currentName = downloadDataModel.fileName
				downloadDataModel.fileName = sanitizeFileNameExtreme(currentName)
				renameIfDownloadFileExistsWithSameName(downloadDataModel)

				copyFileToUserDestination(ytdlpTempfile)
				logger.d("Recovery steps executed for moving file.")
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

		// Copy file and remove the temp source
		val isMp4ConvertSuccessful = moveMoovAtomToStart(sourceFile, outputFile)
		if (!isMp4ConvertSuccessful) sourceFile.copyTo(outputFile, overwrite = true)
		sourceFile.delete()
		logger.d("Copied file to destination: ${outputFile.absolutePath}")

		// Update file metadata
		downloadDataModel.fileSize = outputFile.length()
		downloadDataModel.clearCachedThumbnailFile()

		// Update media playback duration if available
		downloadDataModel.mediaFilePlaybackDuration =
			getAudioPlaybackTimeIfAvailable(downloadDataModel)

		// Clean up temporary cookie file if present
		downloadDataModel.videoInfo?.videoCookieTempPath?.let { cookiePath ->
			val tempCookieFile = File(cookiePath)
			if (tempCookieFile.isFile && tempCookieFile.exists()) {
				tempCookieFile.delete()
				logger.d("Deleted temporary cookie file: ${tempCookieFile.absolutePath}")
			}
		}

		// Save updated metadata
		downloadDataModel.updateInStorage()
		logger.d("Updated download data model after copying file.")
	}

	/**
	 * Moves the 'moov' atom to the beginning of an MP4 file for optimized streaming
	 * using the MP4Parser library. Includes proper initialization and error handling.
	 *
	 * @param inputFile The source MP4 file to be processed
	 * @param outputFile The destination file where the optimized MP4 will be written
	 * @return true if the operation was successful and the output file is valid, false otherwise
	 */
	fun moveMoovAtomToStart(inputFile: File, outputFile: File): Boolean {
		// Pre-validation checks
		if (!inputFile.exists()) {
			logger.e("Input file does not exist: ${inputFile.absolutePath}")
			return false
		}

		if (inputFile.length() == 0L) {
			logger.e("Input file is empty: ${inputFile.absolutePath}")
			return false
		}

		if (!inputFile.canRead()) {
			logger.e("Cannot read input file: ${inputFile.absolutePath}")
			return false
		}

		val outputDir = outputFile.parentFile
		if (outputDir != null && !outputDir.canWrite()) {
			logger.e("Cannot write to output directory: ${outputDir.absolutePath}")
			return false
		}

		val requiredSpace = inputFile.length() * 2
		val availableSpace = outputDir?.freeSpace ?: 0L
		if (availableSpace < requiredSpace) {
			logger.e("Insufficient storage space. Required: $requiredSpace, Available: $availableSpace")
			return false
		}

		return try {
			logger.d("Starting moov atom optimization for: ${inputFile.name}")
			logger.d("Input file size: ${inputFile.length()} bytes")

			// Load the MP4 file into a Movie object
			// This parses the entire MP4 structure including tracks, metadata, and atoms
			val movie: Movie = MovieCreator.build(FileDataSourceImpl(inputFile.absolutePath))
			logger.d("MP4 file parsed successfully. Track count: ${movie.tracks.size}")

			// Build a new container with moov atom at the beginning
			// DefaultMp4Builder automatically optimizes atom placement
			val mp4Builder = DefaultMp4Builder()
			val container = mp4Builder.build(movie)
			logger.d("New MP4 container built with optimized structure")

			// Write the container to the output file
			// Using try-with-resources to ensure proper stream closure
			FileOutputStream(outputFile).use { fos ->
				container.writeContainer(fos.channel)
			}

			val outputSize = outputFile.length()
			logger.d("Optimization completed successfully. Output file size: $outputSize bytes")
			logger.d("Output file created at: ${outputFile.absolutePath}")

			true

		} catch (error: Exception) {
			logger.e("Failed to move moov atom to start: ${error.message}")
			error.printStackTrace()

			// Clean up partially written output file on failure
			if (outputFile.exists()) {
				try {
					outputFile.delete()
					logger.d("Cleaned up partial output file after failure")
				} catch (cleanupError: Exception) {
					logger.e("Failed to clean up output file: ${cleanupError.message}")
				}
			}

			false
		}
	}


	/**
	 * Basic validation to check if a file appears to be a valid MP4 file
	 * by checking for the MP4 signature ('ftyp' atom at the beginning)
	 */
	private fun isValidMp4File(file: File): Boolean {
		if (!file.exists() || file.length() < 12) {
			return false
		}

		return try {
			FileInputStream(file).use { fis ->
				val buffer = ByteArray(12)
				val bytesRead = fis.read(buffer)
				if (bytesRead < 12) {
					return false
				}
				val signature = String(buffer, 4, 4, Charsets.US_ASCII)
				signature == "ftyp"
			}
		} catch (error: Exception) {
			logger.e("Error validating MP4 file: ${error.message}")
			false
		}
	}

}