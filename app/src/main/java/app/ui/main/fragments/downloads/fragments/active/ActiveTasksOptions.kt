package app.ui.main.fragments.downloads.fragments.active

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat.getDrawable
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.downloadSystem
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoInfo
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.dialogs.DownloadFileRenamer
import app.ui.main.fragments.downloads.dialogs.DownloadInfoTracker
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_STREAM_TITLE
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_STREAM_URL
import com.aio.R
import com.aio.R.layout
import com.aio.R.string
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import lib.device.ShareUtility.shareUrl
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.networks.URLUtility.isValidURL
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.ActivityAnimator.animActivitySwipeLeft
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File
import java.lang.ref.WeakReference

/**
 * Manages and provides user actions for active or completed download tasks.
 *
 * This class centralizes all download-related operations, including pausing,
 * resuming, deleting, or removing tasks. It also provides quick access to
 * file-related actions such as opening the media file, copying or sharing
 * the download URL, and renaming downloaded items.
 *
 * The class ensures consistent handling of download states and user
 * interactions within the main download interface.
 *
 * @param motherActivity The parent activity responsible for hosting and controlling these actions.
 */
class ActiveTasksOptions(private val motherActivity: MotherActivity?) {

	// Logger instance for debugging and tracking
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity (avoids memory leaks)
	private val safeMotherActivityRef by lazy { WeakReference(motherActivity).get() }

