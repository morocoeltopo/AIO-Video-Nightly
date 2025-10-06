package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import com.aio.R
import lib.networks.HttpClientProvider
import lib.networks.URLUtilityKT.extractHostUrl
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URL
import java.net.URLEncoder.encode

object DownloadURLHelper {

	private val logger = LogHelperUtils.from(javaClass)

	private const val CONTENT_LENGTH = "Content-Length"
	private const val ACCEPT_RANGE = "Accept-Ranges"
	private const val BYTES = "bytes"
	private const val E_TAG = "ETag"
	private const val LAST_MODIFIED = "Last-Modified"
	private const val CONTENT_DISPOSITION = "Content-Disposition"
	private const val FILE_NAME = "filename="
	private const val SHA_256 = "SHA-256"
	private const val USER_AGENT = "User-Agent"
	private const val HOST = "Host"
	private const val REFERER = "Referer"
	private const val RANGE = "Range"

	fun getFileInfoFromSever(url: URL, downloadModel: DownloadDataModel? = null): RemoteFileInfo {
		val fileInfo = RemoteFileInfo()
		try {
			// Custom cookie jar for session persistence
			val cookieJar = object : CookieJar {
				private val cookies = mutableMapOf<String, List<Cookie>>()
				override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
					this.cookies[url.host] = cookies
				}

				override fun loadForRequest(url: HttpUrl): List<Cookie> {
					return cookies[url.host] ?: emptyList()
				}
			}

			// Configure HTTP client with redirect and cookie support
			val client: OkHttpClient = OkHttpClient.Builder()
				.cookieJar(cookieJar)
				.followRedirects(true)
				.followSslRedirects(true)
				.addInterceptor { chain ->
					val request = chain.request()
					val response = chain.proceed(request)

					// Check if we got redirected
					if (response.isRedirect) {
						val location = response.header("Location")
						logger.d("Redirected to: $location")
					}
					response
				}
				.build()

			// Create the HTTP request builder based on whether the download is from the browser
			val browserDownloadRequest = createRequestBuilderFromUrl(
				url.toString(),
				downloadModel?.globalSettings?.downloadHttpUserAgent,
				downloadModel?.siteReferrer
			).build()

			val normalRequest: Request = Request.Builder().url(url).head().build()
			val request = if (downloadModel != null && downloadModel.isDownloadFromBrowser)
				browserDownloadRequest else normalRequest

			// Make the HTTP request and process the response
			client.newCall(request).execute().use { response ->
				if (response.isSuccessful) {
					// Extract file size
					val contentLength = response.header(CONTENT_LENGTH)
					fileInfo.fileSize = contentLength?.toLong() ?: -1

					// Check for multipart download support
					val acceptRanges = response.header(ACCEPT_RANGE)
					if (acceptRanges == BYTES) fileInfo.isSupportsMultipart = true

					// Check if the file can be resumed (ETag, Last-Modified headers)
					val eTag = response.header(E_TAG)
					val lastModified = response.header(LAST_MODIFIED)
					if (fileInfo.isSupportsMultipart || eTag != null || lastModified != null)
						fileInfo.isSupportsResume = true

					// Extract the file name
					var fileName: String? = null
					val contentDisposition = response.header(CONTENT_DISPOSITION)
					if (contentDisposition != null && contentDisposition.contains(FILE_NAME)) {
						fileName = contentDisposition.split(FILE_NAME.toRegex())
							.dropLastWhile { it.isEmpty() }
							.toTypedArray()[1].replace("\"", "").trim()
					}

					if (fileName == null) {
						val path = url.path
						fileName = path.substring(path.lastIndexOf('/') + 1)
					}

					fileInfo.fileName = fileName
					if (fileInfo.fileName.isEmpty()) {
						fileInfo.fileName = getText(R.string.title_unknown)
					}
				} else {
					// Handle unsuccessful response
					fileInfo.isFileForbidden = true
					fileInfo.fileSize = -1
					fileInfo.errorMessage = "Failed to fetch file details: " +
							"${response.message} (HTTP ${response.code})"
				}
			}
		} catch (error: Exception) {
			// Catch any exceptions and update fileInfo with error details
			fileInfo.isFileForbidden = true
			fileInfo.fileSize = -1
			fileInfo.errorMessage = "Error fetching file details:" +
					" ${error.message ?: "Unknown error"}"
			logger.e(error)
		}

		return fileInfo
	}

	private fun createRequestBuilderFromUrl(
		urlString: String, userAgent: String? = null,
		siteReferer: String? = null, byteRange: String? = null
	): Request.Builder {
		val uri = URI(urlString)
		val host = uri.host
		val query = uri.query

		val requestBuilder = Request.Builder().url(urlString)
		requestBuilder.addHeader(HOST, host)

		if (!userAgent.isNullOrEmpty()) requestBuilder.addHeader(USER_AGENT, userAgent)
		if (!siteReferer.isNullOrEmpty()) requestBuilder.addHeader(REFERER, extractHostUrl(siteReferer))
		if (!byteRange.isNullOrEmpty()) requestBuilder.addHeader(RANGE, byteRange)
		val responseContentDisposition = query?.let { it ->
			it.split("&").find { it.contains("response-content-disposition") }
		}

		responseContentDisposition?.let {
			val decodedDisposition = it.split("=")[1]
			requestBuilder.addHeader(
				CONTENT_DISPOSITION,
				"attachment; filename=${encode(decodedDisposition, "UTF-8")}"
			)
		}

		return requestBuilder
	}

	fun fetchChecksumFromHeaders(
		fileUrl: URL,
		targetAlgorithm: String = SHA_256,
		onConnectionStatus: (String) -> Unit
	): String? {
		try {
			var statusString = getText(R.string.title_connecting)
			onConnectionStatus(statusString)

			val okHttpClient = HttpClientProvider.okHttpClient
			val request = Request.Builder().url(fileUrl).head().build()

			okHttpClient.newCall(request).execute().use { httpResponse ->
				statusString = INSTANCE.getString(R.string.title_connected_b_, httpResponse.code)
				onConnectionStatus(statusString)

				if (!httpResponse.isSuccessful) {
					statusString = INSTANCE.getString(R.string.title_failed_b_, httpResponse.code)
					onConnectionStatus(statusString)
					return null
				}

				val commonHeaderKeys = listOf(
					"X-Checksum-$targetAlgorithm",   // custom
					"X-Checksum",                    // custom
					"Content-MD5",                   // base64 of MD5
					"ETag"                           // often MD5-like in some servers
				)

				for (key in commonHeaderKeys) {
					httpResponse.header(key)?.let { raw ->
						statusString = INSTANCE.getString(R.string.title_found_in_headers_b_, key)
						onConnectionStatus(statusString)
						return raw.trim().trim('"')
					}
				}

				statusString = getText(R.string.title_no_checksum_header_found)
				onConnectionStatus(statusString)
			}
		} catch (error: Exception) {
			logger.e("Error found in fetching checksum from http headers:", error)
			onConnectionStatus("Error: ${error.message}")
		}

		return null
	}

}
