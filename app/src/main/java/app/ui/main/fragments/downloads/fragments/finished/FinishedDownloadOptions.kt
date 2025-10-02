package app.ui.main.fragments.downloads.fragments.finished

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.view.View.GONE
import android.view.View.OnClickListener
import android.view.View.VISIBLE
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
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.ui.main.fragments.downloads.dialogs.DownloadFileRenamer
import app.ui.main.fragments.downloads.dialogs.DownloadInfoTracker
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.MediaPlayerActivity.Companion.FROM_FINISHED_DOWNLOADS_LIST
import app.ui.others.media_player.MediaPlayerActivity.Companion.PLAY_MEDIA_FILE_PATH
import app.ui.others.media_player.MediaPlayerActivity.Companion.WHERE_DID_YOU_COME_FROM
import app.ui.others.media_player.dialogs.Mp4ToAudioConverterDialog.showMp4ToAudioConverterDialog
import com.aio.R
import lib.device.ShareUtility.openApkFile
import lib.device.ShareUtility.openFile
import lib.device.ShareUtility.shareMediaFile
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.files.VideoFilesUtility.moveMoovAtomToStart
import lib.networks.URLUtility.isValidURL
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.MsgDialogUtils
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.ViewUtility
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File

/**
 * A class that handles showing and managing options for finished downloads.
 * Provides functionality like playing media, opening files, sharing, deleting, renaming, etc.
 *
 * @param finishedTasksFragment The parent fragment that contains the finished downloads list
 */
class FinishedDownloadOptions(finishedTasksFragment: FinishedTasksFragment?) : OnClickListener {

	// Logger instance for this class
	private val logger = LogHelperUtils.from(javaClass)

	// Safe references to avoid memory leaks
	private val safeFinishedTasksFragmentRef = finishedTasksFragment?.safeFinishTasksFragment
	private val safeMotherActivityRef = safeFinishedTasksFragmentRef?.safeMotherActivityRef

	// Lazy initialization of dialog builder (ensures it's only created when needed)
	private val dialogBuilder: DialogBuilder? by lazy { DialogBuilder(safeMotherActivityRef) }

	// Components for file operations
	private lateinit var downloadFileRenamer: DownloadFileRenamer
	private lateinit var downloadInfoTracker: DownloadInfoTracker
	private var downloadDataModel: DownloadDataModel? = null

	init {
		logger.d("Initializing FinishedDownloadOptions")

		// Initialize dialog view and set click listeners for all option buttons
		dialogBuilder?.let { dialogBuilder ->
			dialogBuilder.setView(R.layout.frag_down_4_finish_1_onclick_1)
			ViewUtility.setViewOnClickListener(
				onClickListener = this,
				layout = dialogBuilder.view,
				ids = listOf(
					R.id.btn_file_info_card,
					R.id.btn_play_the_media,
					R.id.btn_open_download_file,
					R.id.btn_copy_site_link,
					R.id.btn_share_download_file,
					R.id.btn_clear_download,
					R.id.btn_delete_download,
					R.id.btn_rename_download,
					R.id.btn_discover_more,
					R.id.btn_move_to_private,
					R.id.btn_remove_thumbnail,
					R.id.btn_fix_unseekable_mp4_file,
					R.id.btn_mp4_to_mp3_convert,
					R.id.btn_download_system_information
				).toIntArray()
			)
			logger.d("Dialog builder initialized with click listeners")
		}
	}

