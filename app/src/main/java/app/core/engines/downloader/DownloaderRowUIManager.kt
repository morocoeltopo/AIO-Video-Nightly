package app.core.engines.downloader

import android.view.View
import android.view.View.GONE
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOApp.Companion.internalDataFolder
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import com.aio.R
import com.anggrayudi.storage.file.getAbsolutePath
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.isBlackThumbnail
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.showView
import java.io.File
import java.lang.ref.WeakReference

/**
 * UI controller class for managing individual download row views in the download list.
 * This class handles all UI updates and visual state management for a single download item,
 * including thumbnails, progress indicators, status text, and various icons.
 *
 * The class uses lazy initialization for view components and weak references to prevent memory leaks.
 * It maintains state for dialog visibility, thumbnail caching, and settings changes.
 */
class DownloaderRowUIManager(private val downloadRowView: View) {

	/** Logger instance for tracking UI events and debugging */
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to the row view to prevent memory leaks in lists */
	private val rowViewRef = WeakReference(downloadRowView)

	/** Tracks whether any dialog is currently being shown to prevent multiple overlapping dialogs */
	private var isShowingAnyDialog = false

	/** Flag indicating if the thumbnail has been loaded and cached to avoid redundant loading */
	private var cachedThumbLoaded = false

	/** Tracks changes in thumbnail settings to trigger UI updates when preferences change */
	private var isThumbnailSettingsChanged = false

	// Lazy-initialized view components for the download row

	/** ImageView for displaying the file thumbnail or default icon */
	private val thumbnailView: ImageView by lazy { downloadRowView.findViewById(R.id.img_file_thumbnail) }

