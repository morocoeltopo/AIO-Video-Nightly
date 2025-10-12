package app.ui.main.fragments.downloads.fragments.finished

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat.getDrawable
import androidx.core.net.toUri
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import com.aio.R
import lib.device.DateTimeUtils.formatLastModifiedDate
import lib.files.FileSizeFormatter.humanReadableSizeOf
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.networks.DownloaderUtils.getAudioPlaybackTimeIfAvailable
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.showView
import java.io.File

/**
 * ViewHolder class for displaying a finished download item in the list.
 * It handles view initialization, data binding, and thumbnail/image loading.
 *
 * Responsibilities:
 * - Displaying download item information (title, file info, duration)
 * - Loading and displaying favicons and thumbnails
 * - Handling click events
 * - Caching formatted details for performance
 */
class FinishedTasksViewHolder(val layout: View) {

	// Logger instance for debugging and tracking
	private val logger = LogHelperUtils.from(javaClass)

	// Cache to store already formatted detail info for reuse to improve performance
	private val detailsCache = mutableMapOf<String, Spanned>()

	// UI components lazy initialization
	private val container: RelativeLayout by lazy { layout.findViewById(R.id.button_finish_download_row) }
	private val thumbnail: ImageView by lazy { layout.findViewById(R.id.img_file_thumbnail) }
	private val favicon: ImageView by lazy { layout.findViewById(R.id.img_site_favicon) }
	private val title: TextView by lazy { layout.findViewById(R.id.txt_file_name) }
	private val fileInfo: TextView by lazy { layout.findViewById(R.id.txt_file_info) }
	private val duration: TextView by lazy { layout.findViewById(R.id.txt_media_duration) }
	private val durationContainer: View by lazy { layout.findViewById(R.id.container_media_duration) }
	private val mediaIndicator: View by lazy { layout.findViewById(R.id.img_media_play_indicator) }
	private val fileTypeIndicator: ImageView by lazy { layout.findViewById(R.id.img_file_type_indicator) }
	private val privateFolderImageView: ImageView by lazy { layout.findViewById(R.id.img_private_folder_indicator) }

	/**
	 * Binds the download data and sets up click listeners.
	 *
	 * @param downloadDataModel The download data model to display
	 * @param onClickItemEvent Click event handler for user interactions
	 */
	fun updateView(
		downloadDataModel: DownloadDataModel,
		onClickItemEvent: FinishedTasksClickEvents
	) {
		logger.d("Updating view for download ID: ${downloadDataModel.id}")
		showDownloadedFileInfo(downloadDataModel)
		setupItemClickEvents(onClickItemEvent, downloadDataModel)
	}

	/**
	 * Displays file information like name, category, size, playback time, and last modified date.
	 * Also initiates thumbnail update.
	 *
	 * @param downloadDataModel The download data model containing file information
	 */
	private fun showDownloadedFileInfo(downloadDataModel: DownloadDataModel) {
		logger.d("Showing file info for download ID: ${downloadDataModel.id}")
		title.apply { text = downloadDataModel.fileName }
		updateFilesInfo(downloadDataModel)
		updateFaviconInfo(downloadDataModel)
		updateThumbnailInfo(downloadDataModel)
		updateFileTypeIndicator(downloadDataModel)
		updatePrivateFolderIndicator(downloadDataModel)
	}

	/**
	 * Sets up click and long click listeners on the finished download item.
	 *
	 * @param onClick The click event handler interface
	 * @param downloadDataModel The download data model associated with this item
	 */
	private fun setupItemClickEvents(
		onClick: FinishedTasksClickEvents,
		downloadDataModel: DownloadDataModel
	) {
		logger.d("Setting up click events for download ID: ${downloadDataModel.id}")
		container.apply {
			isClickable = true
			setOnClickListener { onClick.onFinishedDownloadClick(downloadDataModel) }
			setOnLongClickListener(View.OnLongClickListener {
				onClick.onFinishedDownloadLongClick(downloadDataModel)
				return@OnLongClickListener true
			})
		}
	}

