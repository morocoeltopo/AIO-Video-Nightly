package app.core.engines.updater

import lib.device.AppVersionUtility.versionName
import lib.process.LogHelperUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class AIOUpdater {

	private val logger by lazy { LogHelperUtils.from(javaClass) }

	companion object {
		const val GITHUB_UPDATE_INFO_URL =
			"https://raw.githubusercontent.com/shibaFoss/AIO-Video-Downloader" +
					"/refs/heads/master/others/version_info.txt"
	}

	data class UpdateInfo(
		val latestVersion: String?,
		val latestApkUrl: String?,
		val changelogUrl: String?,
		val publishedDate: String?
	)

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

	fun getLatestApkUrl(): String? {
		return fetchUpdateInfo(GITHUB_UPDATE_INFO_URL)?.latestApkUrl
	}

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
}
