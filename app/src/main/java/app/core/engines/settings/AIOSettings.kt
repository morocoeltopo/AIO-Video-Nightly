package app.core.engines.settings

import android.content.Context.MODE_PRIVATE
import androidx.documentfile.provider.DocumentFile
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioDSLJsonInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOLanguage.Companion.ENGLISH
import app.core.FSTBuilder.fstConfig
import com.aio.R.string
import com.anggrayudi.storage.file.DocumentFileCompat.fromFullPath
import com.anggrayudi.storage.file.getAbsolutePath
import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import lib.files.FileSystemUtility.isWritableFile
import lib.files.FileSystemUtility.readStringFromInternalStorage
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable

/**
 * Class representing persistent user settings for the AIO application.
 *
 * This class manages all user preferences and application state that needs to persist between sessions.
 * It handles serialization to both JSON and binary formats for reliability and performance.
 *
 * Features include:
 * - User preferences for downloads, browser, and UI
 * - Analytics tracking for user interactions
 * - Persistent storage management
 * - Default values for all settings
 * - Validation of storage locations
 */
@CompiledJson
@Entity
class AIOSettings : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	@Id
	var id: Long = 0

	// Basic user state
	@JsonAttribute(name = "userInstallationId")
	var userInstallationId: String = ""

	@JsonAttribute(name = "isFirstTimeLanguageSelectionComplete")
	var isFirstTimeLanguageSelectionComplete: Boolean = false

	@JsonAttribute(name = "hasUserRatedTheApplication")
	var hasUserRatedTheApplication: Boolean = false

	@JsonAttribute(name = "totalNumberOfSuccessfulDownloads")
	var totalNumberOfSuccessfulDownloads: Int = 0

	@JsonAttribute(name = "totalUsageTimeInMs")
	var totalUsageTimeInMs: Float = 0.0f

	@JsonAttribute(name = "totalUsageTimeInFormat")
	var totalUsageTimeInFormat: String = ""

	@JsonAttribute(name = "lastProcessedClipboardText")
	var lastProcessedClipboardText: String = ""

	// Default download location
	@JsonAttribute(name = "defaultDownloadLocation")
	var defaultDownloadLocation: Int = PRIVATE_FOLDER

	// Language & regions settings
	@JsonAttribute(name = "userSelectedUILanguage")
	var userSelectedUILanguage: String = ENGLISH

	@JsonAttribute(name = "userSelectedContentRegion")
	var userSelectedContentRegion: String = "IN"

	// Other settings
	@JsonAttribute(name = "themeAppearance")
	//-1 for automatic, 1 for dark, 2 for light
	var themeAppearance: Int = -1

	@JsonAttribute(name = "enableDailyContentSuggestion")
	var enableDailyContentSuggestion: Boolean = true

	// Analytics / interaction counters
	@JsonAttribute(name = "totalClickCountOnLanguageChange")
	var totalClickCountOnLanguageChange: Int = 0

	@JsonAttribute(name = "totalClickCountOnMediaPlayback")
	var totalClickCountOnMediaPlayback: Int = 0

	@JsonAttribute(name = "totalClickCountOnHowToGuide")
	var totalClickCountOnHowToGuide: Int = 0

	@JsonAttribute(name = "totalClickCountOnVideoUrlEditor")
	var totalClickCountOnVideoUrlEditor: Int = 0

	@JsonAttribute(name = "totalClickCountOnHomeHistory")
	var totalClickCountOnHomeHistory: Int = 0

	@JsonAttribute(name = "totalClickCountOnHomeBookmarks")
	var totalClickCountOnHomeBookmarks: Int = 0

	@JsonAttribute(name = "totalClickCountOnRecentDownloads")
	var totalClickCountOnRecentDownloads: Int = 0

	@JsonAttribute(name = "totalClickCountOnHomeFavicon")
	var totalClickCountOnHomeFavicon: Int = 0

	@JsonAttribute(name = "totalClickCountOnVersionCheck")
	var totalClickCountOnVersionCheck: Int = 0

	@JsonAttribute(name = "totalInterstitialAdClick")
	var totalInterstitialAdClick: Int = 0

	@JsonAttribute(name = "totalInterstitialImpression")
	var totalInterstitialImpression: Int = 0

	@JsonAttribute(name = "totalRewardedAdClick")
	var totalRewardedAdClick: Int = 0

	@JsonAttribute(name = "totalRewardedImpression")
	var totalRewardedImpression: Int = 0

	// Path to WhatsApp Statuses folder
	@JsonAttribute(name = "whatsAppStatusFullFolderPath")
	val whatsAppStatusFullFolderPath: String = getText(string.text_whatsapp_status_file_dir)

	// Download preferences
	@JsonAttribute(name = "downloadSingleUIProgress")
	var downloadSingleUIProgress: Boolean = true

	@JsonAttribute(name = "downloadHideVideoThumbnail")
	var downloadHideVideoThumbnail: Boolean = false

	@JsonAttribute(name = "downloadPlayNotificationSound")
	var downloadPlayNotificationSound: Boolean = true

	@JsonAttribute(name = "downloadHideNotification")
	var downloadHideNotification: Boolean = false

	@JsonAttribute(name = "hideDownloadProgressFromUI")
	var hideDownloadProgressFromUI: Boolean = false

	@JsonAttribute(name = "downloadAutoRemoveTasks")
	var downloadAutoRemoveTasks: Boolean = false

	@JsonAttribute(name = "downloadAutoRemoveTaskAfterNDays")
	var downloadAutoRemoveTaskAfterNDays: Int = 0

	@JsonAttribute(name = "openDownloadedFileOnSingleClick")
	var openDownloadedFileOnSingleClick: Boolean = true

	// Advanced download features
	@JsonAttribute(name = "downloadAutoResume")
	var downloadAutoResume: Boolean = true

	@JsonAttribute(name = "downloadAutoResumeMaxErrors")
	var downloadAutoResumeMaxErrors: Int = 35

	@JsonAttribute(name = "downloadAutoLinkRedirection")
	var downloadAutoLinkRedirection: Boolean = true

	@JsonAttribute(name = "downloadAutoFolderCatalog")
	var downloadAutoFolderCatalog: Boolean = true

	@JsonAttribute(name = "downloadAutoThreadSelection")
	var downloadAutoThreadSelection: Boolean = true

	@JsonAttribute(name = "downloadAutoFileMoveToPrivate")
	var downloadAutoFileMoveToPrivate: Boolean = false

	@JsonAttribute(name = "downloadAutoConvertVideosToMp3")
	var downloadAutoConvertVideosToMp3: Boolean = false

	// Download performance settings
	@JsonAttribute(name = "downloadBufferSize")
	var downloadBufferSize: Int = 1024 * 8

	@JsonAttribute(name = "downloadMaxHttpReadingTimeout")
	var downloadMaxHttpReadingTimeout: Int = 1000 * 30

	@JsonAttribute(name = "downloadDefaultThreadConnections")
	var downloadDefaultThreadConnections: Int = 1

	@JsonAttribute(name = "downloadDefaultParallelConnections")
	var downloadDefaultParallelConnections: Int = 10

	@JsonAttribute(name = "downloadVerifyChecksum")
	var downloadVerifyChecksum: Boolean = false

	@JsonAttribute(name = "downloadMaxNetworkSpeed")
	var downloadMaxNetworkSpeed: Long = 0

	@JsonAttribute(name = "downloadWifiOnly")
	var downloadWifiOnly: Boolean = false

	@JsonAttribute(name = "downloadHttpUserAgent")
	var downloadHttpUserAgent: String = getText(string.text_downloads_default_http_user_agent)

	@JsonAttribute(name = "downloadHttpProxyServer")
	var downloadHttpProxyServer: String = ""

	// Crash handling
	@JsonAttribute(name = "hasAppCrashedRecently")
	var hasAppCrashedRecently: Boolean = false

	// Privacy and limits
	@JsonAttribute(name = "privateFolderPassword")
	var privateFolderPassword: String = ""

	@JsonAttribute(name = "numberOfMaxDownloadThreshold")
	var numberOfMaxDownloadThreshold: Int = 1

	@JsonAttribute(name = "numberOfDownloadsUserDid")
	var numberOfDownloadsUserDid: Int = 0

	// Browser-specific settings
	@JsonAttribute(name = "browserDefaultHomepage")
	var browserDefaultHomepage: String = getText(string.text_https_google_com)

	@JsonAttribute(name = "browserDesktopBrowsing")
	var browserDesktopBrowsing: Boolean = false

	@JsonAttribute(name = "browserEnableAdblocker")
	var browserEnableAdblocker: Boolean = true

	@JsonAttribute(name = "browserEnableJavascript")
	var browserEnableJavascript: Boolean = true

	@JsonAttribute(name = "browserEnableImages")
	var browserEnableImages: Boolean = true

	@JsonAttribute(name = "browserEnablePopupBlocker")
	var browserEnablePopupBlocker: Boolean = true

	@JsonAttribute(name = "browserEnableVideoGrabber")
	var browserEnableVideoGrabber: Boolean = true

	@JsonAttribute(name = "browserHttpUserAgent")
	var browserHttpUserAgent: String = getText(string.text_browser_default_mobile_http_user_agent)

	/**
	 * Reads settings from internal storage and applies them to the current app instance.
	 * Attempts to read from binary format first, falls back to JSON if binary is invalid.
	 * Updates app state and validates user folder selection after successful read.
	 */
	fun readObjectFromStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			initializeLegacyDataParser()
		})
	}

	/**
	 * Attempts to restore previously saved AIO settings during app startup.
	 * Supports both **binary** and **JSON** formats for backward compatibility.
	 *
	 * Priority:
	 * 1. Load binary settings first (faster, more compact).
	 * 2. If binary fails, attempt to load JSON as fallback.
	 *
	 * Automatically re-saves successfully loaded settings to storage and
	 * validates the user-selected download folder to ensure it’s writable.
	 */
	private fun initializeLegacyDataParser() {
		try {
			var isBinaryFileValid = false

			// Retrieve reference to the internal data folder
			val internalDir = AIOApp.internalDataFolder

			// Attempt to locate the binary settings file within internal storage
			val settingsBinaryDataFile = internalDir.findFile(AIO_SETTINGS_FILE_NAME_BINARY)

			// STEP 1: Try to restore from binary settings file
			if (settingsBinaryDataFile != null && settingsBinaryDataFile.exists()) {
				logger.d("Found binary settings file, attempting to load")

				// Get absolute path and read binary contents
				val absolutePath = settingsBinaryDataFile.getAbsolutePath(INSTANCE)
				val objectInMemory = loadFromBinary(File(absolutePath))

				// Validate and apply loaded settings
				if (objectInMemory != null) {
					logger.d("Successfully loaded settings from binary format")

					// Assign loaded data to the global settings instance
					aioSettings = objectInMemory

					// Update persistent storage and validate directory access
					aioSettings.updateInStorage()
					validateUserSelectedFolder()

					isBinaryFileValid = true
				} else {
					logger.d("Failed to load settings from binary format")
				}
			}

			// STEP 2: Fallback to JSON format if binary load fails
			if (!isBinaryFileValid) {
				logger.d("Attempting to load settings from JSON format")

				readStringFromInternalStorage(AIO_SETTINGS_FILE_NAME_JSON).let { jsonString ->
					if (jsonString.isNotEmpty()) {
						// Deserialize JSON into AIOSettings object
						convertJSONStringToClass(jsonString = jsonString).let {
							logger.d("Successfully loaded settings from JSON format")

							// Assign loaded JSON data to the global instance
							aioSettings = it

							// Update persistent cache and validate folder access
							aioSettings.updateInStorage()
							validateUserSelectedFolder()
						}
					} else {
						logger.d("No JSON settings found or file empty")
					}
				}
			}

		} catch (error: Exception) {
			logger.e("Error reading settings from storage: ${error.message}", error)
		}
	}

	/**
	 * Saves the current settings object to the app’s internal storage.
	 *
	 * Behavior:
	 * - Runs asynchronously using [ThreadsUtility.executeInBackground] to prevent UI lag.
	 * - Serializes and saves settings in **two formats**:
	 *   1. **Binary**: compact and fast for internal deserialization.
	 *   2. **JSON**: human-readable format, useful for debugging and external tools.
	 * - Ensures reliability — if one format fails, the other may still succeed.
	 *
	 * Exception handling:
	 * - All operations are wrapped in a try-catch block.
	 * - Logs both success and failure through the [logger].
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating settings in storage")

				// Save optimized binary version
				saveToBinary(fileName = AIO_SETTINGS_FILE_NAME_BINARY)

				// Save readable JSON version
				saveStringToInternalStorage(
					fileName = AIO_SETTINGS_FILE_NAME_JSON,
					data = convertClassToJSON()
				)

				logger.d("Settings successfully updated in storage")
			} catch (error: Exception) {
				logger.e("Error updating settings in storage: ${error.message}", error)
			}
		})
	}

	/**
	 * Serializes and writes the current settings object to a binary file.
	 *
	 * Workflow:
	 * 1. Uses [fstConfig] for high-performance object serialization.
	 * 2. Opens the file output stream in **MODE_PRIVATE** (replaces existing file).
	 * 3. Converts this instance to a byte array and writes it to disk.
	 * 4. Closes the stream safely using `use` for automatic resource cleanup.
	 *
	 * Thread Safety:
	 * - Annotated with [Synchronized] to prevent concurrent write conflicts.
	 *
	 * @param fileName The name of the file (e.g., `"aio_settings.dat"`)
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving settings to binary file: $fileName")

			// Open or create binary file inside app’s internal storage
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)

			fileOutputStream.use { fos ->
				// Serialize current object instance to binary format
				val bytes = fstConfig.asByteArray(this)

				// Write binary data to file
				fos.write(bytes)
				logger.d("Binary settings saved successfully")
			}
		} catch (error: Exception) {
			logger.e("Error saving binary settings: ${error.message}", error)
		}
	}

	/**
	 * Loads and deserializes AIO settings from a binary file.
	 *
	 * Workflow:
	 * 1. Checks if the provided file exists.
	 * 2. Reads all bytes and deserializes them back into an [AIOSettings] object.
	 * 3. Returns `null` if file is missing or corrupted.
	 * 4. Automatically deletes corrupted binary files to prevent reloading errors.
	 *
	 * Reliability:
	 * - Restores previously saved configuration quickly using binary format.
	 * - Serves as a fast-loading alternative to JSON parsing.
	 *
	 * @param settingDataBinaryFile File reference pointing to the stored binary data.
	 * @return A deserialized [AIOSettings] object if successful, or `null` on failure.
	 */
	private fun loadFromBinary(settingDataBinaryFile: File): AIOSettings? {
		if (!settingDataBinaryFile.exists()) {
			logger.d("Binary settings file does not exist")
			return null
		}

		return try {
			logger.d("Loading settings from binary file")

			// Read all bytes and reconstruct the settings object
			val bytes = settingDataBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded settings from binary file")
			} as AIOSettings
		} catch (error: Exception) {
			logger.e("Error loading binary settings: ${error.message}", error)

			// Delete corrupted settings file to avoid repeated failures
			settingDataBinaryFile.delete()
			null
		}
	}

	/**
	 * Validates whether the folder selected by the user is writable.
	 *
	 * Workflow:
	 * 1. Retrieves the current user-selected folder via [getUserSelectedDir].
	 * 2. Checks if it is writable using [isWritableFile].
	 * 3. If not writable or inaccessible, automatically creates a default AIO folder
	 *    in the public download directory to prevent app errors.
	 * 4. Persists the updated settings to storage after validation.
	 *
	 * This method provides robust handling to ensure that invalid or restricted
	 * directory selections do not cause I/O issues during download operations.
	 */
	fun validateUserSelectedFolder() {
		logger.d("Validating user selected folder")

		// Check whether the currently configured folder is writable
		if (!isWritableFile(getUserSelectedDir())) {
			logger.d("User selected folder not writable, creating default folder")

			// If folder is invalid or inaccessible, revert to default AIO folder
			createDefaultAIODownloadFolder()
		}

		// Persist updated folder info in settings (e.g., JSON or binary file)
		aioSettings.updateInStorage()
	}

	/**
	 * Retrieves the user-selected download directory as a [DocumentFile].
	 *
	 * Behavior:
	 * - If the user preference points to the app’s private folder, returns a writable handle
	 *   to that internal directory.
	 * - If the preference targets the system gallery, resolves the external public folder path.
	 * - Returns `null` if no valid option is detected.
	 *
	 * This method uses the `fromFullPath()` utility to safely convert a file path into
	 * a [DocumentFile] object that supports scoped storage and runtime permissions.
	 *
	 * @return A [DocumentFile] representing the selected directory, or `null` if invalid.
	 */
	private fun getUserSelectedDir(): DocumentFile? {
		return when (aioSettings.defaultDownloadLocation) {
			PRIVATE_FOLDER -> {
				logger.d("Getting private folder directory")

				// Internal app data directory (private to the application)
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath

				// Convert to DocumentFile for safe file access
				fromFullPath(
					context = INSTANCE,
					fullPath = internalDataFolderPath,
					requiresWriteAccess = true
				)
			}

			SYSTEM_GALLERY -> {
				logger.d("Getting system gallery directory")

				// Retrieve localized path for system gallery folder
				val resID = string.text_default_aio_download_folder_path
				val externalDataFolderPath = getText(resID)

				// Convert to DocumentFile representing the public gallery path
				fromFullPath(
					context = INSTANCE,
					fullPath = externalDataFolderPath,
					requiresWriteAccess = true
				)
			}

			else -> {
				// Unknown or unsupported download location type
				logger.d("Unknown download location type")
				null
			}
		}
	}

	/**
	 * Attempts to create the default AIO folder inside the public download directory.
	 *
	 * This method:
	 * - Retrieves the localized default folder name from string resources.
	 * - Attempts to create that folder in the device’s shared "Downloads" directory.
	 * - Logs both success and failure states without throwing exceptions.
	 *
	 * The purpose is to ensure that the application always has a designated folder
	 * for storing downloaded or generated media.
	 */
	private fun createDefaultAIODownloadFolder() {
		try {
			logger.d("Creating default AIO download folder")

			// Retrieve default folder name from localized strings (e.g., "AIO Downloads")
			val defaultFolderName = getText(string.title_default_application_folder)

			// Attempt to create directory inside the public download folder
			INSTANCE.getPublicDownloadDir()?.createDirectory(defaultFolderName)

			// Log success if no exception occurred
			logger.d("Default folder created successfully")
		} catch (error: Exception) {
			// Log detailed error message but do not propagate the exception
			logger.e("Error creating default folder: ${error.message}", error)
		}
	}

	/**
	 * Converts the current AIOSettings instance into a JSON string representation.
	 *
	 * This function:
	 * - Uses the global DSL-JSON serializer (`aioDSLJsonInstance`) for efficient serialization.
	 * - Writes the object into an in-memory output stream.
	 * - Returns the final UTF-8 string.
	 *
	 * Useful for persisting user preferences or exporting configuration data.
	 *
	 * @return A JSON string containing all serialized AIOSettings fields.
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting settings to JSON")

		// Create a temporary stream to hold the serialized JSON data
		val outputStream = ByteArrayOutputStream()

		// Serialize the current object instance into the stream
		aioDSLJsonInstance.serialize(this, outputStream)

		// Convert the byte stream to a readable UTF-8 string and return
		return outputStream.toByteArray().decodeToString()
	}

	/**
	 * Converts a JSON-formatted string into an AIOSettings object.
	 *
	 * This function:
	 * - Wraps the given JSON string into an input stream.
	 * - Uses the same DSL-JSON instance to deserialize it into an AIOSettings object.
	 * - Falls back to a new default instance if deserialization fails (null result).
	 *
	 * Commonly used when restoring settings from disk or network.
	 *
	 * @param jsonString The JSON string to deserialize.
	 * @return A fully populated AIOSettings object, or a default one if parsing fails.
	 */
	private fun convertJSONStringToClass(jsonString: String): AIOSettings {
		logger.d("Converting JSON to settings object")

		// Prepare a byte stream from the input JSON string
		val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())

		// Deserialize the JSON into an AIOSettings object; fallback to default if null
		val loadedSettings: AIOSettings = aioDSLJsonInstance
			.deserialize(AIOSettings::class.java, inputStream) ?: AIOSettings()

		return loadedSettings
	}

	companion object {

		/**
		 * Filename used to indicate that dark mode is enabled.
		 * This file acts as a flag for the app's dark mode state.
		 */
		const val AIO_SETTING_DARK_MODE_FILE_NAME: String = "darkmode.on"

		/**
		 * Filename for storing user or app settings in JSON format.
		 */
		const val AIO_SETTINGS_FILE_NAME_JSON: String = "aio_settings.json"

		/**
		 * Filename for storing user or app settings in binary format.
		 */
		const val AIO_SETTINGS_FILE_NAME_BINARY: String = "aio_settings.dat"

		/**
		 * Constant representing the private download folder.
		 * Files saved here are accessible only within the app.
		 */
		const val PRIVATE_FOLDER = 1

		/**
		 * Constant representing the system gallery download folder.
		 * Files saved here are visible to other apps (e.g., Photos, Gallery).
		 */
		const val SYSTEM_GALLERY = 2
	}
}