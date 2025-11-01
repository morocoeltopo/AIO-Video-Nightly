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
import app.core.engines.downloader.DownloadModelBinaryMerger.Companion.MERGRED_DATA_MODEL_BINARY_FILENAME
import app.core.engines.settings.AIOSettings
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoInfo
import com.aio.R.drawable
import com.aio.R.string
import com.anggrayudi.storage.file.getAbsolutePath
import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
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
 * Main data model class representing a download task with comprehensive tracking and metadata.
 *
 * This class serves as the central entity for managing download operations, storing both
 * persistent state and runtime information. It supports serialization/deserialization via
 * JSON and binary formats, and integrates with ObjectBox for database persistence.
 *
 * The model tracks download progress, network statistics, file metadata, and various
 * state flags to manage the complete lifecycle of a download operation.
 */
@CompiledJson
@Entity
class DownloadDataModel : Serializable {

	/** Unique identifier for the objectbox database */
	@Id @JvmField @JsonAttribute(name = "id")
	var id: Long = 0L

	/** Unique identifier for the download task */
	@JvmField @JsonAttribute(name = "downloadID")
	var downloadId: Int = 0

	/** Current operational status (see DownloadStatus constants) */
	@JvmField @JsonAttribute(name = "status")
	var status: Int = DownloadStatus.CLOSE

	/** Indicates if the download process is currently active */
	@JvmField @JsonAttribute(name = "isRunning")
	var isRunning: Boolean = false

	/** Indicates if the download completed successfully */
	@JvmField @JsonAttribute(name = "isComplete")
	var isComplete: Boolean = false

	/** Indicates if the download was explicitly deleted by user or system */
	@JvmField @JsonAttribute(name = "isDeleted")
	var isDeleted: Boolean = false

	/** Indicates if the download was removed from UI but may still exist in storage */
	@JvmField @JsonAttribute(name = "isRemoved")
	var isRemoved: Boolean = false

	/** Flag indicating if file was saved to private/secure storage location */
	@JvmField @JsonAttribute(name = "isWentToPrivateFolder")
	var isWentToPrivateFolder: Boolean = false

	/** Flag indicating if the source download URL has expired or become invalid */
	@JvmField @JsonAttribute(name = "isFileUrlExpired")
	var isFileUrlExpired: Boolean = false

	/** Flag indicating if yt-dlp encountered processing issues during download */
	@JvmField @JsonAttribute(name = "isYtdlpHavingProblem")
	var isYtdlpHavingProblem: Boolean = false

	/** Detailed error message from yt-dlp when processing issues occur */
	@JvmField @JsonAttribute(name = "ytdlpProblemMsg")
	var ytdlpProblemMsg: String = ""

	/** Flag indicating if the expected destination file does not exist after download */
	@JvmField @JsonAttribute(name = "isDestinationFileNotExisted")
	var isDestinationFileNotExisted: Boolean = false

	/** Flag indicating if file integrity check via checksum validation failed */
	@JvmField @JsonAttribute(name = "isFileChecksumValidationFailed")
	var isFileChecksumValidationFailed: Boolean = false

	/** Flag indicating download is paused waiting for network connectivity */
	@JvmField @JsonAttribute(name = "isWaitingForNetwork")
	var isWaitingForNetwork: Boolean = false

	/** Flag indicating failure to access or read from source file location */
	@JvmField @JsonAttribute(name = "isFailedToAccessFile")
	var isFailedToAccessFile: Boolean = false

	/** Flag indicating if URL expiration dialog has been shown to user */
	@JvmField @JsonAttribute(name = "isExpiredURLDialogShown")
	var isExpiredURLDialogShown: Boolean = false

	/** Flag indicating if automatic file categorization has been processed */
	@JvmField @JsonAttribute(name = "isSmartCategoryDirProcessed")
	var isSmartCategoryDirProcessed: Boolean = false

	/** Message to display to user via dialog or notification */
	@JvmField @JsonAttribute(name = "msgToShowUserViaDialog")
	var msgToShowUserViaDialog: String = ""

	/** Flag indicating if download was initiated from browser context */
	@JvmField @JsonAttribute(name = "isDownloadFromBrowser")
	var isDownloadFromBrowser: Boolean = false

	/** Flag indicating if basic yt-dlp metadata extraction completed successfully */
	@JvmField @JsonAttribute(name = "isBasicYtdlpModelInitialized")
	var isBasicYtdlpModelInitialized: Boolean = false

	/** Custom HTTP headers to include in download requests */
	@JvmField @JsonAttribute(name = "additionalWebHeaders")
	var additionalWebHeaders: Map<String, String>? = null

	/** Name of the target file being downloaded */
	@JvmField @JsonAttribute(name = "fileName")
	var fileName: String = ""

	/** Source URL from which the file is being downloaded */
	@JvmField @JsonAttribute(name = "fileURL")
	var fileURL: String = ""

	/** HTTP Referrer header value for the download request */
	@JvmField @JsonAttribute(name = "siteReferrer")
	var siteReferrer: String = ""

	/** Target directory path where file will be saved */
	@JvmField @JsonAttribute(name = "fileDirectory")
	var fileDirectory: String = ""

	/** MIME type of the file being downloaded */
	@JvmField @JsonAttribute(name = "fileMimeType")
	var fileMimeType: String = ""

	/** Content-Disposition header value from server response */
	@JvmField @JsonAttribute(name = "fileContentDisposition")
	var fileContentDisposition: String = ""

	/** Cookie string for authenticated download requests */
	@JvmField @JsonAttribute(name = "siteCookieString")
	var siteCookieString: String = ""

	/** Local filesystem path to the downloaded file's thumbnail */
	@JvmField @JsonAttribute(name = "thumbPath")
	var thumbPath: String = ""

	/** Remote URL source for the file's thumbnail image */
	@JvmField @JsonAttribute(name = "thumbnailUrl")
	var thumbnailUrl: String = ""

	/** Temporary file path used during yt-dlp processing phase */
	@JvmField @JsonAttribute(name = "tempYtdlpDestinationFilePath")
	var tempYtdlpDestinationFilePath: String = ""

	/** Temporary status information during yt-dlp processing */
	@JvmField @JsonAttribute(name = "tempYtdlpStatusInfo")
	var tempYtdlpStatusInfo: String = ""

