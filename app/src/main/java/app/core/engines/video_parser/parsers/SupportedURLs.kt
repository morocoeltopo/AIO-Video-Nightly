package app.core.engines.video_parser.parsers

import androidx.core.net.toUri
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrl
import lib.networks.URLUtilityKT.getBaseDomain
import lib.process.LogHelperUtils
import java.net.URL
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Utility object for identifying and filtering supported video URLs for parsing.
 *
 * Responsibilities:
 * - Detect URLs from major social media and media-sharing platforms.
 * - Normalize YouTube URLs for consistent parsing.
 * - Validate whether a URL is supported by yt-dlp or internal logic.
 * - Detect HLS streams (.m3u8).
 */
object SupportedURLs {

	// Logger instance for debugging
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Set of base domains supported for video parsing via yt-dlp or custom logic.
	 * This includes popular video, music, and media platforms.
	 */
	private val supportedBaseDomains = setOf(
		"youtube", "youtu", "facebook", "instagram", "twitter", "x",
		"tiktok", "reddit", "tumblr", "soundcloud", "bandcamp", "9gag",
		"vk", "imdb", "dailymotion", "bilibili", "twitch", "likee",
		"snapchat", "pinterest", "linkedin", "mixcloud", "audiomack",
		"periscope", "jiosaavn", "hotstar", "youku", "rumble", "odysee",
		"peertube", "bitchute", "liveleak"
	)

	/**
	 * Normalizes YouTube URLs by ensuring a consistent format:
	 * - Converts short `youtu.be` links into standard `youtube.com/watch?v=...` format.
	 * - Removes playlist parameters to avoid fetching unwanted videos.
	 *
	 * @param url The original YouTube URL (short or full).
	 * @return A normalized YouTube watch URL, or the original URL if not YouTube or parsing fails.
	 */
	fun filterYoutubeUrlWithoutPlaylist(url: String): String {
		return try {
			// Ensure only YouTube links are processed
			if (!isYouTubeUrl(url)) {
				logger.d("URL is not YouTube: $url")
				return url
			}

			val uri = url.toUri()
			val host = uri.host ?: return url

			// Handle both youtu.be and youtube.com cases
			val normalizedUrl = when {
				host.contains("youtu.be") -> {
					// Extract video ID from the last path segment
					val videoId = uri.lastPathSegment ?: return url
					"https://www.youtube.com/watch?v=$videoId"
				}

				host.contains("youtube.com") -> {
					// Extract video ID from query parameter ?v=
					val videoId = uri.getQueryParameter("v") ?: return url
					"https://www.youtube.com/watch?v=$videoId"
				}

				else -> url
			}

			logger.d("Normalized YouTube URL: $normalizedUrl")
			normalizedUrl
		} catch (error: Exception) {
			logger.e("Error normalizing YouTube URL: ${error.message}", error)
			url
		}
	}

