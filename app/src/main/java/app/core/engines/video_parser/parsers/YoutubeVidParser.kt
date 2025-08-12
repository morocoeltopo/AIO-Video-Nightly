package app.core.engines.video_parser.parsers

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

class YoutubeVidParser {

    /**
     * Initialize NewPipe with the custom OkHttp-based downloader.
     * Call this once before making requests.
     */
    fun initSystem() {
        NewPipe.init(YTDownloaderImpl())
    }

    /**
     * Fetch video thumbnail URL from a YouTube link.
     * @param url YouTube video link.
     * @return Thumbnail URL as String.
     */
    fun getThumbnail(url: String): String? {
        try {
            val service = ServiceList.YouTube
            val info = StreamInfo.getInfo(service, url)
            return info.thumbnails[0].url
        } catch (error: Exception) {
            error.printStackTrace()
            return null
        }
    }

}