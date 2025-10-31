package app.core.engines.settings

import android.content.Context.MODE_PRIVATE
import androidx.documentfile.provider.DocumentFile
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioDSLJsonInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOLanguage.Companion.ENGLISH
import app.core.FSTBuilder.fstConfig
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
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
 * Class representing persistent user settings for the AIO application using ObjectBox for storage.
 *
 * This class manages all user preferences and application state that needs to persist between sessions.
 * It replaces the previous JSON and binary file storage with ObjectBox database for better performance,
 * reliability, and data integrity.
 *
 * Key Features:
 * - ObjectBox database persistence with automatic CRUD operations
 * - User preferences for downloads, browser, and UI customization
 * - Analytics tracking for user interactions and engagement metrics
 * - Download management settings including performance and behavior configurations
 * - Browser-specific configurations and privacy settings
 * - Application appearance and theme management
 * - Storage location validation and management
 * - Comprehensive default values for all settings
 *
 * Storage Architecture:
 * - Uses ObjectBox as the primary data store with a single entity record (ID = 1)
 * - Maintains backward compatibility with previous serialization annotations
 * - Provides thread-safe operations for concurrent access
 * - Automatic background persistence without manual file management
 *
 * @see io.objectbox.annotation.Entity for database entity configuration
 * @see com.dslplatform.json.CompiledJson for JSON serialization compatibility
 */
