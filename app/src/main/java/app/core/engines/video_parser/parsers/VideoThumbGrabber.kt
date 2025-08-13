package app.core.engines.video_parser.parsers

import android.webkit.WebView
import app.core.AIOApp.Companion.youtubeVidParser
import lib.networks.URLUtilityKT.extractHostUrl
import lib.networks.URLUtilityKT.fetchWebPageContent
import org.jsoup.Jsoup

/**
 * Utility object responsible for extracting the thumbnail image URL from a video page.
 *
 * This parser primarily relies on Open Graph meta tags (`og:image`) present in the HTML
 * source of the video URL.
 */
object VideoThumbGrabber {

    /**
     * Attempts to parse and retrieve the thumbnail image URL from a video page.
     *
     * Matching rules:
     *      - For YouTube Music URLs, returns the thumbnail from the NewPipe extractor.
     *      - Otherwise, searches HTML meta tags with `property="og:image"`.
     *      - Valid image formats: JPEG, JPG, PNG, GIF, WEBP (case-insensitive).
     *
     * @param videoUrl The URL of the video page from which to extract the thumbnail.
     * @param userGivenHtmlBody Optional HTML content of the video page (if already fetched).
     * @param numOfRetry Number of times to retry fetching the page in case of network failure.
     * @return The URL of the thumbnail image if found, otherwise null.
     */
    @JvmStatic
    fun startParsingVideoThumbUrl(
        videoUrl: String,
        userGivenHtmlBody: String? = null,
        numOfRetry: Int = 6,
    ): String? {
        // For YouTube Music, it retrieves the thumbnail directly via [youtubeVidParser]
        val isYoutubeMusicPage = extractHostUrl(videoUrl).contains("music.youtube", true)
        if (isYoutubeMusicPage) {
            val thumbnail = youtubeVidParser.getThumbnail(videoUrl)
            if (thumbnail.isNullOrEmpty() == false) return thumbnail
        }



        // Fetch HTML body either from user input or by making a network call
        val htmlBody = if (userGivenHtmlBody.isNullOrEmpty()) {
            fetchWebPageContent(videoUrl, true, numOfRetry) ?: return null
        } else userGivenHtmlBody

        // Parse the HTML using Jsoup
        val document = Jsoup.parse(htmlBody)

        // Select meta tags with property="og:image"
        val metaTags = document.select("meta[property=og:image]")

        // Loop through each tag to find a valid image URL
        for (metaTag in metaTags) {
            val rawContent = metaTag.attr("content")
            val decodedUrl = org.jsoup.parser.Parser.unescapeEntities(rawContent, true)

            // Regular expression to match common image formats
            val regexPattern = Regex(
                pattern = """https?://[^\s'"<>]+?\.(jpeg|jpg|png|gif|webp)(\?.*)?""",
                option = RegexOption.IGNORE_CASE
            )

            // Return the first valid image URL match
            if (regexPattern.containsMatchIn(decodedUrl)) return decodedUrl
        }

        // No valid thumbnail found
        return null
    }

    /**
     * Extracts the `og:image` URL from the currently loaded page in this WebView.
     *
     * This method evaluates JavaScript in the context of the currently displayed page
     * to retrieve the full HTML, then parses it using Jsoup to locate the
     * `<meta property="og:image" content="...">` tag.
     *
     * **Notes:**
     * - Works after the page is fully loaded.
     * - Returns `null` if no `og:image` tag is found or the tag is empty.
     * - Runs asynchronously; result is delivered via the [onResult] callback.
     *
     * @receiver WebView The WebView whose currently displayed page will be inspected.
     * @param onResult Callback invoked with the `og:image` URL string if found, otherwise `null`.
     */
    @JvmStatic
    fun WebView.getCurrentOgImage(onResult: (String?) -> Unit) {
        evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })();"
        ) { html ->
            val cleanHtml = html
                .replace("\\u003C", "<")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .trim('"')

            val doc = Jsoup.parse(cleanHtml)
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            onResult(ogImage?.takeIf { it.isNotBlank() })
        }
    }

}