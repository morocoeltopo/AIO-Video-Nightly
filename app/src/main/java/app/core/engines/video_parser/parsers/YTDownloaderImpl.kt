package app.core.engines.video_parser.parsers

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(10, TimeUnit.SECONDS)
		.connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
		.dispatcher(Dispatcher().apply {
			maxRequests = 64
			maxRequestsPerHost = 8
		}).build()

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
		val method = request.httpMethod().uppercase()

		// Special fast path: HEAD request for file size check
		if (method == "HEAD") {
			val builder = OkHttpRequest.Builder()
				.url(request.url())
				.head()

			request.headers().forEach { (key, values) ->
				values.forEach { value -> builder.addHeader(key, value) }
			}

			val headResponse = client.newCall(builder.build()).execute()
			return Response(
				headResponse.code,
				headResponse.message,
				headResponse.headers.toMultimap(),
				"", // No body for HEAD
				headResponse.request.url.toString()
			)
		}

		// Build OkHttp request
		val builder = OkHttpRequest.Builder().url(request.url())

		when (method) {
			"GET" -> builder.get()
			"POST" -> builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody())
			"PUT" -> builder.put((request.dataToSend() ?: ByteArray(0)).toRequestBody())
			"DELETE" -> {
				val data = request.dataToSend()
				if (data != null) builder.delete(data.toRequestBody()) else builder.delete()
			}

			else -> builder.get()
		}

		// Add headers
		request.headers().forEach { (key, values) ->
			values.forEach { value -> builder.addHeader(key, value) }
		}

		// Execute request
		val okResponse: OkHttpResponse = client.newCall(builder.build()).execute()

		return Response(
			okResponse.code,
			okResponse.message,
			okResponse.headers.toMultimap(),
			okResponse.body.source().readUtf8(), // Slightly faster UTF-8 read
			okResponse.request.url.toString()
		)
	}
}
