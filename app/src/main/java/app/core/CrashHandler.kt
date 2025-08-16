package app.core

import app.core.AIOApp.Companion.aioSettings
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * CrashHandler is a custom [Thread.UncaughtExceptionHandler] that handles uncaught exceptions
 * across the application and performs necessary logging and state updates.
 *
 * When an uncaught exception occurs, this handler:
 * - Extracts the full stack trace.
 * - Saves the crash information using [aioBackend].
 * - Marks that the app has recently crashed in [aioSettings].
 * - Updates the persisted settings.
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

	private val logger = LogHelperUtils.from(javaClass)

	/** Holds the application context instance from [AIOApp]. */
	private val appInstance = AIOApp.INSTANCE.applicationContext

	/**
	 * Called when an uncaught exception occurs in any thread.
	 *
	 * @param thread The thread that has encountered the exception.
	 * @param exception The uncaught exception.
	 */
	override fun uncaughtException(thread: Thread, exception: Throwable) {
		try {
			// Extract full stack trace as a string
			val stackTrace = StringWriter().use { sw ->
				PrintWriter(sw).use { pw ->
					exception.printStackTrace(pw)
					sw.toString()
				}
			}

			// Save crash information for later inspection
			try {
				// Build directory path
				val externalDataFolderPath = getText(R.string.text_default_aio_download_folder_path)
				val directoryPath = "$externalDataFolderPath${getText(R.string.title_aio_others)}"
				val dir = File(directoryPath)

				// Ensure directory exists
				if (!dir.exists()) {
					dir.mkdirs()
				}

				// Create crash log file
				val crashLogFile = File(dir, "aio_crash_log.txt")

				// Write crash data
				crashLogFile.writeText(stackTrace)

				logger.d("Crash log saved at: ${crashLogFile.absolutePath}")
			} catch (error: Exception) {
				error.printStackTrace()
				logger.d("Failed to save crash log: ${error.message}")
			}

			// Update crash flag in settings
			aioSettings.hasAppCrashedRecently = true
			aioSettings.updateInStorage()
		} catch (error: Exception) {
			// Log any errors that occur while handling the original crash
			logger.e(error)
		}
	}
}