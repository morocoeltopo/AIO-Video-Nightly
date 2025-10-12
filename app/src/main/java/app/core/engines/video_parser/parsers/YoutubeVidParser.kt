package app.core.engines.video_parser.parsers

import lib.process.LogHelperUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * A parser for extracting metadata and stream information from YouTube videos using [NewPipeExtractor].
 * Handles:
 * - Thumbnail URLs
 * - Video titles
 * - Full stream metadata (resolutions, formats, etc.)
 *
 * @throws ExtractionException If YouTube's response parsing fails.
 * @throws ReCaptchaException If blocked by YouTube's bot detection.
 * @throws IOException If network requests fail.
 */
class YoutubeVidParser {

	/** Logger instance for debug messages and error tracking */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Initializes the NewPipe Extractor system with a custom downloader implementation.
	 * Must be called once before any other methods.
	 *
	 * @see YTDownloaderImpl (Assumes a custom OkHttp-based downloader)
	 */
	fun initSystem() {
		NewPipe.init(YTDownloaderImpl())
	}

	/**
	 * Fetches the thumbnail URL of a YouTube video.
	 *
	 * @param youtubeVideoUrl The YouTube video URL (e.g., "https://youtu.be/dQw4w9WgXcQ").
	 * @param onErrorFound The callback on any error found.
	 * @return Thumbnail URL as a [String], or `null` if extraction fails.
	 *
	 * @throws ExtractionException If the video is unavailable or private.
	 * @throws IOException If the network request fails.
	 */
	fun getThumbnail(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		return try {
			val service = ServiceList.YouTube
			val info = StreamInfo.getInfo(service, youtubeVideoUrl)
			info.thumbnails.firstOrNull()?.url // Safely get the first thumbnail
		} catch (error: Exception) {
			error.printStackTrace()
			onErrorFound.invoke(error)
			null
		}
	}

	/**
	 * Extracts the title of a YouTube video.
	 *
	 * @param youtubeVideoUrl The YouTube video URL.
	 * @param onErrorFound The callback on any error found.
	 * @return Video title as a [String], or `null` if extraction fails.
	 *
	 * @throws ParsingException If YouTube's HTML structure changes unexpectedly.
	 */
	fun getTitle(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		return try {
			val service = ServiceList.YouTube
			val info = StreamInfo.getInfo(service, youtubeVideoUrl)
			info.name
		} catch (error: Exception) {
			error.printStackTrace()
			onErrorFound.invoke(error)
			null
		}
	}

	/**
	 * Retrieves complete stream metadata for a YouTube video, including:
	 * - Available resolutions/formats (video and audio)
	 * - Duration, uploader, and other metadata.
	 *
	 * @param youtubeVideoUrl The YouTube video URL.
	 * @param onErrorFound The callback on any error found.
	 * @return A [StreamInfo] object containing all extracted data, or `null` if failed.
	 *
	 * @throws ReCaptchaException If detected as a bot by YouTube.
	 * @throws GeoBlockedException If the video is restricted in the user's region.
	 */
	fun getStreamInfo(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): StreamInfo? {
		return try {
			val service = ServiceList.YouTube
			StreamInfo.getInfo(service, youtubeVideoUrl) // May throw ExtractionException
		} catch (error: Exception) {
			error.printStackTrace()
			onErrorFound.invoke(error)
			null
		}
	}
}