	/** URI representation of the target directory location */
	@JvmField @JsonAttribute(name = "fileDirectoryURI")
	var fileDirectoryURI: String = ""

	/** Automatically determined category name for the file */
	@JvmField @JsonAttribute(name = "fileCategoryName")
	var fileCategoryName: String = ""

	/** Formatted timestamp string indicating download start time */
	@JvmField @JsonAttribute(name = "startTimeDateInFormat")
	var startTimeDateInFormat: String = ""

	/** Unix timestamp in milliseconds indicating download start time */
	@JvmField @JsonAttribute(name = "startTimeDate")
	var startTimeDate: Long = 0L

	/** Formatted timestamp string of last file modification time */
	@JvmField @JsonAttribute(name = "lastModifiedTimeDateInFormat")
	var lastModifiedTimeDateInFormat: String = ""

	/** Unix timestamp in milliseconds of last file modification */
	@JvmField @JsonAttribute(name = "lastModifiedTimeDate")
	var lastModifiedTimeDate: Long = 0L

	/** Flag indicating if file size could not be determined from source */
	@JvmField @JsonAttribute(name = "isUnknownFileSize")
	var isUnknownFileSize: Boolean = false

	/** Total file size in bytes */
	@JvmField @JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L

	/** Cryptographic hash/checksum for file integrity verification */
	@JvmField @JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = "--"

	/** Human-readable formatted string representation of file size */
	@JvmField @JsonAttribute(name = "fileSizeInFormat")
	var fileSizeInFormat: String = ""

	/** Average download speed in bytes per second */
	@JvmField @JsonAttribute(name = "averageSpeed")
	var averageSpeed: Long = 0L

	/** Maximum achieved download speed in bytes per second */
	@JvmField @JsonAttribute(name = "maxSpeed")
	var maxSpeed: Long = 0L

	/** Current real-time download speed in bytes per second */
	@JvmField @JsonAttribute(name = "realtimeSpeed")
	var realtimeSpeed: Long = 0L

	/** Formatted string representation of average download speed */
	@JvmField @JsonAttribute(name = "averageSpeedInFormat")
	var averageSpeedInFormat: String = "--"

	/** Formatted string representation of maximum download speed */
	@JvmField @JsonAttribute(name = "maxSpeedInFormat")
	var maxSpeedInFormat: String = "--"

	/** Formatted string representation of current download speed */
	@JvmField @JsonAttribute(name = "realtimeSpeedInFormat")
	var realtimeSpeedInFormat: String = "--"

	/** Flag indicating if the download supports resumption after interruption */
	@JvmField @JsonAttribute(name = "isResumeSupported")
	var isResumeSupported: Boolean = false

	/** Flag indicating if multi-threaded downloading is supported for this file */
	@JvmField @JsonAttribute(name = "isMultiThreadSupported")
	var isMultiThreadSupported: Boolean = false

	/** Total number of connection retry attempts made */
	@JvmField @JsonAttribute(name = "resumeSessionRetryCount")
	var resumeSessionRetryCount: Int = 0

	/** Total number of connection retries that were tracked */
	@JvmField @JsonAttribute(name = "totalTrackedConnectionRetries")
	var totalTrackedConnectionRetries: Int = 0

	/** Download completion percentage (0-100) */
	@JvmField @JsonAttribute(name = "progressPercentage")
	var progressPercentage: Long = 0L

	/** Formatted string representation of completion percentage */
	@JvmField @JsonAttribute(name = "progressPercentageInFormat")
	var progressPercentageInFormat: String = ""

	/** Total number of bytes downloaded so far */
	@JvmField @JsonAttribute(name = "downloadedByte")
	var downloadedByte: Long = 0L

	/** Formatted string representation of downloaded bytes */
	@JvmField @JsonAttribute(name = "downloadedByteInFormat")
	var downloadedByteInFormat: String = "--"

	/** Array tracking starting byte positions for each download chunk (18 chunks max) */
	@JvmField @JsonAttribute(name = "partStartingPoint")
	var partStartingPoint: LongArray = LongArray(18)

	/** Array tracking ending byte positions for each download chunk (18 chunks max) */
	@JvmField @JsonAttribute(name = "partEndingPoint")
	var partEndingPoint: LongArray = LongArray(18)

	/** Array tracking total size of each download chunk (18 chunks max) */
	@JvmField @JsonAttribute(name = "partChunkSizes")
	var partChunkSizes: LongArray = LongArray(18)

	/** Array tracking bytes downloaded for each chunk (18 chunks max) */
	@JvmField @JsonAttribute(name = "partsDownloadedByte")
	var partsDownloadedByte: LongArray = LongArray(18)

	/** Array tracking completion percentage for each download chunk (18 chunks max) */
	@JvmField @JsonAttribute(name = "partProgressPercentage")
	var partProgressPercentage: IntArray = IntArray(18)

	/** Total time spent on download in milliseconds */
	@JvmField @JsonAttribute(name = "timeSpentInMilliSec")
	var timeSpentInMilliSec: Long = 0L

	/** Estimated remaining time to complete download in seconds */
	@JvmField @JsonAttribute(name = "remainingTimeInSec")
	var remainingTimeInSec: Long = 0L

	/** Formatted string representation of time spent downloading */
	@JvmField @JsonAttribute(name = "timeSpentInFormat")
	var timeSpentInFormat: String = "--"

	/** Formatted string representation of estimated remaining time */
	@JvmField @JsonAttribute(name = "remainingTimeInFormat")
	var remainingTimeInFormat: String = "--"

	/** Current status message for display purposes */
	@JvmField @JsonAttribute(name = "statusInfo")
	var statusInfo: String = "--"

	/** Video-specific metadata for media downloads (transient - not persisted in DB) */
	@io.objectbox.annotation.Transient
	@JvmField @JsonAttribute(name = "videoInfo")
	var videoInfo: VideoInfo? = null

	/** Video format and codec information (transient - not persisted in DB) */
	@io.objectbox.annotation.Transient
	@JvmField @JsonAttribute(name = "videoFormat")
	var videoFormat: VideoFormat? = null

	/** Remote file metadata obtained from server or yt-dlp (transient - not persisted in DB) */
	@io.objectbox.annotation.Transient
	@JvmField @JsonAttribute(name = "remoteFileInfo")
	var remoteFileInfo: RemoteFileInfo? = null

	/** Command string used to execute the download process */
	@JvmField @JsonAttribute(name = "executionCommand")
	var executionCommand: String = ""

