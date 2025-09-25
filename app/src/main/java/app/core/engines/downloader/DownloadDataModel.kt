package app.core.engines.downloader

import android.content.Context.MODE_PRIVATE
import android.net.Uri
import androidx.core.net.toFile
import androidx.documentfile.provider.DocumentFile
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioDSLJsonInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.FSTBuilder.fstConfig
import app.core.engines.settings.AIOSettings
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoInfo
import com.aio.R.drawable
import com.aio.R.string
import com.anggrayudi.storage.file.getAbsolutePath
import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import lib.files.FileExtensions.ARCHIVE_EXTENSIONS
import lib.files.FileExtensions.DOCUMENT_EXTENSIONS
import lib.files.FileExtensions.IMAGE_EXTENSIONS
import lib.files.FileExtensions.MUSIC_EXTENSIONS
import lib.files.FileExtensions.PROGRAM_EXTENSIONS
import lib.files.FileExtensions.VIDEO_EXTENSIONS
import lib.files.FileSizeFormatter
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isWritableFile
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.process.CopyObjectUtils.deepCopy
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.texts.CommonTextUtils.removeDuplicateSlashes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable

/**
 * A comprehensive data model class representing a download item in the application.
 * This class holds all metadata and state information related to a download operation,
 * including progress tracking, status information, file details, and network parameters.
 *
 * The class implements Serializable to allow for persistence and transfer between components.
 */
@CompiledJson
class DownloadDataModel : Serializable {

	/** Unique identifier for the download task */
	@JsonAttribute(name = "id")
	var id: Int = 0

	/** Current operational status (see DownloadStatus constants) */
	@JsonAttribute(name = "status")
	var status: Int = DownloadStatus.CLOSE

	/** Indicates if the download process is currently active */
	@JsonAttribute(name = "isRunning")
	var isRunning: Boolean = false

	/** Indicates if the download completed successfully */
	@JsonAttribute(name = "isComplete")
	var isComplete: Boolean = false

	/** Indicates if the download was explicitly deleted by user or system */
	@JsonAttribute(name = "isDeleted")
	var isDeleted: Boolean = false

	/** Indicates if the download was removed from UI but may still exist in storage */
	@JsonAttribute(name = "isRemoved")
	var isRemoved: Boolean = false

	/** Flag indicating if file was saved to private/secure storage location */
	@JsonAttribute(name = "isWentToPrivateFolder")
	var isWentToPrivateFolder: Boolean = false

	/** Flag indicating if the source download URL has expired or become invalid */
	@JsonAttribute(name = "isFileUrlExpired")
	var isFileUrlExpired: Boolean = false

	/** Flag indicating if yt-dlp encountered processing issues during download */
	@JsonAttribute(name = "isYtdlpHavingProblem")
	var isYtdlpHavingProblem: Boolean = false

	/** Detailed error message from yt-dlp when processing issues occur */
	@JsonAttribute(name = "ytdlpProblemMsg")
	var ytdlpProblemMsg: String = ""

	/** Flag indicating if the expected destination file does not exist after download */
	@JsonAttribute(name = "isDestinationFileNotExisted")
	var isDestinationFileNotExisted: Boolean = false

	/** Flag indicating if file integrity check via checksum validation failed */
	@JsonAttribute(name = "isFileChecksumValidationFailed")
	var isFileChecksumValidationFailed: Boolean = false

	/** Flag indicating download is paused waiting for network connectivity */
	@JsonAttribute(name = "isWaitingForNetwork")
	var isWaitingForNetwork: Boolean = false

	/** Flag indicating failure to access or read from source file location */
	@JsonAttribute(name = "isFailedToAccessFile")
	var isFailedToAccessFile: Boolean = false

	/** Flag indicating if URL expiration dialog has been shown to user */
	@JsonAttribute(name = "isExpiredURLDialogShown")
	var isExpiredURLDialogShown: Boolean = false

	/** Flag indicating if automatic file categorization has been processed */
	@JsonAttribute(name = "isSmartCategoryDirProcessed")
	var isSmartCategoryDirProcessed: Boolean = false

	/** Message to display to user via dialog or notification */
	@JsonAttribute(name = "msgToShowUserViaDialog")
	var msgToShowUserViaDialog: String = ""

	/** Flag indicating if download was initiated from browser context */
	@JsonAttribute(name = "isDownloadFromBrowser")
	var isDownloadFromBrowser: Boolean = false

