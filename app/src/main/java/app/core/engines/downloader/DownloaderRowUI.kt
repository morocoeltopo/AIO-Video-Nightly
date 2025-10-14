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

class DownloaderRowUI(private val downloadRowView: View) {

	private val logger = LogHelperUtils.from(javaClass)
	private val rowViewRef = WeakReference(downloadRowView)

	private var isShowingAnyDialog = false
	private var cachedThumbLoaded = false
	private var isThumbnailSettingsChanged = false

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

	fun updateView(downloadModel: DownloadDataModel) {
		logger.d("UI update: id=${downloadModel.id}, status=${downloadModel.status}")
		rowViewRef.get()?.let {
			updateRowVisibility(downloadModel)
			updateFileNameLabel(downloadModel)
			refreshProgressState(downloadModel)
			refreshThumbnail(downloadModel)
			refreshFavicon(downloadModel)
			refreshMediaPlayIcon(downloadModel)
			refreshMediaDuration(downloadModel)
			refreshFileTypeIcon(downloadModel)
			refreshPrivateFolderIcon(downloadModel)
			showDownloadErrorDialog(downloadModel)
		} ?: logger.d("Row ref lost, skipping update")
	}

	private fun updateRowVisibility(downloadModel: DownloadDataModel) {
		rowViewRef.get()?.let {
			logger.d("Visibility: id=${downloadModel.id}, " +
					"removed=${downloadModel.isRemoved}, " +
					"complete=${downloadModel.isComplete}")
			if (downloadModel.isRemoved ||
				downloadModel.isComplete ||
				downloadModel.isWentToPrivateFolder ||
				downloadModel.globalSettings.hideDownloadProgressFromUI) {
				if (it.visibility != GONE) {
					logger.d("Hiding row: id=${downloadModel.id}")
					it.visibility = GONE
				}
			}
		}
	}

	private fun updateFileNameLabel(downloadModel: DownloadDataModel) {
		fileNameView.text = downloadModel.fileName.ifEmpty {
			logger.d("No filename for id=${downloadModel.id}, showing placeholder")
			getText(R.string.title_getting_name_from_server)
		}
	}

	private fun refreshProgressState(downloadModel: DownloadDataModel) {
		logger.d("Progress: id=${downloadModel.id}, " +
				"${downloadModel.progressPercentage}% ${downloadModel.status}")
		renderProgressDetails(downloadModel)
	}

	private fun shrinkTextToFitView(textView: TextView, text: String, endMatch: String) {
		var newText = text
		val paint = textView.paint
		val availableWidth = textView.width - textView.paddingStart - textView.paddingEnd
		logger.d("Fit text: \"$text\" endMatch=\"$endMatch\"")

		if (availableWidth <= 0) {
			logger.d("Width not ready, retrying")
			textView.post { shrinkTextToFitView(textView, text, endMatch) }
			return
		}

		if (newText.endsWith(endMatch, ignoreCase = true)) {
			logger.d("Trimming text end \"$endMatch\" if needed")
			while (paint.measureText(newText) > availableWidth && newText.length > 4) {
				if (newText.endsWith(endMatch, ignoreCase = true)) {
					newText = newText.dropLast(endMatch.length)
				}
			}
			logger.d("Trimmed text: \"$newText\"")
		} else logger.d("No trim needed")

		textView.text = newText
		logger.d("Text set")
	}

	private fun renderProgressDetails(downloadModel: DownloadDataModel) {
		val context = statusTextView.context
		if (downloadModel.status != DOWNLOADING && downloadModel.ytdlpProblemMsg.isNotEmpty()) {
			logger.d("yt-dlp error for id=${downloadModel.id}: ${downloadModel.ytdlpProblemMsg}")
			statusTextView.text = downloadModel.ytdlpProblemMsg
			statusTextView.setTextColor(context.getColor(R.color.color_error))
		} else {
			val infoInString = downloadModel.generateDownloadInfoInString()
			shrinkTextToFitView(statusTextView, infoInString, "|  --:-- ")
			statusTextView.setTextColor(context.getColor(R.color.color_text_hint))
		}
	}

	private fun refreshThumbnail(downloadModel: DownloadDataModel) {
		val settings = downloadModel.globalSettings
		val shouldHideThumbnail = settings.downloadHideVideoThumbnail
		logger.d("updateFileThumbnail: id=${downloadModel.id}, hideThumb=$shouldHideThumbnail")

		if (shouldHideThumbnail != isThumbnailSettingsChanged) {
			isThumbnailSettingsChanged = true
		}
		if (thumbnailView.tag == null || isThumbnailSettingsChanged) {
			if (shouldHideThumbnail) {
				logger.d("Setting actual thumbnail URI for id=${downloadModel.id}")
				thumbnailView.setImageURI(downloadModel.getThumbnailURI())
				thumbnailView.tag = true
				isThumbnailSettingsChanged = shouldHideThumbnail
			} else {
				logger.d("Using default thumbnail logic for id=${downloadModel.id}")
				loadDefaultThumbnail(downloadModel)
			}
		}
	}

	private fun refreshFileTypeIcon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating file type indicator for download ID: ${downloadDataModel.id}")
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