	/** Playback duration string for media files (e.g., "02:30" for 2 minutes 30 seconds) */
	@JvmField @JsonAttribute(name = "mediaFilePlaybackDuration")
	var mediaFilePlaybackDuration: String = ""

	/** Indicator reflecting whether the download data model is synced to cloud backup */
	@JvmField @JsonAttribute(name = "isSyncToCloudBackup")
	var isSyncToCloudBackup: Boolean = false

	/** Snapshot of global application settings at the time download was initiated (transient - not persisted in DB) */
	@io.objectbox.annotation.Transient
	@JvmField @JsonAttribute(name = "globalSettings")
	var globalSettings: AIOSettings = (deepCopy(aioSettings) ?: aioSettings).apply { id = 0L }

	/**
	 * Companion object containing shared constants and utilities for DownloadDataModel class.
	 * This object holds transient properties and constant values used across all instances
	 * of DownloadDataModel for consistent file naming, storage, and logging.
	 */
	companion object {
		/**
		 * Transient logger instance shared across all DownloadDataModel instances.
		 * Marked as @Transient to exclude from serialization since logger instances
		 * should not be persisted and recreated upon deserialization.
		 */
		@Transient
		var logger = LogHelperUtils.from(DownloadDataModel::class.java)

		// Constants for file naming and storage

		/**
		 * Key used for identifying download model in intent extras or shared preferences
		 */
		const val DOWNLOAD_MODEL_ID_KEY = "DOWNLOAD_MODEL_ID_KEY"

		/**
		 * File extension for JSON-formatted download model files
		 * Format: {downloadId}_download.json
		 */
		const val DOWNLOAD_MODEL_FILE_JSON_EXTENSION = "_download.json"

		/**
		 * File extension for binary-formatted download model files
		 * Format: {downloadId}_download.dat
		 */
		const val DOWNLOAD_MODEL_FILE_BINARY_EXTENSION: String = "_download.dat"

		/**
		 * File extension for cookie files associated with downloads
		 * Format: {downloadId}_cookies.txt
		 */
		const val DOWNLOAD_MODEL_COOKIES_EXTENSION = "_cookies.txt"

		/**
		 * File extension for download thumbnail images
		 * Format: {downloadId}_download.jpg
		 */
		const val THUMB_EXTENSION = "_download.jpg"

		/**
		 * File extension for temporary download files
		 * These files are created during active downloads and removed upon completion
		 */
		const val TEMP_EXTENSION = ".aio_download"

		/**
		 * Converts a JSON file to a DownloadDataModel instance, with fallback to binary format if available.
		 * This method attempts to load the download model using the following priority:
		 * 1. First tries to load from the corresponding binary file (.dat) for better performance
		 * 2. Falls back to JSON deserialization if binary file is missing, invalid, or corrupted
		 * 3. Updates storage after successful load to ensure data consistency
		 *
		 * The method automatically handles corrupted binary files by deleting them and falling back to JSON.
		 *
		 * @param downloadDataModelJSONFile The JSON file containing the download model data
		 * @return DownloadDataModel instance if successful, null if both binary and JSON loading fail
		 */
		fun convertJSONStringToClass(downloadDataModelJSONFile: File): DownloadDataModel? {
			logger.d("Starting JSON to class conversion for file: ${downloadDataModelJSONFile.absolutePath}")
			val internalDir = INSTANCE.filesDir

			// Generate corresponding binary filename by replacing extension with .dat
			val downloadDataModelBinaryFileName = "${downloadDataModelJSONFile.nameWithoutExtension}.dat"
			val downloadDataModelBinaryFile = File(internalDir, downloadDataModelBinaryFileName)

			try {
				var downloadDataModel: DownloadDataModel? = null
				var isBinaryFileValid = false

				// First attempt: Try to load from binary file for better performance
				if (downloadDataModelBinaryFile.exists()) {
					logger.d("Found binary download model file: ${downloadDataModelBinaryFile.name}")
					val absolutePath = downloadDataModelBinaryFile.absolutePath

					logger.d("Attempting to load binary from: $absolutePath")
					val objectInMemory = loadFromBinary(downloadDataModelBinaryFile)

					if (objectInMemory != null) {
						logger.d("Binary load successful for file: ${downloadDataModelBinaryFile.name}")
						downloadDataModel = objectInMemory
						// Update storage to ensure binary and JSON formats are synchronized
						downloadDataModel.updateInStorage()
						isBinaryFileValid = true
					} else {
						logger.d("Binary load failed for file: ${downloadDataModelBinaryFile.name}")
					}
				}

				// Second attempt: Fall back to JSON if binary loading failed or file doesn't exist
				if (!isBinaryFileValid || downloadDataModel == null) {
					logger.d("Attempting JSON load for file: ${downloadDataModelJSONFile.name}")
					val jsonString = downloadDataModelJSONFile.readText(Charsets.UTF_8)

					logger.d("JSON content length: ${jsonString.length} chars")
					val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
					downloadDataModel = aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)

					if (downloadDataModel != null) {
						logger.d("JSON load successful for file: ${downloadDataModelJSONFile.name}")
						// Update storage to create/update the binary version for future faster loading
						downloadDataModel.updateInStorage()
					} else {
						logger.e("Failed to parse JSON for file: ${downloadDataModelJSONFile.name}")
					}
				}

				return downloadDataModel
			} catch (error: Exception) {
				logger.e("Error in conversion: ${error.message}", error)
				try {
					// Clean up potentially corrupted binary file to prevent future loading issues
					downloadDataModelBinaryFile.delete()
					logger.d("Deleted potentially corrupted binary file")
				} catch (error: Exception) {
					logger.e("Failed to delete binary file", error)
				}
				return null
			}
		}

