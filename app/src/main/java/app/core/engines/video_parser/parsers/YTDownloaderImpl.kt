package app.core.engines.video_parser.parsers

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkHttpRequest
import okhttp3.Response as OkHttpResponse

/**
 * [YTDownloaderImpl] is a custom implementation of NewPipe's [Downloader]
 * that uses OkHttp for HTTP requests instead of Java's [java.net.HttpURLConnection].
 *
 * This downloader is responsible for executing network requests made by
 * the NewPipe Extractor library (used for parsing streaming services like YouTube).
 */
class YTDownloaderImpl : Downloader() {

    /**
     * The OkHttp client instance used for all HTTP requests.
     */
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a network request using OkHttp.
     *
     * @param request The [Request] object containing:
     *  - URL
     *  - HTTP method (GET, POST, PUT, DELETE, HEAD)
     *  - Headers
     *  - Optional request body
     *
     * @return A [Response] object containing:
     *  - HTTP status code
     *  - Response message
     *  - Headers
     *  - Response body as String
     *  - Final request URL (after redirects)
     *
     * @throws IOException If a network error occurs.
     * @throws ReCaptchaException If Google reCAPTCHA is triggered (YouTube-specific cases).
     */
    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        // Build OkHttp request
        val builder = OkHttpRequest.Builder()
            .url(request.url())

        // Configure HTTP method & body
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody())
            "PUT" -> builder.put((request.dataToSend() ?: ByteArray(0)).toRequestBody())
            "DELETE" -> {
                val data = request.dataToSend()
                if (data != null) builder.delete(data.toRequestBody())
                else builder.delete()
            }
            else -> builder.get() // Fallback to GET
        }

        // Add all request headers
        request.headers().forEach { (key, values) ->
            values.forEach { value -> builder.addHeader(key, value) }
        }

        // Execute request
        val okResponse: OkHttpResponse = client.newCall(builder.build()).execute()

        // Return NewPipe-compatible response
        return Response(
            okResponse.code,
            okResponse.message,
            okResponse.headers.toMultimap(),
            okResponse.body.string(),
            okResponse.request.url.toString()
        )
    }
}
