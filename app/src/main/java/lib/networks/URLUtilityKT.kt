package lib.networks

import app.core.AIOApp.Companion.youtubeVidParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A collection of helper methods for working with URLs and web content.
 *
 * This object centralizes common networking tasks such as:
 * - Extracting hosts and base domains.
 * - Checking resource availability (favicon, URL expiration, internet connectivity).
 * - Fetching HTML content (desktop and mobile-optimized versions).
 * - Parsing webpage titles and metadata.
 * - Normalizing encoded URLs.
 *
 * **Thread Safety:** All methods are stateless and thread-safe.
 *
 * **Note:** Many methods in this utility make synchronous HTTP requests.
 * For performance, consider running them on a background thread when called from UI contexts.
 */
object URLUtilityKT {

    /**
     * Extracts only the scheme and host from a given URL string.
     *
     * Example:
     * ```
     * extractHostUrl("https://example.com/path/page.html")
     * // returns "https://example.com"
     * ```
     *
     * @param urlString Full URL (must include scheme).
     * @return The scheme + host portion of the URL, or an empty string if parsing fails.
     */
    @JvmStatic
    fun extractHostUrl(urlString: String): String {
        try {
            val uri = URI(urlString)
            return "${uri.scheme}://${uri.host}"
        } catch (error: Exception) {
            error.printStackTrace()
            return ""
        }
    }

