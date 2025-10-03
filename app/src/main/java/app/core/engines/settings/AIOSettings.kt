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
class AIOSettings : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

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

	// Other settings
	@JsonAttribute(name = "enableDarkUIMode")
	var enableDarkUIMode: Boolean = false

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
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val settingsBinaryDataFile = internalDir.findFile(AIO_SETTINGS_FILE_NAME_BINARY)

				if (settingsBinaryDataFile != null && settingsBinaryDataFile.exists()) {
					logger.d("Found binary settings file, attempting to load")
					val absolutePath = settingsBinaryDataFile.getAbsolutePath(INSTANCE)
					val objectInMemory = loadFromBinary(File(absolutePath))

					if (objectInMemory != null) {
						logger.d("Successfully loaded settings from binary format")
						aioSettings = objectInMemory
						aioSettings.updateInStorage()
						validateUserSelectedFolder()
						isBinaryFileValid = true
					} else {
						logger.d("Failed to load settings from binary format")
					}
				}

				if (!isBinaryFileValid) {
					logger.d("Attempting to load settings from JSON format")
					readStringFromInternalStorage(AIO_SETTINGS_FILE_NAME_JSON).let { jsonString ->
						if (jsonString.isNotEmpty()) {
							convertJSONStringToClass(jsonString = jsonString).let {
								logger.d("Successfully loaded settings from JSON format")
								aioSettings = it
								aioSettings.updateInStorage()
								validateUserSelectedFolder()
							}
						}
					}
				}
			} catch (error: Exception) {
				logger.e("Error reading settings from storage: ${error.message}", error)
			}
		})
	}

	/**
	 * Saves current settings to internal storage in both binary and JSON formats.
	 * Executes in background thread to avoid UI freezing.
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating settings in storage")
				saveToBinary(fileName = AIO_SETTINGS_FILE_NAME_BINARY)
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
	 * Saves the current settings object to binary format.
	 * @param fileName The name of the file to save to
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving settings to binary file: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				fos.write(bytes)
				logger.d("Binary settings saved successfully")
			}
		} catch (error: Exception) {
			logger.e("Error saving binary settings: ${error.message}", error)
		}
	}

	/**
	 * Loads settings from a binary file.
	 * @param settingDataBinaryFile The binary file containing settings
	 * @return The loaded AIOSettings object or null if loading fails
	 */
	private fun loadFromBinary(settingDataBinaryFile: File): AIOSettings? {
		if (!settingDataBinaryFile.exists()) {
			logger.d("Binary settings file does not exist")
			return null
		}

		return try {
			logger.d("Loading settings from binary file")
			val bytes = settingDataBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded settings from binary file")
			} as AIOSettings
		} catch (error: Exception) {
			logger.e("Error loading binary settings: ${error.message}", error)
			settingDataBinaryFile.delete()
			null
		}
	}

	/**
	 * Validates whether the user-selected folder is writable.
	 * If not writable, falls back to creating a default download folder.
	 */
	fun validateUserSelectedFolder() {
		logger.d("Validating user selected folder")
		if (!isWritableFile(getUserSelectedDir())) {
			logger.d("User selected folder not writable, creating default folder")
			createDefaultAIODownloadFolder()
		}

		aioSettings.updateInStorage()
	}

	/**
	 * Gets the user-selected download directory as a DocumentFile.
	 * @return DocumentFile representing the selected directory or null if invalid
	 */
	private fun getUserSelectedDir(): DocumentFile? {
		return when (aioSettings.defaultDownloadLocation) {
			PRIVATE_FOLDER -> {
				logger.d("Getting private folder directory")
				val internalDataFolderPath = INSTANCE.dataDir.absolutePath
				fromFullPath(
					context = INSTANCE,
					fullPath = internalDataFolderPath,
					requiresWriteAccess = true
				)
			}

			SYSTEM_GALLERY -> {
				logger.d("Getting system gallery directory")
				val resID = string.text_default_aio_download_folder_path
				val externalDataFolderPath = getText(resID)
				fromFullPath(
					context = INSTANCE,
					fullPath = externalDataFolderPath,
					requiresWriteAccess = true
				)
			}

			else -> {
				logger.d("Unknown download location type")
				null
			}
		}
	}

	/**
	 * Attempts to create a default AIO folder in the public download directory.
	 * Logs success or failure but does not throw exceptions.
	 */
	private fun createDefaultAIODownloadFolder() {
		try {
			logger.d("Creating default AIO download folder")
			val defaultFolderName = getText(string.title_default_application_folder)
			INSTANCE.getPublicDownloadDir()?.createDirectory(defaultFolderName)
			logger.d("Default folder created successfully")
		} catch (error: Exception) {
			logger.e("Error creating default folder: ${error.message}", error)
		}
	}

	/**
	 * Converts this settings object to a JSON string.
	 * @return JSON representation of the settings
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting settings to JSON")
		val outputStream = ByteArrayOutputStream()
		aioDSLJsonInstance.serialize(this, outputStream) // write to stream
		return outputStream.toByteArray().decodeToString() // convert to String
	}

	/**
	 * Updates the UI mode marker by creating or deleting the `darkmode.on` file.
	 *
	 * - Creates the file if dark mode is enabled.
	 * - Deletes the file if dark mode is disabled.
	 *
	 * This lightweight file-based flag allows other parts of the app to
	 * quickly detect the current UI mode without reading settings.
	 */
	fun updateUIMode() {
		try {
			val tempFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)

			if (aioSettings.enableDarkUIMode) {
				if (!tempFile.exists()) tempFile.createNewFile()
			} else tempFile.delete()
		} catch (error: Exception) {
			logger.e("Error saving or deleting darkmode.on file:", error)
		}
	}

	/**
	 * Converts a JSON string to an AIOSettings object.
	 * @param jsonString The JSON string to convert
	 * @return The deserialized AIOSettings object
	 */
	private fun convertJSONStringToClass(jsonString: String): AIOSettings {
		logger.d("Converting JSON to settings object")
		val inputStream = ByteArrayInputStream(jsonString.encodeToByteArray())
		val loadedSettings: AIOSettings = aioDSLJsonInstance.deserialize(
			AIOSettings::class.java, inputStream
		) ?: AIOSettings()
		return loadedSettings
	}

	companion object {

		const val AIO_SETTING_DARK_MODE_FILE_NAME: String = "darkmode.on"

		/**
		 * JSON settings filename
		 */
		const val AIO_SETTINGS_FILE_NAME_JSON: String = "aio_settings.json"

		/**
		 * Binary settings filename
		 */
		const val AIO_SETTINGS_FILE_NAME_BINARY: String = "aio_settings.dat"

		/**
		 * Download location constant for private folder
		 */
		const val PRIVATE_FOLDER = 1

		/**
		 * Download location constant for system gallery
		 */
		const val SYSTEM_GALLERY = 2
	}
}