	private fun refreshMediaDuration(downloadDataModel: DownloadDataModel) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val fileName = downloadDataModel.fileName
			if (isVideoByName(fileName) || isAudioByName(fileName)) {
				val playbackDuration = downloadDataModel.mediaFilePlaybackDuration
				val cleanedData = playbackDuration.replace("(", "").replace(")", "")
				if (cleanedData.isNotEmpty() && !cleanedData.contentEquals("--:--", true)) {
					executeOnMain {
						showView(mediaDurationContainer, true)
						mediaDurationView.text = cleanedData
					}
				} else executeOnMain { hideView(mediaDurationContainer, shouldAnimate = false) }
			} else executeOnMain { mediaDurationContainer.visibility = GONE }
		})
	}

	private fun refreshMediaPlayIcon(downloadDataModel: DownloadDataModel) {
		val fileName = downloadDataModel.fileName
		if (isVideoByName(fileName) || isAudioByName(fileName))
			mediaPlayIconView.visibility = View.VISIBLE
		else mediaPlayIconView.visibility = GONE
	}

	private fun refreshFavicon(downloadDataModel: DownloadDataModel) {
		logger.d("Updating favicon for download ID: ${downloadDataModel.id}")
		val defaultFaviconResId = R.drawable.ic_image_default_favicon
		val defaultFaviconDrawable = ResourcesCompat.getDrawable(
			rowViewRef.get()?.context?.resources ?: INSTANCE.resources, defaultFaviconResId, null
		)
		if (shouldHideVideoThumbnail(downloadDataModel)) {
			logger.d("Video thumbnails not allowed, using default favicon")
			executeOnMainThread { siteFaviconView.setImageDrawable(defaultFaviconDrawable) }
			return
		}
		ThreadsUtility.executeInBackground({
			val referralSite = downloadDataModel.siteReferrer
			logger.d("Loading favicon for site: $referralSite")
			aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
				val faviconImgFile = File(faviconFilePath)
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
						logger.d("Error setting favicon: ${error.message}")
						error.printStackTrace()
						showView(siteFaviconView, true)
						siteFaviconView.setImageResource(defaultFaviconResId)
					}
				}
			}
		}, errorHandler = {
			logger.e("Error loading favicon: ${it.message}", it)
			siteFaviconView.setImageDrawable(defaultFaviconDrawable)
		})
	}

	private fun shouldHideVideoThumbnail(downloadDataModel: DownloadDataModel): Boolean {
		val model = downloadDataModel
		val globalSettings = model.globalSettings
		val isVideoHidden = globalSettings.downloadHideVideoThumbnail
		val downloadFile = model.getDestinationDocumentFile()
		val result = isVideo(downloadFile) && isVideoHidden
		logger.d("Video thumbnail allowed: ${!result}")
		return result
	}

	private fun loadDefaultThumbnail(downloadModel: DownloadDataModel) {
		if (downloadModel.isUnknownFileSize) {
			displayDefaultThumbnailIcon(downloadModel)
			thumbnailView.tag = true
			return
		}

		val videoInfo = downloadModel.videoInfo
		val videoFormat = downloadModel.videoFormat
		val thumbPath = downloadModel.thumbPath

		if (downloadModel.progressPercentage > 5 ||
			videoInfo?.videoThumbnailUrl != null ||
			downloadModel.thumbnailUrl.isNotEmpty()) {
			executeInBackground {
				if (cachedThumbLoaded) return@executeInBackground
				val defaultThumb = downloadModel.getThumbnailDrawableID()
				val cachedThumbPath = thumbPath
				if (cachedThumbPath.isNotEmpty() && File(cachedThumbPath).exists()) {
					executeOnMainThread {
						loadBitmapToImageView(thumbPath, defaultThumb)
						cachedThumbLoaded = true
					}
					return@executeInBackground
				} else {
					val videoDestinationFile = if (videoInfo != null && videoFormat != null) {
						val ytdlpDestinationFilePath = downloadModel.tempYtdlpDestinationFilePath
						val ytdlpId = File(ytdlpDestinationFilePath).name
						var destinationFile = File(ytdlpDestinationFilePath)
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
						downloadModel.getDestinationFile()
					}

					val thumbnailUrl = videoInfo?.videoThumbnailUrl ?: downloadModel.thumbnailUrl
					val bitmap = getThumbnailFromFile(videoDestinationFile, thumbnailUrl, 420)
					if (bitmap != null) {
						val rotatedBitmap = if (bitmap.height > bitmap.width) {
							rotateBitmap(bitmap, 270f)
						} else bitmap

						val thumbnailName = "${downloadModel.id}$THUMB_EXTENSION"
						saveBitmapToFile(bitmapToSave = rotatedBitmap,
							fileName = thumbnailName)?.let { filePath ->
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
			displayDefaultThumbnailIcon(downloadModel)
		}
	}

	private fun loadBitmapToImageView(thumbFilePath: String, defaultThumb: Int) {
		try {
			thumbnailView.setImageURI(File(thumbFilePath).toUri())
		} catch (error: Exception) {
			logger.e("Error loading thumbnail: ${error.message}", error)
			thumbnailView.setImageResource(defaultThumb)
		}
	}

	private fun displayDefaultThumbnailIcon(downloadModel: DownloadDataModel) {
		logger.d("Showing default thumb for id=${downloadModel.id}")
		val context = rowViewRef.get()?.context ?: INSTANCE
		val drawableID = downloadModel.getThumbnailDrawableID()
		val drawable = getDrawable(context, drawableID)
		thumbnailView.setImageDrawable(drawable)
	}

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