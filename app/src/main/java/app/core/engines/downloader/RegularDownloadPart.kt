package app.core.engines.downloader

import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtilityKT
import lib.networks.URLUtilityKT.extractHostUrl
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

open class RegularDownloadPart(private val regularDownloader: RegularDownloader) {

	private val logger = LogHelperUtils.from(javaClass)
	private var partStartPoint: Long = 0
	private var partEndingPoint: Long = 0
	private var isPartDownloadCanceled: Boolean = false

	open var isPartCanceledByUser: Boolean = false
	open var partIndex: Int = 0
	open var partChunkSize: Long = 0
	open var partDownloadedByte: Long = 0
	open var partDownloadErrorException: Exception? = null
	open var partDownloadStatus: Int = CLOSE

	private val downloadDataModel = regularDownloader.downloadDataModel
	private val downloadGlobalSettings = downloadDataModel.globalSettings
	private val partStatusDownloadPartListener: DownloadPartListener = regularDownloader

	fun initiate(
		downloadPartIndex: Int,
		downloadStartingPoint: Long,
		downloadEndingPoint: Long,
		downloadChunkSize: Long,
		downloadedByte: Long
	) {
		this.partIndex = downloadPartIndex
		this.partStartPoint = downloadStartingPoint
		this.partEndingPoint = downloadEndingPoint
		this.partChunkSize = downloadChunkSize
		this.partDownloadedByte = downloadedByte
	}

	fun startDownload() {
		executeInBackground(codeBlock = {
			try {
				prepareForDownloading().let { isReadyToDownload ->
					if (isReadyToDownload) tryDownloading()
				}
			} catch (error: Exception) {
				logger.e("Error while trying to start download in download part:", error)
				cancelDownload()
			}
		})
	}

	fun stopDownloadSilently(isCanceledByUser: Boolean = false) {
		isPartCanceledByUser = isCanceledByUser
		isPartDownloadCanceled = true
	}

	fun verifyNetworkConnection(): Boolean {
		val isWifiOnly = downloadGlobalSettings.downloadWifiOnly
		if (!isNetworkAvailable() || (isWifiOnly && !isWifiEnabled())) {
			cancelDownload()
			return false
		}
		return true
	}

	fun isInternetConnected(): Boolean = URLUtilityKT.isInternetConnected()

	private fun cancelDownload() {
		isPartDownloadCanceled = true
		updateDownloadPartStatus(status = CLOSE)
	}

	private fun prepareForDownloading(): Boolean {
		isPartDownloadCanceled = false
		isPartCanceledByUser = false
		val isNetworkReady = verifyNetworkConnection()
		return isNetworkReady
	}

	private suspend fun tryDownloading() {
		if (isPartCompleted()) {
			updateDownloadPartStatus(status = COMPLETE)
			return
		}

		isPartReadyToDownload().let { isPartReadyToDownload ->
			if (isPartReadyToDownload) downloadFromServer()
		}
	}

	private fun isPartCompleted(): Boolean {
		val isCompleted = partDownloadedByte >= partChunkSize
		return isCompleted
	}

	private fun isPartReadyToDownload(): Boolean {
		val isReady = !isPartDownloadCanceled && partDownloadStatus != COMPLETE
		return isReady
	}

	private fun isSingleThreaded(): Boolean {
		return regularDownloader.downloadDataModel.isUnknownFileSize
				|| regularDownloader.downloadDataModel
			.globalSettings.downloadDefaultThreadConnections == 1
	}

	private fun updateDownloadPartStatus(status: Int) {
		partDownloadStatus = status
		when (status) {
			CLOSE -> partStatusDownloadPartListener.onPartCanceled(this)
			COMPLETE -> partStatusDownloadPartListener.onPartCompleted(this)
		}
	}

