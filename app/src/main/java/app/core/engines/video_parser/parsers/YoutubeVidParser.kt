package app.core.engines.video_parser.parsers

import lib.process.LogHelperUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * A YouTube video parser that extracts metadata, thumbnails, and stream details
 * using the [NewPipe] extraction framework.
 *
 * ### Responsibilities:
 * - Initialize and configure the NewPipe extraction engine.
 * - Fetch video thumbnails, titles, and stream metadata.
 * - Gracefully handle exceptions (e.g., ReCaptcha, extraction, network issues).
 *
 * ### Notes:
 * This class wraps [NewPipe]’s functionality in a lightweight API for safer use.
 * Each function logs its progress and potential errors for easier debugging.
 */
class YoutubeVidParser {

	/** Logger instance for structured debug and error messages */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Initializes the NewPipe Extractor system with a custom downloader.
	 * This should be called before invoking any extraction methods.
	 *
	 * It registers [YTDownloaderImpl] as the default downloader,
	 * which internally uses OkHttp for efficient and safe requests.
	 *
	 * Example:
	 * ```
	 * val parser = YoutubeVidParser()
	 * parser.initSystem()
	 * ```
	 */
	fun initSystem() {
		logger.d("Initializing NewPipe extraction system...")
		NewPipe.init(YTDownloaderImpl())
		logger.d("NewPipe initialized successfully with YTDownloaderImpl.")
	}

	/**
	 * Extracts the thumbnail URL of a YouTube video.
	 *
	 * @param youtubeVideoUrl The full YouTube video URL (e.g., "https://youtu.be/dQw4w9WgXcQ").
	 * @param onErrorFound Callback invoked if an exception occurs.
	 * @return The first available thumbnail URL, or `null` if not found or extraction fails.
	 *
	 * @throws Exception Various exceptions may occur (network, parsing, captcha).
	 */
	fun getThumbnail(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		logger.d("Fetching thumbnail for URL: $youtubeVideoUrl")
		return try {
			val service = ServiceList.YouTube
			val info = StreamInfo.getInfo(service, youtubeVideoUrl)
			val thumbnailUrl = info.thumbnails.firstOrNull()?.url
			logger.d("Thumbnail fetched successfully: $thumbnailUrl")
			thumbnailUrl
		} catch (error: Exception) {
			logger.e("Error fetching thumbnail for $youtubeVideoUrl -> ${error.localizedMessage}", error)
			onErrorFound.invoke(error)
			null
		}
	}

	/**
	 * Retrieves the title of a YouTube video.
	 *
	 * @param youtubeVideoUrl The target YouTube video URL.
	 * @param onErrorFound Callback invoked when an exception is caught.
	 * @return The video title, or `null` if extraction fails.
	 */
	fun getTitle(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): String? {
		logger.d("Fetching video title for URL: $youtubeVideoUrl")
		return try {
			val service = ServiceList.YouTube
			val info = StreamInfo.getInfo(service, youtubeVideoUrl)
			logger.d("Video title extracted successfully: ${info.name}")
			info.name
		} catch (error: Exception) {
			logger.e("Error fetching title for $youtubeVideoUrl -> ${error.localizedMessage}", error)
			onErrorFound.invoke(error)
			null
		}
	}

	/**
	 * Retrieves complete stream metadata for a YouTube video, including:
	 * - Available resolutions and formats
	 * - Duration, uploader, and other metadata
	 *
	 * @param youtubeVideoUrl The target YouTube video URL.
	 * @param onErrorFound Callback invoked if extraction fails.
	 * @return A [StreamInfo] object containing all available video data, or `null` if unsuccessful.
	 *
	 * @throws Exception If NewPipe extraction fails or YouTube returns unexpected data.
	 */
	fun getStreamInfo(youtubeVideoUrl: String, onErrorFound: (Exception) -> Unit = {}): StreamInfo? {
		logger.d("Fetching stream metadata for URL: $youtubeVideoUrl")
		return try {
			val service = ServiceList.YouTube
			val streamInfo = StreamInfo.getInfo(service, youtubeVideoUrl)
			logger.d("Stream metadata extracted successfully for URL: $youtubeVideoUrl")
			logger.d("Video title: ${streamInfo.name}, duration: ${streamInfo.duration}, streams: ${streamInfo.videoStreams.size}")
			streamInfo // May throw ExtractionException
		} catch (error: Exception) {
			logger.e("Error fetching stream info for $youtubeVideoUrl -> ${error.localizedMessage}", error)
			onErrorFound.invoke(error)
			null
		}
	}
}