	// Builder for showing dialogs
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeMotherActivityRef)

	// Currently active download model shown in dialog
	private var downloadDataModel: DownloadDataModel? = null

	// Lazy-initialized helpers
	private lateinit var downloadFileRenamer: DownloadFileRenamer
	private lateinit var downloadInfoTracker: DownloadInfoTracker

	init {
		logger.d("Init block -> Initializing dialog views")
		initializeDialogViews()
	}

	/**
	 * Opens and initializes the options dialog for the specified download item.
	 *
	 * Functionality:
	 * - Prevents multiple dialogs from appearing simultaneously.
	 * - Caches the active [DownloadDataModel] for reference.
	 * - Populates the dialog UI with relevant file details such as name, URL, and icons.
	 *
	 * @param downloadModel The [DownloadDataModel] representing the selected download item.
	 */
	fun show(downloadModel: DownloadDataModel) {
		if (!dialogBuilder.isShowing) {
			logger.d("show() -> Opening dialog for file: ${downloadModel.fileName}")
			downloadDataModel = downloadModel
			dialogBuilder.show()
			updateDialogFileInfo(downloadModel)
		} else {
			logger.d("show() -> Dialog already showing, skipping")
		}
	}

	/**
	 * Safely dismisses the active options dialog if it is currently displayed.
	 *
	 * Ensures that the dialog is only closed when visible, preventing redundant
	 * dismiss calls or potential UI state exceptions.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("close() -> Closing dialog")
			dialogBuilder.close()
		} else {
			logger.d("close() -> No dialog to close")
		}
	}

	/**
	 * Populates the dialog UI with information from the given download model.
	 *
	 * This method updates all key UI elements to reflect the current download’s
	 * metadata and visual details, including:
	 * - File name and source URL
	 * - Thumbnail preview
	 * - File type indicator icon
	 * - Website favicon
	 * - Media play indicator (shown for audio/video files)
	 *
	 * @param downloadModel The [DownloadDataModel] containing the download’s details to display.
	 */
	private fun updateDialogFileInfo(downloadModel: DownloadDataModel) {
		logger.d("updateDialogFileInfo() -> Updating dialog info for file: ${downloadModel.fileName}")

		dialogBuilder.view.apply {
			// File name
			findViewById<TextView>(R.id.txt_file_title).apply {
				isSelected = true
				text = downloadModel.fileName
				logger.d("updateDialogFileInfo() -> File name set: ${downloadModel.fileName}")
			}

			// File URL
			findViewById<TextView>(R.id.txt_file_url).apply {
				text = downloadModel.fileURL
				logger.d("updateDialogFileInfo() -> File URL set: ${downloadModel.fileURL}")
			}

			// Thumbnail
			findViewById<ImageView>(R.id.img_file_thumbnail).apply {
				updateThumbnail(this, downloadModel)
				logger.d("updateDialogFileInfo() -> Thumbnail updated")
			}

			// File type indicator
			findViewById<ImageView>(R.id.img_file_type_indicator).apply {
				updateFileTypeIndicator(this, downloadModel)
				logger.d("updateDialogFileInfo() -> File type indicator updated")
			}

			// Private folder indication
			findViewById<ImageView>(R.id.img_private_folder_indicator).apply {
				updatePrivateFolderIndicator(this, downloadModel)
				logger.d("updateDialogFileInfo() -> Site favicon updated")
			}

			// Site favicon
			findViewById<ImageView>(R.id.img_site_favicon).apply {
				updateFaviconInfo(this, downloadModel)
				logger.d("updateDialogFileInfo() -> Site favicon updated")
			}

			// Media play indicator (audio/video only)
			findViewById<ImageView>(R.id.img_media_play_indicator).apply {
				updateMediaPlayIndicator(this, downloadModel)
				logger.d("updateDialogFileInfo() -> Media play indicator updated")
			}

			if (isMediaFile(downloadModel)) {
				// Show playback duration if available
				findViewById<View>(R.id.container_media_duration).apply {
					val txtMediaPlaybackDuration = findViewById<TextView>(R.id.txt_media_duration)
					val mediaFilePlaybackDuration = downloadModel.mediaFilePlaybackDuration
					val playbackTimeString = mediaFilePlaybackDuration.replace("(", "").replace(")", "")
					if (playbackTimeString.isNotEmpty()) {
						showView(this, true)
						showView(txtMediaPlaybackDuration, true)
						txtMediaPlaybackDuration.text = playbackTimeString
					}
				}
			} else {
				findViewById<View>(R.id.container_media_duration).visibility = View.GONE
			}
		}
	}

	/**
	 * Determines if a downloaded file is a supported media file.
	 *
	 * @param downloadModel The [DownloadDataModel] representing the downloaded file.
	 * @return `true` if the file is audio or video based on its name; otherwise `false`.
	 */
	private fun isMediaFile(downloadModel: DownloadDataModel): Boolean =
		isAudioByName(downloadModel.fileName) || isVideoByName(downloadModel.fileName)

	/**
	 * Updates the visibility of the media play indicator based on the file type.
	 *
	 * Displays the play icon for audio and video files, while hiding it for
	 * non-media file types to maintain a clean UI presentation.
	 *
	 * @param mediaPlayIndicator The [ImageView] that represents the media play icon.
	 * @param downloadDataModel The [DownloadDataModel] containing file metadata used for type detection.
	 */
	private fun updateMediaPlayIndicator(
		mediaPlayIndicator: ImageView,
		downloadDataModel: DownloadDataModel
	) {
		val fileName = downloadDataModel.fileName
		mediaPlayIndicator.visibility = if (isMediaFile(downloadDataModel)) {
			logger.d("updateMediaPlayIndicator() -> Media file detected ($fileName), indicator VISIBLE")
			View.VISIBLE
		} else {
			logger.d("updateMediaPlayIndicator() -> Non-media file detected ($fileName), indicator GONE")
			View.GONE
		}
	}

	/**
	 * Updates the file type indicator icon in the UI based on the file type
	 * detected from the download model's file name.
	 *
	 * @param fileTypeIndicator The imageview that related to the information
	 * @param downloadDataModel The model containing information about the downloaded file
	 */
	private fun updateFileTypeIndicator(
		fileTypeIndicator: ImageView,
		downloadDataModel: DownloadDataModel
	) {
		logger.d("Updating file type indicator for download ID: ${downloadDataModel.downloadId}")

		// Determine the correct icon by checking file type via file name
		fileTypeIndicator.setImageResource(
			when {
				isImageByName(downloadDataModel.fileName) -> R.drawable.ic_button_images   // Image files
				isAudioByName(downloadDataModel.fileName) -> R.drawable.ic_button_audio    // Audio files
				isVideoByName(downloadDataModel.fileName) -> R.drawable.ic_button_video    // Video files
				isDocumentByName(downloadDataModel.fileName) -> R.drawable.ic_button_document // Documents
				isArchiveByName(downloadDataModel.fileName) -> R.drawable.ic_button_archives  // Archives
				isProgramByName(downloadDataModel.fileName) -> R.drawable.ic_button_programs  // Executables/programs
				else -> R.drawable.ic_button_file // Default for unknown file types
			}
		)
	}

	/**
	 * Updates the private folder indicator icon in the download dialog.
	 *
	 * Reflects the current download location setting by changing the icon
	 * to indicate whether files are saved to a private (locked) folder or
	 * a public directory. This ensures visual consistency with the user's
	 * active preference in the UI.
	 *
	 * @param privateFolderImageView The [ImageView] used to display the private folder icon.
	 * @param downloadModel The [DownloadDataModel] containing the user's download configuration.
	 */
	private fun updatePrivateFolderIndicator(privateFolderImageView: ImageView, downloadModel: DownloadDataModel) {
		logger.d("Updating private folder indicator UI state")
		val downloadLocation = downloadModel.globalSettings.defaultDownloadLocation
		logger.d("Current download location: $downloadLocation")

		// Update indicator icon based on folder type
		privateFolderImageView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock  // Private folder
				else -> R.drawable.ic_button_folder         // Normal folder
			}
		)
	}

	/**
	 * Loads and displays the favicon for a specific download entry.
	 *
	 * Functionality:
	 * - Attempts to fetch a cached favicon using `AIOApp.aioFavicons`.
	 * - Falls back to a default favicon drawable if unavailable or access fails.
	 * - Skips favicon loading if video thumbnails are restricted by settings.
	 *
	 * Execution:
	 * - Runs favicon retrieval in a background thread for efficiency.
	 * - Updates the `ImageView` safely on the main thread.
	 *
	 * @param favicon The [ImageView] where the favicon will be displayed.
	 * @param downloadDataModel The [DownloadDataModel] containing the site referrer used for favicon lookup.
	 */
	private fun updateFaviconInfo(favicon: ImageView, downloadDataModel: DownloadDataModel) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.downloadId}")
		val defaultFaviconResId = R.drawable.ic_image_default_favicon
		val defaultFaviconDrawable = getDrawable(INSTANCE.resources, defaultFaviconResId, null)

		// Skip favicon loading if video thumbnails are not allowed
		if (isVideoThumbnailNotAllowed(downloadDataModel)) {
			logger.d("Video thumbnails not allowed, using default favicon")
			executeOnMainThread { favicon.setImageDrawable(defaultFaviconDrawable) }
			return
		}

		ThreadsUtility.executeInBackground(codeBlock = {
			val referralSite = downloadDataModel.siteReferrer
			logger.d("Loading favicon for site: $referralSite")
			aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
				val faviconImgFile = File(faviconFilePath)
				if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
					logger.d("Favicon file not found")
					return@executeInBackground
				}
				val faviconImgURI = faviconImgFile.toUri()
				ThreadsUtility.executeOnMain(codeBlock = {
					try {
						logger.d("Setting favicon from URI")
						showView(favicon, true)
						favicon.setImageURI(faviconImgURI)
					} catch (error: Exception) {
						logger.d("Error setting favicon: ${error.message}")
						error.printStackTrace()
						showView(favicon, true)
						favicon.setImageResource(defaultFaviconResId)
					}
				})
			}
		}, errorHandler = {
			logger.e("Error loading favicon: ${it.message}", it)
			favicon.setImageDrawable(defaultFaviconDrawable)
		})
	}

	/**
	 * Determines whether video thumbnails should be hidden for a given download item.
	 *
	 * Behavior:
	 * - Returns `true` only when:
	 *   - The file type is video, **and**
	 *   - The global settings disable showing video thumbnails.
	 * - Used to decide whether to skip thumbnail or favicon rendering for videos.
	 *
	 * @param downloadDataModel The [DownloadDataModel] containing file type and global settings.
	 * @return `true` if video thumbnails are disabled, otherwise `false`.
	 */
	private fun isVideoThumbnailNotAllowed(downloadDataModel: DownloadDataModel): Boolean {
		val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail
		val result = isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden
		logger.d("isVideoThumbnailNotAllowed() -> File: ${downloadDataModel.fileName}, Result: $result")
		return result
	}

	/**
	 * Updates and displays the thumbnail for a given download.
	 *
	 * Behavior:
	 * - Attempts to load APK icon first (if file is an APK).
	 * - If APK loading fails:
	 *   - Tries cached thumbnail path if available.
	 *   - Otherwise generates a new thumbnail from video/file.
	 *   - Handles portrait orientation by rotating the bitmap.
	 *   - Saves thumbnail for reuse.
	 *
	 * @param thumbImageView The ImageView where the thumbnail should be displayed
	 * @param downloadModel The download model containing file and thumbnail info
	 */
	private fun updateThumbnail(thumbImageView: ImageView, downloadModel: DownloadDataModel) {
		val destinationFile = downloadModel.getDestinationFile()
		val defaultThumb = downloadModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

		logger.d("updateThumbnail() -> Updating thumbnail for file: ${downloadModel.fileName}")

		// Step 1: Try loading APK thumbnail first
		if (loadApkThumbnail(
				downloadModel = downloadModel,
				imageViewHolder = thumbImageView,
				defaultThumbDrawable = defaultThumbDrawable
			)
		) {
			logger.d("updateThumbnail() -> APK thumbnail loaded successfully, skipping further processing")
			return
		}

		// Step 2: Handle normal thumbnails in background
		ThreadsUtility.executeInBackground(codeBlock = {
			logger.d("updateThumbnail() -> Executing thumbnail load in background thread")

			// Case A: Cached thumbnail available
			val cachedThumbPath = downloadModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				logger.d("updateThumbnail() -> Cached thumbnail found at: $cachedThumbPath")
				executeOnMainThread {
					loadBitmapWithGlide(thumbImageView, downloadModel.thumbPath, defaultThumb)
				}
				return@executeInBackground
			}

			// Case B: Generate new thumbnail
			val thumbnailUrl = downloadModel.videoInfo?.videoThumbnailUrl
			val bitmap = getThumbnailFromFile(destinationFile, thumbnailUrl, 420)

			if (bitmap != null) {
				logger.d("updateThumbnail() -> Thumbnail generated successfully for: ${downloadModel.fileName}")

				// Rotate portrait thumbnails for better display
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) {
					logger.d("updateThumbnail() -> Thumbnail is portrait, rotating by 270°")
					rotateBitmap(bitmap = bitmap, angle = 270f)
				} else bitmap

				// Save thumbnail to persistent storage
				val thumbnailName = "${downloadModel.downloadId}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					logger.d("updateThumbnail() -> Thumbnail saved at: $filePath")
					downloadModel.thumbPath = filePath
					downloadModel.updateInStorage()

					// Load saved thumbnail into UI
					executeOnMainThread {
						loadBitmapWithGlide(
							thumbImageView = thumbImageView,
							thumbFilePath = downloadModel.thumbPath,
							defaultThumb = defaultThumb
						)
					}
				}
			} else {
				logger.d("updateThumbnail() -> Failed to generate thumbnail for: ${downloadModel.fileName}")
			}
		})
	}

	/**
	 * Loads a thumbnail image into the provided [ImageView] from a given file path URI.
	 *
	 * Behavior:
	 * - Attempts to decode and display the bitmap from the given file path.
	 * - Falls back to a default thumbnail image if loading fails or the file is missing.
	 *
	 * @param thumbImageView The [ImageView] where the thumbnail will be displayed.
	 * @param thumbFilePath The absolute file path pointing to the thumbnail image.
	 * @param defaultThumb The resource ID of the default image to display on failure.
	 */
	private fun loadBitmapWithGlide(
		thumbImageView: ImageView,
		thumbFilePath: String,
		defaultThumb: Int
	) {
		try {
			val imgURI = File(thumbFilePath).toUri()
			thumbImageView.setImageURI(imgURI)
			logger.d("loadBitmapWithGlide() -> Loaded thumbnail from path: $thumbFilePath")
		} catch (error: Exception) {
			logger.e("loadBitmapWithGlide() -> Failed to load thumbnail, using default", error)
			thumbImageView.setImageResource(defaultThumb)
		}
	}

	/**
	 * Loads an APK file's icon and sets it as a thumbnail in the specified [ImageView].
	 *
	 * Behavior:
	 * - If the APK file does not exist or the path is invalid, displays the fallback thumbnail.
	 * - If the APK is valid, extracts its application icon via [PackageManager] and sets it in [imageViewHolder].
	 * - Catches any exceptions, logs the error, and applies the fallback thumbnail while resetting view styling.
	 *
	 * @param downloadModel The [DownloadDataModel] containing the APK file information.
	 * @param imageViewHolder The [ImageView] where the APK icon or fallback thumbnail will be displayed.
	 * @param defaultThumbDrawable The drawable resource to use as a fallback if APK icon cannot be loaded.
	 * @return `true` if the APK icon was successfully loaded, `false` otherwise.
	 */
	private fun loadApkThumbnail(
		downloadModel: DownloadDataModel,
		imageViewHolder: ImageView,
		defaultThumbDrawable: Drawable?
	): Boolean {
		logger.d("loadApkThumbnail() -> Attempting to load thumbnail for file: ${downloadModel.fileName}")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val apkFile = downloadModel.getDestinationFile()
			logger.d("loadApkThumbnail() -> Checking file at path: ${apkFile.absolutePath}")

			// Validate file existence and extension
			if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
				logger.d("loadApkThumbnail() -> File is missing or not an APK, using default thumbnail")
				imageViewHolder.setImageDrawable(defaultThumbDrawable)
				return false
			}

			// Try extracting APK icon
			val packageManager: PackageManager = safeMotherActivityRef.packageManager
			return try {
				val packageInfo: PackageInfo? = packageManager.getPackageArchiveInfo(
					apkFile.absolutePath,
					PackageManager.GET_ACTIVITIES
				)

				packageInfo?.applicationInfo?.let { appInfo ->
					// Assign file paths for proper icon loading
					appInfo.sourceDir = apkFile.absolutePath
					appInfo.publicSourceDir = apkFile.absolutePath

					// Load and set icon
					val icon: Drawable = appInfo.loadIcon(packageManager)
					imageViewHolder.setImageDrawable(icon)
					logger.d("loadApkThumbnail() -> APK icon loaded successfully")
					true
				} ?: run {
					logger.d("loadApkThumbnail() -> Failed: packageInfo or applicationInfo is null")
					false
				}
			} catch (error: Exception) {
				logger.e("loadApkThumbnail() -> Error while loading APK thumbnail", error)

				// Apply fallback thumbnail with safe styling
				imageViewHolder.apply {
					scaleType = ImageView.ScaleType.FIT_CENTER
					setPadding(0, 0, 0, 0)
					setImageDrawable(defaultThumbDrawable)
				}
				false
			}
		}

		logger.d("loadApkThumbnail() -> Failed: safeMotherActivityRef is null")
		return false
	}

	/**
	 * Plays the media file linked to the given download.
	 *
	 * Behavior:
	 * 1. For video files with available metadata:
	 *    - Extracts a playable stream URL using [YoutubeDL].
	 *    - Displays a waiting dialog while extraction occurs.
	 *    - Launches the media player activity with the obtained stream URL.
	 *
	 * 2. For non-video files or when metadata is unavailable:
	 *    - Opens the local media file directly using [MediaPlayerActivity].
	 *
	 * @param downloadModel The [DownloadDataModel] representing the media to play.
	 */
	@OptIn(UnstableApi::class)
	private fun playTheMedia() {
		logger.d("playTheMedia() -> Attempting to play media")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			// Close the options dialog first
			close()

			if (downloadDataModel?.videoInfo != null && downloadDataModel?.videoFormat != null) {
				logger.d("playTheMedia() -> Video info and format found, preparing stream extraction")

				// Show loading dialog while stream is prepared
				val waitingDialog = WaitingDialog(
					baseActivityInf = safeMotherActivityRef,
					loadingMessage = getText(string.title_preparing_video_please_wait)
				)
				waitingDialog.show()

				// Run YoutubeDL extraction in background
				executeInBackground {
					val videoUrl = downloadDataModel!!.videoInfo!!.videoUrl
					val request = YoutubeDLRequest(videoUrl).apply {
						addOption("-f", "best")
					}
					logger.d("playTheMedia() -> Sending YoutubeDL request for video: $videoUrl")

					getInstance().getInfo(request).let { info ->
						executeOnMainThread {
							waitingDialog.dialogBuilder?.let { dialogBuilder ->
								if (dialogBuilder.isShowing) {
									logger.d("playTheMedia() -> Closing waiting dialog")
									waitingDialog.close()

									info.url?.let { extractedUrl ->
										logger.d("playTheMedia() -> Stream URL extracted successfully, launching player")
										openMediaPlayerActivity(
											downloadDataModel!!.videoInfo!!,
											downloadDataModel!!.videoFormat!!,
											extractedUrl
										)
									} ?: run {
										logger.d("playTheMedia() -> Failed to extract stream URL (null)")
									}
								}
							}
						}
					}
				}

				// Animate transition
				animActivitySwipeLeft(safeMotherActivityRef)
			} else {
				logger.d("playTheMedia() -> No video info/format found, falling back to local file playback")

				// Fallback for non-video files
				this.close()
				val context = safeMotherActivityRef
				val destinationActivity = MediaPlayerActivity::class.java

				context.startActivity(Intent(context, destinationActivity).apply {
					flags = context.getSingleTopIntentFlags()
					downloadDataModel?.let {
						putExtra(INTENT_EXTRA_STREAM_URL, it.fileURL)
						putExtra(INTENT_EXTRA_STREAM_TITLE, it.fileName)
					}
				})

				// Animate transition
				animActivitySwipeLeft(context)
				this@ActiveTasksOptions.close()
			}
		} ?: run {
			logger.d("playTheMedia() -> Failed: safeMotherActivityRef is null")
		}
	}

	/**
	 * Launches the media player activity to play a streamable video.
	 *
	 * Behavior:
	 * - Uses the provided video metadata and format information to configure playback.
	 * - Opens the player with the given stream URL for immediate playback.
	 *
	 * @param videoInfo Metadata of the video, including title and source.
	 * @param videoFormat Selected video format details (resolution, codec, etc.).
	 * @param streamableMediaUrl The URL of the playable video stream.
	 */
	@OptIn(UnstableApi::class)
	private fun openMediaPlayerActivity(
		videoInfo: VideoInfo,
		videoFormat: VideoFormat,
		streamableMediaUrl: String
	) {
		logger.d("openMediaPlayerActivity() -> Preparing intent for video: ${videoInfo.videoTitle}")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val activity = safeMotherActivityRef
			val playerClass = MediaPlayerActivity::class.java

			activity.startActivity(Intent(activity, playerClass).apply {
				flags = activity.getSingleTopIntentFlags()
				putExtra(INTENT_EXTRA_STREAM_URL, streamableMediaUrl)

				// Build proper title with extension
				val selectedExtension = videoFormat.formatExtension
				val streamingTitle = "${videoInfo.videoTitle}.$selectedExtension"
				putExtra(INTENT_EXTRA_STREAM_TITLE, streamingTitle)
			})

			logger.d("openMediaPlayerActivity() -> Launched MediaPlayerActivity with title: ${videoInfo.videoTitle}")
		} ?: run {
			logger.d("openMediaPlayerActivity() -> Failed: safeMotherActivityRef is null")
		}
	}

	/**
	 * Resumes a paused or interrupted download task.
	 *
	 * Behavior:
	 * - Closes the currently visible options dialog before resuming.
	 * - If the download task is still active, temporarily pauses it and retries after a short delay.
	 * - Detects yt-dlp errors related to login/authentication, prompting the user to log in via browser.
	 * - Otherwise, resumes the download task using standard download procedures.
	 */
	private fun resumeDownloadTask() {
		logger.d("Attempting to resume download task")

		// Close the options dialog immediately
		close()

		downloadDataModel?.let { model ->
			logger.d("Processing resume request for download ID: ${model.downloadId}")

			ThreadsUtility.executeInBackground(codeBlock = {
				// If already active, pause first then resume after delay
				if (downloadSystem.searchActiveDownloadTaskWith(model) != null) {
					logger.d("Download ID: ${model.downloadId} is already active, pausing before resume")
					ThreadsUtility.executeOnMain { pauseDownloadTask() }
					delay(1000)
				}

				ThreadsUtility.executeOnMain {
					// Detect yt-dlp login issue condition
					val hasProblem = model.isYtdlpHavingProblem &&
							model.ytdlpProblemMsg.isNotEmpty() &&
							model.status != DOWNLOADING
					val isLoginIssue = model.ytdlpProblemMsg.contains("login", true)

					if (hasProblem && isLoginIssue) {
						logger.d("Download ID: ${model.downloadId} requires login (yt-dlp issue detected), prompting user")

						// Show login-required dialog
						showMessageDialog(
							baseActivityInf = safeMotherActivityRef,
							titleTextViewCustomize = {
								it.setText(string.title_login_required)
								safeMotherActivityRef?.getColor(R.color.color_error)
									?.let { colorResId -> it.setTextColor(colorResId) }
							},
							messageTextViewCustomize = {
								it.setText(string.text_login_to_download_private_videos)
							},
							isNegativeButtonVisible = false,
							positiveButtonTextCustomize = {
								it.setText(string.title_login_now)
								it.setLeftSideDrawable(R.drawable.ic_button_login)
							}
						)?.apply {
							setOnClickForPositiveButton {
								logger.d("User chose to login for download ID: ${model.downloadId}")
								close()

								// Open browser for login process
								safeMotherActivityRef?.let { safeMotherActivityRef ->
									val browserFragment = safeMotherActivityRef.browserFragment
									val webviewEngine = browserFragment?.getBrowserWebEngine()
										?: return@setOnClickForPositiveButton

									logger.d("Opening browser for login (ID: ${model.downloadId})")
									val sideNavigation = safeMotherActivityRef.sideNavigation
									sideNavigation?.addNewBrowsingTab(model.siteReferrer, webviewEngine)
									safeMotherActivityRef.openBrowserFragment()

									// Clear error state
									model.isYtdlpHavingProblem = false
									model.ytdlpProblemMsg = ""
									logger.d("Cleared yt-dlp error state for download ID: ${model.downloadId}")
								}
							}
						}?.show()
					} else {
						// Normal resume
						logger.d("Resuming download normally for ID: ${model.downloadId}")
						downloadSystem.resumeDownload(
							downloadModel = model,
							coroutineScope = CoroutineScope(Dispatchers.IO),
							onResumed = {
								showToast(
									activityInf = safeMotherActivityRef,
									msgId = string.title_resumed_task_successfully
								)
							}
						)
						logger.d("Download ID: ${model.downloadId} resumed successfully")
					}
				}
			})
		} ?: run {
			logger.d("resumeDownloadTask() failed: downloadDataModel is null")
		}
	}

	/**
	 * Pauses an ongoing download task.
	 *
	 * Behavior:
	 * - Closes the currently visible options dialog before pausing.
	 * - If the task is already paused, displays a toast and exits.
	 * - If the task does not support resuming, warns the user prior to pausing.
	 * - Otherwise, pauses the download task normally.
	 */

	private fun pauseDownloadTask() {
		logger.d("Attempting to pause download task")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			// Close the options dialog immediately
			close()

			downloadDataModel?.let { downloadModel ->
				logger.d("Processing pause request for download ID: ${downloadModel.downloadId}")

				// Check if already paused
				if (downloadSystem.searchActiveDownloadTaskWith(downloadModel) == null) {
					logger.d("Download ID: ${downloadModel.downloadId} is already paused")
					showToast(
						activityInf = safeMotherActivityRef,
						msgId = string.title_download_task_already_paused
					)
					return
				}

				// Show warning if resume is not supported
				if (!downloadModel.isResumeSupported) {
					logger.d("Download ID: ${downloadModel.downloadId} does not support resume; showing warning dialog")

					getMessageDialog(
						baseActivityInf = safeMotherActivityRef,
						isNegativeButtonVisible = false,
						messageTextViewCustomize = {
							it.setText(string.text_warning_resume_not_supported)
						},
						negativeButtonTextCustomize = {
							it.setLeftSideDrawable(R.drawable.ic_button_cancel)
						},
						positiveButtonTextCustomize = {
							it.setText(string.title_pause_anyway)
							it.setLeftSideDrawable(R.drawable.ic_button_media_pause)
						}
					)?.apply {
						setOnClickForPositiveButton {
							logger.d("User confirmed pause despite resume not being supported for ID: ${downloadModel.downloadId}")
							this.close()
							dialogBuilder.close()
							downloadSystem.pauseDownload(downloadModel = downloadModel)
							logger.d("Download ID: ${downloadModel.downloadId} paused successfully (resume not supported)")
						}
						this.show()
					}
				} else {
					// Normal pause operation
					logger.d("Download ID: ${downloadModel.downloadId} supports resume; pausing normally")
					downloadSystem.pauseDownload(downloadModel = downloadModel)
					logger.d("Download ID: ${downloadModel.downloadId} paused successfully")
				}
			} ?: run {
				logger.d("pauseDownloadTask() failed: downloadDataModel is null")
			}
		} ?: run {
			logger.d("pauseDownloadTask() failed: safeMotherActivityRef is null")
		}
	}

	/**
	 * Removes a download task from the system list but keeps the file intact.
	 *
	 * Workflow:
	 * 1. If the task is active, attempt to pause it before removal.
	 * 2. Check if it is still active; if yes, show a warning dialog and stop.
	 * 3. If not active, show a confirmation dialog to the user.
	 * 4. On confirmation, clear the task from the system and update the UI.
	 */
	private fun removeDownloadTask() {
		logger.d("Attempting to remove download task (keep file)")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			downloadDataModel?.let { downloadDataModel ->
				// Run in background to prevent blocking UI
				ThreadsUtility.executeInBackground(codeBlock = {
					// Pause task first if active
					if (downloadSystem.searchActiveDownloadTaskWith(downloadDataModel) != null) {
						logger.d("Download ID: ${downloadDataModel.downloadId} is active; pausing before remove")
						ThreadsUtility.executeOnMain { pauseDownloadTask() }
						delay(1500) // Ensure pause is applied before proceeding
					}

					ThreadsUtility.executeOnMain {
						// Prevent removal of still-active downloads
						val taskInf = downloadSystem.searchActiveDownloadTaskWith(downloadDataModel)
						if (taskInf != null) {
							logger.d("Download ID: ${downloadDataModel.downloadId} is still active; cannot remove")

							showMessageDialog(
								baseActivityInf = safeMotherActivityRef,
								isNegativeButtonVisible = false,
								messageTextViewCustomize = {
									it.setText(string.text_cant_remove_one_active_download)
								},
								positiveButtonTextCustomize = {
									it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
								}
							)
							return@executeOnMain
						}
					}
				})
			}

			// Ask user for confirmation before removing
			logger.d("Showing confirmation dialog for remove action")
			getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isTitleVisible = true,
				isNegativeButtonVisible = false,
				titleTextViewCustomize = {
					it.setText(string.title_are_you_sure_about_this)
				},
				messageTextViewCustomize = {
					it.setText(string.text_are_you_sure_about_clear)
				},
				positiveButtonTextCustomize = {
					it.setText(string.title_clear_from_list)
					it.setLeftSideDrawable(R.drawable.ic_button_clear)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("User confirmed removal for download ID: ${downloadDataModel?.downloadId}")
					close()
					this@ActiveTasksOptions.close()

					// Perform removal
					downloadDataModel?.let {
						downloadSystem.clearDownload(it) {
							executeOnMainThread {
								logger.d("Download ID: ${it.downloadId} cleared successfully (file retained)")
								showToast(
									activityInf = safeMotherActivityRef,
									msgId = string.title_successfully_cleared
								)
							}
						}
					}
				}
				show()
			}
		} ?: run {
			logger.d("No valid activity reference; cannot remove task")
		}
	}

	/**
	 * Deletes a download task and its associated file.
	 *
	 * Workflow:
	 * 1. If the task is active, attempt to pause it first.
	 * 2. Show a confirmation dialog to the user.
	 * 3. If confirmed, delete the task and its file, then update the UI.
	 */
	private fun deleteDownloadTask() {
		logger.d("Attempting to delete download task")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			downloadDataModel?.let { downloadDataModel ->
				// Run in background to avoid blocking UI
				ThreadsUtility.executeInBackground(codeBlock = {
					// If task is active, pause it before deletion attempt
					if (downloadSystem.searchActiveDownloadTaskWith(downloadDataModel) != null) {
						logger.d("Download ID: ${downloadDataModel.downloadId} is active; pausing before delete")
						ThreadsUtility.executeOnMain { pauseDownloadTask() }
						delay(1500) // Small delay to ensure pause takes effect
					}

					ThreadsUtility.executeOnMain {
						// Re-check if still active before deletion
						val taskInf = downloadSystem.searchActiveDownloadTaskWith(downloadDataModel)
						if (taskInf != null) {
							logger.d("Download ID: ${downloadDataModel.downloadId} is still active; cannot delete")

							showMessageDialog(
								baseActivityInf = safeMotherActivityRef,
								isNegativeButtonVisible = false,
								messageTextViewCustomize = {
									it.setText(string.text_cant_delete_on_active_download)
								},
								positiveButtonTextCustomize = {
									it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
								}
							)
							return@executeOnMain
						}
					}
				})
			}

			// Show confirmation dialog for deletion
			logger.d("Showing confirmation dialog for delete action")
			getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				titleTextViewCustomize = {
					it.setText(string.title_are_you_sure_about_this)
				},
				isTitleVisible = true,
				isNegativeButtonVisible = false,
				messageTextViewCustomize = {
					it.setText(string.text_are_you_sure_about_delete)
				},
				positiveButtonTextCustomize = {
					it.setText(string.title_delete_file)
					it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("User confirmed deletion for download ID: ${downloadDataModel?.downloadId}")
					close()
					this@ActiveTasksOptions.close()

					// Perform deletion
					downloadDataModel?.let {
						downloadSystem.deleteDownload(it) {
							executeOnMainThread {
								logger.d("Download ID: ${it.downloadId} deleted successfully")
								showToast(
									activityInf = safeMotherActivityRef,
									msgId = string.title_successfully_deleted
								)
							}
						}
					}
				}
				show()
			}
		} ?: run {
			logger.d("No valid activity reference; cannot delete task")
		}
	}

	/**
	 * Renames a download task.
	 * - Initializes the file renamer if not already created.
	 * - Prevents renaming if the download is currently active.
	 * - If eligible, opens the renamer dialog for the selected download.
	 */
	private fun renameDownloadTask() {
		logger.d("Attempting to rename download task")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			// Lazy initialize renamer if not already initialized
			if (!::downloadFileRenamer.isInitialized) {
				logger.d("Initializing DownloadFileRenamer")
				downloadFileRenamer = DownloadFileRenamer(
					motherActivity = safeMotherActivityRef,
					downloadDataModel = downloadDataModel!!
				) { dialogBuilder.close() }
			}

			downloadDataModel?.let { downloadDataModel ->
				logger.d("Checking if download ID: ${downloadDataModel.downloadId} is active before renaming")

				// Prevent renaming of active downloads
				val taskInf = downloadSystem.searchActiveDownloadTaskWith(downloadDataModel)
				if (taskInf != null) {
					logger.d("Download ID: ${downloadDataModel.downloadId} is active; rename not allowed")

					showMessageDialog(
						baseActivityInf = safeMotherActivityRef,
						isNegativeButtonVisible = false,
						messageTextViewCustomize = {
							it.setText(string.text_cant_rename_on_active_download)
						},
						positiveButtonTextCustomize = {
							it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
						}
					)
					return
				}

				// Show rename dialog for eligible downloads
				logger.d("Download ID: ${downloadDataModel.downloadId} is eligible for rename; opening renamer")
				downloadFileRenamer.show(downloadDataModel)
			} ?: run {
				logger.d("No valid DownloadDataModel found; cannot rename task")
			}
		}
	}

	private fun toggleDownloadThumbnail() {
		safeMotherActivityRef?.let { safeMotherActivity ->
			safeMotherActivity.doSomeVibration(50)
			showToast(
				activityInf = safeMotherActivity,
				msgId = string.title_experimental_feature
			)
		}
	}

	/**
	 * Copies the download URL to the clipboard if it's valid.
	 * - If valid: copies, shows confirmation toast, and closes the dialog.
	 * - If invalid: shows a warning toast.
	 */
	private fun copyDownloadFileLink() {
		logger.d("Attempting to copy download URL to clipboard")

		downloadDataModel?.fileURL?.takeIf { isValidURL(it) }?.let { fileUrl ->
			logger.d("Valid URL found: $fileUrl")
			copyTextToClipboard(safeMotherActivityRef, fileUrl)
			showToast(
				activityInf = safeMotherActivityRef,
				msgId = string.title_file_url_has_been_copied
			)
			close()
			logger.d("Download URL copied to clipboard and dialog closed")
		} ?: run {
			logger.d("No valid download URL found to copy")
			showToast(
				activityInf = safeMotherActivityRef,
				msgId = string.title_dont_have_anything_to_copy
			)
		}
	}

	/**
	 * Shares the download URL with other apps if it's valid.
	 * - If valid: opens share intent with title text and closes the dialog after sharing.
	 * - If invalid: shows a warning toast.
	 */
	private fun shareDownloadFileLink() {
		logger.d("Attempting to share download URL")

		downloadDataModel?.fileURL?.takeIf { isValidURL(it) }?.let { fileUrl ->
			val titleText = getText(string.title_share_download_file_url)
			logger.d("Valid URL found for sharing: $fileUrl")
			shareUrl(safeMotherActivityRef, fileUrl, titleText) {
				logger.d("Share intent completed, closing dialog")
				close()
			}
		} ?: run {
			logger.d("No valid download URL found to share")
			showToast(
				activityInf = safeMotherActivityRef,
				msgId = string.title_dont_have_anything_to_share
			)
		}
	}

	/**
	 * Opens the download's referrer link in the browser.
	 * - If referrer link is missing: vibrates device and shows a toast.
	 * - If found: closes current dialogs and opens the link in a new browser tab.
	 */
	private fun openDownloadReferrerLink() {
		logger.d("Attempting to open download referrer link")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val downloadSiteReferrerLink = downloadDataModel?.siteReferrer

			if (downloadSiteReferrerLink.isNullOrEmpty()) {
				logger.d("No referrer link found for this download")
				safeMotherActivityRef.doSomeVibration(50)
				showToast(
					activityInf = safeMotherActivityRef,
					msgId = string.title_no_referer_link_found
				)
				return
			}

			logger.d("Valid referrer link found: $downloadSiteReferrerLink")
			this.close()
			this@ActiveTasksOptions.close()

			// Open the link in the browser
			val webviewEngine = safeMotherActivityRef.browserFragment?.browserFragmentBody?.webviewEngine!!
			safeMotherActivityRef.sideNavigation?.addNewBrowsingTab(downloadSiteReferrerLink, webviewEngine)
			safeMotherActivityRef.openBrowserFragment()
			logger.d("Referrer link opened in browser successfully")
		}
	}

	/**
	 * Opens the download information tracker dialog.
	 * - Lazily initializes the tracker if not already created.
	 * - Displays detailed info for the current download.
	 */
	private fun openDownloadInfoTracker() {
		logger.d("Attempting to open DownloadInfoTracker")

		safeMotherActivityRef?.let { safeMotherActivityRef ->
			// Initialize tracker if not already initialized
			if (!::downloadInfoTracker.isInitialized) {
				logger.d("Initializing DownloadInfoTracker")
				downloadInfoTracker = DownloadInfoTracker(safeMotherActivityRef)
			}

			downloadInfoTracker.show(downloadDataModel!!)
			close()
			logger.d("DownloadInfoTracker displayed successfully and dialog closed")
		}
	}

	/**
	 * Open advanced download settings dialog.
	 */
	private fun openAdvancedDownloadSettings() {
		safeMotherActivityRef?.let { safeMotherActivity ->
			safeMotherActivity.doSomeVibration(50)
			showToast(
				activityInf = safeMotherActivity,
				msgId = string.title_experimental_feature
			)
		}
	}

	/**
	 * Initializes the dialog by inflating its layout and setting up click listeners
	 * for all option buttons related to an active download task.
	 *
	 * This function:
	 * - Inflates the dialog layout.
	 * - Creates a mapping of button IDs to their respective actions.
	 * - Iterates over the map to assign click listeners dynamically.
	 */
	private fun initializeDialogViews() {
		logger.d("Initializing dialog views for active download options")

		// Inflate the dialog layout and access its root view
		dialogBuilder.setView(layout.frag_down_3_active_1_onclick_1).view.apply {
			logger.d("Dialog layout set with frag_down_3_active_1_onclick_1")

			// Map of button view IDs to their respective action handlers
			val clickActions = mapOf(
				R.id.btn_file_info_card to {
					logger.d("File info button clicked")
					openDownloadReferrerLink()
				},
				R.id.btn_resume_download to {
					logger.d("Resume download button clicked")
					resumeDownloadTask()
				},
				R.id.btn_pause_download to {
					logger.d("Pause download button clicked")
					pauseDownloadTask()
				},
				R.id.btn_clear_download to {
					logger.d("Clear download button clicked")
					removeDownloadTask()
				},
				R.id.btn_delete_download to {
					logger.d("Delete download button clicked")
					deleteDownloadTask()
				},
				R.id.btn_rename_download to {
					logger.d("Rename download button clicked")
					renameDownloadTask()
				},
				R.id.btn_remove_thumbnail to {
					logger.d("Rename download button clicked")
					toggleDownloadThumbnail()
				},
				R.id.btn_copy_site_link to {
					logger.d("Copy download site link button clicked")

				},
				R.id.btn_copy_download_url to {
					logger.d("Copy download URL button clicked")
					copyDownloadFileLink()
				},
				R.id.btn_share_download_url to {
					logger.d("Share download URL button clicked")
					shareDownloadFileLink()
				},
				R.id.btn_discover_more to {
					logger.d("Discover more button clicked")
					openDownloadReferrerLink()
				},
				R.id.btn_advanced_download_settings to {
					logger.d("Advance download settings button clicked")
					openAdvancedDownloadSettings()
				},
				R.id.btn_download_system_information to {
					logger.d("Download system info button clicked")
					openDownloadInfoTracker()
				}
			)

			// Assign click listeners for each button dynamically
			clickActions.forEach { (viewId, action) ->
				findViewById<View>(viewId).setOnClickListener {
					logger.d("Button with ID $viewId clicked")
					action()
				}
			}
			logger.d("All dialog buttons initialized with click listeners")
		}
	}

}