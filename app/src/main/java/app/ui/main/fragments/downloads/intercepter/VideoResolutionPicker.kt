package app.ui.main.fragments.downloads.intercepter

import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import app.core.AIOApp.Companion.IS_PREMIUM_USER
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoInfo
import app.core.engines.video_parser.parsers.VideoFormatsUtils.parseSize
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.others.information.IntentInterceptActivity
import com.aio.R
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.device.StorageUtility.getFreeExternalStorageSpace
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.animateFadInOutAnim
import lib.ui.ViewUtility.closeAnyAnimation
import lib.ui.ViewUtility.loadThumbnailFromUrl
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

/**
 * A dialog that allows users to select video resolution/format for downloading.
 *
 * This class handles:
 * - Displaying available video formats
 * - Showing video metadata (title, thumbnail, duration)
 * - Managing download restrictions and ads
 * - Initiating the download process
 *
 * @param baseActivity The parent activity that hosts this dialog
 * @param videoInfo Contains video metadata and available formats
 * @param errorCallBack Callback invoked if initialization fails
 * @param onDialogClose Callback invoked if dialog closes
 */
class VideoResolutionPicker(
	private val baseActivity: BaseActivity?,
	private val videoInfo: VideoInfo,
	private val errorCallBack: () -> Unit = {},
	private val onDialogClose: () -> Unit = {}
) {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to avoid memory leaks
	private val safeBaseActivityRef = WeakReference(baseActivity).get()
	private val dialogBuilder = DialogBuilder(safeBaseActivityRef)
	private val downloadModel = DownloadDataModel()
	private var titleExtractedFromUrl: String? = null

	// UI Components
	private lateinit var videoThumbnail: ImageView
	private lateinit var videoDuration: TextView
	private lateinit var videoTitleView: TextView
	private lateinit var formatsGridView: GridView
	private lateinit var videoFormatAdapter: VideoFormatAdapter

	init {
		try {
			// Initialize dialog layout and components
			val dialogLayoutResId = R.layout.dialog_video_res_picker_1
			dialogBuilder.setView(dialogLayoutResId)
			dialogBuilder.setCancelable(true)
			dialogBuilder.dialog.setOnCancelListener { onDialogClose.invoke() }
			dialogBuilder.dialog.setOnDismissListener { onDialogClose.invoke() }

			dialogBuilder.view.apply {
				initializeDialogLayoutViews(this)
				setupButtonsOnClickListeners(this)
			}
		} catch (error: Exception) {
			error.printStackTrace()
			close()
			errorCallBack()
		}
	}

	/**
	 * Shows the resolution picker dialog if not already showing
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			dialogBuilder.show()
		}
	}

	/**
	 * Closes the dialog and optionally finishes the parent activity
	 */
	fun close() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			if (dialogBuilder.isShowing) {
				dialogBuilder.close()
				executeOnMainThread {
					try {
						// Special handling for IntentInterceptActivity
						val condition = safeBaseActivityRef is IntentInterceptActivity
						if (condition) safeBaseActivityRef.finish()
					} catch (error: Exception) {
						error.printStackTrace()
					}
				}
			}
		}
	}

	/**
	 * Initializes all views in the dialog layout
	 * @param layout The root view of the dialog
	 */
	private fun initializeDialogLayoutViews(layout: View) {
		showVideoTitleFromURL(layout)
		showVideoThumb(layout)
		showVideoURL(layout)
		showSiteFavicon(layout)
		setupFormatsGridAdapter(layout)
	}

	/**
	 * Displays the video title, fetching from metadata or webpage if needed
	 * @param layout The root view containing title TextView
	 */
	private fun showVideoTitleFromURL(layout: View) {
		videoTitleView = layout.findViewById(R.id.txt_video_title)
		if (!videoInfo.videoTitle.isNullOrEmpty()) {
			// Use title from video info if available
			videoTitleView.isSelected = true
			videoTitleView.text = videoInfo.videoTitle
			titleExtractedFromUrl = videoInfo.videoTitle
			return
		}

		// Fetch title from webpage in background
		executeInBackground(codeBlock = {
			executeOnMain { animateFadInOutAnim(videoTitleView) }
			val websiteUrl = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
			getWebpageTitleOrDescription(websiteUrl) { resultedTitle ->
				executeOnMainThread {
					videoTitleView.isSelected = true
					closeAnyAnimation(videoTitleView)

					if (resultedTitle.isNullOrEmpty()) {
						// Fallback to generated title if webpage title not found
						updateTitleByFormatId(videoTitleView)
					} else {
						videoTitleView.text = resultedTitle
						videoInfo.videoTitle = resultedTitle
						titleExtractedFromUrl = resultedTitle
					}
				}
			}
		})
	}

	/**
	 * Loads and displays the video thumbnail
	 * @param layout The root view containing thumbnail ImageView
	 */
	private fun showVideoThumb(layout: View) {
		if (!::videoThumbnail.isInitialized) {
			videoThumbnail = layout.findViewById(R.id.image_video_thumbnail)
		}

		// Load thumbnail in background
		executeInBackground(codeBlock = {
			val websiteUrl = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
			val videoThumbnailByReferer = videoInfo.videoThumbnailByReferer
			// Determine whether to use referer URL or direct video URL for thumb parsing
			val thumbParsingUrl = if (videoThumbnailByReferer) websiteUrl else videoInfo.videoUrl
			val thumbImageUrl = videoInfo.videoThumbnailUrl
				?: startParsingVideoThumbUrl(thumbParsingUrl)
				?: return@executeInBackground

			// Update UI with thumbnail on main thread
			executeOnMain {
				videoInfo.videoThumbnailUrl = thumbImageUrl
				loadThumbnailFromUrl(thumbImageUrl, videoThumbnail)
			}
		})
	}

	/**
	 * Displays the video URL
	 * @param layout The root view containing URL TextView
	 */
	private fun showVideoURL(layout: View) {
		layout.findViewById<TextView>(R.id.txt_video_url).apply {
			isSelected = true
			text = videoInfo.videoUrl
		}
	}

	/**
	 * Loads and displays the site favicon inside the given layout.
	 *
	 * @param layout The parent [View] containing an ImageView with ID [R.id.img_site_favicon].
	 */
	private fun showSiteFavicon(layout: View) {
		layout.findViewById<ImageView>(R.id.img_site_favicon).let { favicon ->
			val defaultFaviconResId = R.drawable.ic_button_information
			logger.i("Attempting to update site favicon for URL: ${videoInfo.videoUrl}")

			executeInBackground(codeBlock = {
				val referralSite = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
				logger.i("Fetching favicon for site: $referralSite")

				aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
					val faviconImgFile = File(faviconFilePath)

					if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
						logger.e("Favicon file not found or invalid at: $faviconFilePath")
						return@executeInBackground
					}

					val faviconImgURI = faviconImgFile.toUri()
					executeOnMain(codeBlock = {
						try {
							logger.i("Applying favicon from local file")
							showView(favicon, true)
							favicon.setImageURI(faviconImgURI)
						} catch (error: Exception) {
							logger.e("Failed to set favicon: ${error.message}", error)
							showView(favicon, true)
							favicon.setImageResource(defaultFaviconResId)
						}
					})
				}
			}, errorHandler = {
				logger.e("Unexpected error while loading favicon: ${it.message}", it)
			})
		}
	}

	/**
	 * Sets up the GridView adapter for video formats
	 * @param layout The root view containing formats GridView
	 */
	private fun setupFormatsGridAdapter(layout: View) {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			formatsGridView = layout.findViewById(R.id.video_format_container)
			videoFormatAdapter =
				VideoFormatAdapter(safeBaseActivityRef, videoInfo, videoInfo.videoFormats) {
					val videoTitleView = layout.findViewById<TextView>(R.id.txt_video_title)
					updateTitleByFormatId(videoTitleView)
				}; formatsGridView.adapter = videoFormatAdapter
		}
	}

	/**
	 * Updates the title based on selected video format
	 * @param textView The TextView to update
	 */
	private fun updateTitleByFormatId(textView: TextView) {
		if (!titleExtractedFromUrl.isNullOrEmpty()) return
		val madeUpTitle = madeUpTitleFromSelectedVideoFormat()
		textView.text = madeUpTitle
		videoInfo.videoTitle = madeUpTitle
	}

	/**
	 * Generates a title from video format metadata
	 * @return Generated title string
	 */
	private fun madeUpTitleFromSelectedVideoFormat(): String {
		if (videoFormatAdapter.selectedPosition < 0) {
			// Default text when no format selected
			val textResID = R.string.title_pick_video_resolution_for_file_name
			return getText(textResID)
		}

		val selectedFormat = videoFormatAdapter.selectedPosition
		val videoFormat = videoInfo.videoFormats[selectedFormat]
		// Construct title from format metadata
		val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"${getBaseDomain(videoInfo.videoUrl)}"
		return madeUpTitle
	}

	/**
	 * Sets up click listeners for dialog buttons
	 * @param dialogLayout The root view of the dialog
	 */
	private fun setupButtonsOnClickListeners(dialogLayout: View) = with(dialogLayout) {
		// Check download restrictions
		val numberOfDownloadsUserDid = aioSettings.numberOfDownloadsUserDid
		val maxDownloadThreshold = aioSettings.numberOfMaxDownloadThreshold
		if (numberOfDownloadsUserDid >= maxDownloadThreshold) {
			if (!IS_PREMIUM_USER && !IS_ULTIMATE_VERSION_UNLOCKED) {
				// Show "watch ad to download" for non-premium users over threshold
				val btnDownload = dialogLayout.findViewById<TextView>(R.id.btn_dialog_positive)
				btnDownload.let {
					it.setLeftSideDrawable(R.drawable.ic_button_video)
					it.setText(R.string.title_watch_ad_to_download)
				}
			}
		}

		// Set click listeners for buttons
		listOf(
			R.id.btn_file_info_card to { openVideoUrlInBrowser() },
			R.id.btn_dialog_positive_container to { downloadSelectedVideoFormat() })
			.forEach { (id, action) -> findViewById<View>(id).setOnClickListener { action() } }
	}

	/**
	 * Initiates download of the selected video format
	 */
	private fun downloadSelectedVideoFormat() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			val selectedFormat = videoFormatAdapter.selectedPosition
			if (selectedFormat < 0) {
				// No format selected feedback
				safeBaseActivityRef.doSomeVibration(20)
				showToast(
					activity = safeBaseActivityRef,
					msgId = R.string.title_select_a_video_resolution
				)
				return
			}

			if (videoInfo.videoTitle.isNullOrEmpty()) {
				// Wait for title to load feedback
				safeBaseActivityRef.doSomeVibration(20)
				showToast(
					activity = safeBaseActivityRef,
					msgId = R.string.title_wait_till_server_return_video_title
				)
				return
			}

			addVideoFormatToDownloadSystem(selectedFormat)
		}
	}

	/**
	 * Adds the selected video format to download system
	 * @param selectedFormat Index of the selected format
	 */
	private fun addVideoFormatToDownloadSystem(selectedFormat: Int) {
		val videoFormat: VideoFormat = videoInfo.videoFormats[selectedFormat]
		addToDownloadSystem(videoFormat)
		close()
	}

	/**
	 * Prepares and adds the download task to the download system
	 * @param videoFormat The selected video format to download
	 */
	private fun addToDownloadSystem(videoFormat: VideoFormat) {
		executeInBackground(codeBlock = {
			safeBaseActivityRef?.let { safeBaseActivityRef ->
				try {
					// Configure download model with video info
					downloadModel.videoInfo = videoInfo
					downloadModel.videoFormat = videoFormat

					// Set cookies and referrer if available
					val urlCookie = videoInfo.videoCookie
					if (!urlCookie.isNullOrEmpty()) {
						downloadModel.siteCookieString = urlCookie
					}

					if (videoInfo.videoUrlReferer != null) {
						downloadModel.siteReferrer = videoInfo.videoUrlReferer!!
					}

					// Ensure we have a title
					if (titleExtractedFromUrl.isNullOrEmpty()) {
						videoInfo.videoTitle = madeUpTitleFromSelectedVideoFormat()
					}

					// Parse and set file size
					val sizeInFormat = cleanFileSize(videoFormat.formatFileSize)
					downloadModel.fileSize = parseSize(sizeInFormat)
					if (downloadModel.fileSize > 0) {
						downloadModel.fileSizeInFormat =
							getHumanReadableFormat(downloadModel.fileSize)
					}

					// Check available storage space
					val freeSpace = getFreeExternalStorageSpace()
					(freeSpace > downloadModel.fileSize).let { hasEnoughSpace ->
						if (hasEnoughSpace) {
							// Add to download system
							downloadSystem.addDownload(downloadModel, onAdded = {
								val toastMsgResId = R.string.title_download_added_successfully
								showToast(activity = safeBaseActivityRef, msgId = toastMsgResId)
							})

							// Update download stats
							aioSettings.numberOfDownloadsUserDid++
							aioSettings.totalNumberOfSuccessfulDownloads++
							aioSettings.updateInStorage()
						} else {
							// Show not enough space warning
							executeOnMainThread {
								val icButtonCheckedCircle = R.drawable.ic_button_checked_circle
								val textWarningNotEnoughSpaceMsg =
									R.string.text_warning_not_enough_space_msg
								showMessageDialog(
									baseActivityInf = safeBaseActivityRef,
									isNegativeButtonVisible = false,
									messageTextViewCustomize = {
										it.setText(
											textWarningNotEnoughSpaceMsg
										)
									},
									positiveButtonTextCustomize = {
										it.setLeftSideDrawable(
											icButtonCheckedCircle
										)
									}
								)?.apply {
									setOnClickForPositiveButton {
										this@VideoResolutionPicker.close()
										this.close()
									}
								}
							}
						}
					}
				} catch (error: Exception) {
					error.printStackTrace()
					val failedToAddResId = R.string.title_failed_to_add_download_task
					executeOnMain {
						safeBaseActivityRef.doSomeVibration(20)
						showToast(activity = safeBaseActivityRef, msgId = failedToAddResId)
					}
				}
			}
		})
	}

	/**
	 * Opens the video URL in external browser
	 */
	private fun openVideoUrlInBrowser() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			openLinkInSystemBrowser(videoInfo.videoUrl, safeBaseActivityRef) {
				safeBaseActivityRef.doSomeVibration(40)
				showToast(
					activity = safeBaseActivityRef,
					msgId = R.string.title_failed_open_the_video
				)
			}
		}
	}

	/**
	 * Cleans file size string by removing non-digit prefixes
	 * @param input The raw file size string
	 * @return Cleaned numeric string
	 */
	private fun cleanFileSize(input: String): String {
		return input.replace(Regex("^\\D+"), "")
	}
}