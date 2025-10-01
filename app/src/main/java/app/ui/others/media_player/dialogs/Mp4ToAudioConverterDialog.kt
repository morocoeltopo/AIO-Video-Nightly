package app.ui.others.media_player.dialogs

import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSystemUtility
import lib.files.VideoToAudioConverter
import lib.files.VideoToAudioConverter.ConversionListener
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CopyObjectUtils.deepCopy
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File

/**
 * Handles conversion of MP4 video files to MP3 audio files.
 * Displays a dialog with progress updates, supports cancellation,
 * and adds the converted file to the download system.
 */
object Mp4ToAudioConverterDialog {

	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Shows the MP4 to Audio conversion dialog.
	 *
	 * @param baseActivityRef Reference to the calling BaseActivity
	 * @param downloadModel The download data model for the video file
	 */
	@OptIn(UnstableApi::class)
	fun showMp4ToAudioConverterDialog(
		baseActivityRef: BaseActivity?,
		downloadModel: DownloadDataModel?
	) {
		baseActivityRef?.let { safeActivityRef ->
			logger.d("Initializing MP4 to Audio conversion dialog")
			val videoToAudioConverter = VideoToAudioConverter()

			// Create waiting dialog to display conversion progress
			val waitingDialog = WaitingDialog(
				baseActivityInf = safeActivityRef,
				loadingMessage = getText(R.string.title_converting_audio_progress_0),
				isCancelable = false,
				shouldHideOkayButton = false
			)

			var messageTextView: TextView? = null

			// Setup dialog views
			waitingDialog.dialogBuilder?.view?.apply {
				messageTextView = findViewById(R.id.txt_progress_info)

				// Configure Cancel button
				findViewById<TextView>(R.id.btn_dialog_positive)?.apply {
					this.setText(R.string.title_cancel_converting)
					this.setLeftSideDrawable(R.drawable.ic_button_cancel)
				}

				// Cancel conversion if button clicked
				findViewById<View>(R.id.btn_dialog_positive_container)
					?.setOnClickListener {
						logger.d("User clicked cancel during conversion")
						videoToAudioConverter.cancel()
						waitingDialog.close()
					}
			}

			// Pause player if inside MediaPlayerActivity
			if (safeActivityRef is MediaPlayerActivity) {
				logger.d("Pausing MediaPlayer before conversion")
				safeActivityRef.pausePlayer()
			}

			waitingDialog.show()
			logger.d("Conversion dialog displayed")

			// Start background conversion process
			ThreadsUtility.executeInBackground(codeBlock = {
				try {
					downloadModel?.let { downloadDataModel ->
						val inputMediaFilePath = downloadDataModel.getDestinationFile().absolutePath
						val convertedAudioFileName = downloadDataModel.fileName + "_converted.mp3"
						val outputPath = downloadDataModel.fileDirectory
						val outputMediaFile = File(outputPath, convertedAudioFileName)

						logger.d("Starting audio extraction from: $inputMediaFilePath -> $outputMediaFile")

						// Begin conversion
						videoToAudioConverter.extractAudio(
							inputFile = inputMediaFilePath,
							outputFile = outputMediaFile.absolutePath,
							listener = object : ConversionListener {

								override fun onProgress(progress: Int) {
									// Update progress on UI thread
									executeOnMainThread {
										val resId = R.string.title_converting_audio_progress
										val progressString = INSTANCE.getString(resId, "${progress}%")
										logger.d("Conversion progress: $progress%")
										messageTextView?.text = progressString
									}
								}

								override fun onSuccess(outputFile: String) {
									executeOnMainThread {
										logger.d("Conversion completed successfully: $outputFile")
										waitingDialog.close()

										// Resume media player if paused
										if (safeActivityRef is MediaPlayerActivity) {
											safeActivityRef.resumePlayer()
											logger.d("Resumed MediaPlayer after conversion")
										}

										// Add converted audio to media store
										FileSystemUtility.addToMediaStore(outputMediaFile)
										showToast(
											activity = safeActivityRef,
											msgId = R.string.title_converted_successfully
										)

										try {
											addNewDownloadModelToSystem(downloadDataModel, outputMediaFile)
										} catch (error: Exception) {
											logger.d("Error adding converted file to system: ${error.message}")
											error.printStackTrace()
										}
									}
								}

								override fun onFailure(errorMessage: String) {
									executeOnMainThread {
										logger.d("Conversion failed: $errorMessage")
										waitingDialog.close()
										if (safeActivityRef is MediaPlayerActivity) {
											safeActivityRef.resumePlayer()
											logger.d("Resumed MediaPlayer after failure")
										}; showToast(
										activity = safeActivityRef,
										msgId = R.string.title_converting_failed
									)
									}
								}
							}
						)
					}
				} catch (error: Exception) {
					logger.d("Unexpected error during conversion: ${error.message}")
					executeOnMainThread {
						waitingDialog.close()
						if (safeActivityRef is MediaPlayerActivity) {
							safeActivityRef.resumePlayer()
							logger.d("Resumed MediaPlayer after unexpected error")
						}

						showToast(
							activity = safeActivityRef,
							msgId = R.string.title_something_went_wrong
						)
					}
				}
			})
		}
	}

	/**
	 * Adds the converted audio file to the download system as a new entry.
	 *
	 * @param downloadDataModel The original download model
	 * @param outputMediaFile The newly converted audio file
	 */
	private fun addNewDownloadModelToSystem(
		downloadDataModel: DownloadDataModel,
		outputMediaFile: File
	) {
		logger.d("Adding converted audio file to download system: ${outputMediaFile.name}")

		// Create a deep copy of the original model with new file details
		val copiedDataModel = deepCopy(downloadDataModel)
		if (copiedDataModel == null) return
		copiedDataModel.id = getUniqueNumberForDownloadModels()
		copiedDataModel.fileName = outputMediaFile.name
		copiedDataModel.fileDirectory = outputMediaFile.parentFile?.absolutePath.toString()
		copiedDataModel.fileSize = outputMediaFile.length()
		copiedDataModel.fileSizeInFormat = getHumanReadableFormat(copiedDataModel.fileSize)
		copiedDataModel.fileCategoryName = copiedDataModel.getUpdatedCategoryName()

		copiedDataModel.startTimeDate = System.currentTimeMillis()
		copiedDataModel.lastModifiedTimeDate = System.currentTimeMillis()

		val lastModifiedTimeDate = copiedDataModel.lastModifiedTimeDate
		copiedDataModel.startTimeDateInFormat = millisToDateTimeString(lastModifiedTimeDate)
		copiedDataModel.lastModifiedTimeDateInFormat = millisToDateTimeString(lastModifiedTimeDate)
		logger.d("Converted model prepared, updating storage and UI")

		copiedDataModel.updateInStorage()
		downloadSystem.addAndSortFinishedDownloadDataModels(copiedDataModel)

		// Update UI to reflect the new entry
		val downloadsUIManager = downloadSystem.downloadsUIManager
		val finishedTasksFragment = downloadsUIManager.finishedTasksFragment
		val finishedTasksListAdapter = finishedTasksFragment?.finishedTasksListAdapter
		finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
		logger.d("Download system updated with converted file: ${copiedDataModel.fileName}")
	}
}