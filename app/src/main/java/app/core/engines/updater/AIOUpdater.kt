package app.core.engines.updater

import android.content.Context
import lib.device.AppVersionUtility.versionName
import lib.process.LogHelperUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * AIOUpdater is responsible for checking the latest version of the app
 * by fetching version information from a remote GitHub-hosted text file.
 *
 * The remote file must be in the format:
 * ```
 * latest_version=1.2.3
 * latest_apk_url=https://example.com/app.apk
 * changelog_url=https://example.com/changelog
 * published_date=2025-08-16
 * ```
 *
 * Features:
 * - Retrieves the latest version information, APK URL, changelog URL, and published date.
 * - Compares local app version with the latest version to determine if an update is available.
 * - Uses OkHttp for network requests.
 *
 * Example usage:
 * ```
 * val updater = AIOUpdater()
 * if (updater.isNewUpdateAvailable()) {
 *     val apkUrl = updater.getLatestApkUrl()
 *     // Prompt user to update
 * }
 * ```
 */
class AIOUpdater {

	/** Logger instance for debugging and error tracking. */
	private val logger by lazy { LogHelperUtils.from(javaClass) }

	companion object {
		const val APK_FILE_NAME = "Latest_AIO_Video_Downloader_Version"

		/**
		 * URL pointing to the version information file on GitHub.
		 * This file is read to determine if a new update is available.
		 */
		const val GITHUB_UPDATE_INFO_URL =
			"https://raw.githubusercontent.com/shibaFoss/AIO-Video-Downloader" +
					"/refs/heads/master/others/version_info.txt"
	}

	/**
	 * Data model representing all relevant update information.
	 *
	 * @property latestVersion The latest version name (e.g., "1.2.3").
	 * @property latestApkUrl Direct download link for the latest APK.
	 * @property changelogUrl Link to the changelog or release notes.
	 * @property publishedDate Date the update was published.
	 */
	data class UpdateInfo(
		val latestVersion: String?,
		val latestApkUrl: String?,
		val changelogUrl: String?,
		val publishedDate: String?
	)

	/**
	 * Fetches and parses the update information file from the given URL.
	 *
	 * @param url The URL of the version information text file.
	 * @return [UpdateInfo] object if successful, or null if the request fails.
	 */
	private fun fetchUpdateInfo(url: String): UpdateInfo? {
		val client = OkHttpClient()
		val request = Request.Builder().url(url).build()

		return try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					logger.d("Request failed with code: ${response.code}")
					return null
				}

				val lines = response.body.string().lines().associate {
					val parts = it.split("=", limit = 2)
					if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
				}

				UpdateInfo(
					latestVersion = lines["latest_version"],
					latestApkUrl = lines["latest_apk_url"],
					changelogUrl = lines["changelog_url"],
					publishedDate = lines["published_date"]
				)
			}
		} catch (error: IOException) {
			logger.e("Error fetching update info: ${error.localizedMessage}")
			null
		}
	}

	/**
	 * Retrieves the latest APK download URL from the remote version file.
	 *
	 * @return The latest APK URL as a String, or null if unavailable.
	 */
	fun getLatestApkUrl(): String? {
		return fetchUpdateInfo(GITHUB_UPDATE_INFO_URL)?.latestApkUrl
	}

	/**
	 * Checks if a new app update is available by comparing the local version
	 * with the remote version from the GitHub file.
	 *
	 * @return True if a newer version is available, false otherwise.
	 */
	fun isNewUpdateAvailable(): Boolean {
		val updateInfo = fetchUpdateInfo(GITHUB_UPDATE_INFO_URL) ?: return false
		val onlineVersion = updateInfo.latestVersion?.replace(".", "")
		val localVersion = versionName?.replace(".", "") ?: return false

		return try {
			onlineVersion?.toInt()?.let { onlineVersionNumber ->
				localVersion.toInt() < onlineVersionNumber
			} ?: false
		} catch (error: NumberFormatException) {
			logger.e("Error parsing version numbers: ${error.localizedMessage}")
			false
		}
	}

	/**
	 * Silently downloads a file from the given URL and saves it as "update.apk"
	 * in the app's private files directory. This does not support resume.
	 *
	 * @param context Application or activity context.
	 * @param url The direct URL to the APK file.
	 * @return The File object pointing to the saved APK, or null if download fails.
	 */
	fun downloadUpdateApkSilently(context: Context?, url: String): File? {
		if (context == null) return null
		val client = OkHttpClient()
		val request = Request.Builder().url(url).build()
		val outputFile = File(context.filesDir, "update.apk")

		return try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) throw IOException("Unexpected code $response")

				response.body.byteStream().use { input ->
					FileOutputStream(outputFile).use { output ->
						val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
						var bytesRead: Int
						while (input.read(buffer).also { bytesRead = it } != -1) {
							output.write(buffer, 0, bytesRead)
						}
					}
				}
				outputFile
			}
		} catch (error: Exception) {
			error.printStackTrace()
			null
		}
	}

}