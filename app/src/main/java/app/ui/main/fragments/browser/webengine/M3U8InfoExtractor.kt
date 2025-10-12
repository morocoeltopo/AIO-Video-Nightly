package app.ui.main.fragments.browser.webengine

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.aio.R
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import java.util.regex.Pattern

/**
 * Handles asynchronous extraction of video metadata such as resolutions and duration.
 *
 * Each extraction task runs in a background thread using [ThreadsUtility],
 * ensuring that UI responsiveness is maintained. Results or errors are returned
 * through the [InfoCallback] interface on the main thread.
 */
class M3U8InfoExtractor {

	/** Logger instance for debug messages and error tracking */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Extracts available video resolutions from a remote HLS (.m3u8) playlist.
	 *
	 * @param m3u8Url The remote URL of the HLS playlist.
	 * @param callback The [InfoCallback] to receive extraction results or errors.
	 */
	fun extractResolutionsAndDuration(m3u8Url: String, callback: InfoCallback) {
		logger.d("Starting resolution extraction for URL: $m3u8Url")

		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val resolutions = fetchResolutionsFromM3U8(m3u8Url)
				if (resolutions.isNotEmpty()) {
					logger.d("Resolutions extracted successfully: $resolutions")
					executeOnMain { callback.onResolutions(resolutions) }
				} else {
					logger.d("No resolutions found in playlist.")
					executeOnMain { callback.onError("No resolutions found in playlist.") }
				}
			} catch (error: Exception) {
				logger.e("Resolution extraction failed for URL: $m3u8Url", error)
				executeOnMain { callback.onError("Resolution extraction failed: ${error.message}") }
			}
		})
	}

	/**
	 * Extracts total video duration from an HLS (.m3u8) playlist by parsing segment metadata.
	 *
	 * @param m3u8Url The remote URL of the HLS playlist.
	 * @param callback The [InfoCallback] to receive duration results or errors.
	 */
	fun extractDuration(m3u8Url: String, callback: (Long) -> Unit) {
		logger.d("Starting duration extraction for URL: $m3u8Url")

		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val duration = getDurationFromM3U8(m3u8Url)
				if (duration > 0) {
					logger.d("Duration extracted successfully: $duration ms")
					executeOnMain { callback(duration) }
				} else {
					logger.d("No valid duration found in playlist.")
				}
			} catch (error: Exception) {
				logger.e("Duration extraction failed for URL: $m3u8Url", error)
			}
		})
	}

	/**
	 * Fetches and parses the M3U8 playlist to determine available resolutions.
	 *
	 * @param m3u8Url The URL of the HLS playlist
	 * @return List of resolution strings (e.g., ["1920×1080", "1280×720"])
	 */
	@OptIn(UnstableApi::class)
	private fun fetchResolutionsFromM3U8(m3u8Url: String): List<String> {
		// Configure HTTP data source with user agent
		val factory = DefaultHttpDataSource.Factory()
		val userAgent = getText(R.string.title_mobile_user_agent)
		val dataSourceFactory = factory.setUserAgent(userAgent)
		val playlistParser = HlsPlaylistParser()

		try {
			val dataSource = dataSourceFactory.createDataSource()
			val dataSpec = DataSpec(m3u8Url.toUri())
			dataSource.open(dataSpec)

			DataSourceInputStream(dataSource, dataSpec).use { stream ->
				return when (val playlist = playlistParser.parse(dataSpec.uri, stream)) {
					// Case 1: Master playlist with multiple variants
					is HlsMultivariantPlaylist -> {
						playlist.variants.mapNotNull { variant ->
							when {
								variant.format.width > 0 && variant.format.height > 0 ->
									"${variant.format.width}×${variant.format.height}"

								variant.format.height > 0 ->
									"${variant.format.height}p"

								else -> null
							}
						}.distinct()
					}

					// Case 2: Media playlist (single stream)
					is HlsMediaPlaylist -> {
						extractResolutionFromUrl(m3u8Url)?.let { listOf(it) }
							?: listOf(getText(R.string.title_player_default))
					}

					else -> emptyList()
				}
			}
		} catch (error: Exception) {
			error.printStackTrace()
			return emptyList()
		}
	}

	/**
	 * Fetches and parses an HLS (.m3u8) playlist to determine total video duration.
	 *
	 * @param m3u8Url The URL of the HLS playlist
	 * @return Total duration in milliseconds (or 0L if unavailable)
	 */
	@OptIn(UnstableApi::class)
	fun getDurationFromM3U8(m3u8Url: String): Long {
		logger.d("Fetching duration from HLS playlist: $m3u8Url")

		val factory = DefaultHttpDataSource.Factory()
		val userAgent = getText(R.string.title_mobile_user_agent)
		val dataSourceFactory = factory.setUserAgent(userAgent)
		val playlistParser = HlsPlaylistParser()

		return try {
			val dataSource = dataSourceFactory.createDataSource()
			val dataSpec = DataSpec(m3u8Url.toUri())
			dataSource.open(dataSpec)

			DataSourceInputStream(dataSource, dataSpec).use { stream ->
				when (val playlist = playlistParser.parse(dataSpec.uri, stream)) {
					is HlsMultivariantPlaylist -> {
						logger.d("Master playlist detected — fetching first variant for duration")
						playlist.variants.firstOrNull()?.url?.let { variantUrl ->
							getDurationFromM3U8(variantUrl.toString())
						} ?: 0L
					}

					is HlsMediaPlaylist -> {
						val durationMs = (playlist.durationUs / 1000)
						logger.d("Media playlist duration: ${durationMs}ms")
						durationMs
					}

					else -> {
						logger.e("Unknown playlist type for: $m3u8Url")
						0L
					}
				}
			}
		} catch (error: Exception) {
			logger.e("Failed to fetch duration from M3U8: $m3u8Url", error)
			0L
		}
	}

	/**
	 * Extracts resolution information from the URL pattern.
	 *
	 * Example supports three pattern types:
	 * 1. Direct resolution in path (e.g., "480p.av1.mp4.m3u8")
	 * 2. Multi-resolution declarations (e.g., "multi=256x144:144p:,...")
	 * 3. Traditional patterns (e.g., "/1280x720/", "_720p")
	 *
	 * @param url The M3U8 URL to analyze
	 * @return Extracted resolution string or null if no match found
	 */
	private fun extractResolutionFromUrl(url: String): String? {
		// Pattern 1: Direct resolution in filename (e.g., "480p.av1.mp4.m3u8")
		"/(\\d{3,4})[pP]\\.".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}p"
		}

		// Pattern 2: Multi-resolution declaration (e.g., "multi=256x144:144p:,...")
		"multi=(.*?):(.*?):/".toRegex().find(url)?.let { match ->
			return match.groupValues[1]
				.split(",")
				.last()
				.substringBefore(":")
				.let { if (it.contains("x")) it else "${it}p" }
		}

		// Pattern 3: Direct resolution in filename (e.g., "440x250.mp4.m3u8")
		"(\\d{3,4})[xX×](\\d{3,4})\\.mp4\\.m3u8".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}×${match.groupValues[2]}"
		}

		// Pattern 4: Multi-resolution declaration (new format)
		"multi=(\\d+x\\d+):(\\d+x\\d+)/".toRegex().find(url)?.let { match ->
			// Return the highest resolution (last one)
			return match.groupValues.last().replace("x", "×")
		}

		// Pattern 5: Resolution in path (e.g., "/720p/stream.m3u8")
		"/(\\d{3,4})[pP]/".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}p"
		}

		// Pattern 6. Quality suffix filename (e.g., "stream_720p.m3u8")
		"[_\\-](\\d{3,4})[pP]\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 7. Compact multi-resolution (e.g., "multi=444x250:440x250/")
		"multi=([^:]+)".toRegex().find(url)?.let {
			return it.groupValues[1].split(":").last().replace("x", "×")
		}

		// Pattern 8. Dimensions in sub-path (e.g., "/1280x720/stream.m3u8")
		"/(\\d{3,4})[xX×](\\d{3,4})/".toRegex().find(url)?.let {
			return "${it.groupValues[1]}×${it.groupValues[2]}"
		}

		// Pattern 9. Bitrate-quality suffix (e.g., "stream_3000k_1080p.m3u8")
		"[_\\-]\\d+k[_\\-](\\d{3,4})[pP]\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 10. CDN-style format (e.g., "stream_1080p_h264.m3u8")
		"[_\\-](\\d{3,4})[pP]_[a-z0-9]+\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 11. Quality prefix (e.g., "hls_720p_stream.m3u8")
		"[_\\-](\\d{3,4})[pP][_\\-]".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 12: Traditional resolution patterns
		val patterns = listOf(
			"/(\\d{3,4})[xX×](\\d{3,4})/",  // e.g., /1280x720/
			"[_\\-](\\d{3,4})[pP]",          // e.g., _720p
			"[_\\-](\\d{3,4})[xX×](\\d{3,4})" // e.g., -1280x720
		)

		// Pattern 13: for PHNCDN-style URLs (e.g., "720P_4000K_465426665.mp4")
		"/(\\d{3,4})[pP]_(\\d+)K_\\d+\\.mp4/".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"  // Returns "720p" for the example
		}

		patterns.forEach { pattern ->
			Pattern.compile(pattern).matcher(url).let { matcher ->
				if (matcher.find()) {
					return when (matcher.groupCount()) {
						1 -> "${matcher.group(1)}p"
						2 -> "${matcher.group(1)}×${matcher.group(2)}"
						else -> null
					}
				}
			}
		}

		return null
	}

	/**
	 * Callback interface for delivering media information extraction results.
	 *
	 * This interface provides methods to asynchronously return parsed metadata
	 * such as video duration, available resolutions, or error details when extraction fails.
	 * Typically used with HLS (.m3u8) or MP4 video metadata parsers.
	 */
	interface InfoCallback {

		/**
		 * Called when the video duration is successfully extracted.
		 *
		 * @param duration Duration of the video in milliseconds.
		 */
		fun onDuration(duration: Long)

		/**
		 * Called when available video resolutions are successfully detected.
		 *
		 * @param resolutions List of available resolutions, such as ["1920×1080", "1280×720"].
		 */
		fun onResolutions(resolutions: List<String>)

		/**
		 * Called when metadata extraction fails.
		 *
		 * @param errorMessage Description of the failure cause or related debug information.
		 */
		fun onError(errorMessage: String)
	}

}