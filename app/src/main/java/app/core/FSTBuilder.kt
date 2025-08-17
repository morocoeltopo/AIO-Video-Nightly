package app.core

import org.nustaq.serialization.FSTConfiguration

/**
 * Singleton object responsible for managing the global [FSTConfiguration] instance.
 *
 * This ensures a single, shared configuration of the **FST (Fast-Serialization)**
 * library is used across the entire application. Using a single instance avoids
 * unnecessary memory overhead, keeps serialization consistent, and improves performance.
 *
 * ### Usage
 * Access the configuration directly:
 * ```kotlin
 * val bytes = FSTBuilder.fstConfig.asByteArray(myObject)
 * val obj = FSTBuilder.fstConfig.asObject(bytes)
 * ```
 *
 * ### Notes
 * - The default configuration is created via [FSTConfiguration.createDefaultConfiguration].
 * - This instance is thread-safe and can be used throughout the app.
 */
object FSTBuilder {

	/**
	 * Global FST configuration instance shared across the application.
	 *
	 * Provides high-performance serialization and deserialization of
	 * objects to/from binary format.
	 */
	@JvmStatic
	val fstConfig: FSTConfiguration = FSTConfiguration.createDefaultConfiguration()
}
