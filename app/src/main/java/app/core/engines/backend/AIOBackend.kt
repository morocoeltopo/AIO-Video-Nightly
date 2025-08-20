package app.core.engines.backend

import android.os.Build
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.DownloadDataModel
import com.aio.R
import com.parse.Parse
import com.parse.ParseInstallation.getCurrentInstallation
import com.parse.ParseObject
import lib.device.AppVersionUtility.versionName
import lib.device.DeviceInfoUtils.getDeviceInformation
import lib.device.DeviceUtility.getDeviceUserCountry
import lib.networks.NetworkUtility.isWifiEnabled
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.texts.CommonTextUtils.getText

/**
 * Handles all backend operations for the app, including:
 * - Parse server initialization
 * - Analytics tracking
 * - Crash reporting
 * - User feedback collection
 * - Download logging
 *
 * All operations are executed asynchronously where possible
 * to ensure the main thread (UI) is never blocked.
 */
class AIOBackend {

	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Initializes the Parse backend on object creation.
	 * Uses a background thread to prevent blocking the UI thread.
	 */
	init {
		executeInBackground(codeBlock = { initParseBackend() })
	}

	/**
	 * Initializes the Parse backend with credentials stored in string resources.
	 * Sets up the current installation with device and app details for tracking.
	 */
	private fun initParseBackend() {
		try {
			// Initialize Parse with credentials from resources
			Parse.initialize(
				Parse.Configuration.Builder(INSTANCE)
					.applicationId(getText(R.string.text_back4app_app_id))
					.clientKey(getText(R.string.text_back4app_client_key))
					.server(getText(R.string.text_back4app_server_url))
					.build()
			)

			// Track installation information
			val installation = getCurrentInstallation()
			installation.apply {
				put("user_country", getDeviceUserCountry())
				put("device_model", Build.MODEL)
				put("device_brand", Build.BRAND)
				put("device_version", Build.VERSION.RELEASE)
				put("app_version", versionName ?: "n/a")
				put("network_type", if (isWifiEnabled()) "Wifi" else "Mobile Data")
				put("extra_device_info", getDeviceInformation(INSTANCE))
			}

			// Save installation info to server and locally
			installation.saveInBackground { error ->
				if (error == null) {
					val installationId = installation.installationId
					saveInstallationIdLocally(installationId)
				} else logger.d("Failed to save installation: ${error.message}")
			}
		} catch (error: Exception) {
			logger.d("initParseBackend failed: ${error.message}")
		}
	}

	/**
	 * Tracks runtime stats and user behavior for analytics.
	 * Metrics include app usage time, downloads, clicks, and settings state.
	 */
	fun trackApplicationInfo() {
		try {
			val installation = getCurrentInstallation()
			installation.apply {
				put("app_runtime", aioSettings.totalUsageTimeInFormat)
				put("total_downloads", aioSettings.totalNumberOfSuccessfulDownloads)
				put("guide_clicks", aioSettings.totalClickCountOnHowToGuide)
				put("media_playbacks", aioSettings.totalClickCountOnMediaPlayback)
				put("language_changes", aioSettings.totalClickCountOnLanguageChange)
				put("video_editor_clicks", aioSettings.totalClickCountOnVideoUrlEditor)
				put("browser_bookmarks", aioBookmark.getBookmarkLibrary().size)
				put("browser_history", aioHistory.getHistoryLibrary().size)
				put("aio_settings_json", aioSettings.convertClassToJSON())
			}
			installation.saveInBackground()
		} catch (error: Exception) {
			logger.d("trackApplicationInfo failed: ${error.message}")
		}
	}