		/**
		 * Loads a DownloadDataModel instance from a binary file using FST deserialization.
		 * This method attempts to read and deserialize a binary file containing a previously
		 * saved DownloadDataModel. If the file is corrupted or invalid, it will be deleted
		 * automatically to prevent future loading attempts.
		 *
		 * The method performs the following steps:
		 * 1. Checks if the binary file exists at the specified path
		 * 2. Reads the entire file content as a byte array
		 * 3. Uses FST configuration to deserialize the bytes back into a DownloadDataModel object
		 * 4. Handles corruption by deleting the problematic file
		 *
		 * @param downloadDataModelBinaryFile The File object pointing to the binary file to load from
		 * @return Deserialized DownloadDataModel instance if successful, null if file doesn't exist
		 *         or deserialization fails
		 */
		private fun loadFromBinary(downloadDataModelBinaryFile: File): DownloadDataModel? {
			logger.d("Starting binary load from: ${downloadDataModelBinaryFile.absolutePath}")

			// Verify that the binary file exists before attempting to load
			if (!downloadDataModelBinaryFile.exists()) {
				logger.d("Binary file not found at: ${downloadDataModelBinaryFile.absolutePath}")
				return null
			}

			return try {
				// Read the entire binary file content into a byte array
				logger.d("Reading binary file content")
				val bytes = downloadDataModelBinaryFile.readBytes()
				logger.d("Binary file size: ${bytes.size} bytes")

				// Deserialize the byte array back into a DownloadDataModel object using FST
				val result = fstConfig.asObject(bytes).apply {
					logger.d("Binary deserialization completed")
				} as DownloadDataModel
				logger.d("Binary load successful")
				result
			} catch (error: Exception) {
				// Handle deserialization errors by logging and cleaning up the corrupted file
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

	/**
	 * Primary constructor initialization block for DownloadDataModel.
	 * This block is executed when a new instance of DownloadDataModel is created.
	 * It performs the initial setup by resetting all properties to their default values
	 * and ensures the model starts in a clean, consistent state.
	 *
	 * The initialization process:
	 * 1. Logs the creation of a new download model instance
	 * 2. Calls resetToDefaultValues() to initialize all properties with appropriate defaults
	 * 3. Sets up a unique download ID and configures default file directory based on settings
	 */
	init {
		logger.d("Initializing new DownloadDataModel")
		resetToDefaultValues()
	}

	/**
	 * Updates the download model in persistent storage with the current state.
	 * This synchronized method ensures thread-safe persistence of the download model
	 * by saving it in both binary and JSON formats, along with associated cookies
	 * and database records.
	 *
	 * The method performs the following operations in background:
	 * 1. Validates that the model has sufficient data (filename or URL) to be saved
	 * 2. Saves any available cookies for authenticated downloads
	 * 3. Cleans up transient properties before persistence
	 * 4. Saves the model in binary format for efficient storage
	 * 5. Saves the model in JSON format for readability and compatibility
	 * 6. Updates database records with the current model state
	 *
	 * Note: This method is typically called when download state changes significantly
	 * or when the app needs to persist current progress.
	 */
	@Synchronized
	fun updateInStorage() {
		logger.d("Starting storage update for download ID: $downloadId")
		ThreadsUtility.executeInBackground(codeBlock = {
			// Validate that the model has essential data before saving
			if (fileName.isEmpty() && fileURL.isEmpty()) {
				logger.d("Empty filename and URL, skipping update")
				return@executeInBackground
			}

			// Prepare the model for persistence by saving cookies and cleaning transient data
			logger.d("Saving cookies and cleaning model before storage")
			saveCookiesIfAvailable()
			cleanTheModelBeforeSavingToStorage()

			// Save the model in binary format for efficient storage and quick retrieval
			logger.d("Saving to binary format")
			saveToBinaryFormat("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")

			// Save the model in JSON format for readability and cross-platform compatibility
			logger.d("Saving to JSON format")
			val json = convertClassToJSON()
			logger.d("JSON content length: ${json.length} chars")

			saveStringToInternalStorage("$downloadId$DOWNLOAD_MODEL_FILE_JSON_EXTENSION", json)

			// Update database records to maintain consistency across storage layers
			DownloadModelsDBManager.saveDownloadWithRelationsInDB(this)
			logger.d("Storage update completed for download ID: $downloadId")
		}, errorHandler = { error ->
			logger.e("Storage update failed for download ID: $downloadId", error)
		})
	}

	/**
	 * Saves the current download model to a binary file using FST (Fast Serialization) configuration.
	 * This synchronized method ensures thread-safe serialization and file operations when
	 * persisting the download model to binary format for efficient storage and retrieval.
	 *
	 * The method performs the following operations:
	 * 1. Deletes any existing binary file for this download ID to ensure clean state
	 * 2. Serializes the current object to byte array using FST configuration
	 * 3. Writes the serialized bytes to a private file in the app's internal storage
	 *
	 * @param fileName The name of the binary file to save the model to
	 */
	@Synchronized
	private fun saveToBinaryFormat(fileName: String) {
		try {
			logger.d("Saving to binary file: $fileName")
			val internalDir = AIOApp.internalDataFolder

			// Find and delete any existing binary file for this download ID
			val modelBinaryFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")

			if (isWritableFile(modelBinaryFile)) {
				modelBinaryFile?.delete()?.let { isDeletedSuccessful ->
					if (isDeletedSuccessful) logger.d("Deleted existing binary file successfully")
					else logger.d("Failed to delete existing binary file")
				}
			}

			// Create new binary file and serialize the model object
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				// Serialize the current object to byte array using FST configuration
				val bytes = fstConfig.asByteArray(this)
				logger.d("Serialized binary size: ${bytes.size} bytes")

				// Write the serialized bytes to the file
				fos.write(bytes)
				logger.d("Binary save successful for file: $fileName")
			}
		} catch (error: Exception) {
			logger.e("Binary save error for file: $fileName", error)
		}
	}

	/**
	 * Completely deletes all files and data associated with this download model from disk and database.
	 * This synchronized method ensures thread-safe deletion of all download-related files including:
	 * - Model data files (JSON and binary formats)
	 * - Thumbnail images
	 * - Cookie files
	 * - Temporary download files
	 * - The actual downloaded file (in private folder)
	 * - Database records and relations
	 *
	 * The deletion is performed on a background thread to avoid blocking the UI and includes
	 * comprehensive error handling and logging for each deletion operation.
	 */
	@Synchronized
	fun deleteModelFromDisk() {
		logger.d("Starting model deletion for download ID: $downloadId")
		ThreadsUtility.executeInBackground(codeBlock = {
			val internalDir = AIOApp.internalDataFolder

			// Identify all files associated with this download model
			val mergredBinaryFile = internalDir.findFile(MERGRED_DATA_MODEL_BINARY_FILENAME)
			val modelJsonFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_JSON_EXTENSION")
			val modelBinaryFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_FILE_BINARY_EXTENSION")
			val cookieFile = internalDir.findFile("$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION")
			val thumbFile = internalDir.findFile("$downloadId$THUMB_EXTENSION")

			// Delete JSON model file with writable check
			logger.d("Deleting JSON file")
			isWritableFile(modelJsonFile).let {
				if (it) modelJsonFile?.delete()?.let { logger.d("Deleted JSON file successfully") }
			}

			// Delete binary model file with writable check
			logger.d("Deleting binary file")
			isWritableFile(modelBinaryFile).let {
				if (it) modelBinaryFile?.delete()?.let { logger.d("Deleted binary file successfully") }
			}

			// Delete thumbnail file with writable check
			logger.d("Deleting thumbnail file")
			isWritableFile(thumbFile).let {
				if (it) thumbFile?.delete()?.let { logger.d("Deleted thumbnail file successfully") }
			}

			// Delete cookie file with writable check
			logger.d("Deleting cookies file")
			isWritableFile(cookieFile).let {
				if (it) cookieFile?.delete()?.let { logger.d("Deleted cookies file successfully") }
			}

			// Delete merged binary file (if exists) with writable check
			logger.d("Deleting Merged binary file")
			isWritableFile(mergredBinaryFile).let {
				if (it) mergredBinaryFile?.delete()?.let { logger.d("Deleted Merged binary file successfully") }
			}

			// Delete all temporary files created during download process
			logger.d("Deleting temporary files")
			deleteAllTempDownloadedFiles(internalDir)

			// Delete the actual downloaded file if stored in private folder
			if (globalSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
				logger.d("Deleting downloaded file from private folder")
				val downloadedFile = getDestinationDocumentFile()
				isWritableFile(downloadedFile).let { isDeletedSuccessful ->
					if (isDeletedSuccessful) downloadedFile.delete().let {
						logger.d("Deleted downloaded file successfully")
					}
				}
			}

			// Remove all database records associated with this download
			DownloadModelsDBManager.deleteDownloadWithRelations(this)
			logger.d("Model deletion completed for download ID: $downloadId")
		}, errorHandler = { error ->
			logger.e("Deletion error for download ID: $downloadId", error)
		})
	}

	/**
	 * Retrieves the file path of the saved cookies file if it exists and is available.
	 * This method checks if cookies have been saved for this download and returns
	 * the absolute file path to the cookies file, which can be used by download
	 * tools or libraries that require cookie authentication.
	 *
	 * The method first verifies that cookies are actually available in the model
	 * before checking for the existence of the physical cookie file.
	 *
	 * @return Absolute path to the cookies file if available, null if no cookies
	 *         are available or the file doesn't exist
	 */
	fun getCookieFilePathIfAvailable(): String? {
		// Check if the model contains any cookie data
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies available for download ID: $downloadId")
			return null
		}

		// Generate the expected cookie file name using download ID and cookies extension
		val cookieFileName = "$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)

		// Return the absolute path if the cookie file exists, otherwise return null
		return if (cookieFile != null && cookieFile.exists()) {
			logger.d("Found cookies file for download ID: $downloadId")
			cookieFile.getAbsolutePath(INSTANCE)
		} else {
			logger.d("No cookies file found for download ID: $downloadId")
			null
		}
	}

