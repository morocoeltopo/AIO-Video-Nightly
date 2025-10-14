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
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.isBlackThumbnail
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.showView
import java.io.File
import java.lang.ref.WeakReference

/**
 * A UI controller class that manages the display and interaction of download items in a list row.
 *
 * Handles:
 * - Thumbnails (cached, default, or dynamically generated)
 * - Progress bars & status messages
 * - Visibility based on download state
 * - Error dialogs for failed downloads
 *
 * Uses WeakReference to prevent memory leaks from holding strong references to views.
 */
class DownloaderRowUI(private val downloadRowView: View) {

	private val logger = LogHelperUtils.from(javaClass)
	private val rowViewRef = WeakReference(downloadRowView)

	private var isShowingAnyDialog = false      // Tracks if an error dialog is currently shown
	private var cachedThumbLoaded = false      // Tracks if thumbnail has been loaded from cache
	private var isThumbnailSettingsChanged = false // Tracks changes to thumbnail visibility setting

	// Lazy-initialized view references for better performance
	private val thumbnailView: ImageView by lazy { downloadRowView.findViewById(R.id.img_file_thumbnail) }
	private val statusIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_status_indicator) }
	private val fileNameView: TextView by lazy { downloadRowView.findViewById(R.id.txt_file_name) }
	private val statusTextView: TextView by lazy { downloadRowView.findViewById(R.id.txt_download_status) }
	private val siteFaviconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_site_favicon) }
	private val mediaDurationView: TextView by lazy { downloadRowView.findViewById(R.id.txt_media_duration) }
	private val mediaDurationContainer: View by lazy { downloadRowView.findViewById(R.id.container_media_duration) }
	private val fileTypeIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_file_type_indicator) }
	private val mediaPlayIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_media_play_indicator) }
	private val privateFolderIconView: ImageView by lazy { downloadRowView.findViewById(R.id.img_private_folder_indicator) }

	/**
	 * Main update method that refreshes all UI elements for a download item.
	 * Called whenever the download state changes to update the UI accordingly.
	 *
	 * @param downloadModel The DownloadDataModel containing current download state
	 */
	fun updateView(downloadModel: DownloadDataModel) {
		logger.d("Updating UI for downloadId=${downloadModel.id}, status=${downloadModel.status}")
		rowViewRef.get()?.let { safeRowLayoutRef ->
			updateRowVisibility(downloadModel, safeRowLayoutRef)
			updateFileNameLabel(downloadModel)
			refreshProgressState(downloadModel)
			refreshThumbnail(downloadModel)
			refreshFavicon(downloadModel)
			refreshMediaPlayIcon(downloadModel)
			refreshMediaDuration(downloadModel)
			refreshFileTypeIcon(downloadModel)
			refreshPrivateFolderIcon(downloadModel)
			showDownloadErrorDialog(downloadModel)
		} ?: logger.d("Row layout reference lost, skipping update.")
	}

	/**
	 * Controls the overall visibility of the row based on download state.
	 * Hides rows for completed, removed, or private folder downloads.
	 *
	 * @param downloadModel The download data model containing state information
	 * @param rowLayout The view layout to update visibility for
	 */
	private fun updateRowVisibility(downloadModel: DownloadDataModel, rowLayout: View) {
		logger.d(
			"updateEntireVisibility: id=${downloadModel.id}," +
					" removed=${downloadModel.isRemoved}, complete=${downloadModel.isComplete}"
		)
		if (downloadModel.isRemoved ||
			downloadModel.isComplete ||
			downloadModel.isWentToPrivateFolder ||
			downloadModel.globalSettings.hideDownloadProgressFromUI
		) {
			if (rowLayout.visibility != GONE) {
				logger.d("Hiding row for id=${downloadModel.id}")
				rowLayout.visibility = GONE
			}
		}
	}

	/**
	 * Updates the filename display, showing placeholder text if name isn't available yet.
	 *
	 * @param downloadModel The download data model containing file information
	 */
	private fun updateFileNameLabel(downloadModel: DownloadDataModel) {
		fileNameView.text = downloadModel.fileName.ifEmpty {
			logger.d("Filename not available yet for id=${downloadModel.id}, showing placeholder")
			getText(R.string.title_getting_name_from_server)
		}
	}

	/**
	 * Updates all progress-related UI elements including progress bars and status text.
	 *
	 * @param downloadModel The download data model containing progress information
	 */
	private fun refreshProgressState(downloadModel: DownloadDataModel) {
		logger.d(
			"updateDownloadProgress: id=${downloadModel.id}," +
					" progress=${downloadModel.progressPercentage}," +
					" status=${downloadModel.status}"
		)
		renderProgressDetails(downloadModel)
	}

	/**
	 * Ensures that the given text fits within the width of a [TextView].
	 *
	 * This function only trims the text if it ends with a specific substring.
	 * It repeatedly removes 4 characters from the end of the text until the
	 * rendered width fits inside the [TextView]'s available space.
	 *
	 * Usage scenario:
	 * Useful when dynamic UI elements (like file names or URLs) must fit
	 * within fixed-width layouts, and trimming should only occur for
	 * texts with a specific suffix.
	 *
	 * @param textView The [TextView] where the text will be displayed.
	 * @param text The original text content to fit inside the [TextView].
	 * @param endMatch The substring to check at the end of the text.
	 *                  Trimming occurs only if this substring is present.
	 */
	/**
	 * Adjusts the text of a [TextView] to fit within its width.
	 *
	 * Trims 4 characters at a time if necessary. If the text ends with
	 * the specified `endMatch` substring and trimming occurs, the
	 * `endMatch` will be removed entirely.
	 *
	 * @param textView The [TextView] to update.
	 * @param text The original text content.
	 * @param endMatch The substring to check at the end. It will be removed if trimming occurs.
	 */
	private fun shrinkTextToFitView(textView: TextView, text: String, endMatch: String) {
		var newText = text
		val paint = textView.paint
		val availableWidth = textView.width - textView.paddingStart - textView.paddingEnd

		logger.d("Starting text fitting for TextView with text: \"$text\" (endMatch: \"$endMatch\")")

		// Wait until layout is ready (width available)
		if (availableWidth <= 0) {
			logger.d("TextView width not ready yet. Reposting layout check.")
			textView.post { shrinkTextToFitView(textView, text, endMatch) }
			return
		}

		// Proceed only if the text ends with the specified substring
		if (newText.endsWith(endMatch, ignoreCase = true)) {
			logger.d("Text ends with \"$endMatch\"; trimming will be applied if necessary.")

			// Trim 4 characters at a time until the text fits
			while (paint.measureText(newText) > availableWidth && newText.length > 4) {
				logger.d(
					"Measured width (${paint.measureText(newText)}) " +
							"exceeds available width ($availableWidth). Trimming..."
				)

				if (newText.endsWith(endMatch, ignoreCase = true)) {
					logger.d("Removing endMatch \"$endMatch\" after trimming.")
					newText = newText.dropLast(endMatch.length)
				}
			}

			logger.d("Final trimmed text: \"$newText\"")
		} else {
			logger.d("Text does not end with \"$endMatch\"; no trimming performed.")
		}

		// Apply final text to the TextView
		textView.text = newText
		logger.d("Text set to TextView successfully.")
	}

	/**
	 * Updates the progress bar and status text with current download information.
	 * Shows error messages in red if there are yt-dlp problems.
	 *
	 * @param downloadModel The download data model containing status information
	 */
	private fun renderProgressDetails(downloadModel: DownloadDataModel) {
		if (downloadModel.status != DOWNLOADING && downloadModel.ytdlpProblemMsg.isNotEmpty()) {
			logger.d("yt-dlp error for id=${downloadModel.id}: ${downloadModel.ytdlpProblemMsg}")
			statusTextView.text = downloadModel.ytdlpProblemMsg
			statusTextView.setTextColor(statusTextView.context.getColor(R.color.color_error))
		} else {
			val infoInString = downloadModel.generateDownloadInfoInString()
			shrinkTextToFitView(textView = statusTextView, text = infoInString, endMatch = "|  --:-- ")
			statusTextView.setTextColor(statusTextView.context.getColor(R.color.color_text_hint))
		}
	}

	/**
	 * Updates the file thumbnail based on download state and settings.
	 * Handles both video thumbnails and default icons for other file types.
	 *
	 * @param downloadModel The download data model containing thumbnail information
	 */
	private fun refreshThumbnail(downloadModel: DownloadDataModel) {
		logger.d(
			"updateFileThumbnail: id=${downloadModel.id}, " +
					"hideThumb=${downloadModel.globalSettings.downloadHideVideoThumbnail}"
		)
		// Check if thumbnail visibility setting changed
		if (downloadModel.globalSettings.downloadHideVideoThumbnail
			!= isThumbnailSettingsChanged
		) isThumbnailSettingsChanged = true

		// Only update thumbnail if not set or settings changed
		if (thumbnailView.tag == null || isThumbnailSettingsChanged) {
			if (downloadModel.globalSettings.downloadHideVideoThumbnail) {
				// Show actual thumbnail if enabled in settings
				logger.d("Setting actual thumbnail URI for id=${downloadModel.id}")
				thumbnailView.setImageURI(downloadModel.getThumbnailURI())
				thumbnailView.tag = true
				isThumbnailSettingsChanged =
					downloadModel.globalSettings.downloadHideVideoThumbnail
			} else {
				// Show default thumbnail
				logger.d("Using default thumbnail logic for id=${downloadModel.id}")
				loadDefaultThumbnail(downloadModel)
			}
		}
	}

	/**
	 * Updates the file type indicator icon in the UI based on the file type
	 * detected from the download model's file name.
	 *
	 * @param downloadDataModel The model containing information about the downloaded file
	 */
	private fun refreshFileTypeIcon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating file type indicator for download ID: ${downloadDataModel.id}")

		// Determine the correct icon by checking file type via file name
		fileTypeIconView.setImageResource(
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
	 * Extracts and displays playback time from the file info text if available.
	 * Shows/hides the duration container based on whether playback time exists.
	 *
	 * @param downloadDataModel the associated download data model
	 */
	private fun refreshMediaDuration(downloadDataModel: DownloadDataModel) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val fileName = downloadDataModel.fileName
			if (isVideoByName(fileName) || isAudioByName(fileName)) {
				val cleanedData = downloadDataModel.mediaFilePlaybackDuration
					.replace("(", "")
					.replace(")", "")
				if (cleanedData.isNotEmpty()) {
					ThreadsUtility.executeOnMain {
						showView(targetView = mediaDurationContainer, shouldAnimate = true)
						mediaDurationView.text = cleanedData
					}
				} else {
					// optional: handle case when still empty after retries
					ThreadsUtility.executeOnMain {
						showView(targetView = mediaDurationContainer, shouldAnimate = false)
					}
				}
			} else {
				ThreadsUtility.executeOnMain { mediaDurationContainer.visibility = GONE }
			}
		})
	}

	/**
	 * Updates the media play indicator visibility based on file type.
	 * Shows the play indicator for video and audio files only.
	 *
	 * @param downloadDataModel The download data model containing file information
	 */
	private fun refreshMediaPlayIcon(downloadDataModel: DownloadDataModel) {
		val fileName = downloadDataModel.fileName
		if (isVideoByName(fileName) || isAudioByName(fileName))
			mediaPlayIconView.visibility = View.VISIBLE
		else mediaPlayIconView.visibility = GONE
	}

	/**
	 * Loads and sets the favicon for the given download item.
	 * Attempts to load a favicon from cache (via AIOApp.aioFavicons). If unavailable,
	 * falls back to a default drawable.
	 *
	 * @param downloadDataModel The download data model containing the site referrer
	 */
	private fun refreshFavicon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.id}")
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
						showView(siteFaviconView, true)
						siteFaviconView.setImageURI(faviconImgURI)
					} catch (error: Exception) {
						logger.d("Error setting favicon: ${error.message}")
						error.printStackTrace()
						showView(siteFaviconView, true)
						siteFaviconView.setImageResource(defaultFaviconResId)
					}
				})
			}
		}, errorHandler = {
			logger.e("Error loading favicon: ${it.message}", it)
			siteFaviconView.setImageDrawable(defaultFaviconDrawable)
		})
	}

	/**
	 * Checks if video thumbnails are allowed for this download based on settings.
	 *
	 * @param downloadDataModel The download data model
	 * @return true if thumbnails are not allowed, false otherwise
	 */
	private fun shouldHideVideoThumbnail(downloadDataModel: DownloadDataModel): Boolean {
		val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail
		val result = isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden
		logger.d("Video thumbnail allowed: ${!result}")
		return result
	}

	/**
	 * Updates the thumbnail to either a default icon or generates a video thumbnail.
	 * Handles both cached thumbnails and dynamically generated ones.
	 *
	 * @param downloadModel The download data model containing thumbnail information
	 */
	private fun loadDefaultThumbnail(downloadModel: DownloadDataModel) {
		// For unknown sizes, show default icon
		if (downloadModel.isUnknownFileSize) {
			displayDefaultThumbnailIcon(downloadModel)
			thumbnailView.tag = true; return
		}

		// For videos with sufficient progress or known thumbnail URLs
		if (downloadModel.progressPercentage > 5 ||
			downloadModel.videoInfo?.videoThumbnailUrl != null ||
			downloadModel.thumbnailUrl.isNotEmpty()
		) {
			executeInBackground {
				if (cachedThumbLoaded) return@executeInBackground

				val defaultThumb = downloadModel.getThumbnailDrawableID()
				val cachedThumbPath = downloadModel.thumbPath

				// Check for cached thumbnail first
				if (cachedThumbPath.isNotEmpty() && File(cachedThumbPath).exists()) {
					executeOnMainThread {
						loadBitmapToImageView(downloadModel.thumbPath, defaultThumb)
						cachedThumbLoaded = true
					}
					return@executeInBackground
				} else {
					// Determine which file to use for thumbnail generation
					val videoDestinationFile = if (downloadModel.videoInfo != null
						&& downloadModel.videoFormat != null
					) {
						// Handle yt-dlp partial downloads
						val ytdlpId = File(downloadModel.tempYtdlpDestinationFilePath).name
						var destinationFile = File(downloadModel.tempYtdlpDestinationFilePath)
						internalDataFolder.listFiles().forEach { file ->
							try {
								file?.let {
									if (!file.isFile) return@let
									if (file.name!!.startsWith(ytdlpId)
										&& file.name!!.endsWith("part")
									)
										destinationFile = File(file.getAbsolutePath(INSTANCE))
								}
							} catch (error: Exception) {
								error.printStackTrace()
							}
						}; destinationFile
					} else {
						// Regular download file
						downloadModel.getDestinationFile()
					}

					// Get thumbnail URL from either video info or download model
					val videoInfoThumbnailUrl = downloadModel.videoInfo?.videoThumbnailUrl
					val downloadModelThumbUrl = downloadModel.thumbnailUrl
					val thumbnailUrl = videoInfoThumbnailUrl ?: downloadModelThumbUrl

					// Generate thumbnail bitmap
					val bitmap = getThumbnailFromFile(
						targetFile = videoDestinationFile,
						thumbnailUrl = thumbnailUrl,
						requiredThumbWidth = 420
					)

					if (bitmap != null) {
						// Rotate portrait videos to landscape
						val isPortrait = bitmap.height > bitmap.width
						val rotatedBitmap = if (isPortrait) {
							rotateBitmap(bitmap, 270f)
						} else bitmap

						// Save and display the thumbnail
						val thumbnailName = "${downloadModel.id}$THUMB_EXTENSION"
						saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
							if (!isBlackThumbnail(File(filePath))) {
								downloadModel.thumbPath = filePath
								downloadModel.updateInStorage()
								executeOnMainThread {
									loadBitmapToImageView(downloadModel.thumbPath, defaultThumb)
								}
							}
						}
					}
				}
			}
		} else {
			// Not enough progress for thumbnail - show default
			displayDefaultThumbnailIcon(downloadModel)
		}
	}

	/**
	 * Loads a thumbnail bitmap into the ImageView, falling back to default icon on error.
	 *
	 * @param thumbFilePath The path to the thumbnail file
	 * @param defaultThumb The default thumbnail resource ID to use if loading fails
	 */
	private fun loadBitmapToImageView(thumbFilePath: String, defaultThumb: Int) {
		try {
			thumbnailView.setImageURI(File(thumbFilePath).toUri())
		} catch (error: Exception) {
			logger.e("Error loading thumbnail into ImageView: ${error.message}", error)
			thumbnailView.setImageResource(defaultThumb)
		}
	}

	/**
	 * Shows the default thumbnail icon based on file type.
	 *
	 * @param downloadModel The download data model containing file type information
	 */
	private fun displayDefaultThumbnailIcon(downloadModel: DownloadDataModel) {
		logger.d("Showing default thumb for id=${downloadModel.id}")
		thumbnailView.setImageDrawable(
			getDrawable(rowViewRef.get()?.context ?: INSTANCE, downloadModel.getThumbnailDrawableID())
		)
	}

	/**
	 * Refreshes the private folder indicator in the dialog UI.
	 *
	 * Updates the icon and checkbox to reflect whether downloads are
	 * currently set to a private (locked) folder or a public directory.
	 * Ensures the UI accurately represents the user's active download
	 * location preference.
	 *
	 * @param downloadModel The [DownloadDataModel] containing
	 * the dialog's private folder indicator and settings.
	 */
	private fun refreshPrivateFolderIcon(downloadModel: DownloadDataModel) {
		logger.d("Updating private folder indicator UI state")
		val downloadLocation = downloadModel.globalSettings.defaultDownloadLocation
		logger.d("Current download location: $downloadLocation")

		// Update indicator icon based on folder type
		privateFolderIconView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock  // Private folder
				else -> R.drawable.ic_button_folder         // Normal folder
			}
		)
	}

	/**
	 * Shows error messages to the user via dialog if present in the download model.
	 * Prevents multiple dialogs from showing simultaneously.
	 *
	 * @param downloadDataModel The download data model containing error messages
	 */
	private fun showDownloadErrorDialog(downloadDataModel: DownloadDataModel) {
		if (downloadDataModel.msgToShowUserViaDialog.isNotEmpty()) {
			logger.d("Showing error dialog for id=${downloadDataModel.id}")
			downloadSystem.downloadsUIManager.activeTasksFragment?.let {
				if (!isShowingAnyDialog) {
					MsgDialogUtils.showMessageDialog(
						baseActivityInf = it.safeBaseActivityRef,
						titleText = getText(R.string.title_download_failed),
						isTitleVisible = true,
						isCancelable = false,
						messageTxt = downloadDataModel.msgToShowUserViaDialog,
						isNegativeButtonVisible = false,
						dialogBuilderCustomize = { dialogBuilder ->
							isShowingAnyDialog = true
							dialogBuilder.setOnClickForPositiveButton {
								dialogBuilder.close()
								this@DownloaderRowUI.isShowingAnyDialog = false
								downloadDataModel.msgToShowUserViaDialog = ""
								downloadDataModel.updateInStorage()
							}
						}
					)
				}
			}
		}
	}
}