package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.downloader.RegularDownloadPart.DownloadPartListener
import app.core.engines.settings.AIOSettings
import com.aio.R.raw
import com.aio.R.string
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
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL

class RegularDownloader(override val downloadDataModel: DownloadDataModel) :
	DownloadTaskInf, DownloadPartListener, AIOTimerListener {

	private val logger: LogHelperUtils = LogHelperUtils.from(javaClass)
	private val downloadSettings: AIOSettings = downloadDataModel.globalSettings
	private var destinationOutputFile: File = downloadDataModel.getDestinationFile()
	private var allDownloadParts: ArrayList<RegularDownloadPart> = ArrayList()
	private var netSpeedTracker: NetSpeedTracker? = null
	override var downloadStatusListener: DownloadTaskListener? = null

	override suspend fun initiateDownload() {
		downloadDataModel.status = CLOSE
		downloadDataModel.isRunning = false
		downloadDataModel.isWaitingForNetwork = false
		downloadDataModel.resumeSessionRetryCount = 0
		destinationOutputFile = downloadDataModel.getDestinationFile()

		val statusInfoString = getText(string.title_waiting_to_join)
		downloadDataModel.statusInfo = statusInfoString
		downloadDataModel.updateInStorage()
	}

	override suspend fun startDownload() {
		configureDownloadModel()
		configureDownloadParts()
		createEmptyOutputDestinationFile()
		val isSuccessfullyExecuted = startAllDownloadThreads()

		if (isSuccessfullyExecuted) {
			val statusInfoString = getText(string.title_started_downloading)
			updateDownloadStatus(statusInfo = statusInfoString, status = DOWNLOADING)
			aioTimer.register(this@RegularDownloader)
		} else {
			val statusInfoString = getText(string.title_download_io_failed)
			updateDownloadStatus(statusInfo = statusInfoString, status = CLOSE)
		}
	}

	override suspend fun cancelDownload(cancelReason: String, isCanceledByUser: Boolean) {
		try {
			allDownloadParts.forEach { part ->
				part.stopDownloadSilently(isCanceledByUser = isCanceledByUser)
			}

			val statusMessage = cancelReason.ifEmpty { getText(string.title_paused) }
			updateDownloadStatus(statusInfo = statusMessage, status = CLOSE)
		} catch (error: Exception) {
			logger.e("Error while canceling download process:", error)
		}
	}

	@Synchronized
	override fun onPartCanceled(downloadPart: RegularDownloadPart) {
		ThreadsUtility.executeInBackground(codeBlock = {
			if (downloadPart.isPartCanceledByUser) return@executeInBackground
			if (isCriticalErrorFoundInDownloadPart(downloadPart)) {
				if (downloadDataModel.isFileUrlExpired) {
					val cancelReason = getText(string.title_link_expired_paused)
					cancelDownload(cancelReason = cancelReason)
					return@executeInBackground
				}

				if (downloadDataModel.isDestinationFileNotExisted) {
					val cancelReason = getText(string.title_file_deleted_paused)
					cancelDownload(cancelReason = cancelReason)
					return@executeInBackground
				}
			} else {
				tryRestartingDownloadPart(regularDownloadPart = downloadPart)
			}

			downloadDataModel.updateInStorage()
			if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
				if (destinationOutputFile.exists()) {
					destinationOutputFile.delete()
				}
			}
		})
	}

	@Synchronized
	override fun onPartCompleted(downloadPart: RegularDownloadPart) {
		ThreadsUtility.executeInBackground(codeBlock = {
			var allPartsCompleted = true

			for ((index, part) in allDownloadParts.withIndex()) {
				if (!allPartsCompleted) break

				if (!downloadDataModel.isUnknownFileSize && part.partChunkSize > 0) {
					val expectedSize = downloadDataModel.partChunkSizes[index]
					val actualSize = part.partDownloadedByte

					if (actualSize < expectedSize) {
						allPartsCompleted = false
						break
					}
				}

				if (part.partDownloadStatus != COMPLETE) {
					allPartsCompleted = false
					break
				}
			}

			if (!allPartsCompleted) return@executeInBackground
			if (downloadSettings.downloadPlayNotificationSound) {
				AudioPlayerUtils(INSTANCE).play(raw.sound_download_finished)
			}

			downloadDataModel.isRunning = false
			downloadDataModel.isComplete = true
			val statusInfoString = getText(string.title_completed)
			updateDownloadStatus(statusInfo = statusInfoString, status = COMPLETE)
		})
	}

	override fun onAIOTimerTick(loopCount: Double) {
		handleDownloadProgress()
	}

	override suspend fun updateDownloadStatus(statusInfo: String?, status: Int) {
		if (!statusInfo.isNullOrEmpty()) downloadDataModel.statusInfo = statusInfo
		downloadDataModel.status = status
		downloadDataModel.isRunning = (status == DOWNLOADING)
		downloadDataModel.isComplete = (status == COMPLETE)
		downloadDataModel.updateInStorage()

		downloadStatusListener?.onStatusUpdate(this@RegularDownloader)
		if (!downloadDataModel.isRunning && downloadDataModel.status != DOWNLOADING) {
			aioTimer.unregister(this@RegularDownloader)
		}
	}

	private suspend fun configureDownloadModel() {
		val statusInfoString = getText(string.title_validating_download_task)
		updateDownloadStatus(statusInfo = statusInfoString)

		if (doesDownloadModelHasPreviousData()) return
		configureDownloadAutoResumeSettings()
		configureDownloadAutoRemoveSettings()
		configureDownloadAutoFilterURL()
		configureDownloadFileInfo()
		configureDownloadPartRange()
	}

	private suspend fun doesDownloadModelHasPreviousData(): Boolean {
		val isPreviousDataFound = downloadDataModel.downloadedByte > 0
		if (isPreviousDataFound) {
			val statusInfoString = getText(string.title_previous_download_data_found)
			updateDownloadStatus(statusInfo = statusInfoString)
			if (!destinationOutputFile.exists()) {
				downloadDataModel.isFailedToAccessFile = true
				val cancelReasonString = getText(string.title_failed_deleted_paused)
				cancelDownload(cancelReason = cancelReasonString)
			}
		}
		return isPreviousDataFound
	}

	private suspend fun configureDownloadAutoResumeSettings() {
		val statusInfoString = getText(string.title_configuring_auto_resume)
		updateDownloadStatus(statusInfo = statusInfoString)
		if (!downloadSettings.downloadAutoResume) {
			downloadSettings.downloadAutoResumeMaxErrors = 0
		}
	}

	private suspend fun configureDownloadAutoRemoveSettings() {
		val statusInfoString = getText(string.title_configuring_auto_remove)
		updateDownloadStatus(statusInfo = statusInfoString)
		if (!downloadSettings.downloadAutoRemoveTasks) {
			downloadSettings.downloadAutoRemoveTaskAfterNDays = 0
		}
	}

	private suspend fun configureDownloadAutoFilterURL() {
		if (!downloadSettings.downloadAutoLinkRedirection) return

		val statusInfoString = getText(string.title_filtering_url)
		updateDownloadStatus(statusInfo = statusInfoString)

		val originalURL = getOriginalURL(downloadDataModel.fileURL)
		if (originalURL != null && originalURL.isNotEmpty()) {
			downloadDataModel.fileURL = originalURL
		}
	}

	private suspend fun configureDownloadFileInfo() {
		if (downloadDataModel.fileSize > 0) return

		val statusInfoString = getText(string.title_recalculating_file_size)
		updateDownloadStatus(statusInfo = statusInfoString)

		if (!isValidURL(downloadDataModel.fileURL)) {
			val cancelReasonString = getText(string.title_invalid_file_url)
			cancelDownload(cancelReason = cancelReasonString)
			return
		}

		val fileURL = URL(downloadDataModel.fileURL)
		val remoteFileInfo = getFileInfoFromSever(fileURL)
		downloadDataModel.remoteFileInfo = remoteFileInfo
		downloadDataModel.fileSize = remoteFileInfo.fileSize
		downloadDataModel.fileChecksum = remoteFileInfo.fileChecksum

		if (downloadDataModel.fileName.isEmpty()) {
			downloadDataModel.fileName = remoteFileInfo.fileName
		}

		downloadDataModel.isUnknownFileSize = false
		downloadDataModel.isResumeSupported = remoteFileInfo.isSupportsResume
		downloadDataModel.isMultiThreadSupported = remoteFileInfo.isSupportsMultipart
		downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)
		if (downloadDataModel.isMultiThreadSupported) {
			val downloadThreads = getOptimalNumberOfDownloadParts(downloadDataModel.fileSize)
			downloadSettings.downloadDefaultThreadConnections = downloadThreads
		}

		if (downloadDataModel.fileSize <= 1) {
			downloadDataModel.isUnknownFileSize = true
			downloadDataModel.isMultiThreadSupported = false
			downloadSettings.downloadDefaultThreadConnections = 1
			downloadDataModel.fileSizeInFormat = getText(string.title_unknown)
		}
	}

	private fun configureDownloadPartRange() {
		val numberOfThreads = downloadSettings.downloadDefaultThreadConnections
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
			val endByte = if (threadNumber == numberOfThreads - 1) fileSize - 1
			else alignToBoundary(startByte + basePartSize - 1, alignmentBoundary)
			ranges.add(Pair(startByte, endByte))
		}

		return ranges
	}

	private fun alignToBoundary(position: Long, boundary: Long): Long {
		val alignedPosition = ((position + boundary - 1) / boundary) * boundary
		return alignedPosition
	}

	private suspend fun configureDownloadParts() {
		allDownloadParts = generateDownloadParts()
	}

	private suspend fun generateDownloadParts(): ArrayList<RegularDownloadPart> {
		val statusInfoString = getText(string.title_generating_download_parts)
		updateDownloadStatus(statusInfo = statusInfoString)

		val numberOfThreads = downloadSettings.downloadDefaultThreadConnections
		val regularDownloadParts = ArrayList<RegularDownloadPart>(numberOfThreads)

		for (index in 0 until numberOfThreads) {
			val downloadPart = RegularDownloadPart(regularDownloader = this@RegularDownloader)
			downloadPart.initiate(
				downloadPartIndex = index,
				downloadStartingPoint = downloadDataModel.partStartingPoint[index],
				downloadEndingPoint = downloadDataModel.partEndingPoint[index],
				downloadChunkSize = downloadDataModel.partChunkSizes[index],
				downloadedByte = downloadDataModel.partsDownloadedByte[index]
			)
			regularDownloadParts.add(downloadPart)
		}
		return regularDownloadParts
	}

	private suspend fun createEmptyOutputDestinationFile() {
		if (downloadDataModel.isDeleted) return
		try {
			if (destinationOutputFile.exists()) return
			if (canUseMultiThreadedDownload()) {
				val statusInfoString = getText(string.title_creating_empty_destination_file)
				updateDownloadStatus(statusInfo = statusInfoString)
				RandomAccessFile(destinationOutputFile, "rw").setLength(downloadDataModel.fileSize)
			}
		} catch (error: IOException) {
			logger.e("Error while creating an empty output destination file:", error)
			downloadDataModel.resumeSessionRetryCount++
			downloadDataModel.totalTrackedConnectionRetries++
			downloadDataModel.isFailedToAccessFile = true
			val cancelReasonString = getText(string.title_download_io_failed)
			cancelDownload(cancelReason = cancelReasonString)
		}
	}

	@Synchronized
	private fun canUseMultiThreadedDownload(): Boolean {
		val maxDownloadThreads = downloadSettings.downloadDefaultThreadConnections
		val isNotUnknownFileSize = !downloadDataModel.isUnknownFileSize
		val isMultiThreadingSupported =
			(downloadDataModel.fileSize > 0) && (maxDownloadThreads > 1) && isNotUnknownFileSize
		return isMultiThreadingSupported
	}

	@Synchronized
	private fun startAllDownloadThreads(): Boolean {
		if (downloadDataModel.isFailedToAccessFile) {
			val errorMsgString = getText(string.text_failed_to_write_file_to_storage)
			downloadDataModel.msgToShowUserViaDialog = errorMsgString
			return false
		} else {
			allDownloadParts.forEach { downloadPart ->
				if (downloadPart.partDownloadStatus != DOWNLOADING) {
					downloadPart.startDownload()
				}
			}
			return true
		}
	}

	@Synchronized
	private fun isCriticalErrorFoundInDownloadPart(regularDownloadPart: RegularDownloadPart): Boolean {
		if (regularDownloadPart.partDownloadErrorException != null) {
			if (regularDownloadPart.partDownloadErrorException is FileNotFoundException) {
				downloadDataModel.isFileUrlExpired = true
				if (!destinationOutputFile.exists()) {
					downloadDataModel.isDestinationFileNotExisted = true
				}
				return true
			}
		}
		return false
	}

	@Synchronized
	private fun isRetryingDownloadAllowed(): Boolean {
		val maxErrorAllowed = downloadSettings.downloadAutoResumeMaxErrors
		val sessionRetryCount = downloadDataModel.resumeSessionRetryCount
		val retryAllowed = downloadDataModel.isRunning && sessionRetryCount < maxErrorAllowed
		return retryAllowed
	}

	private suspend fun tryRestartingDownloadPart(regularDownloadPart: RegularDownloadPart) {
		if (isRetryingDownloadAllowed()) {
			if (!isNetworkAvailable()) {
				downloadDataModel.isWaitingForNetwork = true
				val statusInfoString = getText(string.title_waiting_for_network)
				updateDownloadStatus(statusInfoString)
				return
			}

			if (downloadSettings.downloadWifiOnly && !isWifiEnabled()) {
				downloadDataModel.isWaitingForNetwork = true
				val statusInfoString = getText(string.title_waiting_for_wifi)
				updateDownloadStatus(statusInfoString)
				return
			}

			if (!regularDownloadPart.isInternetConnected()) {
				downloadDataModel.isWaitingForNetwork = true
				val statusInfoString = getText(string.title_waiting_for_internet)
				updateDownloadStatus(statusInfoString)
				return
			}

			if (downloadDataModel.isWaitingForNetwork) {
				downloadDataModel.isWaitingForNetwork = false
				val statusInfoString = getText(string.title_started_downloading)
				updateDownloadStatus(statusInfoString)
				val partDownloadStatus = regularDownloadPart.partDownloadStatus
				if (partDownloadStatus != DOWNLOADING && partDownloadStatus != COMPLETE) {
					regularDownloadPart.startDownload()
				}
			}

			downloadDataModel.resumeSessionRetryCount++
			downloadDataModel.totalTrackedConnectionRetries++
		}
	}

	@Synchronized
	private fun handleDownloadProgress() {
		if (downloadDataModel.isRunning && downloadDataModel.status == DOWNLOADING) {
			updateDownloadProgress()
		} else {
			aioTimer.unregister(this@RegularDownloader)
		}
	}

	@Synchronized
	private fun updateDownloadProgress() {
		ThreadsUtility.executeInBackground(codeBlock = {
			calculateProgressAndModifyDownloadModel()
			checkNetworkConnectionAndRetryDownload()
			updateDownloadStatus()
		})
	}

	@Synchronized
	private fun calculateProgressAndModifyDownloadModel() {
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

	@Synchronized
	private fun validatingDownloadCompletion() {
		if (downloadDataModel.isUnknownFileSize) return
		if (downloadDataModel.fileSize < 1) return

		allDownloadParts.forEach {
			if (it.partDownloadedByte >= it.partChunkSize) {
				if (it.partDownloadStatus != COMPLETE) {
					it.stopDownloadSilently()
				}
			}
		}
	}

	@Synchronized
	private fun calculateDownloadedBytes() {
		downloadDataModel.downloadedByte = 0

		allDownloadParts.forEach { downloadPart ->
			downloadDataModel.downloadedByte += downloadPart.partDownloadedByte
			downloadDataModel.partsDownloadedByte[downloadPart.partIndex] =
				downloadPart.partDownloadedByte
			downloadDataModel.partProgressPercentage[downloadPart.partIndex] =
				if (downloadDataModel.isUnknownFileSize) 0 else {
					((downloadPart.partDownloadedByte * 100) / downloadPart.partChunkSize).toInt()
				}
		}

		downloadDataModel.downloadedByteInFormat =
			getHumanReadableFormat(downloadDataModel.downloadedByte)

		if (!canUseMultiThreadedDownload() && downloadDataModel.isUnknownFileSize) {
			downloadDataModel.fileSize = downloadDataModel.getDestinationFile().length()
			downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)
		}
	}

	@Synchronized
	private fun calculateDownloadPercentage() {
		downloadDataModel.progressPercentage =
			if (downloadDataModel.isUnknownFileSize) 0 else {
				if (downloadDataModel.fileSize != 0L) {
					(downloadDataModel.downloadedByte * 100) / downloadDataModel.fileSize
				} else 0
			}

		downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
	}

	@Synchronized
	private fun updateLastModificationDate() {
		downloadDataModel.lastModifiedTimeDate = System.currentTimeMillis()
		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
	}

	@Synchronized
	private fun calculateAverageDownloadSpeed() {
		val downloadedByte = downloadDataModel.downloadedByte
		val downloadedTime = downloadDataModel.timeSpentInMilliSec
		downloadDataModel.averageSpeed = calculateDownloadSpeed(downloadedByte, downloadedTime)
		downloadDataModel.averageSpeedInFormat = calculateDownloadSpeedInFormat(
			downloadedByte,
			downloadedTime
		)
	}

	@Synchronized
	private fun calculateRealtimeDownloadSpeed() {
		val bytesDownloaded = downloadDataModel.downloadedByte
		if (netSpeedTracker == null) {
			netSpeedTracker = NetSpeedTracker(initialBytesDownloaded = bytesDownloaded)
		}

		netSpeedTracker?.let {
			it.update(bytesDownloaded)
			val currentSpeed = it.getCurrentSpeed()

			downloadDataModel.realtimeSpeed = if (currentSpeed < 0) 0 else currentSpeed
			downloadDataModel.realtimeSpeedInFormat = if (currentSpeed < 0)
				formatDownloadSpeedInSimpleForm(0.0) else it.getFormattedSpeed()

			if (!downloadDataModel.isRunning) {
				downloadDataModel.realtimeSpeed = 0
				downloadDataModel.realtimeSpeedInFormat = "--"
			}
		}
	}

	@Synchronized
	private fun calculateMaxDownloadSpeed() {
		if (downloadDataModel.realtimeSpeed > downloadDataModel.maxSpeed) {
			downloadDataModel.maxSpeed = downloadDataModel.realtimeSpeed
			downloadDataModel.maxSpeedInFormat =
				formatDownloadSpeedInSimpleForm(downloadDataModel.maxSpeed.toDouble())
		}
	}

	@Synchronized
	private fun calculateTotalDownloadedTime() {
		if (!downloadDataModel.isWaitingForNetwork) {
			downloadDataModel.timeSpentInMilliSec += 200
		}

		val timeSpentInMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		downloadDataModel.timeSpentInFormat =
			calculateTime(timeSpentInMillis, getText(string.text_spent))
	}

	@Synchronized
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

	private suspend fun checkNetworkConnectionAndRetryDownload() {
		allDownloadParts.forEach { downloadPart -> downloadPart.verifyNetworkConnection() }
		if (downloadDataModel.isWaitingForNetwork) {
			if (isNetworkAvailable() && isInternetConnected()) {
				if (downloadSettings.downloadWifiOnly && !isWifiEnabled()) return
				downloadDataModel.isWaitingForNetwork = false
				updateDownloadStatus(getText(string.title_started_downloading))
				startAllDownloadThreads()
			}
		}
	}
}