	/**
	 * Saves the site cookies to internal storage in Netscape format if available.
	 * This method persists cookies associated with the download to enable authenticated
	 * downloads and resume capabilities. Cookies are stored in a file named with the
	 * download ID and a cookies extension.
	 *
	 * The method provides an override option to force saving even if a cookie file
	 * already exists, which is useful for updating expired or changed cookies.
	 *
	 * @param shouldOverride If true, will overwrite existing cookie file;
	 *                       if false, will skip saving if file already exists
	 */
	fun saveCookiesIfAvailable(shouldOverride: Boolean = false) {
		// Check if there are any cookies to save
		if (siteCookieString.isEmpty()) {
			logger.d("No cookies to save for download ID: $downloadId")
			return
		}

		// Generate the cookie file name using download ID and cookies extension
		val cookieFileName = "$downloadId$DOWNLOAD_MODEL_COOKIES_EXTENSION"
		val internalDir = AIOApp.internalDataFolder
		val cookieFile = internalDir.findFile(cookieFileName)

		// Skip saving if file already exists and override is not requested
		if (!shouldOverride && cookieFile != null && cookieFile.exists()) {
			logger.d("Cookies file already exists and override not requested for download ID: $downloadId")
			return
		}

		// Proceed with saving the cookies to internal storage
		logger.d("Saving cookies for download ID: $downloadId")
		saveStringToInternalStorage(
			fileName = cookieFileName,
			data = generateNetscapeFormattedCookieString(siteCookieString)
		)
		logger.d("Cookies saved successfully for download ID: $downloadId")
	}

	/**
	 * Converts a standard HTTP cookie string into Netscape-style cookie file format.
	 * This method transforms cookies from the common "name=value; name2=value2" format
	 * into the Netscape HTTP Cookie File format used by many download tools and browsers.
	 *
	 * The Netscape format includes:
	 * - Domain (left empty in this implementation)
	 * - Flag indicating if all hosts within the domain can access the cookie
	 * - Path where the cookie is valid
	 * - Secure flag (FALSE for non-HTTPS cookies)
	 * - Expiration timestamp (set to distant future)
	 * - Cookie name and value
	 *
	 * @param cookieString The original cookie string in standard HTTP format
	 * @return Formatted string in Netscape HTTP Cookie File format
	 */
	private fun generateNetscapeFormattedCookieString(cookieString: String): String {
		logger.d("Generating Netscape formatted cookie string")

		// Split the cookie string into individual cookies and trim whitespace
		val cookies = cookieString.split(";").map { it.trim() }

		// Define fixed values for Netscape cookie format
		val domain = ""  // Empty domain for broad applicability
		val path = "/"   // Root path for maximum accessibility
		val secure = "FALSE"  // Non-HTTPS cookie
		val expiry = "2147483647"  // Distant future expiration (year 2038)

		// Build the Netscape-formatted cookie file content
		val stringBuilder = StringBuilder()

		// Add file header with generation information
		stringBuilder.append("# Netscape HTTP Cookie File\n")
		stringBuilder.append("# This file was generated by the app.\n\n")

		// Process each cookie and convert to Netscape format
		for (cookie in cookies) {
			val parts = cookie.split("=", limit = 2)
			if (parts.size == 2) {
				val name = parts[0].trim()
				val value = parts[1].trim()

				// Append cookie in Netscape format: domain, flag, path, secure, expiry, name, value
				stringBuilder.append("$domain\tFALSE\t$path\t$secure\t$expiry\t$name\t$value\n")
			}
		}

		logger.d("Generated Netscape cookie string with ${cookies.size} cookies")
		return stringBuilder.toString()
	}