	/** Flag indicating if basic yt-dlp metadata extraction completed successfully */
	@JsonAttribute(name = "isBasicYtdlpModelInitialized")
	var isBasicYtdlpModelInitialized: Boolean = false

	/** Custom HTTP headers to include in download requests */
	@JsonAttribute(name = "additionalWebHeaders")
	var additionalWebHeaders: Map<String, String>? = null

	/** Name of the target file being downloaded */
	@JsonAttribute(name = "fileName")
	var fileName: String = ""

	/** Source URL from which the file is being downloaded */
	@JsonAttribute(name = "fileURL")
	var fileURL: String = ""

	/** HTTP Referrer header value for the download request */
	@JsonAttribute(name = "siteReferrer")
	var siteReferrer: String = ""

	/** Target directory path where file will be saved */
	@JsonAttribute(name = "fileDirectory")
	var fileDirectory: String = ""

	/** MIME type of the file being downloaded */
	@JsonAttribute(name = "fileMimeType")
	var fileMimeType: String = ""

	/** Content-Disposition header value from server response */
	@JsonAttribute(name = "fileContentDisposition")
	var fileContentDisposition: String = ""

	/** Cookie string for authenticated download requests */
	@JsonAttribute(name = "siteCookieString")
	var siteCookieString: String = ""

	/** Local filesystem path to the downloaded file's thumbnail */
	@JsonAttribute(name = "thumbPath")
	var thumbPath: String = ""

	/** Remote URL source for the file's thumbnail image */
	@JsonAttribute(name = "thumbnailUrl")
	var thumbnailUrl: String = ""

	/** Temporary file path used during yt-dlp processing phase */
	@JsonAttribute(name = "tempYtdlpDestinationFilePath")
	var tempYtdlpDestinationFilePath: String = ""

	/** Temporary status information during yt-dlp processing */
	@JsonAttribute(name = "tempYtdlpStatusInfo")
	var tempYtdlpStatusInfo: String = ""

	/** URI representation of the target directory location */
	@JsonAttribute(name = "fileDirectoryURI")
	var fileDirectoryURI: String = ""

	/** Automatically determined category name for the file */
	@JsonAttribute(name = "fileCategoryName")
	var fileCategoryName: String = ""

	/** Formatted timestamp string indicating download start time */
	@JsonAttribute(name = "startTimeDateInFormat")
	var startTimeDateInFormat: String = ""

	/** Unix timestamp in milliseconds indicating download start time */
	@JsonAttribute(name = "startTimeDate")
	var startTimeDate: Long = 0L

	/** Formatted timestamp string of last file modification time */
	@JsonAttribute(name = "lastModifiedTimeDateInFormat")
	var lastModifiedTimeDateInFormat: String = ""

	/** Unix timestamp in milliseconds of last file modification */
	@JsonAttribute(name = "lastModifiedTimeDate")
	var lastModifiedTimeDate: Long = 0L

	/** Flag indicating if file size could not be determined from source */
	@JsonAttribute(name = "isUnknownFileSize")
	var isUnknownFileSize: Boolean = false

	/** Total file size in bytes */
	@JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L

	/** Cryptographic hash/checksum for file integrity verification */
	@JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = "--"

	/** Human-readable formatted string representation of file size */
	@JsonAttribute(name = "fileSizeInFormat")
	var fileSizeInFormat: String = ""

	/** Average download speed in bytes per second */
	@JsonAttribute(name = "averageSpeed")
	var averageSpeed: Long = 0L

	/** Maximum achieved download speed in bytes per second */
	@JsonAttribute(name = "maxSpeed")
	var maxSpeed: Long = 0L

	/** Current real-time download speed in bytes per second */
	@JsonAttribute(name = "realtimeSpeed")
	var realtimeSpeed: Long = 0L

	/** Formatted string representation of average download speed */
	@JsonAttribute(name = "averageSpeedInFormat")
	var averageSpeedInFormat: String = "--"

	/** Formatted string representation of maximum download speed */
	@JsonAttribute(name = "maxSpeedInFormat")
	var maxSpeedInFormat: String = "--"

	/** Formatted string representation of current download speed */
	@JsonAttribute(name = "realtimeSpeedInFormat")
	var realtimeSpeedInFormat: String = "--"

	/** Flag indicating if the download supports resumption after interruption */
	@JsonAttribute(name = "isResumeSupported")
	var isResumeSupported: Boolean = false

	/** Flag indicating if multi-threaded downloading is supported for this file */
	@JsonAttribute(name = "isMultiThreadSupported")
	var isMultiThreadSupported: Boolean = false

