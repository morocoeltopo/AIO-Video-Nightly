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
 * [YTDownloaderImpl] is a custom implementation of NewPipe's [Downloader]
 * that uses OkHttp for HTTP requests instead of Java's [java.net.HttpURLConnection].
 *
 * This downloader is responsible for executing network requests made by
 * the NewPipe Extractor library (used for parsing streaming services like YouTube).
 */
class YTDownloaderImpl : Downloader() {

	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * The OkHttp client instance used for all HTTP requests.
	 */
	private val okHttpClient = HttpClientProvider.okHttpClient

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

		val builder = OkHttpRequest.Builder().url(request.url())

		// Add headers once
		request.headers().forEach { (key, values) ->
			values.forEach { builder.addHeader(key, it) }
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

			else -> builder.get()
		}

		// Execute request
		val okResponse: OkHttpResponse = okHttpClient.newCall(builder.build()).execute()

		// Read body efficiently
		val responseBody = okResponse.body.string()

		return Response(
			okResponse.code,
			okResponse.message,
			okResponse.headers.toMultimap(),
			responseBody,
			okResponse.request.url.toString()
		)
	}
}
