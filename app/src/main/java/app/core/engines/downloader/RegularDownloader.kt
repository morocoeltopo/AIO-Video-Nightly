package app.core.engines.downloader

import android.os.CountDownTimer
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.downloader.RegularDownloadPart.DownloadPartListener
import com.aio.R.raw
import com.aio.R.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lib.device.DateTimeUtils.calculateTime
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSizeFormatter.humanReadableSizeOf
import lib.networks.DownloaderUtils.calculateDownloadSpeed
import lib.networks.DownloaderUtils.calculateDownloadSpeedInFormat
import lib.networks.DownloaderUtils.formatDownloadSpeedInSimpleForm
import lib.networks.DownloaderUtils.getFormattedPercentage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.DownloaderUtils.getOptimalNumberOfDownloadParts
import lib.networks.DownloaderUtils.getRemainingDownloadTime
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtility.getOriginalURL
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.isInternetConnected
import lib.process.AudioPlayerUtils
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL

class RegularDownloader(override val downloadDataModel: DownloadDataModel) :
	DownloadTaskInf, DownloadPartListener , AIOTimerListener{

	private val logger = LogHelperUtils.from(javaClass)
	private val downloadSettingsConfig = downloadDataModel.globalSettings
	private var destinationFile: File = downloadDataModel.getDestinationFile()
	private var downloadParts: ArrayList<RegularDownloadPart> = ArrayList()
	private var downloadTimer: CountDownTimer? = null
	private var netSpeedTracker: NetSpeedTracker? = null
	override var statusListener: DownloadTaskListener? = null

	override fun initiate() {
		initDownloadDataModel()
		initDestinationFile()
		initDownloadTaskTimer()
	}

	override fun startDownload() {
		CoroutineScope(Dispatchers.IO).launch {
			configureDownloadModel()
			configureDownloadParts()
			createEmptyDestinationFile()
			startAllDownloadThreads().let { isSuccess ->
				if (isSuccess) {
					updateDownloadStatus(getText(string.title_started_downloading), DOWNLOADING)
					downloadTimer?.start()
				} else {
					updateDownloadStatus(getText(string.title_download_io_failed), CLOSE)
				}
			}
		}
	}

	override fun cancelDownload(cancelReason: String) {
		try {
			downloadParts.forEach { part -> part.stopDownload() }
			val statusMessage = cancelReason.ifEmpty { getText(string.title_paused) }
			updateDownloadStatus(statusMessage, CLOSE)
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	@Synchronized
	override fun onPartCanceled(downloadPart: RegularDownloadPart) {
		CoroutineScope(Dispatchers.IO).launch {
			val isCritical = isCriticalErrorFoundInDownloadPart(downloadPart)
			if (isCritical) {
				if (downloadDataModel.isFileUrlExpired) {
					cancelDownload(getText(string.title_link_expired)); return@launch
				}

				if (downloadDataModel.isDestinationFileNotExisted) {
					cancelDownload(getText(string.title_file_deleted_paused)); return@launch
				}
			} else {
				restartDownload(downloadPart)
			}

			downloadDataModel.updateInStorage()

			// Clean up if the download was marked as deleted
			if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
				if (destinationFile.exists()) destinationFile.delete()
			}
		}
	}

	@Synchronized
	override fun onPartCompleted(downloadPart: RegularDownloadPart) {
		CoroutineScope(Dispatchers.IO).launch {
			var isAllPartCompleted = true
			downloadParts.forEach { part ->
				if (part.partDownloadStatus != COMPLETE) isAllPartCompleted = false
			}

			// Return early if any part is not completed yet
			if (!isAllPartCompleted) return@launch

			// Play completion sound if enabled
			if (downloadSettingsConfig.downloadPlayNotificationSound) {
				AudioPlayerUtils(INSTANCE).play(raw.sound_download_finished)
			}

			downloadDataModel.isRunning = false
			downloadDataModel.isComplete = true
			updateDownloadStatus(getText(string.title_completed), COMPLETE)
		}
	}

	override fun onAIOTimerTick(loopCount: Double) {

	}


	private fun initDownloadDataModel() {
		downloadDataModel.status = CLOSE
		downloadDataModel.isRunning = false
		downloadDataModel.isWaitingForNetwork = false
		downloadDataModel.totalConnectionRetries = 0
		downloadDataModel.statusInfo = getText(string.title_waiting_to_join)
		downloadDataModel.updateInStorage()
	}

	private fun initDestinationFile() {
		destinationFile = downloadDataModel.getDestinationFile()
	}

	private fun initDownloadTaskTimer() {
		CoroutineScope(Dispatchers.Main).launch {
			downloadTimer = object : CountDownTimer((1000 * 60), 500) {

				override fun onTick(millisUntilFinished: Long) {
					updateDownloadProgress()
				}

				override fun onFinish() {
					if (downloadDataModel.status == DOWNLOADING) {
						start()
					}
				}
			}
		}
	}

	private fun configureDownloadModel() {
		updateDownloadStatus(getText(string.title_validating_download))
		if (doesDownloadModelHasPreviousData()) return

		configureDownloadAutoResumeSettings()
		configureDownloadAutoRemoveSettings()
		configureDownloadAutoFilterURL()
		configureDownloadFileInfo()
		configureDownloadPartRange()
	}


	private fun doesDownloadModelHasPreviousData(): Boolean {
		val isPreviousDataFound = downloadDataModel.downloadedByte > 0

		if (isPreviousDataFound) {
			updateDownloadStatus(getText(string.title_old_data_found))

			if (!destinationFile.exists()) {
				downloadDataModel.isFailedToAccessFile = true
				cancelDownload(getText(string.title_failed_deleted_paused))
			}
		}; return isPreviousDataFound
	}

	private fun configureDownloadAutoResumeSettings() {
		updateDownloadStatus(getText(string.title_configuring_auto_resume))
		if (!downloadSettingsConfig.downloadAutoResume) {
			downloadSettingsConfig.downloadAutoResumeMaxErrors = 0
		}
	}

	private fun configureDownloadAutoRemoveSettings() {
		updateDownloadStatus(statusInfo = getText(string.title_configuring_auto_remove))
		if (!downloadSettingsConfig.downloadAutoRemoveTasks) {
			downloadSettingsConfig.downloadAutoRemoveTaskAfterNDays = 0
		}
	}

	private fun configureDownloadAutoFilterURL() {
		if (downloadSettingsConfig.downloadAutoLinkRedirection) {
			updateDownloadStatus(statusInfo = getText(string.title_filtering_url))
			val originalURL = getOriginalURL(downloadDataModel.fileURL)
			if (originalURL != null) downloadDataModel.fileURL = originalURL
		}
	}


	private fun configureDownloadFileInfo() {
		if (downloadDataModel.fileSize <= 1) {
			updateDownloadStatus(statusInfo = getText(string.title_recalculating_file_size))

			if (!isValidURL(downloadDataModel.fileURL)) {
				cancelDownload(getText(string.title_invalid_file_url))
			} else {
				val fileInfo = getFileInfoFromSever(URL(downloadDataModel.fileURL))
				downloadDataModel.fileSize = fileInfo.fileSize
				downloadDataModel.fileChecksum = fileInfo.fileChecksum

				if (downloadDataModel.fileName.isEmpty()) {
					downloadDataModel.fileName = fileInfo.fileName
				}

				downloadDataModel.isUnknownFileSize = false
				downloadDataModel.isResumeSupported = fileInfo.isSupportsResume
				downloadDataModel.isMultiThreadSupported = fileInfo.isSupportsMultipart
				downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)
				if (downloadDataModel.isMultiThreadSupported) {
					val downloadThreads = getOptimalNumberOfDownloadParts(downloadDataModel.fileSize)
					downloadSettingsConfig.downloadDefaultThreadConnections = downloadThreads
				}

				// Handle edge case: file size is still unknown after server check
				if (downloadDataModel.fileSize <= 1) {
					downloadDataModel.isUnknownFileSize = true
					downloadDataModel.isMultiThreadSupported = false
					downloadSettingsConfig.downloadDefaultThreadConnections = 1
					downloadDataModel.fileSizeInFormat = getText(string.title_unknown)
				}
			}
		}
	}

	private fun configureDownloadPartRange() {
		val numberOfThreads = downloadSettingsConfig.downloadDefaultThreadConnections
		downloadDataModel.partStartingPoint = LongArray(numberOfThreads)
		downloadDataModel.partEndingPoint = LongArray(numberOfThreads)
		downloadDataModel.partChunkSizes = LongArray(numberOfThreads)
		downloadDataModel.partsDownloadedByte = LongArray(numberOfThreads)
		downloadDataModel.partProgressPercentage = IntArray(numberOfThreads)

		if (downloadDataModel.isUnknownFileSize || downloadDataModel.fileSize < 1) {
			downloadDataModel.partChunkSizes[0] = downloadDataModel.fileSize
		} else {
			val ranges = calculateAlignedPartRanges(downloadDataModel.fileSize, numberOfThreads)
			ranges.forEachIndexed { index, (start, end) ->
				downloadDataModel.partStartingPoint[index] = start
				downloadDataModel.partEndingPoint[index] = end
				downloadDataModel.partChunkSizes[index] = end - start + 1
			}
		}
	}

	private fun calculateAlignedPartRanges(
		fileSize: Long,
		numberOfThreads: Int,
		alignmentBoundary: Long = 4096L
	): List<Pair<Long, Long>> {
		val basePartSize = fileSize / numberOfThreads
		val ranges = mutableListOf<Pair<Long, Long>>()

		for (threadNumber in 0 until numberOfThreads) {
			val startByte = threadNumber * basePartSize
			val endByte = if (threadNumber == numberOfThreads - 1) {
				fileSize - 1
			} else {
				alignToBoundary(startByte + basePartSize - 1, alignmentBoundary)
			}
			ranges.add(Pair(startByte, endByte))
		}

		return ranges
	}


	private fun alignToBoundary(position: Long, boundary: Long): Long {
		val alignedPosition = ((position + boundary - 1) / boundary) * boundary
		return alignedPosition
	}

	private fun configureDownloadParts() {
		downloadParts = generateDownloadParts()
	}

	private fun createEmptyDestinationFile() {
		if (downloadDataModel.isDeleted) return
		try {
			if (destinationFile.exists()) return
			if (isMultiThreadDownloadSupported()) {
				updateDownloadStatus(getText(string.title_creating_empty_file))
				RandomAccessFile(destinationFile, "rw").setLength(downloadDataModel.fileSize)
			}
		} catch (error: IOException) {
			error.printStackTrace()
			downloadDataModel.totalConnectionRetries++
			downloadDataModel.isFailedToAccessFile = true
			cancelDownload(getText(string.title_download_io_failed))
		}
	}

	private fun isMultiThreadDownloadSupported(): Boolean {
		val maxThreadsAllowed = downloadSettingsConfig.downloadDefaultThreadConnections
		val isNotUnknownFileSize = !downloadDataModel.isUnknownFileSize
		val isSupported =
			downloadDataModel.fileSize > 0 && maxThreadsAllowed > 1 && isNotUnknownFileSize
		return isSupported
	}

	private fun isCriticalErrorFoundInDownloadPart(downloadPart: RegularDownloadPart): Boolean {
		val errorFound = downloadPart.partDownloadErrorException != null
		if (errorFound) {
			if (downloadPart.partDownloadErrorException is FileNotFoundException) {
				downloadDataModel.isFileUrlExpired = true
				if (!destinationFile.exists()) downloadDataModel.isDestinationFileNotExisted = true
				return true
			}
		}
		return false
	}

	private fun isRetryingAllowed(): Boolean {
		val maxErrorAllowed = downloadSettingsConfig.downloadAutoResumeMaxErrors
		val retryAllowed = downloadDataModel.isRunning &&
				downloadDataModel.totalConnectionRetries < maxErrorAllowed
		return retryAllowed
	}

	private fun restartDownload(downloadPart: RegularDownloadPart) {
		if (isRetryingAllowed()) {
			if (!isNetworkAvailable()) {
				downloadDataModel.isWaitingForNetwork = true
				updateDownloadStatus(getText(string.title_waiting_for_network))
				return
			}

			if (downloadSettingsConfig.downloadWifiOnly && !isWifiEnabled()) {
				downloadDataModel.isWaitingForNetwork = true
				updateDownloadStatus(getText(string.title_waiting_for_wifi))
				return
			}

			if (!downloadPart.isInternetConnected()) {
				downloadDataModel.isWaitingForNetwork = true
				updateDownloadStatus(getText(string.title_waiting_for_internet))
				return
			}

			if (downloadDataModel.isWaitingForNetwork) {
				downloadDataModel.isWaitingForNetwork = false
				updateDownloadStatus(getText(string.title_started_downloading))
				downloadPart.startDownload()
			}

			downloadDataModel.totalConnectionRetries++
		}
	}

	private fun startAllDownloadThreads(): Boolean {
		if (downloadDataModel.isFailedToAccessFile) {
			downloadDataModel.msgToShowUserViaDialog =
				getText(string.text_failed_to_write_file_to_storage)
			return false
		} else {
			downloadParts.forEach { downloadPart ->
				if (downloadPart.partDownloadStatus != DOWNLOADING) {
					downloadPart.startDownload()
				}
			}
			return true
		}
	}

	private fun generateDownloadParts(): ArrayList<RegularDownloadPart> {
		updateDownloadStatus(getText(string.title_generating_parts))

		val numberOfThreads = downloadSettingsConfig.downloadDefaultThreadConnections
		val regularDownloadParts = ArrayList<RegularDownloadPart>(numberOfThreads)

		// Create and configure each download part
		for (index in 0 until numberOfThreads) {
			val downloadPart = RegularDownloadPart(this@RegularDownloader)
			downloadPart.initiate(
				partIndex = index,
				startingPoint = downloadDataModel.partStartingPoint[index],
				endingPoint = downloadDataModel.partEndingPoint[index],
				chunkSize = downloadDataModel.partChunkSizes[index],
				downloadedByte = downloadDataModel.partsDownloadedByte[index]
			)
			regularDownloadParts.add(downloadPart)
		}

		return regularDownloadParts
	}


	private fun updateDownloadProgress() {
		CoroutineScope(Dispatchers.IO).launch {
			calculateProgressAndModifyDownloadModel()
			checkNetworkConnectionAndRetryDownload()
			if (!downloadDataModel.isRunning) downloadTimer?.cancel()
			updateDownloadStatus()
		}
	}

	override fun updateDownloadStatus(statusInfo: String?, status: Int) {
		CoroutineScope(Dispatchers.IO).launch {
			if (!statusInfo.isNullOrEmpty()) {
				downloadDataModel.statusInfo = statusInfo
			}

			downloadDataModel.status = status
			downloadDataModel.isRunning = (status == DOWNLOADING)
			downloadDataModel.isComplete = (status == COMPLETE)
			downloadDataModel.updateInStorage()

			CoroutineScope(Dispatchers.Main).launch {
				statusListener?.onStatusUpdate(this@RegularDownloader)
			}

			if (!downloadDataModel.isRunning || downloadDataModel.isComplete) {
				downloadTimer?.cancel()
			}
		}
	}

	private fun calculateProgressAndModifyDownloadModel() {
		calculateDownloadRetries()
		calculateTotalDownloadedTime()
		calculateDownloadedBytes()
		calculateDownloadPercentage()
		updateLastModificationDate()
		calculateAverageDownloadSpeed()
		calculateRealtimeDownloadSpeed()
		calculateMaxDownloadSpeed()
		calculateRemainingDownloadTime()
		validatingDownloadCompletion()
		downloadDataModel.updateInStorage()
	}

	private fun validatingDownloadCompletion() {
		if (downloadDataModel.isUnknownFileSize) return
		if (downloadDataModel.fileSize < 1) return

		downloadParts.forEach {
			if (it.partDownloadedByte >= it.partChunkSize) {
				if (it.partDownloadStatus != COMPLETE) {
					it.stopDownload().apply { startDownload() }
				}
			}
		}
	}

	private fun calculateDownloadedBytes() {
		downloadDataModel.downloadedByte = 0

		downloadParts.forEach { downloadPart ->
			downloadDataModel.downloadedByte += downloadPart.partDownloadedByte
			downloadDataModel.partsDownloadedByte[downloadPart.partIndex] =
				downloadPart.partDownloadedByte
			downloadDataModel.partProgressPercentage[downloadPart.partIndex] =
				if (downloadDataModel.isUnknownFileSize) {
					0
				} else {
					((downloadPart.partDownloadedByte * 100) / downloadPart.partChunkSize).toInt()
				}
		}

		// Format total downloaded bytes
		downloadDataModel.downloadedByteInFormat =
			getHumanReadableFormat(downloadDataModel.downloadedByte)

		// Update unknown file size dynamically
		if (!isMultiThreadDownloadSupported() && downloadDataModel.isUnknownFileSize) {
			downloadDataModel.fileSize = downloadDataModel.getDestinationFile().length()
			downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)
		}
	}

	private fun calculateDownloadPercentage() {
		downloadDataModel.progressPercentage = if (downloadDataModel.isUnknownFileSize) {
			0
		} else {
			if (downloadDataModel.fileSize != 0L) {
				(downloadDataModel.downloadedByte * 100) / downloadDataModel.fileSize
			} else {
				0
			}
		}

		downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
	}

	private fun updateLastModificationDate() {
		downloadDataModel.lastModifiedTimeDate = System.currentTimeMillis()
		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
	}


	private fun calculateAverageDownloadSpeed() {
		val downloadedByte = downloadDataModel.downloadedByte
		val downloadedTime = downloadDataModel.timeSpentInMilliSec
		downloadDataModel.averageSpeed = calculateDownloadSpeed(downloadedByte, downloadedTime)
		downloadDataModel.averageSpeedInFormat = calculateDownloadSpeedInFormat(
			downloadedByte,
			downloadedTime
		)
	}

	private fun calculateRealtimeDownloadSpeed() {
		if (netSpeedTracker == null) {
			netSpeedTracker = NetSpeedTracker(
				initialBytesDownloaded = downloadDataModel.downloadedByte
			)
		}

		netSpeedTracker?.let {
			it.update(downloadDataModel.downloadedByte)
			val currentSpeed = it.getCurrentSpeed()

			downloadDataModel.realtimeSpeed = (if (currentSpeed < 0) 0 else currentSpeed)
			downloadDataModel.realtimeSpeedInFormat = (if (currentSpeed < 0)
				formatDownloadSpeedInSimpleForm(0.0) else it.getFormattedSpeed())

			if (!downloadDataModel.isRunning) {
				downloadDataModel.realtimeSpeed = 0
				downloadDataModel.realtimeSpeedInFormat = "--"
			}
		}
	}

	private fun calculateMaxDownloadSpeed() {
		if (downloadDataModel.realtimeSpeed > downloadDataModel.maxSpeed) {
			downloadDataModel.maxSpeed = downloadDataModel.realtimeSpeed
			downloadDataModel.maxSpeedInFormat =
				formatDownloadSpeedInSimpleForm(downloadDataModel.maxSpeed.toDouble())
		}
	}


	private fun calculateDownloadRetries() {
		logger.d("Updating total connection retries")
		downloadDataModel.totalTrackedConnectionRetries =
			downloadDataModel.totalConnectionRetries + downloadDataModel.totalUnresetConnectionRetries
	}


	private fun calculateTotalDownloadedTime() {
		if (!downloadDataModel.isWaitingForNetwork) {
			downloadDataModel.timeSpentInMilliSec += 500
		}

		val timeSpentInMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		downloadDataModel.timeSpentInFormat =
			calculateTime(timeSpentInMillis, getText(string.text_spent))
	}

	private fun calculateRemainingDownloadTime() {
		if (!downloadDataModel.isUnknownFileSize || !downloadDataModel.isWaitingForNetwork) {
			val remainingByte = downloadDataModel.fileSize - downloadDataModel.downloadedByte
			val averageSpeed = downloadDataModel.averageSpeed
			val remainingTime = getRemainingDownloadTime(remainingByte, averageSpeed)

			downloadDataModel.remainingTimeInSec = remainingTime
			downloadDataModel.remainingTimeInFormat =
				calculateTime(remainingTime.toFloat(), getText(string.text_left))
		} else {
			downloadDataModel.remainingTimeInSec = 0L
			downloadDataModel.remainingTimeInFormat = "-:-"
		}
	}

	private fun checkNetworkConnectionAndRetryDownload() {
		// Check connectivity status for each download part
		downloadParts.forEach { downloadPart -> downloadPart.verifyNetworkConnection() }

		// If download is waiting for network, attempt resuming when possible
		if (downloadDataModel.isWaitingForNetwork) {
			if (isNetworkAvailable() && isInternetConnected()) {
				if (downloadSettingsConfig.downloadWifiOnly && !isWifiEnabled()) return

				downloadDataModel.isWaitingForNetwork = false
				updateDownloadStatus(getText(string.title_started_downloading))
				startAllDownloadThreads()
			}
		}
	}
}