    /**
     * Checks if a URL contains no path beyond the root.
     *
     * Example:
     * ```
     * isHostOnly("https://example.com")      // true
     * isHostOnly("https://example.com/")     // true
     * isHostOnly("https://example.com/page") // false
     * ```
     *
     * @param url The URL to check.
     * @return `true` if URL has no path or only "/", otherwise `false`.
     */
    @JvmStatic
    fun isHostOnly(url: String): Boolean {
        return try {
            val parsedUrl = URL(url)
            val path = parsedUrl.path
            path.isNullOrEmpty() || path == "/"
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    /**
     * Asynchronously retrieves the `<title>` content from a webpage.
     *
     * This method performs a GET request and parses the returned HTML with Jsoup.
     *
     * @param url Webpage URL.
     * @param callback Receives the extracted title, or `null` if unavailable or an error occurs.
     */
    @JvmStatic
    fun getTitleByParsingHTML(url: String, callback: (String?) -> Unit) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            callback(null); return
                        }
                        val html = response.body.string()
                        if (html.isEmpty()) {
                            callback(null); return
                        }
                        val document = Jsoup.parse(html)
                        val title = document.title().ifEmpty { null }
                        callback(title)
                    }
                }
            })
        } catch (error: Exception) {
            error.printStackTrace()
            callback(null)
        }
    }

    /**
     * Retrieves the OpenGraph title or description from a webpage.
     *
     * Special Case:
     * - If the URL belongs to YouTube Music, attempts to get the title using
     *   the `youtubeVidParser` before fetching HTML.
     *
     * @param websiteUrl Webpage URL.
     * @param returnDescriptionL If `true`, fetches `og:description`; otherwise `og:title`.
     * @param userGivenHtmlBody Optional pre-fetched HTML to avoid another network request.
     * @param callback Receives the title/description, or `null` if unavailable.
     */
    @JvmStatic
    fun getWebpageTitleOrDescription(
        websiteUrl: String,
        returnDescriptionL: Boolean = false,
        userGivenHtmlBody: String? = null,
        callback: (String?) -> Unit
    ) {
        try {
            val isYoutubeMusicPage = extractHostUrl(websiteUrl).contains("music.youtube", true)
            if (isYoutubeMusicPage) {
                val title = youtubeVidParser.getTitle(websiteUrl)
                if (title.isNullOrEmpty() == false) {
                    return callback.invoke("${title}_Youtube_Music_Audio")
                }
            }

            val htmlBody = if (userGivenHtmlBody.isNullOrEmpty()) {
                fetchWebPageContent(
                    url = websiteUrl,
                    retry = true,
                    numOfRetry = 6
                ) ?: return callback(null)
            } else userGivenHtmlBody
            val document = Jsoup.parse(htmlBody)
            val metaTag = document.selectFirst(
                if (returnDescriptionL) "meta[property=og:description]"
                else "meta[property=og:title]"
            )
            return callback(metaTag?.attr("content"))
        } catch (error: Exception) {
            error.printStackTrace()
            callback(null)
        }
    }

    /**
     * Finds the favicon URL for a site.
     *
     * Search order:
     * 1. Checks `<site>/favicon.ico`.
     * 2. Parses HTML for `<link rel="icon">` or `<link rel="shortcut icon">`.
     *
     * @param websiteUrl The site’s base URL.
     * @return The favicon URL if found, or `null` if unavailable.
     */
    @JvmStatic
    fun getFaviconUrl(websiteUrl: String): String? {
        val standardFaviconUrl = "$websiteUrl/favicon.ico"
        if (isFaviconAvailable(standardFaviconUrl)) return standardFaviconUrl

        return try {
            val doc: Document = Jsoup.connect(websiteUrl).get()
            val faviconUrl = doc.head().select("link[rel~=(icon|shortcut icon)]")
                .mapNotNull { it.attr("href").takeIf { href -> href.isNotEmpty() } }
                .map { href -> if (href.startsWith("http")) href else "$websiteUrl/$href" }
                .firstOrNull { isFaviconAvailable(it) }
            faviconUrl
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    /**
     * Determines whether a favicon resource exists.
     *
     * @param faviconUrl Direct favicon URL.
     * @return `true` if HTTP HEAD request returns 200 OK, otherwise `false`.
     */
    @JvmStatic
    fun isFaviconAvailable(faviconUrl: String): Boolean {
        return try {
            val url = URL(faviconUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            val isAvailable = connection.responseCode == HTTP_OK
            isAvailable
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    /**
     * Performs a HEAD request to determine file size from HTTP headers.
     *
     * @param httpClient OkHttpClient instance (can be reused for multiple calls).
     * @param url File URL.
     * @return Size in bytes, or `-1` if not available.
     */
    @JvmStatic
    fun fetchFileSize(httpClient: OkHttpClient, url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            httpClient.newCall(request).execute().use { response ->
                val fileSize = response.header("Content-Length")?.toLong() ?: -1L
                fileSize
            }
        } catch (error: Exception) {
            error.printStackTrace()
            -1L
        }
    }


    /**
     * Checks internet connectivity by sending a short request to Google.
     *
     * @return `true` if Google responds with HTTP 200, otherwise `false`.
     */
    @JvmStatic
    fun isInternetConnected(): Boolean {
        return try {
            val url = URL("https://www.google.com")
            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                connectTimeout = 1000
                readTimeout = 1000
                connect()
                val isConnected = responseCode == HTTP_OK
                isConnected
            }
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    /**
     * Replaces spaces with `%20` for safe URL encoding.
     *
     * @param input The raw string.
     * @return A URL-safe string with spaces percent-encoded.
     */
    @JvmStatic
    fun getUrlSafeString(input: String): String {
        return input.replace(" ", "%20")
    }

    /**
     * Extracts the second-level domain from a URL.
     *
     * Example:
     * ```
     * getBaseDomain("https://www.google.com") // "google"
     * ```
     *
     * @param url Full URL.
     * @return Base domain without TLD, or `null` if invalid.
     */
    @JvmStatic
    fun getBaseDomain(url: String): String? {
        return try {
            val domain = URL(url).host
            val parts = domain.split(".")
            val baseDomain = if (parts.size > 2) {
                parts[parts.size - 2]
            } else {
                parts[0]
            }

            baseDomain
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    /**
     * Extracts only the hostname from a URL.
     *
     * @param urlString The full URL.
     * @return Host name, or `null` if invalid.
     */
    @JvmStatic
    fun getHostFromUrl(urlString: String?): String? {
        return try {
            urlString?.let { URL(it).host }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    /**
     * Returns a Google favicon service link for a given domain.
     *
     * @param domain Domain name (no scheme).
     * @return Direct link to a 128px favicon image.
     */
    @JvmStatic
    fun getGoogleFaviconUrl(domain: String): String {
        return "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    }

    /**
     * Checks if a URL is considered expired (HTTP status ≥ 400).
     *
     * @param urlString Full URL.
     * @return `true` if the URL returns an error status, otherwise `false`.
     */
    @JvmStatic
    fun isUrlExpired(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            val responseCode = connection.responseCode
            val isExpired = responseCode >= 400
            isExpired
        } catch (error: Exception) {
            error.printStackTrace()
            true
        }
    }

    /**
     * Removes the `www.` prefix from a URL string.
     *
     * @param url May be `null`.
     * @return String without `www.`, or `""` if `url` is `null`.
     */
    @JvmStatic
    fun removeWwwFromUrl(url: String?): String {
        if (url == null) return ""
        return try {
            url.replaceFirst("www.", "")
        } catch (error: Exception) {
            error.printStackTrace()
            url
        }
    }

    /**
     * Fetches HTML using old mobile browser user-agents.
     *
     * Useful for:
     * - Reducing heavy modern scripts.
     * - Accessing mobile-optimized versions of pages.
     *
     * Retry logic:
     * - If `retry` is true, will retry `numOfRetry` times with different user-agents.
     * - Wait time increases with each attempt.
     *
     * @param url Target webpage.
     * @param retry Enable retry logic.
     * @param numOfRetry Retry attempts if enabled.
     * @param timeoutSeconds Timeout per request in seconds.
     * @return HTML string or `null` if all attempts fail.
     */
    @JvmStatic
    fun fetchMobileWebPageContent(
        url: String,
        retry: Boolean = false,
        numOfRetry: Int = 0,
        timeoutSeconds: Int = 30
    ): String? {
        val oldMobileUserAgents = listOf(
            // Old iPhone Safari
            "Mozilla/5.0 (iPhone; CPU iPhone OS 9_3_5 like Mac OS X) " +
                    "AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13G36 Safari/601.1",
            // Old Android Chrome
            "Mozilla/5.0 (Linux; Android 4.4.2; Nexus 5 Build/KOT49H) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/34.0.1847.114 Mobile Safari/537.36",
            // Even older Android WebKit browser
            "Mozilla/5.0 (Linux; U; Android 2.3.6; en-us; GT-I9000 Build/GINGERBREAD) " +
                    "AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1"
        )

        fun fetch(attempt: Int = 0): String? {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", oldMobileUserAgents[attempt % oldMobileUserAgents.size])
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()

            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful)
                        response.body.string().takeIf { it.isNotEmpty() }
                    else null
                }
            } catch (error: IOException) {
                error.printStackTrace(); null
            } catch (error: Exception) {
                error.printStackTrace(); null
            }
        }

        if (retry && numOfRetry > 0) {
            var attempt = 0
            while (attempt < numOfRetry) {
                fetch(attempt)?.let { return it }
                attempt++
                if (attempt < numOfRetry) Thread.sleep(1000L * attempt)
            }
            return null
        }

        return fetch()
    }

    /**
     * Fetches HTML content of a webpage, optionally retrying.
     *
     * If the URL is a social media link, mobile-fetch mode is used automatically.
     *
     * @param url Target URL.
     * @param retry Enable retry logic.
     * @param numOfRetry Retry attempts.
     * @return HTML string or `null` if all attempts fail.
     */
    @JvmStatic
    fun fetchWebPageContent(
        url: String,
        retry: Boolean = false,
        numOfRetry: Int = 0
    ): String? {
        if (retry && numOfRetry > 0) {
            var index = 0
            var htmlBody: String? = ""
            while (index < numOfRetry || htmlBody.isNullOrEmpty()) {
                htmlBody = fetchMobileWebPageContent(url)
                if (!htmlBody.isNullOrEmpty()) return htmlBody
                index++
            }
        }

        return fetchMobileWebPageContent(url)
    }

    /**
     * Normalizes an encoded URL by decoding and re-encoding all query parameters,
     * and ensuring consistent ordering.
     *
     * @param url URL to normalize.
     * @return Normalized URL, or the original if normalization fails.
     */
    @JvmStatic
    fun normalizeEncodedUrl(url: String): String {
        try {
            val unescapedUrl = url.replace("\\/", "/")
            val uri = URI(unescapedUrl)
            val baseUrl = "${uri.scheme}://${uri.host}${uri.path}"
            val query = uri.query ?: return baseUrl

            val queryParams = query.split("&").associate {
                it.split("=").let { pair ->
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                    key to value
                }
            }.toSortedMap()

            val normalizedQuery = queryParams.map { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }.joinToString("&")

            return "$baseUrl?$normalizedQuery"
        } catch (error: Exception) {
            error.printStackTrace()
            return url
        }
    }
}