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
import com.aio.R
import lib.device.ShareUtility.openApkFile
import lib.device.ShareUtility.openFile
import lib.device.ShareUtility.shareMediaFile
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isAudioByName
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

	private val logger = LogHelperUtils.from(javaClass)

	// Safe references to avoid memory leaks
	private val safeFinishedTasksFragmentRef = finishedTasksFragment?.safeFinishTasksFragment
	private val safeMotherActivityRef = safeFinishedTasksFragmentRef?.safeMotherActivityRef

	// Lazy initialization of dialog builder
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
	 * @param dataModel The download data model to show options for
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
	 * @param model The download data model
	 */
	fun setDownloadModel(model: DownloadDataModel) {
		logger.d("Setting download model for ID: ${model.id}")
		this.downloadDataModel = model
	}

	/**
	 * Closes the options dialog if it's showing.
	 */
	fun close() {
		logger.d("Closing options dialog")
		dialogBuilder?.let { dialogBuilder ->
			if (dialogBuilder.isShowing) dialogBuilder.close()
		}
	}

	/**
	 * Handles click events for all option buttons.
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
				R.id.btn_remove_thumbnail -> removeThumbnail()
				R.id.btn_fix_unseekable_mp4_file -> fixUnseekableMp4s()
				R.id.btn_download_system_information -> downloadInfo()
			}
		}
	}

	/**
	 * Updates the dialog's title and thumbnail views with download information.
	 * @param downloadModel The download data model containing the information to display
	 */
	private fun updateDialogViewsWith(downloadModel: DownloadDataModel) {
		logger.d("Updating title and thumbnails for download ID: ${downloadModel.id}")
		dialogBuilder?.let { dialogBuilder ->
			dialogBuilder.view.apply {
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

				txtFileNameTitle.isSelected = true
				txtFileNameTitle.text = downloadModel.fileName
				txtFileUrlSubTitle.text = downloadModel.fileURL

				imgFileThumbnail.apply {
					if (!downloadModel.globalSettings.downloadHideVideoThumbnail) {
						logger.d("Video thumbnails are enabled, updating thumbnail")
						updateThumbnail(this, downloadModel)
					} else {
						logger.d("Video thumbnails are disabled, using default thumbnail")
						val defaultThumb = downloadModel.getThumbnailDrawableID()
						this.setImageResource(defaultThumb)
					}
				}

				updateFaviconInfo(downloadModel, imgFileFavicon)

				btnToggleThumbnail.apply {
					val thumbnailSetting = downloadModel.globalSettings.downloadHideVideoThumbnail
					text = (if (thumbnailSetting) getText(R.string.title_show_thumbnail)
					else getText(R.string.title_hide_thumbnail))
				}

				if (isAudioByName(downloadModel.fileName)) txtPlayTheFile.text =
					getText(R.string.title_play_the_audio)
				else if (isVideoByName(downloadModel.fileName)) txtPlayTheFile.text =
					getText(R.string.title_play_the_video)
				else txtPlayTheFile.text = getText(R.string.title_open_the_file)

				if (isMediaFile(downloadModel)) {
					logger.d("Media file detected, showing all related view containers")
					btnConvertMp4ToAudio.visibility =
						if (isVideoByName(downloadModel.fileName)) VISIBLE else GONE
					btnFixUnseekableMp4VideoFiles.visibility =
						if (isVideoByName(downloadModel.fileName)) VISIBLE else GONE

					imgMediaPlayIndicator.apply { showView(this, true) }
					containerMediaDuration.apply {
						val mediaFilePlaybackDuration = downloadModel.mediaFilePlaybackDuration
						val playbackTimeString =
							mediaFilePlaybackDuration.replace("(", "").replace(")", "")
						if (playbackTimeString.isNotEmpty()) {
							showView(this, true)
							showView(txtMediaPlaybackDuration, true)
							txtMediaPlaybackDuration.text = playbackTimeString
						}
					}
				} else {
					logger.d("Non-media file, hiding duration container")
					containerMediaDuration.visibility = GONE
					imgMediaPlayIndicator.visibility = GONE
					btnConvertMp4ToAudio.visibility = GONE
					btnFixUnseekableMp4VideoFiles.visibility = GONE
				}
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
		val defaultFaviconResId = R.drawable.ic_button_information
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
			logger.d("Error loading favicon: ${it.message}")
			it.printStackTrace()
		})
	}

	/**
	 * Updates the thumbnail image for the download.
	 * @param thumbImageView The ImageView to display the thumbnail in
	 * @param downloadModel The download data model containing thumbnail information
	 */
	private fun updateThumbnail(thumbImageView: ImageView, downloadModel: DownloadDataModel) {
		logger.d("Updating thumbnail for download ID: ${downloadModel.id}")
		val destinationFile = downloadModel.getDestinationFile()
		val defaultThumb = downloadModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

		// Try to load APK thumbnail first (returns if successful)
		if (loadApkThumbnail(downloadModel, thumbImageView, defaultThumbDrawable)) {
			logger.d("APK thumbnail loaded successfully")
			return
		}

		// Load thumbnail in background to avoid UI freezing
		executeInBackground {
			logger.d("Loading thumbnail in background thread")
			// Check if we have a cached thumbnail path
			val cachedThumbPath = downloadModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				logger.d("Using cached thumbnail at: $cachedThumbPath")
				executeOnMainThread {
					loadBitmapWithGlide(thumbImageView, downloadModel.thumbPath, defaultThumb)
				}; return@executeInBackground
			}

			// Generate thumbnail from file or URL
			logger.d("Generating new thumbnail from file/URL")
			val thumbnailUrl = downloadModel.videoInfo?.videoThumbnailUrl
			val bitmap = getThumbnailFromFile(destinationFile, thumbnailUrl, 420)
			if (bitmap != null) {
				// Rotate portrait images to landscape for better display
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) {
					logger.d("Rotating portrait thumbnail to landscape")
					rotateBitmap(bitmap, 270f)
				} else bitmap

				// Save thumbnail to file for caching
				val thumbnailName = "${downloadModel.id}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					logger.d("Saved thumbnail to cache: $filePath")
					downloadModel.thumbPath = filePath
					downloadModel.updateInStorage()
					executeOnMainThread {
						loadBitmapWithGlide(
							thumbImageView = thumbImageView,
							thumbFilePath = downloadModel.thumbPath,
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
	 * Loads a thumbnail image into an ImageView using Glide.
	 * @param thumbImageView The ImageView to load the thumbnail into
	 * @param thumbFilePath The path to the thumbnail file
	 * @param defaultThumb The default thumbnail resource ID to use if loading fails
	 */
	private fun loadBitmapWithGlide(
		thumbImageView: ImageView,
		thumbFilePath: String,
		defaultThumb: Int
	) {
		try {
			logger.d("Loading thumbnail with Glide from: $thumbFilePath")
			val imgURI = File(thumbFilePath).toUri()
			thumbImageView.setImageURI(imgURI)
		} catch (error: Exception) {
			logger.d("Error loading thumbnail with Glide: ${error.message}")
			error.printStackTrace()
			thumbImageView.setImageResource(defaultThumb)
		}
	}

	/**
	 * Loads an APK file's icon as its thumbnail.
	 * @param downloadModel The download data model containing APK information
	 * @param imageViewHolder The ImageView to display the APK icon
	 * @param defaultThumbDrawable The default thumbnail to use if APK icon can't be loaded
	 * @return true if APK icon was loaded successfully, false otherwise
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
					if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
						logger.d("File is not an APK or doesn't exist, using default thumbnail")
						imageViewHolder.setImageDrawable(defaultThumbDrawable)
						return false
					}

					val packageManager: PackageManager = safeMotherActivityRef.packageManager
					return try {
						logger.d("Loading APK package info")
						val packageInfo: PackageInfo? = packageManager.getPackageArchiveInfo(
							apkFile.absolutePath, PackageManager.GET_ACTIVITIES
						)
						packageInfo?.applicationInfo?.let { appInfo ->
							logger.d("APK package info found, loading icon")
							appInfo.sourceDir = apkFile.absolutePath
							appInfo.publicSourceDir = apkFile.absolutePath
							val icon: Drawable = appInfo.loadIcon(packageManager)
							imageViewHolder.setImageDrawable(icon)
							true
						} ?: run {
							logger.d("No package info found for APK")
							false
						}
					} catch (error: Exception) {
						logger.d("Error loading APK thumbnail: ${error.message}")
						error.printStackTrace()
						imageViewHolder.apply {
							scaleType = ImageView.ScaleType.FIT_CENTER
							setPadding(0, 0, 0, 0)
							setImageDrawable(defaultThumbDrawable)
						}
						false
					}
				}
			}
		}; return false
	}

	/**
	 * Plays the media file associated with the download.
	 * Uses MediaPlayerActivity for audio/video, falls back to openFile() for other types.
	 */
	@OptIn(UnstableApi::class)
	fun playTheMedia() {
		logger.d("Play media option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				dialogBuilder?.let { _ ->
					downloadDataModel?.let {
						if (isMediaFile(it)) {
							logger.d("Starting MediaPlayerActivity for audio/video file")
							// Start media player activity for audio/video files
							safeMotherActivityRef.startActivity(
								Intent(
									safeMotherActivityRef,
									MediaPlayerActivity::class.java
								).apply {
									flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
									downloadDataModel?.let {
										putExtra(DOWNLOAD_MODEL_ID_KEY, downloadDataModel!!.id)
										putExtra(PLAY_MEDIA_FILE_PATH, true)
										putExtra(
											WHERE_DID_YOU_COME_FROM,
											FROM_FINISHED_DOWNLOADS_LIST
										)
									}
								})
							animActivityFade(safeMotherActivityRef)
							close()
						} else {
							logger.d("Non-media file, opening with default app")
							// For non-media files, just open them
							openFile()
						}
					}
				}
			}
		}
	}

	private fun isMediaFile(downloadModel: DownloadDataModel): Boolean =
		isAudioByName(downloadModel.fileName) || isVideoByName(downloadModel.fileName)

	/**
	 * Opens the downloaded file using appropriate application.
	 * Handles APK files specially with proper installation flow.
	 */
	fun openFile() {
		logger.d("Open file option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeActivityRef ->
				dialogBuilder?.let { _ ->
					close()
					val extensions = listOf("apk").toTypedArray()
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

	private fun copySiteLink() {
		downloadDataModel?.siteReferrer?.takeIf { isValidURL(it) }?.let { fileUrl ->
			copyTextToClipboard(safeMotherActivityRef, fileUrl)
			showToast(getText(R.string.title_file_url_has_been_copied))
			close()
		} ?: run { showToast(getText(R.string.title_dont_have_anything_to_copy)) }
	}

	/**
	 * Shares the downloaded file with other applications.
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
	 * Clears the download from the list without deleting the file.
	 */
	fun clearFromList() {
		logger.d("Clear from list option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize =
						{ it.setText(R.string.title_are_you_sure_about_this) },
					messageTextViewCustomize =
						{ it.setText(R.string.text_are_you_sure_about_clear) },
					positiveButtonTextCustomize = {
						it.setText(R.string.title_clear_from_list)
						it.setLeftSideDrawable(R.drawable.ic_button_clear)
					}
				)?.apply {
					setOnClickForPositiveButton {
						logger.d("User confirmed clear from list")
						close()
						this@FinishedDownloadOptions.close()
						// Remove from storage and list
						downloadDataModel?.deleteModelFromDisk()
						downloadSystem.finishedDownloadDataModels.remove(downloadDataModel!!)
						showToast(getText(R.string.title_successfully_cleared))
					}; show()
				}
			}
		}
	}

	/**
	 * Deletes the downloaded file from storage and removes it from the list.
	 */
	fun deleteFile() {
		logger.d("Delete file option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize =
						{ it.setText(R.string.title_are_you_sure_about_this) },
					messageTextViewCustomize =
						{ it.setText(R.string.text_are_you_sure_about_delete) },
					positiveButtonTextCustomize = {
						it.setText(R.string.title_delete_file)
						it.setLeftSideDrawable(R.drawable.ic_button_delete)
					}
				)?.apply {
					setOnClickForPositiveButton {
						logger.d("User confirmed file deletion")
						close()
						this@FinishedDownloadOptions.close()
						// Delete in background to avoid UI freezing
						executeInBackground {
							logger.d("Deleting file in background")
							downloadDataModel?.deleteModelFromDisk()
							downloadDataModel?.getDestinationFile()?.delete()
							downloadSystem.finishedDownloadDataModels.remove(downloadDataModel!!)
							executeOnMainThread {
								showToast(getText(R.string.title_successfully_deleted))
							}
						}
					}; show()
				}
			}
		}
	}

	/**
	 * Shows a dialog to rename the downloaded file.
	 */
	fun renameFile() {
		logger.d("Rename file option selected")
		safeFinishedTasksFragmentRef?.let { safeFinishedDownloadFragmentRef ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				if (!::downloadFileRenamer.isInitialized) {
					logger.d("Initializing DownloadFileRenamer for the first time")
					// Initialize file renamer if not already done
					downloadFileRenamer =
						DownloadFileRenamer(safeMotherActivityRef, downloadDataModel!!) {
							// Callback after successful rename
							logger.d("File rename completed successfully")
							executeOnMainThread {
								dialogBuilder?.close()
								delay(300, object : OnTaskFinishListener {
									override fun afterDelay() = safeFinishedDownloadFragmentRef
										.finishedTasksListAdapter.notifyDataSetChangedOnSort(true)
								})
							}
						}
				}

				// Show rename dialog with current model
				logger.d("Showing rename dialog for download ID: ${downloadDataModel?.id}")
				downloadFileRenamer.downloadDataModel = downloadDataModel!!
				downloadFileRenamer.show(downloadDataModel!!)
			}
		}
	}

	/**
	 * Opens the associated webpage for the download in browser.
	 * Shows warning if no referrer link is available.
	 */
	fun discoverMore() {
		logger.d("Discover more option selected")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val siteReferrerLink = downloadDataModel!!.siteReferrer
			if (siteReferrerLink.isEmpty()) {
				logger.d("No site referrer link available")
				close()
				safeMotherActivityRef.doSomeVibration(20)
				val msgTxt = getText(R.string.text_missing_webpage_link_info)
				MsgDialogUtils.showMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					titleText = getText(R.string.text_missing_associate_webpage),
					isTitleVisible = true,
					messageTxt = msgTxt,
					isNegativeButtonVisible = false
				); return
			}

			val referrerLink = downloadDataModel?.siteReferrer
			val browserFragment = safeMotherActivityRef.browserFragment
			val webviewEngine = browserFragment?.browserFragmentBody?.webviewEngine!!

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
				}?.show(); return
			}

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
	 * Placeholder for moving file to private storage (upcoming feature).
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
	 * Toggles video thumbnail visibility for the download.
	 */
	fun removeThumbnail() {
		logger.d("Remove thumbnail option selected")
		safeFinishedTasksFragmentRef?.let { finishedFragment ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				close()
				try {
					logger.d("Toggling thumbnail visibility setting")
					val globalSettings = downloadDataModel?.globalSettings
					globalSettings?.downloadHideVideoThumbnail =
						!globalSettings.downloadHideVideoThumbnail
					downloadDataModel?.updateInStorage()
					val finishedTasksListAdapter = finishedFragment.finishedTasksListAdapter
					finishedTasksListAdapter.notifyDataSetChangedOnSort(true)
					logger.d("Thumbnail visibility toggled successfully")
				} catch (error: Exception) {
					logger.e("Error found at hide/show thumbnail -", error)
					showToast(msgId = R.string.title_something_went_wrong)
				}
			}
		}
	}

	fun fixUnseekableMp4s() {
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isNegativeButtonVisible = false,
				isTitleVisible = true,
				titleTextViewCustomize = {
					val resources = safeMotherActivityRef.resources
					val errorColor = resources.getColor(R.color.color_error, null)
					it.setTextColor(errorColor)
					it.text = getText(R.string.title_are_you_sure_about_this)
				}, messageTextViewCustomize = {
					it.setText(R.string.text_msg_of_fixing_unseekable_mp4_files)
				}, positiveButtonTextCustomize = {
					it.setLeftSideDrawable(R.drawable.ic_button_fix_hand)
					it.setText(R.string.title_proceed_anyway)

				}
			)?.apply {
				setOnClickForPositiveButton {
					this.close()
					val destinationFile = downloadDataModel?.getDestinationFile()
					if (destinationFile == null || destinationFile.exists() == false) {
						safeMotherActivityRef.doSomeVibration(50)
						showToast(msgId = R.string.title_something_went_wrong)
						return@setOnClickForPositiveButton
					}
					val waitingDialog = WaitingDialog(
						baseActivityInf = safeMotherActivityRef,
						loadingMessage = getText(R.string.title_fixing_mp4_file_please_wait),
						isCancelable = false,
						shouldHideOkayButton = true
					)

					ThreadsUtility.executeInBackground(codeBlock = {
						try {
							ThreadsUtility.executeOnMain { waitingDialog.show() }
							downloadDataModel?.getDestinationFile()
							moveMoovAtomToStart(destinationFile, destinationFile)
							ThreadsUtility.executeOnMain {
								showToast(msgId = R.string.title_fixing_mp4_done_successfully)
								waitingDialog.close()
							}
						} catch (error: Exception) {
							logger.e("Error in fixing unseekable mp4 file:", error)
							ThreadsUtility.executeOnMain { waitingDialog.close() }
						}
					})
				}
			}?.show()
		}
	}

	/**
	 * Shows detailed information about the download.
	 */
	fun downloadInfo() {
		logger.d("Download info option selected")
		safeFinishedTasksFragmentRef?.let { _ ->
			safeMotherActivityRef?.let { safeMotherActivityRef ->
				close()
				if (!::downloadInfoTracker.isInitialized) {
					logger.d("Initializing DownloadInfoTracker for the first time")
					downloadInfoTracker = DownloadInfoTracker(safeMotherActivityRef)
				}
				logger.d("Showing download info for ID: ${downloadDataModel?.id}")
				downloadInfoTracker.show(downloadDataModel!!)
			}
		}
	}
}