	/**
	 * Updates the file information text with category, size, playback time, and modification date.
	 * Uses caching to avoid recomputing the same information repeatedly.
	 *
	 * @param downloadDataModel The download data model containing file metadata
	 */
	private fun updateFilesInfo(downloadDataModel: DownloadDataModel) {
		ThreadsUtility.executeInBackground(codeBlock = {
			// Check if we have cached details for this download
			val cacheDetails = detailsCache[downloadDataModel.id.toString()]
			if (cacheDetails != null) {
				logger.d("Using cached details for download ID: ${downloadDataModel.id}")
				ThreadsUtility.executeOnMain {
					fileInfo.text = cacheDetails
					updatePlaybackTime(downloadDataModel)
				}
				return@executeInBackground
			}

			// Extract and format file information
			val category = downloadDataModel.getUpdatedCategoryName(shouldRemoveAIOPrefix = true)
			val fileSize = humanReadableSizeOf(downloadDataModel.fileSize.toDouble())
			val playbackTime = downloadDataModel.mediaFilePlaybackDuration.ifEmpty {
				logger.d("Getting audio playback time for download ID: ${downloadDataModel.id}")
				getAudioPlaybackTimeIfAvailable(downloadDataModel)
			}

			// Save playback time if newly fetched
			if (downloadDataModel.mediaFilePlaybackDuration.isEmpty() && playbackTime.isNotEmpty()) {
				logger.d("Saving new playback time for download ID: ${downloadDataModel.id}")
				downloadDataModel.mediaFilePlaybackDuration = playbackTime
				downloadDataModel.updateInStorage()
			}

			val modifyDate = formatLastModifiedDate(downloadDataModel.lastModifiedTimeDate)

			// Format and display the file info text
			ThreadsUtility.executeOnMain {
				fileInfo.apply {
					val detail = fromHtmlStringToSpanned(
						context.getString(
							R.string.title_b_b_b_date_b,
							getText(R.string.title_info), category.removePrefix("AIO"),
							fileSize, playbackTime, modifyDate
						)
					)
					// Cache the formatted details for future use
					detailsCache[downloadDataModel.id.toString()] = detail
					fileInfo.text = detail
					updatePlaybackTime(downloadDataModel)
				}
			}
		})
	}