	/** Total number of connection retry attempts made */
	@JsonAttribute(name = "totalConnectionRetries")
	var totalConnectionRetries: Int = 0

	/** Total number of connection retries that weren't reset */
	@JsonAttribute(name = "totalUnresetConnectionRetries")
	var totalUnresetConnectionRetries: Int = 0

	/** Total number of connection retries that were tracked */
	@JsonAttribute(name = "totalTrackedConnectionRetries")
	var totalTrackedConnectionRetries: Int = 0

	/** Download completion percentage (0-100) */
	@JsonAttribute(name = "progressPercentage")
	var progressPercentage: Long = 0L

	/** Formatted string representation of completion percentage */
	@JsonAttribute(name = "progressPercentageInFormat")
	var progressPercentageInFormat: String = ""

	/** Total number of bytes downloaded so far */
	@JsonAttribute(name = "downloadedByte")
	var downloadedByte: Long = 0L

	/** Formatted string representation of downloaded bytes */
	@JsonAttribute(name = "downloadedByteInFormat")
	var downloadedByteInFormat: String = "--"

	/** Array tracking starting byte positions for each download chunk (18 chunks max) */
	@JsonAttribute(name = "partStartingPoint")
	var partStartingPoint: LongArray = LongArray(18)

	/** Array tracking ending byte positions for each download chunk (18 chunks max) */
	@JsonAttribute(name = "partEndingPoint")
	var partEndingPoint: LongArray = LongArray(18)

	/** Array tracking total size of each download chunk (18 chunks max) */
	@JsonAttribute(name = "partChunkSizes")
	var partChunkSizes: LongArray = LongArray(18)

	/** Array tracking bytes downloaded for each chunk (18 chunks max) */
	@JsonAttribute(name = "partsDownloadedByte")
	var partsDownloadedByte: LongArray = LongArray(18)

	/** Array tracking completion percentage for each download chunk (18 chunks max) */
	@JsonAttribute(name = "partProgressPercentage")
	var partProgressPercentage: IntArray = IntArray(18)

	/** Total time spent on download in milliseconds */
	@JsonAttribute(name = "timeSpentInMilliSec")
	var timeSpentInMilliSec: Long = 0L

	/** Estimated remaining time to complete download in seconds */
	@JsonAttribute(name = "remainingTimeInSec")
	var remainingTimeInSec: Long = 0L

	/** Formatted string representation of time spent downloading */
	@JsonAttribute(name = "timeSpentInFormat")
	var timeSpentInFormat: String = "--"

	/** Formatted string representation of estimated remaining time */
	@JsonAttribute(name = "remainingTimeInFormat")
	var remainingTimeInFormat: String = "--"

	/** Current status message for display purposes */
	@JsonAttribute(name = "statusInfo")
	var statusInfo: String = "--"

	/** Video-specific metadata for media downloads */
	@JsonAttribute(name = "videoInfo")
	var videoInfo: VideoInfo? = null

	/** Video format and codec information */
	@JsonAttribute(name = "videoFormat")
	var videoFormat: VideoFormat? = null

	/** Command string used to execute the download process */
	@JsonAttribute(name = "executionCommand")
	var executionCommand: String = ""

	/** Playback duration string for media files (e.g., "02:30" for 2 minutes 30 seconds) */
	@JsonAttribute(name = "mediaFilePlaybackDuration")
	var mediaFilePlaybackDuration: String = ""

	/** Snapshot of application settings at the time download was initiated */
	@JsonAttribute(name = "globalSettings")
	lateinit var globalSettings: AIOSettings