	/**
	 * Logs download details to the backend for analytics.
	 * @param downloadDataModel The model containing download details
	 */
	fun saveDownloadLog(downloadDataModel: DownloadDataModel) {
		try {
			val cloudTable = ParseObject("DownloadLogs").apply {
				put("installation_id", getCurrentInstallation().installationId)
				put("file_name", downloadDataModel.fileName)
				put("file_directory", downloadDataModel.fileDirectory)
				put("file_url", downloadDataModel.fileURL)
				put("downloaded_date", downloadDataModel.lastModifiedTimeDateInFormat)
				put("downloaded_time", downloadDataModel.timeSpentInFormat)
				put("average_speed", downloadDataModel.averageSpeedInFormat)
				put("entire_class_json", downloadDataModel.convertClassToJSON())
				put("user_country", getDeviceUserCountry())
				put("device_model", Build.MODEL)
				put("device_brand", Build.BRAND)
				put("device_version", Build.VERSION.RELEASE)
				put("app_version", versionName ?: "n/a")
				put("network_type", if (isWifiEnabled()) "Wifi" else "Mobile Data")
			}

			cloudTable.saveInBackground()
		} catch (error: Exception) {
			logger.d("saveDownloadLog failed: ${error.message}")
		}
	}

	/**
	 * Saves feedback messages provided by the user.
	 * @param userMessage Feedback text entered by the user
	 */
	fun saveUserFeedback(userMessage: String) {
		try {
			val cloudTable = ParseObject("UserFeedbacks")
				.apply { put("message", userMessage) }
			cloudTable.saveInBackground()
		} catch (error: Exception) {
			logger.d("saveUserFeedback failed: ${error.message}")
		}
	}

	/**
	 * Logs crash details to the backend for debugging purposes.
	 * @param detailedLogMsg Crash details and stack trace
	 */
	fun saveAppCrashedInfo(detailedLogMsg: String) {
		try {
			val cloudTable = ParseObject("AppCrashedInfo").apply {
				put("detailed_log_msg", detailedLogMsg)
				put("device_details", getDeviceInformation(INSTANCE))
			}

			cloudTable.saveInBackground()
		} catch (error: Exception) {
			logger.d("saveAppCrashedInfo failed: ${error.message}")
		}
	}

	/**
	 * Saves the unique Parse installation ID locally.
	 * @param installationId The ID assigned by Parse
	 */
	private fun saveInstallationIdLocally(installationId: String) {
		try {
			if (installationId.isNotEmpty()) {
				aioSettings.userInstallationId = installationId
				aioSettings.updateInStorage()
			}
		} catch (error: Exception) {
			logger.d("saveInstallationIdLocally failed: ${error.message}")
		}
	}

	/** Increments click count on the video URL editor */
	fun updateClickCountOnVideoUrlEditor() {
		aioSettings.totalClickCountOnVideoUrlEditor++
		aioSettings.updateInStorage()
	}

	/** Increments click count on media play button */
	fun updateClickCountOnMediaPlayButton() {
		aioSettings.totalClickCountOnMediaPlayback++
		aioSettings.updateInStorage()
	}

	/** Increments click count on language selector */
	fun updateClickCountOnLanguageChanger() {
		aioSettings.totalClickCountOnLanguageChange++
		aioSettings.updateInStorage()
	}

	/** Increments view count for the how-to-download guide */
	fun updateClickCountOnHowToDownload() {
		aioSettings.totalClickCountOnHowToGuide++
		aioSettings.updateInStorage()
	}

	/** Increments click count on home screen bookmarks */
	fun updateClickCountOnHomeBookmark() {
		aioSettings.totalClickCountOnHomeBookmarks++
		aioSettings.updateInStorage()
	}

	/** Increments click count on home screen favicons */
	fun updateClickCountOnHomesFavicon() {
		aioSettings.totalClickCountOnHomeFavicon++
		aioSettings.updateInStorage()
	}

	/** Increments view count for recent downloads list */
	fun updateClickCountOnRecentDownloadsList() {
		aioSettings.totalClickCountOnRecentDownloads++
		aioSettings.updateInStorage()
	}

	/** Increments click count on home screen history */
	fun updateClickCountOnHomeHistory() {
		aioSettings.totalClickCountOnHomeHistory++
		aioSettings.updateInStorage()
	}

	/** Increments count of version update checks */
	fun updateClickCountOnCheckVersionUpdate() {
		aioSettings.totalClickCountOnVersionCheck++
		aioSettings.updateInStorage()
	}
}