	/**
	 * Checks whether the given URL belongs to YouTube (youtube.com or youtu.be).
	 *
	 * @param url The URL to check.
	 * @return True if the host ends with YouTube domains; false otherwise.
	 */
	fun isYouTubeUrl(url: String): Boolean {
		return try {
			val parsedUrl = URL(url)
			val host = parsedUrl.host

			host.endsWith("youtube.com", ignoreCase = true) ||
					host.endsWith("youtu.be", ignoreCase = true) ||
					url.contains("music.youtube", ignoreCase = true)
		} catch (error: Exception) {
			logger.e("Error while checking if URL is a YouTube link: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Instagram.
	 * Supports common Instagram short links like `instagr.am`, `ig.me`, and `ig.com`.
	 * These short domains are often used for shared posts, reels, or profiles.
	 */
	fun isInstagramUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("instagram.com") ||
					host.contains("instagr.am") ||        // Official Instagram short URL
					host.contains("ig.me") ||             // Often used in DMs for sharing
					host.contains("ig.com")               // Sometimes used for redirects
		} catch (error: Exception) {
			logger.e("Error while checking Instagram URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Facebook.
	 * Includes short and alternative domains like `fb.me`, `fb.watch`, and `m.facebook.com`
	 * that Facebook uses for links to posts, videos, or mobile pages.
	 */
	fun isFacebookUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("facebook.com") ||
					host.contains("m.facebook.com") ||    // Mobile site version
					host.contains("fb.me") ||             // Facebook’s official short links
					host.contains("fb.watch")             // Facebook video/watch pages
		} catch (error: Exception) {
			logger.e("Error while checking Facebook URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to TikTok.
	 * Supports the official short URL format `vm.tiktok.com` often used for shared videos.
	 */
	fun isTiktokUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("tiktok.com") ||
					host.contains("vm.tiktok.com")        // TikTok’s shortened links
		} catch (error: Exception) {
			logger.e("Error while checking TikTok URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Pinterest.
	 * Recognizes standard Pinterest domain as well as `pin.it` used for compact sharing links.
	 */
	fun isPinterestUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("pinterest.com") ||
					host.contains("pin.it")               // Common short domain for pins
		} catch (error: Exception) {
			logger.e("Error while checking Pinterest URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Twitter or X.
	 * Handles `twitter.com`, `x.com`, and `t.co`, the official shortener Twitter/X uses for shared links.
	 */
	fun isTwitterOrXUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("twitter.com") ||
					host.contains("x.com") ||             // New official Twitter domain
					host.contains("t.co")                 // Twitter/X short URLs for posts
		} catch (error: Exception) {
			logger.e("Error while checking Twitter/X URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Snapchat.
	 * Supports standard domain and short links like `snp.ac` and `snap.link`
	 * often used for quick sharing of stories or spotlight content.
	 */
	fun isSnapchatUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("snapchat.com") ||
					host.contains("snp.ac") ||            // Snapchat’s short sharing links
					host.contains("snap.link")            // Promotional or spotlight links
		} catch (error: Exception) {
			logger.e("Error while checking Snapchat URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Checks whether the given URL is from a supported social media platform
	 * (Facebook, Instagram, TikTok, YouTube).
	 *
	 * @param url The URL to test.
	 * @return True if the URL matches any known social media domain.
	 */
	fun isSocialMediaUrl(url: String): Boolean {
		return isInstagramUrl(url) ||
				isFacebookUrl(url) ||
				isTiktokUrl(url) ||
				isPinterestUrl(url) ||
				isTwitterOrXUrl(url) ||
				isSnapchatUrl(url)
	}

	/**
	 * Determines if the given URL is supported by yt-dlp or internal parsing logic.
	 * - Uses the base domain for quick matching against a known set of supported sites.
	 * - Also detects HLS stream URLs (.m3u8).
	 *
	 * @param url The URL to check.
	 * @return True if the URL matches supported platforms or stream formats.
	 */
	fun isYtdlpSupportedUrl(url: String): Boolean {
		val baseDomain = getBaseDomain(url)
		val isSupportedUrl = supportedBaseDomains.contains(baseDomain) || isM3U8Url(url)
		return baseDomain != null && isSupportedUrl
	}

	/**
	 * Detects whether the URL points to an M3U8 (HLS) playlist.
	 * These indicate streamable video resources.
	 *
	 * @param url The URL string.
	 * @return True if the URL contains a known m3u8 pattern.
	 */
	fun isM3U8Url(url: String): Boolean {
		return url.contains("/playlist.m3u8", ignoreCase = true) ||
				url.contains("/index.m3u8", ignoreCase = true) ||
				url.contains(".m3u8", ignoreCase = true) ||
				url.contains("m3u8", ignoreCase = true)
	}

	/**
	 * Regex-based pattern matching for supported video URLs.
	 *
	 * This is stricter than [isYtdlpSupportedUrl], since it enforces exact path structures
	 * (e.g., Instagram reels, YouTube watch/shorts/music links, TikTok videos, Twitter statuses).
	 *
	 * Extend this list as needed when adding new platforms.
	 *
	 * @param webpageUrl The full webpage URL.
	 * @return True if the URL matches any known regex pattern.
	 */
	fun isYtdlpSupportedUrlPattern(webpageUrl: String): Boolean {
		val patterns = listOf(
			// Instagram Reel
			Regex("""^https?://(www\.)?instagram\.com/(reel|p|tv|stories)/[A-Za-z0-9_.-]+/?.*""", IGNORE_CASE),

			// YouTube Watch page
			Regex("""^https?://(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]+.*""", IGNORE_CASE),

			// YouTube Shorts
			Regex("""^https?://(www\.)?youtube\.com/shorts/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// YouTube Music
			Regex("""^https?://music\.youtube\.com/watch\?v=[A-Za-z0-9_-]+.*""", IGNORE_CASE),

			// YouTube Shortened link (youtu.be)
			Regex("""^https?://youtu\.be/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Twitter / X status links
			Regex("""^https?://(www\.)?(twitter|x)\.com/[^/]+/status/\d+""", IGNORE_CASE),

			// Twitter status links (with optional query params)
			Regex("""^https?://(www\.)?twitter\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// X.com status links (with optional query params)
			Regex("""^https?://(www\.)?x\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// Mobile Twitter links (with optional query params)
			Regex("""^https?://(mobile\.)?twitter\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// Twitter "i/status" embed links
			Regex("""^https?://(www\.)?(twitter|x)\.com/i/status/\d+.*""", IGNORE_CASE),

			// Pinterest pin
			Regex("""^https?://([a-z]+\.)?pinterest\.com/pin/\d+/?""", IGNORE_CASE),

			// Pinterest short links (pin.it)
			Regex("""^https?://(www\.)?pin\.it/[A-Za-z0-9]+/?""", IGNORE_CASE),

			// TikTok video
			Regex("""^https?://(www\.)?tiktok\.com/@[^/]+/video/\d+""", IGNORE_CASE),

			// Facebook video post
			Regex("""^https?://(www\.)?facebook\.com/.*/videos/\d+/?""", IGNORE_CASE),

			// Facebook watch page
			Regex("""^https?://(www\.)?facebook\.com/watch/\?v=\d+.*""", IGNORE_CASE),

			// Facebook reels
			Regex("""^https?://(www\.)?facebook\.com/reel/\d+/?""", IGNORE_CASE),

			// Facebook short links (fb.watch)
			Regex("""^https?://(www\.)?fb\.watch/[A-Za-z0-9_-]+/?""", IGNORE_CASE),

			// Snapchat Spotlight video
			Regex("""^https?://(www\.)?snapchat\.com/@[^/]+/spotlight/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Snapchat Spotlight video (optional flexible)
			Regex("""^https?://(www\.)?snapchat\.com/@[^/]+/(spotlight|story|video)/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Dailymotion video link
			Regex("""^https?://(www\.)?dailymotion\.com/video/[A-Za-z0-9]+/?(\?.*)?$""", IGNORE_CASE),
		)

		return patterns.any { it.matches(webpageUrl) }
	}
}