	/** ImageView for showing status indicator (e.g., downloading, paused, completed) */
	private val statusIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_status_indicator) }

	/** TextView for displaying the filename or placeholder text */
	private val fileNameView: TextView by lazy { downloadRowView.findViewById(R.id.txt_file_name) }

	/** TextView for showing download progress, speed, and status information */
	private val statusTextView: TextView by lazy { downloadRowView.findViewById(R.id.txt_download_status) }

	/** ImageView for displaying the website favicon for browser-initiated downloads */
	private val siteFaviconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_site_favicon) }

	/** TextView for displaying media duration (audio/video files only) */
	private val mediaDurationView: TextView by lazy { downloadRowView.findViewById(R.id.txt_media_duration) }

	/** Container view that holds the media duration text (shown/hidden based on file type) */
	private val mediaDurationContainer: View by lazy { downloadRowView.findViewById(R.id.container_media_duration) }

	/** ImageView for showing file type icon (document, image, video, audio, etc.) */
	private val fileTypeIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_file_type_indicator) }

	/** ImageView for showing play icon indicator on media files (audio/video) */
	private val mediaPlayIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_media_play_indicator) }

	/** ImageView for indicating whether file is saved to private (locked) or public folder */
	private val privateFolderIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_private_folder_indicator) }

	/**
	 * Updates all UI components of the download row with the current state from the download model.
	 * This method serves as the central coordinator for refreshing the download row's visual
	 * representation, ensuring all UI elements accurately reflect the download's current status.
	 *
	 * The method performs a comprehensive update of all visual components in sequence:
	 * - Row visibility (shown/hidden based on download state)
	 * - File name display
	 * - Progress indicators and status
	 * - Thumbnail image
	 * - Website favicon (for browser downloads)
	 * - Media playback controls (for audio/video files)
	 * - File duration display (for media files)
	 * - File type icon
	 * - Private folder indicator
	 * - Error dialogs (if applicable)
	 *
	 * @param downloadModel The DownloadDataModel containing current download state and metadata
	 */
	fun updateView(downloadModel: DownloadDataModel) {
		logger.d("UI update: id=${downloadModel.downloadId}, status=${downloadModel.status}")

		// Safely retrieve the row view reference - skip update if view is no longer available
		rowViewRef.get()?.let {
			// Update row visibility based on download state (e.g., hidden if removed/deleted)
			updateRowVisibility(downloadModel)

			// Update the primary file name label
			updateFileNameLabel(downloadModel)

			// Refresh progress bars, percentage, speed, and status text
			refreshProgressState(downloadModel)

			// Load or update the file thumbnail image
			refreshThumbnail(downloadModel)

			// Display website favicon for browser-initiated downloads
			refreshFavicon(downloadModel)

			// Show/hide media play icon for audio/video files
			refreshMediaPlayIcon(downloadModel)

			// Display duration for media files (audio/video)
			refreshMediaDuration(downloadModel)

			// Show appropriate file type icon based on file extension
			refreshFileTypeIcon(downloadModel)

			// Display private folder indicator if file saved to secure storage
			refreshPrivateFolderIcon(downloadModel)

			// Show error dialog if download failed and has error message
			showDownloadErrorDialog(downloadModel)
		} ?: logger.d("Row ref lost, skipping update")
	}

	/**
	 * Updates the visibility of the download row based on the download model's state.
	 * This method determines whether the row should be visible or hidden in the UI
	 * based on various completion, removal, and user preference flags.
	 *
	 * The row will be hidden if any of the following conditions are met:
	 * - Download has been removed from the UI (isRemoved)
	 * - Download has completed successfully (isComplete)
	 * - File has been moved to private folder (isWentToPrivateFolder)
	 * - User has enabled setting to hide download progress from UI
	 *
	 * @param downloadModel The DownloadDataModel containing the current state flags
	 */
	private fun updateRowVisibility(downloadModel: DownloadDataModel) {
		rowViewRef.get()?.let {
			logger.d("Visibility: id=${downloadModel.downloadId}, " +
					"removed=${downloadModel.isRemoved}, " +
					"complete=${downloadModel.isComplete}")
			if (downloadModel.isRemoved ||
				downloadModel.isComplete ||
				downloadModel.isWentToPrivateFolder ||
				downloadModel.globalSettings.hideDownloadProgressFromUI) {
				if (it.visibility != GONE) {
					logger.d("Hiding row: id=${downloadModel.downloadId}")
					it.visibility = GONE
				}
			}
		}
	}

	/**
	 * Updates the file name label in the download row with the current filename.
	 * This method displays the actual filename from the download model, or shows
	 * a placeholder message if the filename hasn't been retrieved from the server yet.
	 *
	 * @param downloadModel The DownloadDataModel containing the filename to display
	 */
	private fun updateFileNameLabel(downloadModel: DownloadDataModel) {
		fileNameView.text = downloadModel.fileName.ifEmpty {
			logger.d("No filename for id=${downloadModel.downloadId}, showing placeholder")
			getText(R.string.title_getting_name_from_server)
		}
	}

	/**
	 * Refreshes the progress indicators and status display for the download row.
	 * This method serves as the entry point for updating all progress-related UI
	 * components including progress bars, percentage text, speed indicators, and
	 * status messages based on the current download state.
	 *
	 * @param downloadModel The DownloadDataModel containing progress and status information
	 */
	private fun refreshProgressState(downloadModel: DownloadDataModel) {
		logger.d("Progress: id=${downloadModel.downloadId}, " +
				"${downloadModel.progressPercentage}% ${downloadModel.status}")
		renderProgressDetails(downloadModel)
	}

	/**
	 * Dynamically shrinks text to fit within a TextView's available width while preserving a specific ending.
	 * This method recursively adjusts the text content by removing characters from the end (before the specified
	 * endMatch string) until the text fits within the available view width, or until the text becomes too short.
	 *
	 * The method handles cases where the view width isn't available yet by posting a retry to the message queue.
	 *
	 * @param textView The TextView whose text needs to be fitted
	 * @param text The original text content to be displayed
	 * @param endMatch The string pattern at the end that should be preserved if possible (e.g., file extension)
	 *
	 * Usage example: Shrinking "very_long_filename.mp4" to fit, preserving ".mp4" extension
	 */
	private fun shrinkTextToFitView(textView: TextView, text: String, endMatch: String) {
		var newText = text
		val paint = textView.paint
		val availableWidth = textView.width - textView.paddingStart - textView.paddingEnd
		logger.d("Fit text: \"$text\" endMatch=\"$endMatch\"")

		// If view width isn't available yet, retry after layout pass
		if (availableWidth <= 0) {
			logger.d("Width not ready, retrying")
			textView.post { shrinkTextToFitView(textView, text, endMatch) }
			return
		}

		// Only attempt to trim if the text ends with the specified pattern
		if (newText.endsWith(endMatch, ignoreCase = true)) {
			logger.d("Trimming text end \"$endMatch\" if needed")

			// Gradually remove characters until text fits or becomes too short
			while (paint.measureText(newText) > availableWidth && newText.length > 4) {
				if (newText.endsWith(endMatch, ignoreCase = true)) {
					newText = newText.dropLast(endMatch.length)
				}
			}
			logger.d("Trimmed text: \"$newText\"")
		} else logger.d("No trim needed")

		// Apply the potentially modified text to the TextView
		textView.text = newText
		logger.d("Text set")
	}

	/**
	 * Renders the progress details and status information in the status TextView.
	 * This method displays either yt-dlp error messages or normal download progress information
	 * with appropriate text coloring based on the download state.
	 *
	 * For error states: Shows yt-dlp error messages in error color
	 * For normal states: Shows formatted download info and fits text to available space
	 *
	 * @param downloadModel The DownloadDataModel containing status and progress information
	 */
	private fun renderProgressDetails(downloadModel: DownloadDataModel) {
		val context = statusTextView.context
		// Check if there's a yt-dlp error message to display (and not currently downloading)
		if (downloadModel.status != DOWNLOADING && downloadModel.ytdlpProblemMsg.isNotEmpty()) {
			logger.d("yt-dlp error for id=${downloadModel.downloadId}: ${downloadModel.ytdlpProblemMsg}")
			statusTextView.text = downloadModel.ytdlpProblemMsg
			statusTextView.setTextColor(context.getColor(R.color.color_error))
		} else {
			// Generate and display normal download status information
			val infoInString = downloadModel.generateDownloadInfoInString()
			// Shrink text to fit view while preserving the time format at the end
			shrinkTextToFitView(statusTextView, infoInString, "|  --:-- ")
			statusTextView.setTextColor(context.getColor(R.color.color_text_hint))
		}
	}

	/**
	 * Refreshes the thumbnail image display based on download model and user settings.
	 * This method handles showing/hiding thumbnails according to user preferences and
	 * manages the thumbnail cache to avoid unnecessary reloads.
	 *
	 * The method considers:
	 * - User setting to hide video thumbnails
	 * - Thumbnail settings changes to trigger updates
	 * - Caching to prevent redundant thumbnail loading
	 *
	 * @param downloadModel The DownloadDataModel containing thumbnail information
	 */
	private fun refreshThumbnail(downloadModel: DownloadDataModel) {
		val settings = downloadModel.globalSettings
		val shouldHideThumbnail = settings.downloadHideVideoThumbnail
		logger.d("updateFileThumbnail: id=${downloadModel.downloadId}, hideThumb=$shouldHideThumbnail")

		// Track if thumbnail settings have changed to force refresh
		if (shouldHideThumbnail != isThumbnailSettingsChanged) {
			isThumbnailSettingsChanged = true
		}

		// Load thumbnail if not cached or if settings changed
		if (thumbnailView.tag == null || isThumbnailSettingsChanged) {
			if (shouldHideThumbnail) {
				// Show actual thumbnail from file URI when hiding is disabled
				logger.d("Setting actual thumbnail URI for id=${downloadModel.downloadId}")
				thumbnailView.setImageURI(downloadModel.getThumbnailURI())
				thumbnailView.tag = true
				isThumbnailSettingsChanged = shouldHideThumbnail
			} else {
				// Use default thumbnail logic when hiding is enabled
				logger.d("Using default thumbnail logic for id=${downloadModel.downloadId}")
				loadDefaultThumbnail(downloadModel)
			}
		}
	}

	/**
	 * Updates the file type icon based on the downloaded file's extension.
	 * This method determines the appropriate icon to display by checking the file name
	 * against known file type categories and sets the corresponding drawable resource.
	 *
	 * The icon categories include:
	 * - Images (R.drawable.ic_button_images)
	 * - Audio files (R.drawable.ic_button_audio)
	 * - Video files (R.drawable.ic_button_video)
	 * - Documents (R.drawable.ic_button_document)
	 * - Archives (R.drawable.ic_button_archives)
	 * - Programs/Executables (R.drawable.ic_button_programs)
	 * - Default file icon for all other types
	 *
	 * @param downloadDataModel The DownloadDataModel containing the file name to categorize
	 */
	private fun refreshFileTypeIcon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating file type indicator for download ID: ${downloadDataModel.downloadId}")
		fileTypeIconView.setImageResource(
			when {
				isImageByName(downloadDataModel.fileName) -> R.drawable.ic_button_images
				isAudioByName(downloadDataModel.fileName) -> R.drawable.ic_button_audio
				isVideoByName(downloadDataModel.fileName) -> R.drawable.ic_button_video
				isDocumentByName(downloadDataModel.fileName) -> R.drawable.ic_button_document
				isArchiveByName(downloadDataModel.fileName) -> R.drawable.ic_button_archives
				isProgramByName(downloadDataModel.fileName) -> R.drawable.ic_button_programs
				else -> R.drawable.ic_button_file
			}
		)
	}

	/**
	 * Updates the media duration display for audio and video files.
	 * This method processes the media file's playback duration in a background thread
	 * and updates the UI on the main thread to avoid blocking the UI.
	 *
	 * The method:
	 * - Only processes audio and video files
	 * - Cleans up duration formatting (removes parentheses)
	 * - Shows duration if valid and non-empty
	 * - Hides duration container for non-media files or invalid durations
	 *
	 * @param downloadDataModel The DownloadDataModel containing media file information
	 */
	private fun refreshMediaDuration(downloadDataModel: DownloadDataModel) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val fileName = downloadDataModel.fileName
			// Only process duration for audio or video files
			if (isVideoByName(fileName) || isAudioByName(fileName)) {
				val playbackDuration = downloadDataModel.mediaFilePlaybackDuration
				// Clean up duration format by removing parentheses
				val cleanedData = playbackDuration.replace("(", "").replace(")", "")

				// Show duration if it's not empty and not the default placeholder
				if (cleanedData.isNotEmpty() && !cleanedData.contentEquals("--:--", true)) {
					executeOnMain {
						showView(mediaDurationContainer, true)
						mediaDurationView.text = cleanedData
					}
				} else executeOnMain { hideView(mediaDurationContainer, shouldAnimate = false) }
			} else executeOnMain { mediaDurationContainer.visibility = GONE }
		})
	}

	/**
	 * Controls the visibility of the media play icon based on file type.
	 * This method shows the play icon for audio and video files, and hides it for all other file types.
	 * The play icon indicates that the file can be played back directly within the app.
	 *
	 * @param downloadDataModel The DownloadDataModel containing the file name to check
	 */
	private fun refreshMediaPlayIcon(downloadDataModel: DownloadDataModel) {
		val fileName = downloadDataModel.fileName
		if (isVideoByName(fileName) || isAudioByName(fileName))
			mediaPlayIconView.visibility = View.VISIBLE
		else mediaPlayIconView.visibility = GONE
	}

	/**
	 * Loads and displays the favicon (website icon) associated with the download's source.
	 * This method attempts to retrieve the favicon from the referral site and falls back
	 * to a default icon if the favicon cannot be loaded or if video thumbnails are disabled.
	 *
	 * The process:
	 * 1. Checks if video thumbnails are allowed (falls back to default if not)
	 * 2. Attempts to load favicon from the referral site in background thread
	 * 3. Sets the favicon image on the main thread if found
	 * 4. Falls back to default favicon if loading fails or file doesn't exist
	 *
	 * @param downloadDataModel The DownloadDataModel containing site referral information
	 */
	private fun refreshFavicon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.downloadId}")
		val defaultFaviconResId = R.drawable.ic_image_default_favicon
		val defaultFaviconDrawable = ResourcesCompat.getDrawable(
			rowViewRef.get()?.context?.resources ?: INSTANCE.resources, defaultFaviconResId, null
		)

		// Skip favicon loading if video thumbnails are not allowed
		if (shouldHideVideoThumbnail(downloadDataModel)) {
			logger.d("Video thumbnails not allowed, using default favicon")
			executeOnMainThread { siteFaviconView.setImageDrawable(defaultFaviconDrawable) }
			return
		}

		// Load favicon in background thread to avoid blocking UI
		ThreadsUtility.executeInBackground(codeBlock = {
			val referralSite = downloadDataModel.siteReferrer
			logger.d("Loading favicon for site: $referralSite")

			// Attempt to retrieve favicon file path from favicon cache
			aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
				val faviconImgFile = File(faviconFilePath)

				// Verify favicon file exists and is valid
				if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
					logger.d("Favicon file not found"); return@executeInBackground
				}

				val faviconImgURI = faviconImgFile.toUri()
				executeOnMain {
					try {
						logger.d("Setting favicon from URI")
						showView(siteFaviconView, true)
						siteFaviconView.setImageURI(faviconImgURI)
					} catch (error: Exception) {
						// Fall back to default favicon if URI loading fails
						logger.d("Error setting favicon: ${error.message}")
						error.printStackTrace()
						showView(siteFaviconView, true)
						siteFaviconView.setImageResource(defaultFaviconResId)
					}
				}
			}
		}, errorHandler = { error ->
			// Handle any errors during favicon loading process
			logger.e("Error loading favicon: ${error.message}", error)
			siteFaviconView.setImageDrawable(defaultFaviconDrawable)
		})
	}

	/**
	 * Determines whether video thumbnails should be hidden based on user settings and file type.
	 * This method checks both the global application settings for thumbnail visibility
	 * and the actual file type to make a decision about showing or hiding thumbnails.
	 *
	 * The method considers two factors:
	 * 1. User preference from global settings (downloadHideVideoThumbnail)
	 * 2. Whether the downloaded file is actually a video file
	 *
	 * Video thumbnails are hidden only when BOTH conditions are true:
	 * - User has enabled the "hide video thumbnails" setting
	 * - The downloaded file is a video file
	 *
	 * @param downloadDataModel The DownloadDataModel containing file information and user settings
	 * @return Boolean indicating whether video thumbnails should be hidden (true = hide, false = show)
	 */
	private fun shouldHideVideoThumbnail(downloadDataModel: DownloadDataModel): Boolean {
		val model = downloadDataModel
		val globalSettings = model.globalSettings
		val isVideoHidden = globalSettings.downloadHideVideoThumbnail
		val downloadFile = model.getDestinationDocumentFile()
		val result = isVideo(downloadFile) && isVideoHidden
		logger.d("Video thumbnail allowed: ${!result}")
		return result
	}

	/**
	 * Loads the default thumbnail for a download, handling various scenarios including:
	 * - Unknown file sizes (fallback to default icon)
	 * - Cached thumbnails (load from existing file)
	 * - Video files with progress > 5% or available thumbnail URLs
	 * - Generating new thumbnails from video files or remote URLs
	 *
	 * This method implements a multi-stage thumbnail loading strategy:
	 * 1. Check for unknown file size -> show default icon
	 * 2. Check for cached thumbnail -> load from cache
	 * 3. Check download progress or thumbnail availability -> generate new thumbnail
	 * 4. Fallback to default icon if no thumbnail can be generated
	 *
	 * @param downloadModel The DownloadDataModel containing thumbnail information and file metadata
	 */
	private fun loadDefaultThumbnail(downloadModel: DownloadDataModel) {
		// Early return for unknown file sizes - use default icon
		if (downloadModel.isUnknownFileSize) {
			displayDefaultThumbnailIcon(downloadModel)
			thumbnailView.tag = true
			return
		}

		val videoInfo = downloadModel.videoInfo
		val videoFormat = downloadModel.videoFormat
		val thumbPath = downloadModel.thumbPath

		// Only attempt thumbnail generation for downloads with sufficient progress or available thumbnails
		if (downloadModel.progressPercentage > 5 ||
			videoInfo?.videoThumbnailUrl != null ||
			downloadModel.thumbnailUrl.isNotEmpty()) {
			executeInBackground {
				// Skip if thumbnail already loaded to avoid duplicate processing
				if (cachedThumbLoaded) return@executeInBackground

				val defaultThumb = downloadModel.getThumbnailDrawableID()
				val cachedThumbPath = thumbPath

				// Check if cached thumbnail exists and load it
				if (cachedThumbPath.isNotEmpty() && File(cachedThumbPath).exists()) {
					executeOnMainThread {
						loadBitmapToImageView(thumbPath, defaultThumb)
						cachedThumbLoaded = true
					}
					return@executeInBackground
				} else {
					// Determine the appropriate source file for thumbnail generation
					val videoDestinationFile = if (videoInfo != null && videoFormat != null) {
						// Handle yt-dlp video files and their temporary parts
						val ytdlpDestinationFilePath = downloadModel.tempYtdlpDestinationFilePath
						val ytdlpId = File(ytdlpDestinationFilePath).name
						var destinationFile = File(ytdlpDestinationFilePath)

						// Search for partial download files that match the yt-dlp ID
						internalDataFolder.listFiles().forEach { file ->
							try {
								file?.let {
									if (!file.isFile) return@let
									if (file.name!!.startsWith(ytdlpId)
										&& file.name!!.endsWith("part")) {
										destinationFile = File(file.getAbsolutePath(INSTANCE))
									}
								}
							} catch (error: Exception) {
								logger.e("Error processing file:", error)
							}
						}
						destinationFile
					} else {
						// Use regular destination file for non-video downloads
						downloadModel.getDestinationFile()
					}

					// Generate thumbnail from file or remote URL
					val thumbnailUrl = videoInfo?.videoThumbnailUrl ?: downloadModel.thumbnailUrl
					val bitmap = getThumbnailFromFile(videoDestinationFile, thumbnailUrl, 420)

					if (bitmap != null) {
						// Rotate portrait-oriented thumbnails for proper display
						val rotatedBitmap = if (bitmap.height > bitmap.width) {
							rotateBitmap(bitmap, 270f)
						} else bitmap

						// Save generated thumbnail to cache
						val thumbnailName = "${downloadModel.downloadId}$THUMB_EXTENSION"
						saveBitmapToFile(bitmapToSave = rotatedBitmap,
							fileName = thumbnailName)?.let { filePath ->
							// Only use non-black thumbnails (valid content)
							if (!isBlackThumbnail(File(filePath))) {
								downloadModel.thumbPath = filePath
								downloadModel.updateInStorage()
								executeOnMainThread {
									loadBitmapToImageView(
										thumbFilePath = thumbPath,
										defaultThumb = defaultThumb
									)
								}
							}
						}
					}
				}
			}
		} else {
			// Fallback to default icon for insufficient progress or no thumbnail sources
			displayDefaultThumbnailIcon(downloadModel)
		}
	}

	/**
	 * Loads a bitmap image from file path into the thumbnail view with fallback to default image.
	 * This method attempts to load a thumbnail image from the specified file path and displays
	 * a default placeholder image if the file cannot be loaded or doesn't exist.
	 *
	 * @param thumbFilePath The file system path to the thumbnail image file
	 * @param defaultThumb The resource ID of the default thumbnail to display on error
	 */
	private fun loadBitmapToImageView(thumbFilePath: String, defaultThumb: Int) {
		try {
			// Attempt to load thumbnail from the specified file path
			thumbnailView.setImageURI(File(thumbFilePath).toUri())
		} catch (error: Exception) {
			// Fall back to default thumbnail if file loading fails
			logger.e("Error loading thumbnail: ${error.message}", error)
			thumbnailView.setImageResource(defaultThumb)
		}
	}

	/**
	 * Displays the default thumbnail icon for a download when no custom thumbnail is available.
	 * This method shows a generic file-type appropriate icon as a placeholder when:
	 * - File size is unknown
	 * - Download progress is insufficient (<5%)
	 * - Thumbnail generation fails
	 * - No remote thumbnail URL is available
	 *
	 * @param downloadModel The DownloadDataModel used to determine the appropriate default icon
	 */
	private fun displayDefaultThumbnailIcon(downloadModel: DownloadDataModel) {
		logger.d("Showing default thumb for id=${downloadModel.downloadId}")
		val context = rowViewRef.get()?.context ?: INSTANCE
		val drawableID = downloadModel.getThumbnailDrawableID()
		val drawable = getDrawable(context, drawableID)
		thumbnailView.setImageDrawable(drawable)
	}

	/**
	 * Updates the private folder indicator icon based on the current download location setting.
	 * This method shows different icons to indicate whether the file is being downloaded to:
	 * - Private folder (locked icon): App-specific secure storage
	 * - Other locations (folder icon): Public or system-accessible storage
	 *
	 * The icon helps users quickly identify the privacy level of their download location.
	 *
	 * @param downloadModel The DownloadDataModel containing the current download location settings
	 */
	private fun refreshPrivateFolderIcon(downloadModel: DownloadDataModel) {
		logger.d("Updating private folder indicator UI state")
		val downloadLocation = downloadModel.globalSettings.defaultDownloadLocation
		logger.d("Current download location: $downloadLocation")
		privateFolderIconView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock
				else -> R.drawable.ic_button_folder
			}
		)
	}

	/**
	 * Displays an error dialog to the user when a download failure occurs.
	 * This method shows a modal dialog with the error message stored in the download model,
	 * allowing the user to acknowledge the issue. The dialog is only shown if there's a
	 * message to display and no other dialog is currently visible.
	 *
	 * The method handles the following:
	 * - Checks if there's an error message to display
	 * - Ensures no other dialog is currently showing to prevent overlap
	 * - Retrieves the active tasks fragment for context
	 * - Shows a non-cancelable dialog with the error details
	 * - Clears the error message after user acknowledgment to prevent repeated displays
	 *
	 * @param downloadDataModel The download model containing the error message to display
	 */
	private fun showDownloadErrorDialog(downloadDataModel: DownloadDataModel) {
		// Only proceed if there's an error message to show the user
		if (downloadDataModel.msgToShowUserViaDialog.isNotEmpty()) {
			logger.d("Showing error dialog for id=${downloadDataModel.downloadId}")

			// Get the active tasks fragment to use as context for the dialog
			downloadSystem.downloadsUIManager.activeTasksFragment?.let {
				// Ensure no other dialog is currently showing to prevent overlap
				if (!isShowingAnyDialog) {
					MsgDialogUtils.showMessageDialog(
						baseActivityInf = it.safeBaseActivityRef,
						titleText = getText(R.string.title_download_failed),
						isTitleVisible = true,
						isCancelable = false,
						messageTxt = downloadDataModel.msgToShowUserViaDialog,
						isNegativeButtonVisible = false,
						dialogBuilderCustomize = { dialogBuilder ->
							// Mark that a dialog is now showing to prevent multiple dialogs
							isShowingAnyDialog = true
							dialogBuilder.setOnClickForPositiveButton {
								// Close dialog and reset state when user acknowledges
								dialogBuilder.close()
								this@DownloaderRowUIManager.isShowingAnyDialog = false
								// Clear the error message to prevent showing again
								downloadDataModel.msgToShowUserViaDialog = ""
								// Persist the change to storage
								downloadDataModel.updateInStorage()
							}
						}
					)
				}
			}
		}
	}
}