@CompiledJson
@Entity
class AIOSettings : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Unique identifier for the settings record in ObjectBox database.
	 * Fixed to 1 since there's only one settings instance per application.
	 *
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0L

	// =============================================
	// BASIC USER STATE & APPLICATION IDENTIFICATION
	// =============================================

	/** Unique identifier for the associate download model */
	@JvmField @JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelId: Long = -1L

	/**
	 * Unique installation identifier for analytics and user tracking.
	 * Generated once during first app launch and persisted across sessions.
	 */
	@JvmField @JsonAttribute(name = "userInstallationId")
	var userInstallationId: String = ""

	/**
	 * Flag indicating whether the user has completed the initial language selection flow.
	 * Used to determine if the language selection screen should be shown on app start.
	 */
	@JvmField @JsonAttribute(name = "isFirstTimeLanguageSelectionComplete")
	var isFirstTimeLanguageSelectionComplete: Boolean = false

	/**
	 * Tracks if the user has rated the application in the play store.
	 * Used to control rating prompt frequency and user engagement flows.
	 */
	@JvmField @JsonAttribute(name = "hasUserRatedTheApplication")
	var hasUserRatedTheApplication: Boolean = false

	/**
	 * Counter for total successful download operations completed by the user.
	 * Used for analytics, user engagement metrics, and feature unlocking.
	 */
	@JvmField @JsonAttribute(name = "totalNumberOfSuccessfulDownloads")
	var totalNumberOfSuccessfulDownloads: Int = 0

	/**
	 * Cumulative time spent in the application measured in milliseconds.
	 * Tracked for user engagement analytics and session management.
	 */
	@JvmField @JsonAttribute(name = "totalUsageTimeInMs")
	var totalUsageTimeInMs: Float = 0.0f

	/**
	 * Formatted representation of total usage time for display purposes.
	 * Automatically updated from totalUsageTimeInMs for UI presentation.
	 */
	@JvmField @JsonAttribute(name = "totalUsageTimeInFormat")
	var totalUsageTimeInFormat: String = ""

	/**
	 * Last clipboard text processed by the application for URL detection.
	 * Used to prevent duplicate processing of the same clipboard content.
	 */
	@JvmField @JsonAttribute(name = "lastProcessedClipboardText")
	var lastProcessedClipboardText: String = ""

	// =============================================
	// STORAGE & DOWNLOAD CONFIGURATION
	// =============================================

	/**
	 * Preferred download location setting.
	 *
	 * Possible values:
	 * - [PRIVATE_FOLDER] (1): App's private internal storage
	 * - [SYSTEM_GALLERY] (2): Public gallery or external storage
	 *
	 * @see PRIVATE_FOLDER
	 * @see SYSTEM_GALLERY
	 */
	@JvmField @JsonAttribute(name = "defaultDownloadLocation")
	var defaultDownloadLocation: Int = PRIVATE_FOLDER

	// =============================================
	// LANGUAGE & REGIONAL SETTINGS
	// =============================================

	/**
	 * User-selected UI language for the application.
	 * Defaults to English. Affects all text and interface elements.
	 *
	 * @see app.core.AIOLanguage for available language options
	 */
	@JvmField @JsonAttribute(name = "userSelectedUILanguage")
	var userSelectedUILanguage: String = ENGLISH

	/**
	 * User-selected content region for localized content and services.
	 * Uses ISO country codes (e.g., "IN", "US", "GB").
	 * Affects content recommendations and regional services.
	 */
	@JvmField @JsonAttribute(name = "userSelectedContentRegion")
	var userSelectedContentRegion: String = "IN"

	// =============================================
	// APPEARANCE & UI PREFERENCES
	// =============================================

	/**
	 * Application theme appearance setting.
	 *
	 * Possible values:
	 * - -1: Automatic (follows system theme)
	 * - 1: Dark mode
	 * - 2: Light mode
	 */
	@JvmField @JsonAttribute(name = "themeAppearance")
	var themeAppearance: Int = -1

	/**
	 * Enables or disables daily content suggestions in the application.
	 * When enabled, users receive personalized content recommendations.
	 */
	@JvmField @JsonAttribute(name = "enableDailyContentSuggestion")
	var enableDailyContentSuggestion: Boolean = true

	// =============================================
	// ANALYTICS & USER INTERACTION TRACKING
	// =============================================

	/** Counter for language change button clicks */
	@JvmField @JsonAttribute(name = "totalClickCountOnLanguageChange")
	var totalClickCountOnLanguageChange: Int = 0

	/** Counter for media playback interactions */
	@JvmField @JsonAttribute(name = "totalClickCountOnMediaPlayback")
	var totalClickCountOnMediaPlayback: Int = 0

	/** Counter for how-to guide accesses */
	@JvmField @JsonAttribute(name = "totalClickCountOnHowToGuide")
	var totalClickCountOnHowToGuide: Int = 0

	/** Counter for video URL editor usages */
	@JvmField @JsonAttribute(name = "totalClickCountOnVideoUrlEditor")
	var totalClickCountOnVideoUrlEditor: Int = 0

	/** Counter for home history section accesses */
	@JvmField @JsonAttribute(name = "totalClickCountOnHomeHistory")
	var totalClickCountOnHomeHistory: Int = 0

	/** Counter for bookmark management interactions */
	@JvmField @JsonAttribute(name = "totalClickCountOnHomeBookmarks")
	var totalClickCountOnHomeBookmarks: Int = 0

	/** Counter for recent downloads section accesses */
	@JvmField @JsonAttribute(name = "totalClickCountOnRecentDownloads")
	var totalClickCountOnRecentDownloads: Int = 0

	/** Counter for home screen favicon interactions */
	@JvmField @JsonAttribute(name = "totalClickCountOnHomeFavicon")
	var totalClickCountOnHomeFavicon: Int = 0

	/** Counter for version check operations */
	@JvmField @JsonAttribute(name = "totalClickCountOnVersionCheck")
	var totalClickCountOnVersionCheck: Int = 0

	/** Tracks interstitial advertisement click interactions */
	@JvmField @JsonAttribute(name = "totalInterstitialAdClick")
	var totalInterstitialAdClick: Int = 0

	/** Tracks interstitial advertisement impression counts */
	@JvmField @JsonAttribute(name = "totalInterstitialImpression")
	var totalInterstitialImpression: Int = 0

	/** Tracks rewarded advertisement click interactions */
	@JvmField @JsonAttribute(name = "totalRewardedAdClick")
	var totalRewardedAdClick: Int = 0

	/** Tracks rewarded advertisement impression counts */
	@JvmField @JsonAttribute(name = "totalRewardedImpression")
	var totalRewardedImpression: Int = 0

	// =============================================
	// PATH CONFIGURATIONS
	// =============================================

	/**
	 * Full folder path for WhatsApp status storage.
	 * Read-only value initialized from string resources.
	 */
	@JvmField @JsonAttribute(name = "whatsAppStatusFullFolderPath")
	val whatsAppStatusFullFolderPath: String = getText(string.text_whatsapp_status_file_dir)

	// =============================================
	// DOWNLOAD PREFERENCES & BEHAVIOR
	// =============================================

	/** Enables single progress UI for download operations */
	@JvmField @JsonAttribute(name = "downloadSingleUIProgress")
	var downloadSingleUIProgress: Boolean = true

	/** Hides video thumbnails in download lists for privacy */
	@JvmField @JsonAttribute(name = "downloadHideVideoThumbnail")
	var downloadHideVideoThumbnail: Boolean = false

	/** Plays notification sound on download completion */
	@JvmField @JsonAttribute(name = "downloadPlayNotificationSound")
	var downloadPlayNotificationSound: Boolean = true

	/** Hides system notifications for download operations */
	@JvmField @JsonAttribute(name = "downloadHideNotification")
	var downloadHideNotification: Boolean = false

	/** Hides download progress from main UI elements */
	@JvmField @JsonAttribute(name = "hideDownloadProgressFromUI")
	var hideDownloadProgressFromUI: Boolean = false

	/** Enables automatic removal of completed download tasks */
	@JvmField @JsonAttribute(name = "downloadAutoRemoveTasks")
	var downloadAutoRemoveTasks: Boolean = false

	/** Number of days after which completed tasks are automatically removed */
	@JvmField @JsonAttribute(name = "downloadAutoRemoveTaskAfterNDays")
	var downloadAutoRemoveTaskAfterNDays: Int = 0

	/** Opens downloaded files on single click instead of long press */
	@JvmField @JsonAttribute(name = "openDownloadedFileOnSingleClick")
	var openDownloadedFileOnSingleClick: Boolean = true

	// =============================================
	// ADVANCED DOWNLOAD FEATURES
	// =============================================

	/** Enables automatic resumption of interrupted downloads */
	@JvmField @JsonAttribute(name = "downloadAutoResume")
	var downloadAutoResume: Boolean = true

	/** Maximum number of errors before stopping auto-resume attempts */
	@JvmField @JsonAttribute(name = "downloadAutoResumeMaxErrors")
	var downloadAutoResumeMaxErrors: Int = 35

	/** Enables automatic handling of URL redirections during downloads */
	@JvmField @JsonAttribute(name = "downloadAutoLinkRedirection")
	var downloadAutoLinkRedirection: Boolean = true

	/** Enables automatic cataloging of downloads into folders */
	@JvmField @JsonAttribute(name = "downloadAutoFolderCatalog")
	var downloadAutoFolderCatalog: Boolean = true

	/** Enables automatic thread selection for parallel downloads */
	@JvmField @JsonAttribute(name = "downloadAutoThreadSelection")
	var downloadAutoThreadSelection: Boolean = true

	/** Automatically moves downloaded files to private storage */
	@JvmField @JsonAttribute(name = "downloadAutoFileMoveToPrivate")
	var downloadAutoFileMoveToPrivate: Boolean = false

	/** Automatically converts downloaded videos to MP3 format */
	@JvmField @JsonAttribute(name = "downloadAutoConvertVideosToMp3")
	var downloadAutoConvertVideosToMp3: Boolean = false

	// =============================================
	// DOWNLOAD PERFORMANCE SETTINGS
	// =============================================

	/** Buffer size in bytes for download operations (default: 8KB) */
	@JvmField @JsonAttribute(name = "downloadBufferSize")
	var downloadBufferSize: Int = 1024 * 8

	/** Maximum HTTP read timeout in milliseconds (default: 30 seconds) */
	@JvmField @JsonAttribute(name = "downloadMaxHttpReadingTimeout")
	var downloadMaxHttpReadingTimeout: Int = 1000 * 30

	/** Default number of thread connections per download */
	@JvmField @JsonAttribute(name = "downloadDefaultThreadConnections")
	var downloadDefaultThreadConnections: Int = 1

	/** Default number of parallel download connections */
	@JvmField @JsonAttribute(name = "downloadDefaultParallelConnections")
	var downloadDefaultParallelConnections: Int = 10

	/** Enables checksum verification for downloaded files */
	@JvmField @JsonAttribute(name = "downloadVerifyChecksum")
	var downloadVerifyChecksum: Boolean = false

	/** Maximum network speed in bytes per second (0 = unlimited) */
	@JvmField @JsonAttribute(name = "downloadMaxNetworkSpeed")
	var downloadMaxNetworkSpeed: Long = 0

	/** Restricts downloads to WiFi connections only */
	@JvmField @JsonAttribute(name = "downloadWifiOnly")
	var downloadWifiOnly: Boolean = false

	/** HTTP User-Agent string used for download requests */
	@JvmField @JsonAttribute(name = "downloadHttpUserAgent")
	var downloadHttpUserAgent: String = getText(string.text_downloads_default_http_user_agent)

	/** HTTP proxy server configuration for downloads */
	@JvmField @JsonAttribute(name = "downloadHttpProxyServer")
	var downloadHttpProxyServer: String = ""

	// =============================================
	// APPLICATION STABILITY & CRASH HANDLING
	// =============================================

	/**
	 * Flag indicating if the application crashed during the previous session.
	 * Used for crash recovery and stability monitoring.
	 */
	@JvmField @JsonAttribute(name = "hasAppCrashedRecently")
	var hasAppCrashedRecently: Boolean = false

	// =============================================
	// PRIVACY & SECURITY SETTINGS
	// =============================================

	/** Password for accessing private folder (encrypted storage) */
	@JvmField @JsonAttribute(name = "privateFolderPassword")
	var privateFolderPassword: String = ""

	/** Maximum number of downloads allowed (rate limiting) */
	@JvmField @JsonAttribute(name = "numberOfMaxDownloadThreshold")
	var numberOfMaxDownloadThreshold: Int = 1

	/** Counter for total downloads performed by user */
	@JvmField @JsonAttribute(name = "numberOfDownloadsUserDid")
	var numberOfDownloadsUserDid: Int = 0

	// =============================================
	// BROWSER-SPECIFIC SETTINGS
	// =============================================

	/** Default homepage URL for the in-app browser */
	@JvmField @JsonAttribute(name = "browserDefaultHomepage")
	var browserDefaultHomepage: String = getText(string.text_https_google_com)

	/** Enables desktop-mode browsing instead of mobile view */
	@JvmField @JsonAttribute(name = "browserDesktopBrowsing")
	var browserDesktopBrowsing: Boolean = false

	/** Enables ad-blocking functionality in the browser */
	@JvmField @JsonAttribute(name = "browserEnableAdblocker")
	var browserEnableAdblocker: Boolean = true

	/** Enables JavaScript execution in the browser */
	@JvmField @JsonAttribute(name = "browserEnableJavascript")
	var browserEnableJavascript: Boolean = true

	/** Enables image loading in the browser */
	@JvmField @JsonAttribute(name = "browserEnableImages")
	var browserEnableImages: Boolean = true

	/** Enables popup blocking in the browser */
	@JvmField @JsonAttribute(name = "browserEnablePopupBlocker")
	var browserEnablePopupBlocker: Boolean = true

	/** Enables video grabber functionality in the browser */
	@JvmField @JsonAttribute(name = "browserEnableVideoGrabber")
	var browserEnableVideoGrabber: Boolean = true

	/** HTTP User-Agent string used for browser requests */
	@JvmField @JsonAttribute(name = "browserHttpUserAgent")
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
				AIOSettingsDBManager.saveSettingsInDB(settings = this)
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