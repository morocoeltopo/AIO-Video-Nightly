package lib.networks

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for OkHttpClient.
 *
 * This ensures that all network calls in the app use a single, shared client instance.
 * Benefits of using a singleton OkHttpClient:
 * 1. Connection reuse: Keeps TCP connections alive and reduces handshake overhead.
 * 2. Reduced latency: Reusing connections avoids repeated setup for each request.
 * 3. Resource management: Limits concurrent requests and connections per host.
 *
 * Configuration details:
 * - Redirect handling is enabled for both HTTP and HTTPS.
 * - Supports HTTP/2 and HTTP/1.1 protocols.
 * - Connection and read timeouts are tuned for responsiveness.
 * - ConnectionPool allows multiple idle connections to be kept alive.
 * - Dispatcher controls maximum concurrent requests globally and per host.
 */
object HttpClientProvider {

	/** Lazily initialized OkHttpClient singleton. */
	val okHttpClient: OkHttpClient by lazy {
		OkHttpClient.Builder()
			// Automatically follow redirects for HTTP and HTTPS
			.followRedirects(true)
			.followSslRedirects(true)

			// Enable both HTTP/2 and HTTP/1.1 for better performance and compatibility
			.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

			// Connection timeout: max time to establish a TCP connection
			.connectTimeout(5, TimeUnit.SECONDS)

			// Read timeout: max time waiting for server response
			.readTimeout(10, TimeUnit.SECONDS)

			// Connection pool: keeps up to 20 idle connections alive for 5 minutes
			.connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))

			// Dispatcher: controls concurrency for network requests
			.dispatcher(Dispatcher().apply {
				maxRequests = 64             // Max concurrent requests globally
				maxRequestsPerHost = 16      // Max concurrent requests per host
			})
			.build()
	}
}