	companion object {
		@Transient
		var logger = LogHelperUtils.from(DownloadDataModel::class.java)

		// Constants for file naming and storage
		const val DOWNLOAD_MODEL_ID_KEY = "DOWNLOAD_MODEL_ID_KEY"
		const val DOWNLOAD_MODEL_FILE_JSON_EXTENSION = "_download.json"
		const val DOWNLOAD_MODEL_FILE_BINARY_EXTENSION: String = "_download.dat"
		const val DOWNLOAD_MODEL_COOKIES_EXTENSION = "_cookies.txt"
		const val THUMB_EXTENSION = "_download.jpg"
		const val TEMP_EXTENSION = ".aio_download"

		/**
		 * Converts a JSON string back into a DownloadDataModel instance.
		 * @param downloadDataModelJSONFile The JSON file containing the download model
		 * @return Deserialized DownloadDataModel or null if conversion failed
		 */
		fun convertJSONStringToClass(downloadDataModelJSONFile: File): DownloadDataModel? {
			logger.d("Starting JSON to class conversion for file: ${downloadDataModelJSONFile.absolutePath}")
			val internalDir = AIOApp.internalDataFolder
			val downloadDataModelBinaryFileName = "${downloadDataModelJSONFile.nameWithoutExtension}.dat"
			val downloadDataModelBinaryFile = internalDir.findFile(downloadDataModelBinaryFileName)

			try {
				var downloadDataModel: DownloadDataModel? = null
				var isBinaryFileValid = false

				if (downloadDataModelBinaryFile != null && downloadDataModelBinaryFile.exists()) {
					logger.d("Found binary download model file: ${downloadDataModelBinaryFile.name}")
					val absolutePath = downloadDataModelBinaryFile.getAbsolutePath(INSTANCE)

					logger.d("Attempting to load binary from: $absolutePath")
					val objectInMemory = loadFromBinary(File(absolutePath))

					if (objectInMemory != null) {
						logger.d("Binary load successful for file: ${downloadDataModelBinaryFile.name}")
						downloadDataModel = objectInMemory
						downloadDataModel.updateInStorage()
						isBinaryFileValid = true
					} else {
						logger.d("Binary load failed for file: ${downloadDataModelBinaryFile.name}")
					}
				}

				if (!isBinaryFileValid || downloadDataModel == null) {
					logger.d("Attempting JSON load for file: ${downloadDataModelJSONFile.name}")
					val jsonString = downloadDataModelJSONFile.readText(Charsets.UTF_8)

					logger.d("JSON content length: ${jsonString.length} chars")
					val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
					downloadDataModel = aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)

					if (downloadDataModel != null) {
						logger.d("JSON load successful for file: ${downloadDataModelJSONFile.name}")
						downloadDataModel.updateInStorage()
					} else {
						logger.e("Failed to parse JSON for file: ${downloadDataModelJSONFile.name}")
					}
				}

				return downloadDataModel
			} catch (error: Exception) {
				logger.e("Error in conversion: ${error.message}", error)
				try {
					downloadDataModelBinaryFile?.delete()
					logger.d("Deleted potentially corrupted binary file")
				} catch (error: Exception) {
					logger.e("Failed to delete binary file", error)
				}
				return null
			}
		}

