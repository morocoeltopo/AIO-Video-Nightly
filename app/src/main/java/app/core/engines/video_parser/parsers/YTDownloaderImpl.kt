package app.core.engines.video_parser.parsers

import lib.networks.HttpClientProvider
import lib.process.LogHelperUtils
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import okhttp3.Request as OkHttpRequest
import okhttp3.Response as OkHttpResponse

/**
 * [YTDownloaderImpl] is a custom implementation of NewPipe's [Downloader].
 *
 * Instead of using Java's HttpURLConnection, this class uses a singleton OkHttp client
 * (provided by [HttpClientProvider]) for efficient and fast network requests.
 *
 * Responsibilities:
 * 1. Handles HTTP requests for the NewPipe Extractor library (used for YouTube parsing).
 * 2. Adds custom logging for debugging and monitoring request execution.
 * 3. Supports all major HTTP methods: GET, POST, PUT, DELETE, HEAD.
 * 4. Efficiently reads response bodies as UTF-8 strings.
 */
class YTDownloaderImpl : Downloader() {

	private val logger = LogHelperUtils.from(javaClass)

	/** Singleton OkHttp client shared across all requests. */
	private val okHttpClient = HttpClientProvider.okHttpClient

	/**
	 * Executes a network request using OkHttp and returns a NewPipe-compatible [Response].
	 *
	 * This method logs request start, method, URL, and headers, then executes the request.
	 * It also logs response code and URL for easier debugging.
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
		val method = request.httpMethod().uppercase()
		logger.d("Executing $method request for URL: ${request.url()}")

		// Build OkHttp request
		val builder = OkHttpRequest.Builder().url(request.url())

		// Add headers
		request.headers().forEach { (key, values) ->
			values.forEach { value ->
				builder.addHeader(key, value)
				logger.d("Header added: $key=$value")
			}
		}

		// Set HTTP method and body efficiently
		when (method) {
			"HEAD" -> builder.head()
			"GET" -> builder.get()
			"POST" -> builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody())
			"PUT" -> builder.put((request.dataToSend() ?: ByteArray(0)).toRequestBody())
			"DELETE" -> {
				val data = request.dataToSend()
				if (data != null) builder.delete(data.toRequestBody()) else builder.delete()
			}
			else -> {
				logger.d("Unknown HTTP method '$method', defaulting to GET")
				builder.get()
			}
		}

		// Execute the HTTP request
		val okResponse: OkHttpResponse = okHttpClient.newCall(builder.build()).execute()
		logger.d("Response received: ${okResponse.code} from ${okResponse.request.url}")

		// Read response body
		val responseBody = okResponse.body.string()
		logger.d("Response body length: ${responseBody.length} characters")

		return Response(
			okResponse.code,
			okResponse.message,
			okResponse.headers.toMultimap(),
			responseBody,
			okResponse.request.url.toString()
		)
	}
}