	/**
	 * Shows the options dialog for a specific download.
	 *
	 * Behavior:
	 * - Sets the current [DownloadDataModel] into context.
	 * - Updates dialog UI with download details (thumbnail, title, etc.).
	 * - Displays the dialog if it's not already visible.
	 *
	 * Safeguards:
	 * - Skips showing if the dialog is already visible.
	 * - Requires safe fragment and activity references to prevent memory leaks.
	 *
	 * @param dataModel The [DownloadDataModel] to show options for.
	 */
	fun show(dataModel: DownloadDataModel) {
		logger.d("Showing options dialog for download ID: ${dataModel.id}")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { _ ->
				dialogBuilder?.let { dialogBuilder ->
					if (!dialogBuilder.isShowing) {
						setDownloadModel(dataModel)
						updateDialogViewsWith(dataModel)
						dialogBuilder.show()
						logger.d("Options dialog shown successfully")
					} else {
						logger.d("Dialog is already showing, skipping show()")
					}
				}
			}
		}
	}

	/**
	 * Sets the current download model to operate on.
	 *
	 * This model is used for all file operations (open, rename, share, etc.)
	 * until another model is assigned.
	 *
	 * @param model The [DownloadDataModel] to set as current.
	 */
	fun setDownloadModel(model: DownloadDataModel) {
		logger.d("Setting download model for ID: ${model.id}")
		this.downloadDataModel = model
	}

	/**
	 * Closes the options dialog if it's currently showing.
	 *
	 * Safe-check ensures it doesn't crash if dialog is null or not visible.
	 */
	fun close() {
		logger.d("Closing options dialog")
		dialogBuilder?.let { dialogBuilder ->
			if (dialogBuilder.isShowing) dialogBuilder.close()
		}
	}

	/**
	 * Handles click events for all option buttons in the dialog.
	 *
	 * Each button is mapped to its corresponding action:
	 * - Play, open, share, rename, delete, etc.
	 * - Special cases: MP4 → MP3 conversion, fix unseekable MP4s, show system info.
	 *
	 * @param view The clicked [View].
	 */
	override fun onClick(view: View?) {
		view?.let {
			logger.d("Option button clicked: ${view.id}")
			when (view.id) {
				R.id.btn_file_info_card -> playTheMedia()
				R.id.btn_play_the_media -> playTheMedia()
				R.id.btn_open_download_file -> openFile()
				R.id.btn_copy_site_link -> copySiteLink()
				R.id.btn_share_download_file -> shareFile()
				R.id.btn_clear_download -> clearFromList()
				R.id.btn_delete_download -> deleteFile()
				R.id.btn_rename_download -> renameFile()
				R.id.btn_discover_more -> discoverMore()
				R.id.btn_move_to_private -> moveToPrivate()
				R.id.btn_remove_thumbnail -> toggleThumbnail()
				R.id.btn_fix_unseekable_mp4_file -> fixUnseekableMp4s()
				R.id.btn_mp4_to_mp3_convert -> convertMp4ToAudio()
				R.id.btn_download_system_information -> downloadInfo()
			}
		}
	}

	/**
	 * Updates the dialog's title, thumbnail, and action views with details from the given download model.
	 *
	 * Behavior:
	 * - Updates title and subtitle (file name & URL).
	 * - Handles thumbnail display:
	 *   - Loads video/APK thumbnails if enabled in settings.
	 *   - Falls back to default thumbnail if disabled or unavailable.
	 * - Updates favicon display with site info.
	 * - Toggles thumbnail visibility text based on user settings.
	 * - Updates action buttons (play, convert, fix) depending on file type (audio/video/other).
	 * - Shows media playback duration and play indicator for media files only.
	 *
	 * Threading:
	 * - Runs thumbnail updates asynchronously when needed.
	 * - UI updates always executed on the main thread.
	 *
	 * Error handling:
	 * - Falls back to default icons/drawables when thumbnails or metadata are missing.
	 *
	 * @param downloadModel The [DownloadDataModel] containing the download's metadata and settings.
	 */
	private fun updateDialogViewsWith(downloadModel: DownloadDataModel) {
		logger.d("Updating title and thumbnails for download ID: ${downloadModel.id}")

		dialogBuilder?.let { dialogBuilder ->
			dialogBuilder.view.apply {
				// 🔹 UI References
				val txtFileUrlSubTitle = findViewById<TextView>(R.id.txt_file_url)
				val txtFileNameTitle = findViewById<TextView>(R.id.txt_file_title)
				val txtPlayTheFile = findViewById<TextView>(R.id.txt_play_the_media)
				val imgFileThumbnail = findViewById<ImageView>(R.id.img_file_thumbnail)
				val imgFileFavicon = findViewById<ImageView>(R.id.img_site_favicon)
				val btnToggleThumbnail = findViewById<TextView>(R.id.txt_remove_thumbnail)
				val btnConvertMp4ToAudio = findViewById<View>(R.id.btn_mp4_to_mp3_convert)
				val btnFixUnseekableMp4VideoFiles = findViewById<View>(R.id.container_mp4_file_fix)
				val containerMediaDuration = findViewById<View>(R.id.container_media_duration)
				val txtMediaPlaybackDuration = findViewById<TextView>(R.id.txt_media_duration)
				val imgMediaPlayIndicator = findViewById<View>(R.id.img_media_play_indicator)
				val imgFileTypeIndicator = findViewById<ImageView>(R.id.img_file_type_indicator)

				// 🔹 Title and subtitle
				txtFileNameTitle.isSelected = true
				txtFileNameTitle.text = downloadModel.fileName
				txtFileUrlSubTitle.text = downloadModel.fileURL

				// 🔹 Thumbnail handling
				imgFileThumbnail.apply {
					if (!downloadModel.globalSettings.downloadHideVideoThumbnail) {
						logger.d("Video thumbnails are enabled, updating thumbnail")
						updateThumbnail(this, downloadModel)
					} else {
						logger.d("Video thumbnails are disabled, using default thumbnail")
						val defaultThumb = downloadModel.getThumbnailDrawableID()
						setImageResource(defaultThumb)
					}
				}

				// 🔹 Update favicon (site icon)
				updateFaviconInfo(downloadModel, imgFileFavicon)

				// 🔹 Thumbnail toggle button text
				btnToggleThumbnail.apply {
					val thumbnailSetting = downloadModel.globalSettings.downloadHideVideoThumbnail
					text = (if (thumbnailSetting) getText(R.string.title_show_thumbnail)
					else getText(R.string.title_hide_thumbnail))
				}

				// 🔹 Play action text (based on file type)
				txtPlayTheFile.text = when {
					isAudioByName(downloadModel.fileName) -> getText(R.string.title_play_the_audio)
					isVideoByName(downloadModel.fileName) -> getText(R.string.title_play_the_video)
					else -> getText(R.string.title_open_the_file)
				}

				// 🔹 Media-specific controls
				if (isMediaFile(downloadModel)) {
					logger.d("Media file detected, showing all related view containers")

					// Show convert/fix buttons only for video files
					btnConvertMp4ToAudio.visibility =
						if (isVideoByName(downloadModel.fileName)) VISIBLE else GONE
					btnFixUnseekableMp4VideoFiles.visibility =
						if (isVideoByName(downloadModel.fileName)) VISIBLE else GONE

					// Show play indicator
					imgMediaPlayIndicator.apply { showView(this, true) }

					// Show playback duration if available
					containerMediaDuration.apply {
						val mediaFilePlaybackDuration = downloadModel.mediaFilePlaybackDuration
						val playbackTimeString = mediaFilePlaybackDuration.replace("(", "").replace(")", "")
						if (playbackTimeString.isNotEmpty()) {
							showView(this, true)
							showView(txtMediaPlaybackDuration, true)
							txtMediaPlaybackDuration.text = playbackTimeString
						}
					}
				} else {
					// Non-media files → hide media-only views
					logger.d("Non-media file, hiding duration container")
					containerMediaDuration.visibility = GONE
					imgMediaPlayIndicator.visibility = GONE
					btnConvertMp4ToAudio.visibility = GONE
					btnFixUnseekableMp4VideoFiles.visibility = GONE
				}

				logger.d("Updating file type indicator image view")
				// Show file type indicator based on file name
				imgFileTypeIndicator.setImageResource(
					when {
						isImageByName(downloadModel.fileName) -> R.drawable.ic_button_images
						isAudioByName(downloadModel.fileName) -> R.drawable.ic_button_audio
						isVideoByName(downloadModel.fileName) -> R.drawable.ic_button_video
						isDocumentByName(downloadModel.fileName) -> R.drawable.ic_button_document
						isArchiveByName(downloadModel.fileName) -> R.drawable.ic_button_archives
						isProgramByName(downloadModel.fileName) -> R.drawable.ic_button_programs
						else -> R.drawable.ic_button_file
					}
				)
			}
		}
	}

	/**
	 * Loads and sets the favicon for the given download item.
	 * Attempts to load a favicon from cache (via AIOApp.aioFavicons). If unavailable,
	 * falls back to a default drawable.
	 *
	 * @param downloadDataModel The download data model containing the site referrer
	 */
	private fun updateFaviconInfo(downloadDataModel: DownloadDataModel, imgFavicon: ImageView) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.id}")
		val defaultFaviconResId = R.drawable.ic_image_default_favicon
		val defaultFaviconDrawable = getDrawable(INSTANCE.resources, defaultFaviconResId, null)

		// Skip favicon loading if video thumbnails are not allowed
		if (isVideoThumbnailNotAllowed(downloadDataModel)) {
			logger.d("Video thumbnails not allowed, using default favicon")
			executeOnMainThread { imgFavicon.setImageDrawable(defaultFaviconDrawable) }
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
						showView(imgFavicon, true)
						imgFavicon.setImageURI(faviconImgURI)
					} catch (error: Exception) {
						logger.d("Error setting favicon: ${error.message}")
						error.printStackTrace()
						showView(imgFavicon, true)
						imgFavicon.setImageResource(defaultFaviconResId)
					}
				})
			}
		}, errorHandler = {
			logger.e("Error loading favicon: ${it.message}", it)
			imgFavicon.setImageDrawable(defaultFaviconDrawable)
		})
	}

	/**
	 * Updates the thumbnail image for a given download.
	 *
	 * Behavior:
	 * 1. Attempts to load an APK-specific thumbnail first (if the file is an APK).
	 * 2. If not an APK, tries to use a cached thumbnail path if available.
	 * 3. If no cache exists, generates a new thumbnail from the video file or its URL.
	 * 4. Rotates portrait thumbnails into landscape orientation for consistent display.
	 * 5. Saves newly generated thumbnails for caching to avoid regenerating later.
	 *
	 * Threading:
	 * - Runs heavy thumbnail loading and generation in a background thread.
	 * - Switches back to the main thread for UI updates (to avoid freezing the UI).
	 *
	 * Error handling:
	 * - Falls back to the default thumbnail if loading/generation fails.
	 *
	 * @param thumbImageView The [ImageView] where the thumbnail should be displayed.
	 * @param downloadModel The [DownloadDataModel] containing file and metadata for thumbnail handling.
	 */
	private fun updateThumbnail(thumbImageView: ImageView, downloadModel: DownloadDataModel) {
		logger.d("Updating thumbnail for download ID: ${downloadModel.id}")

		val destinationFile = downloadModel.getDestinationFile()
		val defaultThumb = downloadModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

		// 🔹 Step 1: Try loading an APK thumbnail if this is an APK file
		if (loadApkThumbnail(downloadModel, thumbImageView, defaultThumbDrawable)) {
			logger.d("APK thumbnail loaded successfully")
			return
		}

		// 🔹 Step 2: Handle non-APK thumbnails in a background thread
		executeInBackground {
			logger.d("Loading thumbnail in background thread")

			// If a cached thumbnail already exists, use it directly
			val cachedThumbPath = downloadModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				logger.d("Using cached thumbnail at: $cachedThumbPath")
				executeOnMainThread {
					loadBitmapWithGlide(thumbImageView, cachedThumbPath, defaultThumb)
				}
				return@executeInBackground
			}

			// 🔹 Step 3: No cache found, generate a new thumbnail
			logger.d("Generating new thumbnail from file/URL")
			val thumbnailUrl = downloadModel.videoInfo?.videoThumbnailUrl
			val bitmap = getThumbnailFromFile(destinationFile, thumbnailUrl, 420)

			if (bitmap != null) {
				// 🔹 Step 4: Rotate portrait images for consistent display
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) {
					logger.d("Rotating portrait thumbnail to landscape")
					rotateBitmap(bitmap, 270f)
				} else bitmap

				// 🔹 Step 5: Save thumbnail to cache and update the model
				val thumbnailName = "${downloadModel.id}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					logger.d("Saved thumbnail to cache: $filePath")
					downloadModel.thumbPath = filePath
					downloadModel.updateInStorage()

					// Apply the new thumbnail on the main thread
					executeOnMainThread {
						loadBitmapWithGlide(
							thumbImageView = thumbImageView,
							thumbFilePath = filePath,
							defaultThumb = defaultThumb
						)
					}
				}
			} else {
				logger.d("Failed to generate thumbnail from file/URL")
			}
		}
	}

	/**
	 * Determines whether video thumbnails should be hidden for the given download.
	 *
	 * Behavior:
	 * - Reads the `downloadHideVideoThumbnail` setting from global settings.
	 * - Checks if the downloaded file is a video.
	 * - If the file is a video and the "hide video thumbnail" setting is enabled,
	 *   then thumbnails are not allowed.
	 *
	 * Logging:
	 * - Logs whether the thumbnail is allowed (`true` = visible, `false` = hidden).
	 *
	 * @param downloadDataModel The download data model containing file and settings.
	 * @return `true` if thumbnails are disabled for this video, otherwise `false`.
	 */
	private fun isVideoThumbnailNotAllowed(downloadDataModel: DownloadDataModel): Boolean {
		// Check if "hide video thumbnail" option is enabled in global settings
		val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail

		// Determine if the file is a video and should be hidden
		val result = isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden

		logger.d("Video thumbnail allowed: ${!result}")
		return result
	}

	/**
	 * Loads a thumbnail image into an [ImageView].
	 *
	 * Behavior:
	 * - Tries to load the thumbnail from the given file path.
	 * - If loading succeeds, displays the image.
	 * - If an error occurs (file missing, corrupted, or Glide issue), falls back to
	 *   a default drawable resource.
	 *
	 * Notes:
	 * - Uses [ImageView.setImageURI] directly instead of Glide here.
	 *   (Despite the method name, Glide is not used in this implementation.)
	 *
	 * Error handling:
	 * - Logs any exceptions and applies the default thumbnail to prevent crashes.
	 *
	 * @param thumbImageView The [ImageView] where the thumbnail will be displayed.
	 * @param thumbFilePath The path to the thumbnail image file.
	 * @param defaultThumb The fallback resource ID used if the thumbnail cannot be loaded.
	 */
	private fun loadBitmapWithGlide(
		thumbImageView: ImageView,
		thumbFilePath: String,
		defaultThumb: Int
	) {
		try {
			logger.d("Loading thumbnail with Glide from: $thumbFilePath")

			// Convert file path to URI and set directly to ImageView
			val imgURI = File(thumbFilePath).toUri()
			thumbImageView.setImageURI(imgURI)
		} catch (error: Exception) {
			logger.d("Error loading thumbnail with Glide: ${error.message}")
			error.printStackTrace()

			// Fallback: use default thumbnail resource
			thumbImageView.setImageResource(defaultThumb)
		}
	}

	/**
	 * Loads the icon of an APK file to use it as the file's thumbnail.
	 *
	 * Behavior:
	 * - Verifies that the downloaded file exists and is an `.apk`.
	 * - Uses [PackageManager] to extract the APK's [ApplicationInfo] and load its icon.
	 * - Falls back to a default thumbnail if:
	 *   - The file is missing, not an APK, or corrupted.
	 *   - The APK has no package info or the icon fails to load.
	 *
	 * Error handling:
	 * - If any exception occurs, logs the error and resets the [ImageView] with
	 *   the default thumbnail and safe scaling.
	 *
	 * @param downloadModel The download data model containing the file reference.
	 * @param imageViewHolder The [ImageView] where the APK's icon or fallback thumbnail will be displayed.
	 * @param defaultThumbDrawable The default thumbnail drawable used when the APK icon is unavailable.
	 * @return `true` if the APK icon was successfully loaded, otherwise `false`.
	 */
	private fun loadApkThumbnail(
		downloadModel: DownloadDataModel,
		imageViewHolder: ImageView,
		defaultThumbDrawable: Drawable?
	): Boolean {
		logger.d("Attempting to load APK thumbnail for download ID: ${downloadModel.id}")

		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				dialogBuilder?.let { _ ->
					val apkFile = downloadModel.getDestinationFile()

					// Validate file existence and extension
					if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
						logger.d("File is not an APK or doesn't exist, using default thumbnail")
						imageViewHolder.setImageDrawable(defaultThumbDrawable)
						return false
					}

					val packageManager: PackageManager = safeMotherActivityRef.packageManager
					return try {
						logger.d("Loading APK package info")

						// Extract package info from the APK archive
						val packageInfo: PackageInfo? = packageManager.getPackageArchiveInfo(
							apkFile.absolutePath, PackageManager.GET_ACTIVITIES
						)

						packageInfo?.applicationInfo?.let { appInfo ->
							logger.d("APK package info found, loading icon")

							// Point ApplicationInfo to the APK file
							appInfo.sourceDir = apkFile.absolutePath
							appInfo.publicSourceDir = apkFile.absolutePath

							// Load APK icon into the ImageView
							val icon: Drawable = appInfo.loadIcon(packageManager)
							imageViewHolder.setImageDrawable(icon)
							true
						} ?: run {
							logger.d("No package info found for APK, using default thumbnail")
							imageViewHolder.setImageDrawable(defaultThumbDrawable)
							false
						}
					} catch (error: Exception) {
						logger.d("Error loading APK thumbnail: ${error.message}")
						error.printStackTrace()

						// Fallback: reset ImageView to safe defaults with the fallback thumbnail
						imageViewHolder.apply {
							scaleType = ImageView.ScaleType.FIT_CENTER
							setPadding(0, 0, 0, 0)
							setImageDrawable(defaultThumbDrawable)
						}
						false
					}
				}
			}
		}
		return false
	}

	/**
	 * Plays the media file associated with the download.
	 *
	 * Behavior:
	 * - Checks whether the downloaded file is a supported media type (audio/video).
	 *   - If **yes**, launches [MediaPlayerActivity] with the file for playback.
	 *   - If **no**, falls back to [openFile] to let the system handle the file type.
	 *
	 * Implementation details:
	 * - Passes important extras to the media player activity:
	 *   - [DOWNLOAD_MODEL_ID_KEY]: ID of the download model for tracking.
	 *   - [PLAY_MEDIA_FILE_PATH]: Boolean flag to instruct playback from file path.
	 *   - [WHERE_DID_YOU_COME_FROM]: Used for navigation context ("Finished Downloads List").
	 * - Uses activity fade animation when starting playback.
	 *
	 * ⚠ Requires `UnstableApi` opt-in due to usage of experimental APIs from Media3.
	 */
	@OptIn(UnstableApi::class)
	fun playTheMedia() {
		logger.d("Play media option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				dialogBuilder?.let { _ ->
					downloadDataModel?.let { downloadModel ->
						if (isMediaFile(downloadModel)) {
							logger.d("Starting MediaPlayerActivity for audio/video file")

							// Launch media player activity for audio/video
							safeMotherActivityRef.startActivity(
								Intent(
									safeMotherActivityRef,
									MediaPlayerActivity::class.java
								).apply {
									flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
									putExtra(DOWNLOAD_MODEL_ID_KEY, downloadModel.id)
									putExtra(PLAY_MEDIA_FILE_PATH, true)
									putExtra(WHERE_DID_YOU_COME_FROM, FROM_FINISHED_DOWNLOADS_LIST)
								}
							)

							// Apply fade animation and close dialog
							animActivityFade(safeMotherActivityRef)
							close()
						} else {
							logger.d("Non-media file, opening with default app")

							// Fallback: open file with system’s default handler
							openFile()
						}
					}
				}
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
	 * Opens the downloaded file using an appropriate application.
	 *
	 * Behavior:
	 * - Detects if the file is an APK.
	 *   - If yes, triggers the APK installation flow with proper authority for FileProvider.
	 * - Otherwise, opens the file using the system’s default associated application.
	 *
	 * ⚠ APK handling is treated differently due to Android's installation security requirements.
	 */
	fun openFile() {
		logger.d("Open file option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeActivityRef ->
				dialogBuilder?.let { _ ->
					close()
					val extensions = listOf("apk").toTypedArray()

					// Special case for APK files
					if (endsWithExtension(downloadDataModel!!.fileName, extensions)) {
						logger.d("APK file detected, opening with installation flow")
						// Special handling for APK files
						val authority = "${safeActivityRef.packageName}.provider"
						val apkFile = downloadDataModel!!.getDestinationFile()
						openApkFile(safeActivityRef, apkFile, authority)
					} else {
						logger.d("Opening non-APK file with default app")
						// Open other file types normally
						openFile(downloadDataModel!!.getDestinationFile(), safeActivityRef)
					}
				}
			}
		}
	}

	/**
	 * Copies the site referrer link (if valid) to the clipboard.
	 *
	 * Behavior:
	 * - Validates the referrer URL.
	 * - If valid, copies it to the clipboard and shows a confirmation toast.
	 * - If invalid or missing, shows a warning toast to the user.
	 */
	private fun copySiteLink() {
		downloadDataModel?.siteReferrer
			?.takeIf { isValidURL(it) }
			?.let { fileUrl ->
				copyTextToClipboard(safeMotherActivityRef, fileUrl)
				showToast(
					activityInf = safeMotherActivityRef,
					msgId = R.string.title_file_url_has_been_copied
				)
				close()
			} ?: run {
			showToast(
				activityInf = safeMotherActivityRef,
				msgId = R.string.title_dont_have_anything_to_copy
			)
		}
	}

	/**
	 * Shares the downloaded file with other applications.
	 *
	 * Behavior:
	 * - Uses the system’s sharing intent mechanism.
	 * - Allows the user to send the file to supported apps (e.g., messaging, email, drive).
	 */
	fun shareFile() {
		logger.d("Share file option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				close()
				logger.d("Sharing media file")
				shareMediaFile(
					context = safeMotherActivityRef,
					file = downloadDataModel!!.getDestinationFile()
				)
			}
		}
	}

	/**
	 * Clears the downloaded item from the finished downloads list without deleting the actual file from storage.
	 *
	 * Behavior:
	 * - Displays a confirmation dialog to ensure the user wants to clear the entry.
	 * - If confirmed:
	 *   - Closes the current dialog and options menu.
	 *   - Removes the [downloadDataModel] from persistent storage (model data).
	 *   - Removes the item from the in-memory finished downloads list.
	 *   - Displays a toast message confirming the item was cleared.
	 *
	 * ⚠ Note: The file itself remains on disk. Only its entry in the app’s finished downloads list is removed.
	 */
	fun clearFromList() {
		logger.d("Clear from list option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->

				// Show confirmation dialog before clearing
				getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { it.setText(R.string.title_are_you_sure_about_this) },
					messageTextViewCustomize = { it.setText(R.string.text_are_you_sure_about_clear) },
					positiveButtonTextCustomize = {
						it.setText(R.string.title_clear_from_list)
						it.setLeftSideDrawable(R.drawable.ic_button_clear)
					}
				)?.apply {
					setOnClickForPositiveButton {
						logger.d("User confirmed clear from list")
						close()
						this@FinishedDownloadOptions.close()

						// Remove model reference from disk and list (but keep file intact)
						downloadDataModel?.deleteModelFromDisk()
						downloadSystem.finishedDownloadDataModels.remove(downloadDataModel!!)

						// Notify user of success
						showToast(
							activityInf = safeMotherActivityRef,
							msgId = R.string.title_successfully_cleared
						)
					}
					show()
				}
			}
		}
	}

	/**
	 * Deletes the downloaded file from storage and removes it from the finished downloads list.
	 *
	 * Behavior:
	 * - Shows a confirmation dialog asking the user if they are sure about deleting the file.
	 * - If the user confirms:
	 *   - Closes the current dialog and options menu.
	 *   - Executes file deletion in a background thread to prevent UI freezing:
	 *     - Removes the file from disk.
	 *     - Deletes the associated [downloadDataModel] from storage.
	 *     - Updates the global finished downloads list by removing the entry.
	 *   - On success, shows a toast message confirming deletion.
	 *
	 * This ensures a smooth and safe deletion process while keeping the UI responsive.
	 */
	fun deleteFile() {
		logger.d("Delete file option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->

				// Show confirmation dialog before deleting
				getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { it.setText(R.string.title_are_you_sure_about_this) },
					messageTextViewCustomize = { it.setText(R.string.text_are_you_sure_about_delete) },
					positiveButtonTextCustomize = {
						it.setText(R.string.title_delete_file)
						it.setLeftSideDrawable(R.drawable.ic_button_delete)
					}
				)?.apply {
					setOnClickForPositiveButton {
						logger.d("User confirmed file deletion")
						close()
						this@FinishedDownloadOptions.close()

						// Run deletion in background thread to avoid blocking UI
						executeInBackground {
							logger.d("Deleting file in background")
							downloadDataModel?.deleteModelFromDisk()
							downloadDataModel?.getDestinationFile()?.delete()
							downloadSystem.finishedDownloadDataModels.remove(downloadDataModel!!)

							// Show success toast on main thread after deletion
							executeOnMainThread {
								showToast(
									activityInf = safeMotherActivityRef,
									msgId = R.string.title_successfully_deleted
								)
							}
						}
					}
					show()
				}
			}
		}
	}

	/**
	 * Shows a dialog that allows the user to rename the downloaded file.
	 *
	 * Behavior:
	 * - Initializes [DownloadFileRenamer] if not already created.
	 * - Displays a dialog with the current file name prefilled.
	 * - On successful rename:
	 *   - Closes the options dialog.
	 *   - Waits briefly, then refreshes the finished downloads list
	 *     to reflect the new name in the UI.
	 *
	 * This improves user experience by enabling file management directly
	 * from within the app without leaving the finished tasks screen.
	 */
	fun renameFile() {
		logger.d("Rename file option selected")
		safeFinishedTasksFragmentRef?.let { safeFinishedDownloadFragmentRef ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->

				// Initialize renamer only once (lazy initialization)
				if (!::downloadFileRenamer.isInitialized) {
					logger.d("Initializing DownloadFileRenamer for the first time")

					// Initialize file renamer if not already done
					downloadFileRenamer =
						DownloadFileRenamer(safeMotherActivityRef, downloadDataModel!!) {
							// Callback executed after a successful rename
							logger.d("File rename completed successfully")
							executeOnMainThread {
								dialogBuilder?.close()
								// Delay to ensure UI updates cleanly before refreshing the list
								delay(300, object : OnTaskFinishListener {
									override fun afterDelay() =
										safeFinishedDownloadFragmentRef.finishedTasksListAdapter
											.notifyDataSetChangedOnSort(true)
								})
							}
						}
				}

				// Always update the model before showing rename dialog
				logger.d("Showing rename dialog for download ID: ${downloadDataModel?.id}")
				downloadFileRenamer.downloadDataModel = downloadDataModel!!
				downloadFileRenamer.show(downloadDataModel!!)
			}
		}
	}

	/**
	 * Opens the associated webpage for the current download in the in-app browser.
	 *
	 * Behavior:
	 * - If a valid **site referrer link** exists, it will open directly in a new browser tab.
	 * - If the site referrer link is missing but the **download URL** is available,
	 *   the user is prompted with a dialog to open the download URL instead.
	 * - If neither link is available, a warning dialog is shown to the user.
	 *
	 * This ensures that users can quickly "discover more" about the source of the downloaded file.
	 */
	fun discoverMore() {
		logger.d("Discover more option selected")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val siteReferrerLink = downloadDataModel!!.siteReferrer

			// Case 1: No referrer link at all
			if (siteReferrerLink.isEmpty()) {
				logger.d("No site referrer link available")
				close()
				safeMotherActivityRef.doSomeVibration(20)
				val msgTxt = getText(R.string.text_missing_webpage_link_info)
				MsgDialogUtils.showMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					titleText = getText(R.string.title_missing_associate_webpage),
					isTitleVisible = true,
					messageTxt = msgTxt,
					isNegativeButtonVisible = false
				)
				return
			}

			val referrerLink = downloadDataModel?.siteReferrer
			val browserFragment = safeMotherActivityRef.browserFragment
			val webviewEngine = browserFragment?.browserFragmentBody?.webviewEngine!!

			// Case 2: Referrer link is null/empty -> fallback to download URL
			if (referrerLink.isNullOrEmpty()) {
				logger.d("Referrer link is null or empty, falling back to download URL")

				// Fallback to download URL if referrer is missing
				getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					isTitleVisible = true,
					titleText = getText(R.string.title_no_referral_site_added),
					messageTxt = getText(R.string.text_no_referrer_message_warning),
					positiveButtonText = getText(R.string.title_open_download_url),
					negativeButtonText = getText(R.string.title_cancel)
				)?.apply {
					setOnClickForPositiveButton {
						val fileUrl = downloadDataModel!!.fileURL
						logger.d("Opening download URL in browser: $fileUrl")
						this.close()
						this@FinishedDownloadOptions.close()
						safeMotherActivityRef.sideNavigation
							?.addNewBrowsingTab(fileUrl, webviewEngine)
						safeMotherActivityRef.openBrowserFragment()
					}
				}?.show()
				return
			}

			// Case 3: Valid referrer link available
			this.close()
			this@FinishedDownloadOptions.close()

			// Open the referrer link in browser
			logger.d("Opening referrer link in browser: $referrerLink")
			safeMotherActivityRef.sideNavigation
				?.addNewBrowsingTab(referrerLink, webviewEngine)
			safeMotherActivityRef.openBrowserFragment()
		}
	}

	/**
	 * Moves the file to private storage (placeholder implementation).
	 *
	 * Currently, this feature is **not implemented**. When triggered:
	 * - Provides vibration feedback to the user.
	 * - Displays an "upcoming features" message.
	 *
	 * Intended for future updates to support moving downloads into
	 * app-managed private storage for improved privacy.
	 */
	fun moveToPrivate() {
		logger.d("Move to private option selected (upcoming feature)")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				close()
				safeMotherActivityRef.doSomeVibration(50)
				safeMotherActivityRef.showUpcomingFeatures()
			}
		}
	}

	/**
	 * Toggles the visibility of the video thumbnail for the current download.
	 *
	 * This function:
	 * - Retrieves the associated global settings from [downloadDataModel].
	 * - Flips the `downloadHideVideoThumbnail` flag (hide ↔ show).
	 * - Persists the updated setting in storage.
	 * - Notifies the adapter in [safeFinishedTasksFragmentRef] to refresh the UI.
	 *
	 * If an error occurs during the toggle process, a toast message is shown
	 * to inform the user.
	 */
	fun toggleThumbnail() {
		logger.d("Remove thumbnail option selected")

		// Ensure finished tasks fragment reference exists
		safeFinishedTasksFragmentRef?.let { finishedFragment ->
			// Ensure activity reference exists
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				close()
				try {
					logger.d("Toggling thumbnail visibility setting")

					// Flip the thumbnail visibility flag in global settings
					val globalSettings = downloadDataModel?.globalSettings
					globalSettings?.downloadHideVideoThumbnail =
						!globalSettings.downloadHideVideoThumbnail

					// Persist updated model
					downloadDataModel?.updateInStorage()

					// Notify adapter to refresh list with updated state
					val finishedTasksListAdapter = finishedFragment.finishedTasksListAdapter
					finishedTasksListAdapter.notifyDataSetChangedOnSort(true)

					logger.d("Thumbnail visibility toggled successfully")
					safeMotherActivityRef.homeFragment?.refreshRecentDownloadListUI()
				} catch (error: Exception) {
					logger.e("Error found at hide/show thumbnail -", error)
					showToast(
						activityInf = safeMotherActivityRef,
						msgId = R.string.title_something_went_wrong
					)
				}
			}
		}
	}

	/**
	 * Attempts to fix MP4 files that are unseekable due to the `moov` atom
	 * being placed at the end of the file instead of the start.
	 *
	 * This function:
	 * - Displays a confirmation dialog warning the user that this is an advanced and risky operation.
	 * - If the user confirms, it processes the destination file by moving the `moov` atom to the start.
	 * - Provides user feedback with a waiting dialog and success/failure toasts.
	 *
	 * ⚠️ Warning: This process can sometimes corrupt the video file if interrupted.
	 */
	fun fixUnseekableMp4s() {
		logger.d("Fix unseekable MP4 option selected")

		// Ensure we have a reference to the parent activity before proceeding
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			// Show a confirmation dialog to the user
			getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isNegativeButtonVisible = false, // Only one confirmation option
				isTitleVisible = true,
				titleTextViewCustomize = {
					val resources = safeMotherActivityRef.resources
					val errorColor = resources.getColor(R.color.color_error, null)
					it.setTextColor(errorColor)
					it.text = getText(R.string.title_are_you_sure_about_this)
				},
				messageTextViewCustomize = {
					it.setText(R.string.text_msg_of_fixing_unseekable_mp4_files)
				},
				positiveButtonTextCustomize = {
					it.setLeftSideDrawable(R.drawable.ic_button_fix_hand)
					it.setText(R.string.title_proceed_anyway)
				}
			)?.apply {
				// If the user clicks "Proceed anyway"
				setOnClickForPositiveButton {
					this.close()

					val destinationFile = downloadDataModel?.getDestinationFile()
					// Validate file existence before continuing
					if (destinationFile == null || destinationFile.exists() == false) {
						safeMotherActivityRef.doSomeVibration(50)
						showToast(
							activityInf = safeMotherActivityRef,
							msgId = R.string.title_something_went_wrong
						)
						return@setOnClickForPositiveButton
					}

					// Show a loading/waiting dialog while processing
					val waitingDialog = WaitingDialog(
						baseActivityInf = safeMotherActivityRef,
						loadingMessage = getText(R.string.title_fixing_mp4_file_please_wait),
						isCancelable = false,
						shouldHideOkayButton = true
					)

					// Run the actual fixing process on a background thread
					ThreadsUtility.executeInBackground(codeBlock = {
						try {
							// Show waiting dialog on UI thread
							ThreadsUtility.executeOnMain { waitingDialog.show() }

							// Attempt to move moov atom to start in-place
							moveMoovAtomToStart(destinationFile, destinationFile)

							// Notify success back on the UI thread
							ThreadsUtility.executeOnMain {
								showToast(
									activityInf = safeMotherActivityRef,
									msgId = R.string.title_fixing_mp4_done_successfully
								)
								waitingDialog.close()
							}
						} catch (error: Exception) {
							// Log and clean up on failure
							logger.e("Error in fixing unseekable mp4 file:", error)
							ThreadsUtility.executeOnMain { waitingDialog.close() }
						}
					})
				}
			}?.show()
		}
	}

	/**
	 * Displays detailed information about the current download.
	 *
	 * This function:
	 * - Closes any open UI context to focus on the information view.
	 * - Initializes a [DownloadInfoTracker] if it hasn’t been created already.
	 * - Uses the tracker to display file details such as ID, progress, and stats.
	 */
	fun downloadInfo() {
		logger.d("Download info option selected")

		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				// Close the current context/dialog before showing info
				close()

				// Lazily initialize the tracker if it's not yet available
				if (!::downloadInfoTracker.isInitialized) {
					logger.d("Initializing DownloadInfoTracker for the first time")
					downloadInfoTracker = DownloadInfoTracker(safeMotherActivityRef)
				}

				// Show detailed info about the current download
				logger.d("Showing download info for ID: ${downloadDataModel?.id}")
				downloadInfoTracker.show(downloadDataModel!!)
			}
		}
	}

	/**
	 * Converts the current MP4 video into an audio file.
	 *
	 * This function:
	 * - Opens a dialog that allows the user to configure and start
	 *   the MP4-to-audio conversion process.
	 * - Uses the current activity reference and the associated
	 *   [downloadDataModel] for context and file details.
	 *
	 * ⚠️ Note: The actual conversion logic is handled inside
	 * [showMp4ToAudioConverterDialog].
	 */
	fun convertMp4ToAudio() {
		// Show dialog for MP4 → Audio conversion
		showMp4ToAudioConverterDialog(safeMotherActivityRef, downloadDataModel)
	}

}