	private suspend fun downloadFromServer() {
		lateinit var urlConnection: HttpsURLConnection
		lateinit var inputStream: InputStream
		lateinit var fileURL: URL

		try {
			val downloadDataModel = regularDownloader.downloadDataModel
			withContext(Dispatchers.IO) {
				val destinationFile = downloadDataModel.getDestinationFile()
				if (isSingleThreaded() && !destinationFile.exists()) {
					RandomAccessFile(destinationFile, "rw").setLength(0)
				}

				val randomAccessFile = RandomAccessFile(destinationFile, "rw")
				val fileOutputPosition = calculateFileOutputPosition(randomAccessFile)
				randomAccessFile.seek(fileOutputPosition)

				fileURL = URL(downloadDataModel.fileURL)
				urlConnection = fileURL.openConnection() as HttpsURLConnection

				val fileByteRange: String = calculateRange()
				configureConnection(urlConnection, fileByteRange)
				urlConnection.connect()
				inputStream = urlConnection.inputStream

				val bufferSize = downloadDataModel.globalSettings.downloadBufferSize
				val buffer = ByteArray(bufferSize)
				var fetchedBytes = 0
				val startTime = System.currentTimeMillis()

				while (
					!isPartDownloadCanceled && destinationFile.exists() &&
					inputStream.read(buffer).also { fetchedBytes = it } != -1
				) {
					limitDownloadSpeed(startTime, fetchedBytes)
					if (isSingleThreaded()) {
						randomAccessFile.write(buffer, 0, fetchedBytes)
						partDownloadedByte += fetchedBytes
					} else {
						val bytesToWrite = minOf(fetchedBytes.toLong(), getRemainingByteToWrite())
						randomAccessFile.write(buffer, 0, bytesToWrite.toInt())
						partDownloadedByte += bytesToWrite
						if (partDownloadedByte >= partChunkSize) break
					}
				}

				inputStream.close()
				urlConnection.disconnect()
				randomAccessFile.close()

				if (!destinationFile.exists()) {
					partDownloadErrorException = Exception("Destination file is not found")
					cancelDownload()
					return@withContext
				}

				if (!isPartDownloadCanceled) {
					updateDownloadPartStatus(COMPLETE)
				}
			}
		} catch (error: Exception) {
			logger.e("Error while downloading file in download-part:", error)
			partDownloadErrorException = error
			cancelDownload()
		}
	}

	private suspend fun limitDownloadSpeed(startTime: Long, fetchedBytes: Int) {
		val elapsedTime = System.currentTimeMillis() - startTime
		val speedLimit = downloadDataModel.globalSettings.downloadMaxNetworkSpeed
		if (speedLimit > 0) {
			val expectedTime = (fetchedBytes * 1000L) / speedLimit
			if (elapsedTime < expectedTime) {
				delay(expectedTime - elapsedTime)
			}
		}
	}

	private fun getRemainingByteToWrite(): Long = partChunkSize - partDownloadedByte

	private fun calculateFileOutputPosition(file: RandomAccessFile): Long {
		return if (isResumeNotSupported()) resetDataModelForSingleThread(file)
		else partStartPoint + partDownloadedByte
	}

	private fun resetDataModelForSingleThread(file: RandomAccessFile): Long {
		partDownloadedByte = 0
		downloadDataModel.partsDownloadedByte[partIndex] = 0
		downloadDataModel.partStartingPoint[partIndex] = 0
		downloadDataModel.partProgressPercentage[partIndex] = 0
		file.setLength(partDownloadedByte)
		return partDownloadedByte
	}

	private fun isResumeNotSupported(): Boolean {
		return !regularDownloader.downloadDataModel.isResumeSupported
	}

	private fun configureConnection(urlConnection: HttpsURLConnection, range: String) {
		val settings = regularDownloader.downloadDataModel.globalSettings
		urlConnection.instanceFollowRedirects = true
		urlConnection.useCaches = false
		urlConnection.setRequestProperty("Accept", "*/*")
		urlConnection.setRequestProperty("Range", range)
		urlConnection.setRequestProperty(
			"User-Agent",
			settings.downloadHttpUserAgent.ifEmpty { settings.browserHttpUserAgent }
		)

		if (downloadDataModel.isDownloadFromBrowser) {
			with(downloadDataModel) {
				URI(fileURL).host?.let { urlConnection.setRequestProperty("Host", it) }
				siteReferrer.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Referer", extractHostUrl(it))
				}
				fileContentDisposition.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Content-Disposition", it)
				}
				siteCookieString.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Cookie", it)
				}
				urlConnection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
				urlConnection.setRequestProperty("Sec-Fetch-Dest", "document")
				urlConnection.setRequestProperty("Sec-Fetch-Mode", "navigate")
			}
		}

		urlConnection.setReadTimeout(settings.downloadMaxHttpReadingTimeout)
		urlConnection.setConnectTimeout(settings.downloadMaxHttpReadingTimeout)
	}

	private fun calculateRange(): String {
		return if (isSingleThreaded()) {
			"bytes=$partDownloadedByte-"

		} else if (downloadDataModel.isMultiThreadSupported) {
			"bytes=${partStartPoint + partDownloadedByte}-${partEndingPoint}"

		} else {
			"bytes=${partStartPoint + partDownloadedByte}-"
		}
	}

	private suspend fun verifyRangeSupport(): Boolean {
		return withContext(Dispatchers.IO) {
			try {
				val urlConnection = URL(downloadDataModel.fileURL).openConnection() as HttpsURLConnection
				urlConnection.requestMethod = "HEAD"
				urlConnection.connect()
				val acceptsRanges = urlConnection.getHeaderField("Accept-Ranges") == "bytes"
				urlConnection.disconnect()
				acceptsRanges
			} catch (error: Exception) {
				logger.e("Error in verifying range support of remote file:", error)
				false
			}
		}
	}

	interface DownloadPartListener {
		fun onPartCanceled(downloadPart: RegularDownloadPart)
		fun onPartCompleted(downloadPart: RegularDownloadPart)
	}
}