	/**
	 * Extracts and displays playback time from the file info text if available.
	 * Shows/hides the duration container based on whether playback time exists.
	 *
	 * @param downloadDataModel the associated download data model
	 */
	private fun updatePlaybackTime(downloadDataModel: DownloadDataModel) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val fileName = downloadDataModel.fileName
			if (isVideoByName(fileName) || isAudioByName(fileName)) {
				val cleanedData = downloadDataModel.mediaFilePlaybackDuration
					.replace("(", "")
					.replace(")", "")
				if (cleanedData.isNotEmpty()) {
					ThreadsUtility.executeOnMain {
						showView(targetView = durationContainer, shouldAnimate = true)
						showView(targetView = mediaIndicator, shouldAnimate = true)
						duration.text = cleanedData
					}
				} else {
					// optional: handle case when still empty after retries
					ThreadsUtility.executeOnMain {
						showView(durationContainer, false)
						showView(mediaIndicator, false)
					}
				}
			} else {
				ThreadsUtility.executeOnMain {
					mediaIndicator.visibility = View.GONE
					durationContainer.visibility = View.GONE
				}
			}
		})
	}

	/**
	 * Loads and sets the favicon for the given download item.
	 * Attempts to load a favicon from cache (via AIOApp.aioFavicons). If unavailable,
	 * falls back to a default drawable.
	 *
	 * @param downloadDataModel The download data model containing the site referrer
	 */
	private fun updateFaviconInfo(downloadDataModel: DownloadDataModel) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.id}")
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
	 * Checks if video thumbnails are allowed for this download based on settings.
	 *
	 * @param downloadDataModel The download data model
	 * @return true if thumbnails are not allowed, false otherwise
	 */
	private fun isVideoThumbnailNotAllowed(downloadDataModel: DownloadDataModel): Boolean {
		val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail
		val result = isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden
		logger.d("Video thumbnail allowed: ${!result}")
		return result
	}

	/**
	 * Determines and sets an appropriate thumbnail for the downloaded file.
	 * Loads APK icons, cached thumbnails, or generates a new thumbnail if needed.
	 *
	 * @param downloadDataModel The download data model containing file information
	 */
	private fun updateThumbnailInfo(downloadDataModel: DownloadDataModel) {
		logger.d("Updating thumbnail for download ID: ${downloadDataModel.id}")
		val destinationFile = downloadDataModel.getDestinationFile()
		val defaultThumb = downloadDataModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

		// Skip thumbnail loading if video thumbnails are not allowed
		if (isVideoThumbnailNotAllowed(downloadDataModel)) {
			logger.d("Video thumbnails not allowed, using default thumbnail")
			thumbnail.setImageDrawable(defaultThumbDrawable)
			return
		}

		// First try to load APK thumbnail if applicable
		val isApkThumbnailFound = loadApkThumbnail(
			downloadDataModel = downloadDataModel,
			imageViewHolder = thumbnail,
			defaultThumbDrawable = defaultThumb
		)

		if (isApkThumbnailFound) {
			logger.d("Using APK thumbnail")
			return
		}

		// Otherwise attempt to use or generate a thumbnail
		executeInBackground {
			val cachedThumbPath = downloadDataModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				logger.d("Using cached thumbnail")
				executeOnMainThread {
					loadBitmapWithGlide(
						thumbFilePath = downloadDataModel.thumbPath,
						defaultThumb = defaultThumb
					)
				}
				return@executeInBackground
			}

			logger.d("Generating new thumbnail")
			val bitmap = getThumbnailFromFile(
				targetFile = destinationFile,
				thumbnailUrl = downloadDataModel.videoInfo?.videoThumbnailUrl,
				requiredThumbWidth = 420
			)

			if (bitmap != null) {
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) {
					logger.d("Rotating portrait thumbnail")
					rotateBitmap(bitmap, 270f)
				} else {
					bitmap
				}

				val thumbnailName = "${downloadDataModel.id}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					logger.d("Saved new thumbnail to: $filePath")
					downloadDataModel.thumbPath = filePath
					downloadDataModel.updateInStorage()
					executeOnMainThread {
						loadBitmapWithGlide(
							thumbFilePath = downloadDataModel.thumbPath,
							defaultThumb = defaultThumb
						)
					}
				}
			} else {
				logger.d("Failed to generate thumbnail")
			}
		}
	}

	/**
	 * Updates the file type indicator icon in the UI based on the file type
	 * detected from the download model's file name.
	 *
	 * @param downloadDataModel The model containing information about the downloaded file
	 */
	private fun updateFileTypeIndicator(downloadDataModel: DownloadDataModel) {
		logger.d("Updating file type indicator for download ID: ${downloadDataModel.id}")

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
	private fun updatePrivateFolderIndicator(downloadModel: DownloadDataModel) {
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
	 * Tries to load a thumbnail image using URI and sets it to the ImageView.
	 * Falls back to default if loading fails.
	 *
	 * @param thumbFilePath The path to the thumbnail file
	 * @param defaultThumb The default thumbnail resource ID to use if loading fails
	 */
	private fun loadBitmapWithGlide(thumbFilePath: String, defaultThumb: Int) {
		try {
			logger.d("Loading thumbnail from: $thumbFilePath")
			val imgURI = File(thumbFilePath).toUri()
			thumbnail.setImageURI(imgURI)
		} catch (error: Exception) {
			logger.d("Error loading thumbnail: ${error.message}")
			error.printStackTrace()
			thumbnail.setImageResource(defaultThumb)
		}
	}

	/**
	 * Loads the icon of an APK file as a thumbnail if the file is an APK.
	 *
	 * @param downloadDataModel The download data model
	 * @param imageViewHolder The ImageView to set the thumbnail on
	 * @param defaultThumbDrawable The default thumbnail resource ID
	 * @return true if APK icon was successfully set, false otherwise
	 */
	private fun loadApkThumbnail(
		downloadDataModel: DownloadDataModel,
		imageViewHolder: ImageView,
		defaultThumbDrawable: Int
	): Boolean {
		// First check if we already have a cached thumbnail
		val cachedThumbPath = downloadDataModel.thumbPath
		if (cachedThumbPath.isNotEmpty()) {
			logger.d("Using cached thumbnail")
			executeOnMainThread {
				loadBitmapWithGlide(
					thumbFilePath = downloadDataModel.thumbPath,
					defaultThumb = defaultThumbDrawable
				)
			}; return true
		}

		logger.d("Checking for APK thumbnail")
		val apkFile = downloadDataModel.getDestinationFile()
		if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
			logger.d("Not an APK file or doesn't exist")
			imageViewHolder.setImageResource(defaultThumbDrawable)
			return false
		}

		val packageManager: PackageManager = layout.context.packageManager
		return try {
			logger.d("Loading APK package info")
			val getActivities = PackageManager.GET_ACTIVITIES
			val apkFileAbsolutePath = apkFile.absolutePath
			val packageInfo: PackageInfo? =
				packageManager.getPackageArchiveInfo(apkFileAbsolutePath, getActivities)

			packageInfo?.applicationInfo?.let { appInfo ->
				logger.d("Found APK package info")
				appInfo.sourceDir = apkFileAbsolutePath
				appInfo.publicSourceDir = apkFileAbsolutePath
				val appIconDrawable: Drawable = appInfo.loadIcon(packageManager)
				imageViewHolder.setImageDrawable(appIconDrawable)

				// Save the APK icon as a thumbnail for future use
				ThreadsUtility.executeInBackground(codeBlock = {
					ViewUtility.drawableToBitmap(appIconDrawable)?.let {
						val appIconBitmap = it
						val thumbnailName = "${downloadDataModel.id}$THUMB_EXTENSION"
						saveBitmapToFile(appIconBitmap, thumbnailName)?.let { filePath ->
							logger.d("Saved new thumbnail to: $filePath")
							downloadDataModel.thumbPath = filePath
							downloadDataModel.updateInStorage()
						}
					}
				})
				true
			} ?: run {
				logger.d("No package info found")
				false
			}
		} catch (error: Exception) {
			logger.d("Error loading APK thumbnail: ${error.message}")
			error.printStackTrace()
			imageViewHolder.apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setPadding(0, 0, 0, 0)
				setImageResource(defaultThumbDrawable)
			}
			false
		}
	}
}