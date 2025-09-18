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
import com.aio.R.string
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSystemUtility
import lib.files.VideoToAudioConverter
import lib.files.VideoToAudioConverter.ConversionListener
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CopyObjectUtils.deepCopy
import lib.process.ThreadsUtility
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File

object Mp4ToAudioConverterDialog {

	@OptIn(UnstableApi::class)
	fun showMp4ToAudioConverterDialog(baseActivityRef: BaseActivity?, downloadModel: DownloadDataModel?) {
		baseActivityRef?.let { safeActivityRef ->
			val videoToAudioConverter = VideoToAudioConverter()
			// Setup waiting dialog with progress updates
			val waitingDialog = WaitingDialog(
				baseActivityInf = safeActivityRef,
				loadingMessage = getText(string.text_converting_audio_progress_0),
				isCancelable = false,
				shouldHideOkayButton = false
			)

			var messageTextView: TextView? = null
			waitingDialog.dialogBuilder?.view?.apply {
				messageTextView = findViewById(R.id.txt_progress_info)
				findViewById<TextView>(R.id.btn_dialog_positive)?.apply {
					this.setText(string.text_cancel_converting)
					this.setLeftSideDrawable(R.drawable.ic_button_cancel)
				}

				findViewById<View>(R.id.btn_dialog_positive_container)
					?.setOnClickListener { videoToAudioConverter.cancel(); waitingDialog.close() }
			}

			if (safeActivityRef is MediaPlayerActivity) safeActivityRef.pausePlayer()
			waitingDialog.show()

			// Perform conversion in background thread
			ThreadsUtility.executeInBackground(codeBlock = {
				try {
					downloadModel?.let { downloadDataModel ->
						val inputMediaFilePath = downloadDataModel.getDestinationFile().absolutePath
						val convertedAudioFileName = downloadDataModel.fileName + "_converted.mp3"
						val outputPath = "${getText(string.text_default_aio_download_folder_path)}/AIO Sounds/"
						val outputMediaFile = File(outputPath, convertedAudioFileName)

						// Start the conversion process
						videoToAudioConverter.extractAudio(
							inputFile = inputMediaFilePath,
							outputFile = outputMediaFile.absolutePath,
							listener = object : ConversionListener {
								override fun onProgress(progress: Int) {
									executeOnMainThread {
										val progressString = INSTANCE.getString(
											string.text_converting_audio_progress,
											"${progress}%"
										); messageTextView?.text = progressString
									}
								}

								override fun onSuccess(outputFile: String) {
									executeOnMainThread {
										waitingDialog.close()
										if (safeActivityRef is MediaPlayerActivity) {
											safeActivityRef.resumePlayer()
										}
										// Add to media store and show success message
										FileSystemUtility.addToMediaStore(outputMediaFile)
										showToast(msgId = string.title_converting_audio_has_been_successful)
										try {
											addNewDownloadModelToSystem(
												downloadDataModel,
												outputMediaFile
											)
										} catch (error: Exception) {
											error.printStackTrace()
										}
									}
								}

								override fun onFailure(errorMessage: String) {
									executeOnMainThread {
										waitingDialog.close()
										if (safeActivityRef is MediaPlayerActivity) {
											safeActivityRef.resumePlayer()
										}
										showToast(msgId = string.title_converting_audio_has_been_failed)
									}
								}
							}
						)
					}
				} catch (error: Exception) {
					executeOnMainThread {
						waitingDialog.close()
						if (safeActivityRef is MediaPlayerActivity) {
							safeActivityRef.resumePlayer()
						}
						showToast(msgId = string.title_something_went_wrong)
					}
				}
			})
		}
	}

	/**
	 * Adds the converted audio file to the download system as a new entry.
	 *
	 * @param downloadDataModel The original download model to copy properties from
	 * @param outputMediaFile The converted audio file
	 */
	private fun addNewDownloadModelToSystem(
		downloadDataModel: DownloadDataModel,
		outputMediaFile: File
	) {
		// Create a copy of the original model with updated properties
		val copiedDataModel = deepCopy(downloadDataModel)
		copiedDataModel?.id = getUniqueNumberForDownloadModels()
		copiedDataModel?.apply {
			fileName = outputMediaFile.name
			fileDirectory = outputMediaFile.parentFile?.absolutePath.toString()
			fileSize = outputMediaFile.length()
			fileSizeInFormat = getHumanReadableFormat(fileSize)
			fileCategoryName = getUpdatedCategoryName()
			startTimeDate = System.currentTimeMillis()
			startTimeDateInFormat = millisToDateTimeString(lastModifiedTimeDate)
			lastModifiedTimeDate = System.currentTimeMillis()
			lastModifiedTimeDateInFormat = millisToDateTimeString(lastModifiedTimeDate)
		}?.let {
			// Update storage and UI
			it.updateInStorage()
			downloadSystem.addAndSortFinishedDownloadDataModels(it)
			val downloadsUIManager = downloadSystem.downloadsUIManager
			val finishedTasksFragment = downloadsUIManager.finishedTasksFragment
			val finishedTasksListAdapter = finishedTasksFragment?.finishedTasksListAdapter
			finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
		}
	}
}