	/**
	 * Converts the current DownloadDataModel instance to a JSON string representation.
	 * This method uses the DSL-JSON library to serialize the object into JSON format,
	 * which can be stored in persistent storage or transmitted over network.
	 *
	 * @return JSON string representation of the current DownloadDataModel instance
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting class to JSON for download ID: $downloadId")
		val outputStream = ByteArrayOutputStream()
		aioDSLJsonInstance.serialize(this, outputStream) // write to stream
		return outputStream.toByteArray().decodeToString() // convert to String
	}

	/**
	 * Converts a JSON string back into a DownloadDataModel instance.
	 * This method performs deserialization using the DSL-JSON library to recreate
	 * the object from its JSON representation, typically used when loading from storage.
	 *
	 * @param jsonString The JSON string to convert back to DownloadDataModel
	 * @return DownloadDataModel instance if deserialization succeeds, null otherwise
	 */
	private fun convertJSONStringToClass(jsonString: String): DownloadDataModel? {
		logger.d("Converting JSON to download data model object")
		val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
		return aioDSLJsonInstance.deserialize(DownloadDataModel::class.java, inputStream)
	}

	/**
	 * Gets the temporary directory used for storing incomplete download files.
	 * This directory is used to store partial downloads and temporary files during
	 * the download process, separate from the final destination directory.
	 *
	 * @return File object representing the temporary download directory
	 */
	fun getTempDestinationDir(): File {
		logger.d("Getting temp destination directory for download ID: $downloadId")
		return File("${fileDirectory}.temp/")
	}

	/**
	 * Creates a DocumentFile object representing the final destination file.
	 * This method constructs the complete file path and creates a DocumentFile
	 * wrapper, which is useful for working with the Storage Access Framework
	 * and provides additional file management capabilities.
	 *
	 * @return DocumentFile object pointing to the final download destination
	 */
	fun getDestinationDocumentFile(): DocumentFile {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination DocumentFile for path: $destinationPath")
		return DocumentFile.fromFile(File(destinationPath!!))
	}

	/**
	 * Creates and returns a File object representing the final destination path for the download.
	 * This method constructs the complete file path by combining the directory and filename,
	 * then ensures the path is properly formatted by removing any duplicate slashes.
	 *
	 * @return File object pointing to the final download destination
	 */
	fun getDestinationFile(): File {
		val destinationPath = removeDuplicateSlashes("$fileDirectory/$fileName")
		logger.d("Getting destination File for path: $destinationPath")
		return File(destinationPath!!)
	}

	/**
	 * Creates and returns a File object representing the temporary download file.
	 * This method generates a temporary file path by appending a temporary extension
	 * to the final destination file path. The temporary file is used during the
	 * download process and renamed to the final filename upon completion.
	 *
	 * @return File object pointing to the temporary download file
	 */
	fun getTempDestinationFile(): File {
		val tempFilePath = "${getDestinationFile().absolutePath}${TEMP_EXTENSION}"
		logger.d("Getting temp destination file: $tempFilePath")
		return File(tempFilePath)
	}

	/**
	 * Retrieves the URI of the thumbnail image associated with this download.
	 * This method constructs the thumbnail filename using the download ID and
	 * searches for it in the app's internal data folder.
	 *
	 * @return Uri of the thumbnail file if found, null otherwise
	 */
	fun getThumbnailURI(): Uri? {
		val thumbFilePath = "$downloadId$THUMB_EXTENSION"
		logger.d("Getting thumbnail URI for file: $thumbFilePath")
		return AIOApp.internalDataFolder.findFile(thumbFilePath)?.uri
	}

	/**
	 * Clears the cached thumbnail file for this download.
	 * This method attempts to delete the thumbnail file from storage and updates
	 * the model to reflect that no thumbnail is available. Useful for cleaning up
	 * temporary thumbnails or when regenerating thumbnails.
	 */
	fun clearCachedThumbnailFile() {
		logger.d("Clearing cached thumbnail for download ID: $downloadId")
		try {
			val thumbnailUri = getThumbnailURI()
			if (thumbnailUri != null) {
				thumbnailUri.toFile().delete()
				logger.d("Deleted thumbnail file successfully")
			} else {
				logger.d("No thumbnail file to delete")
			}
			// Clear the thumbnail path reference and persist the change
			thumbPath = ""
			updateInStorage()
			logger.d("Thumbnail cleared successfully for download ID: $downloadId")
		} catch (error: Exception) {
			logger.e("Error clearing thumbnail for download ID: $downloadId", error)
		}
	}

	/**
	 * Returns the resource ID of the default thumbnail drawable to display when no custom thumbnail is available.
	 * This method provides a fallback image for downloads that don't have custom thumbnails
	 * or when thumbnail loading fails.
	 *
	 * @return Resource ID of the default "no thumbnail available" drawable
	 */
	fun getThumbnailDrawableID(): Int {
		logger.d("Getting default thumbnail drawable ID")
		return drawable.image_no_thumb_available
	}

