package app.core.engines.settings

import android.content.Context.MODE_PRIVATE
import androidx.documentfile.provider.DocumentFile
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.kryoDBHelper
import app.core.AIOLanguage.Companion.ENGLISH
import app.core.KryoRegistry
import com.aio.R.string
import com.anggrayudi.storage.file.DocumentFileCompat.fromFullPath
import com.anggrayudi.storage.file.getAbsolutePath
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import lib.files.FileSystemUtility.isWritableFile
import lib.files.FileSystemUtility.readStringFromInternalStorage
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.FileInputStream
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
class AIOSettings : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	// Basic user state
	var userInstallationId: String = ""
	var isFirstTimeLanguageSelectionComplete = false
	var hasUserRatedTheApplication: Boolean = false
	var totalNumberOfSuccessfulDownloads = 0
	var totalUsageTimeInMs = 0.0f
	var totalUsageTimeInFormat = ""
	var lastProcessedClipboardText = ""

	// Default download location
	var defaultDownloadLocation = SYSTEM_GALLERY

	// Language settings
	var userSelectedUILanguage: String = ENGLISH

	// Analytics / interaction counters
	var totalClickCountOnRating = 0
	var totalClickCountOnLanguageChange = 0
	var totalClickCountOnMediaPlayback = 0
	var totalClickCountOnHowToGuide = 0
	var totalClickCountOnVideoUrlEditor = 0
	var totalClickCountOnHomeHistory = 0
	var totalClickCountOnHomeBookmarks = 0
	var totalClickCountOnRecentDownloads = 0
	var totalClickCountOnHomeFavicon = 0
	var totalClickCountOnVersionCheck = 0
	var totalInterstitialAdClick = 0
	var totalInterstitialImpression = 0
	var totalRewardedAdClick = 0
	var totalRewardedImpression = 0

	// Path to WhatsApp Statuses folder (used in status saving)
	val whatsAppStatusFullFolderPath = getText(string.text_whatsapp_status_file_dir)

	// Download preferences
	var downloadSingleUIProgress: Boolean = true
	var downloadHideVideoThumbnail: Boolean = false
	var downloadPlayNotificationSound: Boolean = true
	var downloadHideNotification: Boolean = false
	var hideDownloadProgressFromUI: Boolean = false
	var downloadAutoRemoveTasks: Boolean = false
	var downloadAutoRemoveTaskAfterNDays: Int = 0
	var openDownloadedFileOnSingleClick: Boolean = true

	// Advanced download features
	var downloadAutoResume: Boolean = true
	var downloadAutoResumeMaxErrors: Int = 35
	var downloadAutoLinkRedirection: Boolean = true
	var downloadAutoFolderCatalog: Boolean = true
	var downloadAutoThreadSelection: Boolean = true
	var downloadAutoFileMoveToPrivate: Boolean = false
	var downloadAutoConvertVideosToMp3: Boolean = false

	// Download performance settings
	var downloadBufferSize: Int = 1024 * 8
	var downloadMaxHttpReadingTimeout: Int = 1000 * 10
	var downloadDefaultThreadConnections: Int = 1
	var downloadDefaultParallelConnections: Int = 10
	var downloadVerifyChecksum: Boolean = false
	var downloadMaxNetworkSpeed: Long = 0
	var downloadWifiOnly: Boolean = false
	var downloadHttpUserAgent: String =
		getText(string.text_downloads_default_http_user_agent)
	var downloadHttpProxyServer = ""

	// Crash handling
	var hasAppCrashedRecently: Boolean = false

	// Privacy and limits
	var privateFolderPassword: String = ""
	var numberOfMaxDownloadThreshold = 1
	var numberOfDownloadsUserDid = 0

	// Browser-specific settings
	var browserDefaultHomepage: String = getText(string.text_https_google_com)
	var browserDesktopBrowsing: Boolean = false
	var browserEnableAdblocker: Boolean = false
	var browserEnableJavascript: Boolean = true
	var browserEnableHideImages: Boolean = false
	var browserEnablePopupBlocker: Boolean = false
	var browserEnableVideoGrabber: Boolean = true
	var browserHttpUserAgent: String =
		getText(string.text_browser_default_mobile_http_user_agent)

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
							convertJSONStringToClass(data = jsonString).let {
								logger.d("Successfully loaded settings from JSON format")
								aioSettings = it
								aioSettings.updateInStorage()
								validateUserSelectedFolder()
							}
						}
					}
				}
			} catch (error: Exception) {
				logger.d("Error reading settings from storage: ${error.message}")
				error.printStackTrace()
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
				logger.d("Error updating settings in storage: ${error.message}")
				error.printStackTrace()
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
				Output(fos).use { output ->
					KryoRegistry.registerClasses(kryoDBHelper)
					kryoDBHelper.writeObject(output, this)
				}
			}
			logger.d("Binary settings saved successfully")
		} catch (error: Exception) {
			logger.d("Error saving binary settings: ${error.message}")
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
			FileInputStream(settingDataBinaryFile).use { fis ->
				Input(fis).use { input ->
					KryoRegistry.registerClasses(kryoDBHelper)
					kryoDBHelper.readObject(input, AIOSettings::class.java).also {
						logger.d("Successfully loaded settings from binary file")
					}
				}
			}
		} catch (error: Exception) {
			logger.d("Error loading binary settings: ${error.message}")
			settingDataBinaryFile.delete()
			error.printStackTrace()
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
	 * Gets the user-selected directory as a DocumentFile.
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
				val externalDataFolderPath =
					getText(string.text_default_aio_download_folder_path)
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
			logger.d("Error creating default folder: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Converts this settings object to a JSON string.
	 * @return JSON representation of the settings
	 */
	fun convertClassToJSON(): String {
		logger.d("Converting settings to JSON")
		return aioGSONInstance.toJson(this)
	}

	/**
	 * Converts a JSON string to an AIOSettings object.
	 * @param data The JSON string to convert
	 * @return The deserialized AIOSettings object
	 */
	private fun convertJSONStringToClass(data: String): AIOSettings {
		logger.d("Converting JSON to settings object")
		return aioGSONInstance.fromJson(data, AIOSettings::class.java)
	}

	companion object {

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