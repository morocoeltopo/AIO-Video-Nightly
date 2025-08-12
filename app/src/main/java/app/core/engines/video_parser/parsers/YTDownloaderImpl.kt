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

class YTDownloaderImpl : Downloader() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val builder = OkHttpRequest.Builder()
            .url(request.url())

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

            else -> builder.get()
        }

        request.headers().forEach { (key, values) ->
            values.forEach { value -> builder.addHeader(key, value) }
        }

        val okResponse: OkHttpResponse = client.newCall(builder.build()).execute()

        return Response(
            okResponse.code,
            okResponse.message,
            okResponse.headers.toMultimap(),
            okResponse.body.string(),
            okResponse.request.url.toString()
        )
    }
}