	/**
	 * Generates a comprehensive download status string for display in the UI.
	 * This method creates appropriate status information based on the download type (video vs. non-video)
	 * and current download state, handling special cases for video downloads with yt-dlp integration.
	 *
	 * For video downloads, it provides specialized status handling:
	 * - CLOSE status: Shows waiting, preparing, or failure messages when applicable
	 * - Active status: Shows either normal progress or yt-dlp specific status information
	 *
	 * For non-video downloads, it falls back to standard status information.
	 *
	 * @return Formatted string containing the appropriate download status information
	 */
	fun generateDownloadInfoInString(): String {
		logger.d("Generating download info string for download ID: $downloadId")

		// Handle video downloads with yt-dlp integration
		if (videoFormat != null && videoInfo != null) {
			return if (status == DownloadStatus.CLOSE) {
				// For closed/inactive video downloads, check for special status conditions
				val waitingToJoin = getText(string.title_waiting_to_join).lowercase()
				val preparingToDownload = getText(string.title_preparing_download).lowercase()
				val downloadFailed = getText(string.title_download_io_failed).lowercase()

				// Return special status messages for waiting, preparing, or failed states
				if (statusInfo.lowercase().startsWith(waitingToJoin) ||
					statusInfo.lowercase().startsWith(preparingToDownload) ||
					statusInfo.lowercase().startsWith(downloadFailed)
				) {
					logger.d("Returning special status info")
					statusInfo
				} else {
					// Fall back to normal status info for other closed states
					normalDownloadStatusInfo()
				}
			} else {
				// For active video downloads, choose between normal status and yt-dlp specific status
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
			// For non-video downloads, use standard status information
			logger.d("Returning normal download status info (non-video)")
			return normalDownloadStatusInfo()
		}
	}

	/**
	 * Determines the appropriate category name for the file based on its extension.
	 * This method categorizes files into predefined types (Images, Videos, Sounds, etc.)
	 * by checking the file extension against known extension lists.
	 *
	 * The method supports two naming modes:
	 * 1. With AIO prefix: Returns category names prefixed with "AIO" (e.g., "AIO Images")
	 * 2. Without AIO prefix: Returns generic category names (e.g., "Images")
	 *
	 * @param shouldRemoveAIOPrefix If true, returns generic category names without "AIO" prefix
	 * @return The appropriate category name string based on file extension and prefix preference
	 */
	fun getUpdatedCategoryName(shouldRemoveAIOPrefix: Boolean = false): String {
		logger.d("Getting updated category name for file: $fileName")
		if (shouldRemoveAIOPrefix) {
			// Return category names without "AIO" prefix for generic display
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
			// Return category names with "AIO" prefix for app-specific display
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
	 * Generates a human-readable formatted string representation of the file size.
	 * This method converts the raw byte count into a user-friendly format (e.g., KB, MB, GB)
	 * with appropriate unit suffixes for display in the UI.
	 *
	 * Handles special cases where file size is unknown or invalid:
	 * - Returns "Unknown" for files with size <= 1 byte or explicitly marked as unknown
	 * - Uses FileSizeFormatter for proper formatting of valid file sizes
	 *
	 * @return Formatted file size string (e.g., "1.5 MB") or "Unknown" for invalid sizes
	 */
	fun getFormattedFileSize(): String {
		logger.d("Getting formatted file size for download ID: $downloadId")
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
	 * Extracts the file extension from the file name.
	 * This method parses the file name to determine the file type based on the extension
	 * (the part after the last dot in the filename).
	 *
	 * Examples:
	 * - "document.pdf" returns "pdf"
	 * - "image.jpeg" returns "jpeg"
	 * - "file.with.dots.txt" returns "txt"
	 * - "file_without_extension" returns empty string
	 *
	 * @return The file extension in lowercase, or empty string if no extension is found
	 */
	fun getFileExtension(): String {
		val extension = fileName.substringAfterLast('.', "")
		logger.d("File extension for $fileName: $extension")
		return extension
	}

	/**
	 * Refreshes the download folder path based on current user settings.
	 * This method updates the file directory according to the user's preferred download location
	 * setting, ensuring files are saved to the correct storage location.
	 *
	 * The method handles two main download location options:
	 * 1. PRIVATE_FOLDER: Uses app-specific external or internal storage
	 * 2. SYSTEM_GALLERY: Uses system-defined gallery/download folder
	 *
	 * This is typically called when user changes download location preferences
	 * or when initializing download settings to ensure consistency.
	 */
	fun refreshUpdatedDownloadFolder() {
		logger.d("Refreshing download folder based on user settings")

		// Determine download directory based on user's preferred location setting
		when (globalSettings.defaultDownloadLocation) {
			PRIVATE_FOLDER -> {
				// Attempt to use external private data folder for app-specific storage
				val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
				if (!externalDataFolderPath.isNullOrEmpty()) {
					fileDirectory = externalDataFolderPath
					logger.d("Set file directory to external private folder: $externalDataFolderPath")
				} else {
					// Fallback to internal app storage if external storage is unavailable
					val internalDataFolderPath = INSTANCE.dataDir.absolutePath
					fileDirectory = internalDataFolderPath
					logger.d("External folder unavailable, " +
							"set file directory to internal storage: $internalDataFolderPath")
				}
			}

			SYSTEM_GALLERY -> {
				// Use default system gallery folder for publicly accessible downloads
				val galleryPath = getText(string.text_default_aio_download_folder_path)
				fileDirectory = galleryPath
				logger.d("Set file directory to system gallery: $galleryPath")
			}

			else -> logger.d("Unknown download location, keeping previous fileDirectory: $fileDirectory")
		}
	}

	/**
	 * Deletes all temporary files associated with the current download.
	 * This method cleans up temporary files created during the download process,
	 * including yt-dlp temporary files and cookie files, to free up storage space.
	 *
	 * The method handles two types of temporary files:
	 * 1. yt-dlp temporary download files (identified by filename prefix)
	 * 2. Video cookie temporary files (used for authenticated video downloads)
	 *
	 * @param internalDir The DocumentFile directory where temporary files are stored
	 *
	 * Note: This method is particularly important for video downloads that may create
	 * multiple temporary files during the download and processing phases.
	 */
	private fun deleteAllTempDownloadedFiles(internalDir: DocumentFile) {
		logger.d("Deleting all temp files for download ID: $downloadId")
		try {
			// Only process temporary files for video downloads
			if (videoFormat != null && videoInfo != null) {
				// Delete yt-dlp temporary files that match the filename pattern
				if (tempYtdlpDestinationFilePath.isNotEmpty()) {
					val tempYtdlpFileName = File(tempYtdlpDestinationFilePath).name
					logger.d("Processing yt-dlp temp files with prefix: $tempYtdlpFileName")

					// Iterate through all files in the directory and delete matching temp files
					internalDir.listFiles().forEach { file ->
						try {
							file?.let {
								// Only process files (not directories) that match the temp file pattern
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

				// Delete temporary cookie file used for video authentication
				if (videoInfo!!.videoCookieTempPath.isNotEmpty()) {
					val tempCookieFile = File(videoInfo!!.videoCookieTempPath)
					if (tempCookieFile.isFile && tempCookieFile.exists()) {
						tempCookieFile.delete()
						logger.d("Deleted temp cookie file: ${tempCookieFile.absolutePath}")
					}
				}
			}
			logger.d("Temp files deletion completed for download ID: $downloadId")
		} catch (error: Exception) {
			logger.e("Error deleting temp files for download ID: $downloadId", error)
		}
	}

	/**
	 * Generates a formatted status string for normal download scenarios.
	 * This method creates user-friendly status information displaying download progress,
	 * speed, and remaining time based on the current download state.
	 *
	 * Handles two main cases:
	 * 1. Video downloads: Shows simplified status with video-specific information
	 * 2. Regular downloads: Shows detailed progress including file size, speed, and ETA
	 *
	 * Special consideration for CLOSE status and network waiting states where
	 * speed and time information may not be available or relevant.
	 *
	 * @return Formatted string containing download status information for UI display
	 */
	private fun normalDownloadStatusInfo(): String {
		logger.d("Generating normal download status info for download ID: $downloadId")
		val textDownload = getText(string.title_downloaded)

		// Handle video downloads separately with simplified status format
		if (videoFormat != null && videoInfo != null) {
			val infoString = "$statusInfo  |  $textDownload ($progressPercentage%)" +
					"  |  --/s  |  --:-- "
			logger.d("Generated video download status: $infoString")
			return infoString
		} else {
			// For regular downloads, include detailed progress information
			val totalFileSize = fileSizeInFormat

			// Determine download speed display - show "--/s" for closed or inactive downloads
			val downloadSpeedInfo = if (status == DownloadStatus.CLOSE) "--/s"
			else realtimeSpeedInFormat

			// Determine remaining time display - show "--:--" for closed or network waiting states
			val remainingTimeInfo = if (status == DownloadStatus.CLOSE ||
				isWaitingForNetwork) "--:--" else remainingTimeInFormat

			val downloadingStatus = getText(string.title_started_downloading).lowercase()

			// Choose format based on whether download has actually started
			val result = if (statusInfo.lowercase().startsWith(downloadingStatus)) {
				// Format for actively downloading state
				"$progressPercentageInFormat% Of $totalFileSize  |  " +
						"$downloadSpeedInfo  |  $remainingTimeInfo"
			} else {
				// Format for other states (paused, queued, etc.)
				"$statusInfo  |  $textDownload ($progressPercentage%)  |  " +
						"$downloadSpeedInfo |  $remainingTimeInfo"
			}
			logger.d("Generated normal download status: $result")
			return result
		}
	}

	/**
	 * Resets all model properties to their default values for a new download.
	 * This method initializes a fresh download state with a unique ID and appropriate
	 * file directory based on the user's download location preferences.
	 *
	 * The method handles two main download location scenarios:
	 * 1. PRIVATE_FOLDER: Uses app's external or internal storage
	 * 2. SYSTEM_GALLERY: Uses system-defined download folder
	 *
	 * This should be called when creating a new download instance to ensure
	 * proper initialization before starting the download process.
	 */
	private fun resetToDefaultValues() {
		// Log the start of reset operation
		logger.d("Resetting to default values for new download")

		// Generate and assign a unique identifier for this download model
		downloadId = getUniqueNumberForDownloadModels()
		logger.d("Assigned new download ID: $downloadId")

		// Set file directory based on user's preferred download location setting
		if (aioSettings.defaultDownloadLocation == PRIVATE_FOLDER) {
			// Attempt to use external storage first for private app data
			val externalDataFolderPath = INSTANCE.getExternalDataFolder()?.getAbsolutePath(INSTANCE)
			if (!externalDataFolderPath.isNullOrEmpty()) {
				fileDirectory = externalDataFolderPath
				logger.d("Set file directory to external: $externalDataFolderPath")
			} else {
				// Fall back to internal storage if external is unavailable
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath
				fileDirectory = internalDataFolderPath
				logger.d("Set file directory to internal: $internalDataFolderPath")
			}
		} else if (aioSettings.defaultDownloadLocation == SYSTEM_GALLERY) {
			// Use system gallery/downloads folder for publicly accessible files
			val externalDataFolderPath = getText(string.text_default_aio_download_folder_path)
			fileDirectory = externalDataFolderPath
			logger.d("Set file directory to system gallery: $externalDataFolderPath")
		}

		// Log completion of reset operation with current settings
		logger.d("Reset completed for download ID: $downloadId " +
				"with settings: ${globalSettings.defaultDownloadLocation}")
	}

	/**
	 * Cleans up the download model by resetting transient properties before saving to persistent storage.
	 * This ensures that only persistent state is saved, while runtime/temporary values are cleared.
	 *
	 * The method handles two main scenarios:
	 * 1. Active downloads: Skips cleanup to preserve current progress state
	 * 2. Completed downloads: Finalizes progress values to indicate completion
	 *
	 * Note: This method should only be called when persisting the model to storage, not during active downloads.
	 */
	private fun cleanTheModelBeforeSavingToStorage() {
		// Log the cleanup operation for debugging and tracking purposes
		logger.d("Cleaning model before saving to storage for download ID: $downloadId")

		// Skip cleanup if download is currently running and in DOWNLOADING state
		// This preserves real-time progress information for active downloads
		if (isRunning && status == DownloadStatus.DOWNLOADING) {
			logger.d("Download is running, skipping cleanup")
			return
		}

		// Reset real-time speed metrics as these are transient and shouldn't be persisted
		realtimeSpeed = 0L
		realtimeSpeedInFormat = "--"

		// For completed downloads, finalize all progress metrics to indicate completion
		if (isComplete && status == DownloadStatus.COMPLETE) {
			// Set remaining time to zero for completed downloads
			remainingTimeInSec = 0
			remainingTimeInFormat = "--:--"

			// Set progress to 100% for the main download
			progressPercentage = 100L
			progressPercentageInFormat = getText(string.title_100_percentage)

			// Finalize downloaded bytes to match total file size
			downloadedByte = fileSize
			downloadedByteInFormat = getHumanReadableFormat(downloadedByte)

			// Update progress for all individual parts/chunks to 100%
			partProgressPercentage.forEachIndexed { index, _ ->
				partProgressPercentage[index] = 100
				partsDownloadedByte[index] = partChunkSizes[index]
			}

			// Log successful cleanup for completed download
			logger.d("Model cleaned for completed download ID: $downloadId")
		}
	}
}