		/**
		 * Loads download model from binary file.
		 * @param downloadDataModelBinaryFile The binary file to load from
		 * @return Loaded DownloadDataModel or null if failed
		 */
		private fun loadFromBinary(downloadDataModelBinaryFile: File): DownloadDataModel? {
			logger.d("Starting binary load from: ${downloadDataModelBinaryFile.absolutePath}")
			if (!downloadDataModelBinaryFile.exists()) {
				logger.d("Binary file not found at: ${downloadDataModelBinaryFile.absolutePath}")
				return null
			}

			return try {
				logger.d("Reading binary file content")
				val bytes = downloadDataModelBinaryFile.readBytes()
				logger.d("Binary file size: ${bytes.size} bytes")
				val result = fstConfig.asObject(bytes).apply {
					logger.d("Binary deserialization completed")
				} as DownloadDataModel
				logger.d("Binary load successful")
				result
			} catch (error: Exception) {
				logger.e("Binary load error: ${error.message}", error)
				try {
					downloadDataModelBinaryFile.delete()
					logger.d("Deleted corrupted binary file")
				} catch (error: Exception) {
					logger.e("Failed to delete corrupted binary file", error)
				}
				null
			}
		}
	}

	init {
		logger.d("Initializing new DownloadDataModel")
		resetToDefaultValues()
	}

	/**
	 * Persists the current state to storage.
	 * Handles both binary and JSON formats.
	 */
	@Synchronized
	fun updateInStorage() {
		logger.d("Starting storage update for download ID: $id")
		ThreadsUtility.executeInBackground(codeBlock = {
			if (fileName.isEmpty() && fileURL.isEmpty()) {
				logger.d("Empty filename and URL, skipping update")
				return@executeInBackground
			}

			logger.d("Saving cookies and cleaning model before storage")
			saveCookiesIfAvailable()
			cleanTheModelBeforeSavingToStorage()

			logger.d("Saving to binary format")
			saveToBinary("$id$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")

			logger.d("Saving to JSON format")
			val json = convertClassToJSON()
			logger.d("JSON content length: ${json.length} chars")

			saveStringToInternalStorage("$id$DOWNLOAD_MODEL_FILE_JSON_EXTENSION", json)
			logger.d("Storage update completed for download ID: $id")
		}, errorHandler = { error ->
			logger.e("Storage update failed for download ID: $id", error)
		})
	}

	/**
	 * Saves model to binary format.
	 * @param fileName Target filename
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving to binary file: $fileName")
			val internalDir = AIOApp.internalDataFolder
			val modelBinaryFile = internalDir.findFile("$id$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")

			if (isWritableFile(modelBinaryFile)) {
				modelBinaryFile?.delete()?.let { isDeletedSuccessful ->
					if (isDeletedSuccessful) logger.d("Deleted existing binary file successfully")
					else logger.d("Failed to delete existing binary file")
				}
			}

			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				logger.d("Serialized binary size: ${bytes.size} bytes")
				fos.write(bytes)
				logger.d("Binary save successful for file: $fileName")
			}
		} catch (error: Exception) {
			logger.e("Binary save error for file: $fileName", error)
		}
	}

	/**
	 * Deletes all files associated with this download model from disk.
	 * Includes the model file, cookies, thumbnails, and temporary files.
	 */
	@Synchronized
	fun deleteModelFromDisk() {
		logger.d("Starting model deletion for download ID: $id")
		ThreadsUtility.executeInBackground(codeBlock = {
			val internalDir = AIOApp.internalDataFolder
			val modelJsonFile = internalDir.findFile("$id$DOWNLOAD_MODEL_FILE_JSON_EXTENSION")
			val modelBinaryFile = internalDir.findFile("$id$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")
			val cookieFile = internalDir.findFile("$id$DOWNLOAD_MODEL_COOKIES_EXTENSION")
			val thumbFile = internalDir.findFile("$id$THUMB_EXTENSION")

			logger.d("Deleting JSON file")
			isWritableFile(modelJsonFile).let {
				if (it) modelJsonFile?.delete()?.let { logger.d("Deleted JSON file successfully") }
			}

			logger.d("Deleting binary file")
			isWritableFile(modelBinaryFile).let {
				if (it) modelBinaryFile?.delete()?.let { logger.d("Deleted binary file successfully") }
			}

			logger.d("Deleting thumbnail file")
			isWritableFile(thumbFile).let {
				if (it) thumbFile?.delete()?.let { logger.d("Deleted thumbnail file successfully") }
			}

			logger.d("Deleting cookies file")
			isWritableFile(cookieFile).let {
				if (it) cookieFile?.delete()?.let { logger.d("Deleted cookies file successfully") }
			}

			logger.d("Deleting temporary files")
			deleteAllTempDownloadedFiles(internalDir)

			if (globalSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
				logger.d("Deleting downloaded file from private folder")
				val downloadedFile = getDestinationDocumentFile()
				isWritableFile(downloadedFile).let { isDeletedSuccessful ->
					if (isDeletedSuccessful) downloadedFile.delete().let {
						logger.d("Deleted downloaded file successfully")
					}
				}
			}
			logger.d("Model deletion completed for download ID: $id")
		}, errorHandler = { error ->
			logger.e("Deletion error for download ID: $id", error)
		})
	}

	/**
	 * Retrieves the path to the cookies file if available.
	 * @return Absolute path to cookies file or null if no cookies exist
	 */
	fun getCookieFilePathIfAvailable(): String? {
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies available for download ID: $id")
			return null
		}
		val cookieFileName = "$id$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)
		return if (cookieFile != null && cookieFile.exists()) {
			logger.d("Found cookies file for download ID: $id")
			cookieFile.getAbsolutePath(INSTANCE)
		} else {
			logger.d("No cookies file found for download ID: $id")
			null
		}
	}

	/**
	 * Saves cookies to disk in Netscape format if they exist.
	 * @param shouldOverride Whether to overwrite existing cookie file
	 */
	fun saveCookiesIfAvailable(shouldOverride: Boolean = false) {
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies to save for download ID: $id")
			return
		}
		val cookieFileName = "$id$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)
		if (!shouldOverride && cookieFile != null && cookieFile.exists()) {
			logger.d("Cookies file already exists and override not requested for download ID: $id")
			return
		}
		logger.d("Saving cookies for download ID: $id")
		saveStringToInternalStorage(
			fileName = cookieFileName,
			data = generateNetscapeFormattedCookieString(siteCookieString)
		)
		logger.d("Cookies saved successfully for download ID: $id")
	}

	/**
	 * Converts cookie string to Netscape formatted file content.
	 * @param cookieString Raw cookie string from HTTP headers
	 * @return Formatted cookie file content
	 */
	private fun generateNetscapeFormattedCookieString(cookieString: String): String {
		logger.d("Generating Netscape formatted cookie string")
		val cookies = cookieString.split(";").map { it.trim() }
		val domain = ""
		val path = "/"
		val secure = "FALSE"
		val expiry = "2147483647"

		val stringBuilder = StringBuilder()
		stringBuilder.append("# Netscape HTTP Cookie File\n")
		stringBuilder.append("# This file was generated by the app.\n\n")

		for (cookie in cookies) {
			val parts = cookie.split("=", limit = 2)
			if (parts.size == 2) {
				val name = parts[0].trim()
				val value = parts[1].trim()
				stringBuilder.append("$domain\tFALSE\t$path\t$secure\t$expiry\t$name\t$value\n")
			}
		}
		logger.d("Generated Netscape cookie string with ${cookies.size} cookies")
		return stringBuilder.toString()
	}

	/**
	 * Serializes the model to JSON string.
	 * @return JSON representation of the model
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting class to JSON for download ID: $id")
		val outputStream = ByteArrayOutputStream()
		aioDSLJsonInstance.serialize(this, outputStream) // write to stream
		return outputStream.toByteArray().decodeToString() // convert to String
	}

	/**
	 * Converts a JSON string to an DownloadDataModel object.
	 * @param jsonString The JSON string to convert
	 * @return The deserialized DownloadDataModel object
	 */
	private fun convertJSONStringToClass(jsonString: String): DownloadDataModel? {
		logger.d("Converting JSON to download data model object")
		val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
		return aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)
	}

	/**
	 * Gets the temporary directory for partial downloads.
	 * @return File object representing temp directory
	 */
	fun getTempDestinationDir(): File {
		logger.d("Getting temp destination directory for download ID: $id")
		return File("${fileDirectory}.temp/")
	}

	/**
	 * Gets the destination file as a DocumentFile.
	 * @return DocumentFile representing the download target
	 */
	fun getDestinationDocumentFile(): DocumentFile {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination DocumentFile for path: $destinationPath")
		return DocumentFile.fromFile(File(destinationPath!!))
	}

	/**
	 * Gets the destination file as a regular File.
	 * @return File object representing the download target
	 */
	fun getDestinationFile(): File {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination File for path: $destinationPath")
		return File(destinationPath!!)
	}

	/**
	 * Gets the temporary download file (in-progress download).
	 * @return File object for the temporary download file
	 */
	fun getTempDestinationFile(): File {
		val tempFilePath = "${getDestinationFile().absolutePath}${TEMP_EXTENSION}"
		logger.d("Getting temp destination file: $tempFilePath")
		return File(tempFilePath)
	}

	/**
	 * Gets the URI of the thumbnail image if available.
	 * @return Uri of thumbnail or null if not available
	 */
	fun getThumbnailURI(): Uri? {
		val thumbFilePath = "$id$THUMB_EXTENSION"
		logger.d("Getting thumbnail URI for file: $thumbFilePath")
		return AIOApp.internalDataFolder.findFile(thumbFilePath)?.uri
	}

	/**
	 * Clears any cached thumbnail file and updates storage.
	 */
	fun clearCachedThumbnailFile() {
		logger.d("Clearing cached thumbnail for download ID: $id")
		try {
			val thumbnailUri = getThumbnailURI()
			if (thumbnailUri != null) {
				thumbnailUri.toFile().delete()
				logger.d("Deleted thumbnail file successfully")
			} else {
				logger.d("No thumbnail file to delete")
			}
			thumbPath = ""
			updateInStorage()
			logger.d("Thumbnail cleared successfully for download ID: $id")
		} catch (error: Exception) {
			logger.e("Error clearing thumbnail for download ID: $id", error)
		}
	}

	/**
	 * Gets the default thumbnail drawable resource ID.
	 * @return Resource ID of default thumbnail drawable
	 */
	fun getThumbnailDrawableID(): Int {
		logger.d("Getting default thumbnail drawable ID")
		return drawable.image_no_thumb_available
	}

	/**
	 * Generates a formatted string with download status information.
	 * @return Human-readable status string
	 */
	fun generateDownloadInfoInString(): String {
		logger.d("Generating download info string for download ID: $id")
		if (videoFormat != null && videoInfo != null) {
			return if (status == DownloadStatus.CLOSE) {
				val waitingToJoin = getText(string.title_waiting_to_join).lowercase()
				val preparingToDownload = getText(string.title_preparing_download).lowercase()
				val downloadFailed = getText(string.title_download_io_failed).lowercase()
				if (statusInfo.lowercase().startsWith(waitingToJoin) ||
					statusInfo.lowercase().startsWith(preparingToDownload) ||
					statusInfo.lowercase().startsWith(downloadFailed)
				) {
					logger.d("Returning special status info")
					statusInfo
				} else normalDownloadStatusInfo()
			} else {
				val currentStatus = getText(string.title_started_downloading).lowercase()
				if (!statusInfo.lowercase().startsWith(currentStatus)) {
					logger.d("Returning normal download status info")
					normalDownloadStatusInfo()
				} else {
					logger.d("Returning yt-dlp status info")
					tempYtdlpStatusInfo
				}
			}
		} else {
			logger.d("Returning normal download status info (non-video)")
			return normalDownloadStatusInfo()
		}
	}

	/**
	 * Determines the appropriate category name for the file.
	 * @param shouldRemoveAIOPrefix Whether to exclude "AIO" prefix from category name
	 * @return Localized category name string
	 */
	fun getUpdatedCategoryName(shouldRemoveAIOPrefix: Boolean = false): String {
		logger.d("Getting updated category name for file: $fileName")
		if (shouldRemoveAIOPrefix) {
			val categoryName = when {
				endsWithExtension(fileName, IMAGE_EXTENSIONS) -> getText(string.title_images)
				endsWithExtension(fileName, VIDEO_EXTENSIONS) -> getText(string.title_videos)
				endsWithExtension(fileName, MUSIC_EXTENSIONS) -> getText(string.title_sounds)
				endsWithExtension(fileName, DOCUMENT_EXTENSIONS) -> getText(string.title_documents)
				endsWithExtension(fileName, PROGRAM_EXTENSIONS) -> getText(string.title_programs)
				endsWithExtension(fileName, ARCHIVE_EXTENSIONS) -> getText(string.title_archives)
				else -> getText(string.title_aio_others)
			}
			logger.d("Category name (no prefix): $categoryName")
			return categoryName
		} else {
			val categoryName = when {
				endsWithExtension(fileName, IMAGE_EXTENSIONS) -> getText(string.title_aio_images)
				endsWithExtension(fileName, VIDEO_EXTENSIONS) -> getText(string.title_aio_videos)
				endsWithExtension(fileName, MUSIC_EXTENSIONS) -> getText(string.title_aio_sounds)
				endsWithExtension(fileName, DOCUMENT_EXTENSIONS) -> getText(string.title_aio_documents)
				endsWithExtension(fileName, PROGRAM_EXTENSIONS) -> getText(string.title_aio_programs)
				endsWithExtension(fileName, ARCHIVE_EXTENSIONS) -> getText(string.title_aio_archives)
				else -> getText(string.title_aio_others)
			}
			logger.d("Category name (with prefix): $categoryName")
			return categoryName
		}
	}

	/**
	 * Gets formatted file size string.
	 * @return Human-readable size string or "Unknown" if size not available
	 */
	fun getFormattedFileSize(): String {
		logger.d("Getting formatted file size for download ID: $id")
		return if (fileSize <= 1 || isUnknownFileSize) {
			logger.d("File size unknown")
			getText(string.title_unknown_size)
		} else {
			val formattedSize = FileSizeFormatter.humanReadableSizeOf(fileSize.toDouble())
			logger.d("Formatted file size: $formattedSize")
			formattedSize
		}
	}

	/**
	 * Extracts file extension from filename.
	 * @return File extension (without dot) or empty string if no extension
	 */
	fun getFileExtension(): String {
		val extension = fileName.substringAfterLast('.', "")
		logger.d("File extension for $fileName: $extension")
		return extension
	}

	/**
	 * Deletes all temporary files associated with this download.
	 * @param internalDir The directory containing temporary files
	 */
	private fun deleteAllTempDownloadedFiles(internalDir: DocumentFile) {
		logger.d("Deleting all temp files for download ID: $id")
		try {
			if (videoFormat != null && videoInfo != null) {
				if (tempYtdlpDestinationFilePath.isNotEmpty()) {
					val tempYtdlpFileName = File(tempYtdlpDestinationFilePath).name
					logger.d("Processing yt-dlp temp files with prefix: $tempYtdlpFileName")
					internalDir.listFiles().forEach { file ->
						try {
							file?.let {
								if (!file.isFile) return@let
								if (file.name!!.startsWith(tempYtdlpFileName)) {
									file.delete()
									logger.d("Deleted temp file: ${file.name}")
								}
							}
						} catch (error: Exception) {
							logger.e("Error deleting temp file", error)
						}
					}
				}

				if (videoInfo!!.videoCookieTempPath.isNotEmpty()) {
					val tempCookieFile = File(videoInfo!!.videoCookieTempPath)
					if (tempCookieFile.isFile && tempCookieFile.exists()) {
						tempCookieFile.delete()
						logger.d("Deleted temp cookie file: ${tempCookieFile.absolutePath}")
					}
				}
			}
			logger.d("Temp files deletion completed for download ID: $id")
		} catch (error: Exception) {
			logger.e("Error deleting temp files for download ID: $id", error)
		}
	}

	/**
	 * Generates standard download status information string.
	 * @return Formatted status string with progress, speed, and time remaining
	 */
	private fun normalDownloadStatusInfo(): String {
		logger.d("Generating normal download status info for download ID: $id")
		if (videoFormat != null && videoInfo != null) {
			val textDownload = getText(string.title_downloaded)
			val infoString = "$statusInfo  |  $textDownload ($progressPercentage%)" +
					"  |  --/s  |  --:-- "
			logger.d("Generated video download status: $infoString")
			return infoString
		} else {
			val totalFileSize = fileSizeInFormat
			val downloadSpeedInfo = if (status == DownloadStatus.CLOSE) "--/s"
			else realtimeSpeedInFormat

			val remainingTimeInfo = if (status == DownloadStatus.CLOSE ||
				isWaitingForNetwork
			) "--:--" else remainingTimeInFormat

			val downloadingStatus = getText(string.title_started_downloading).lowercase()
			val result = if (statusInfo.lowercase().startsWith(downloadingStatus)) {
				"$progressPercentageInFormat% Of $totalFileSize | " +
						"$downloadSpeedInfo | $remainingTimeInfo"
			} else {
				"$statusInfo | $totalFileSize | " +
						"$downloadSpeedInfo | $remainingTimeInfo"
			}
			logger.d("Generated normal download status: $result")
			return result
		}
	}

	/**
	 * Resets all fields to default values.
	 * Initializes based on app settings.
	 */
	private fun resetToDefaultValues() {
		logger.d("Resetting to default values for new download")
		id = getUniqueNumberForDownloadModels()
		logger.d("Assigned new download ID: $id")

		if (aioSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
			val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
			if (!externalDataFolderPath.isNullOrEmpty()) {
				fileDirectory = externalDataFolderPath
				logger.d("Set file directory to external: $externalDataFolderPath")
			} else {
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath
				fileDirectory = internalDataFolderPath
				logger.d("Set file directory to internal: $internalDataFolderPath")
			}
		} else if (aioSettings.defaultDownloadLocation == SYSTEM_GALLERY) {
			val externalDataFolderPath = getText(string.text_default_aio_download_folder_path)
			fileDirectory = externalDataFolderPath
			logger.d("Set file directory to system gallery: $externalDataFolderPath")
		}

		globalSettings = deepCopy(aioSettings) ?: aioSettings
		logger.d("Reset completed for download ID: $id with settings: ${globalSettings.defaultDownloadLocation}")
	}

	/**
	 * Cleans model before persistence.
	 * Ensures completed downloads show 100% progress.
	 */
	private fun cleanTheModelBeforeSavingToStorage() {
		logger.d("Cleaning model before saving to storage for download ID: $id")
		if (isRunning && status == DownloadStatus.DOWNLOADING) {
			logger.d("Download is running, skipping cleanup")
			return
		}

		realtimeSpeed = 0L
		realtimeSpeedInFormat = "--"

		if (isComplete && status == DownloadStatus.COMPLETE) {
			remainingTimeInSec = 0
			remainingTimeInFormat = "--:--"
			progressPercentage = 100L
			progressPercentageInFormat = getText(string.title_100_percentage)
			downloadedByte = fileSize
			downloadedByteInFormat = getHumanReadableFormat(downloadedByte)

			partProgressPercentage.forEachIndexed { index, _ ->
				partProgressPercentage[index] = 100
				partsDownloadedByte[index] = partChunkSizes[index]
			}
			logger.d("Model cleaned for completed download ID: $id")
		}
	}
}