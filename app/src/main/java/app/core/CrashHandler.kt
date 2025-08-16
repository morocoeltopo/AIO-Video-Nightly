package app.core

import app.core.AIOApp.Companion.aioSettings
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * [CrashHandler] is a custom implementation of [Thread.UncaughtExceptionHandler].
 *
 * It provides a centralized way to handle uncaught exceptions across the entire app.
 *
 * Responsibilities:
 * - Captures the stack trace of any uncaught exception.
 * - Persists crash logs to a text file for later inspection.
 * - Marks in [aioSettings] that the app has recently crashed.
 * - Updates stored settings to reflect the crash state.
 *
 * This ensures that crash information is retained even after the app restarts,
 * aiding in debugging and recovery.
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

	private val logger = LogHelperUtils.from(javaClass)

	/** Reference to the global application context instance. */
	private val appInstance = AIOApp.INSTANCE.applicationContext

	/**
	 * Handles uncaught exceptions from any thread in the application.
	 *
	 * @param thread The thread that encountered the uncaught exception.
	 * @param exception The exception that was thrown but not caught.
	 */
	override fun uncaughtException(thread: Thread, exception: Throwable) {
		try {
			logger.d("Uncaught exception detected in thread: ${thread.name}")

			// Extract full stack trace as a string
			val stackTrace = StringWriter().use { sw ->
				PrintWriter(sw).use { pw ->
					exception.printStackTrace(pw)
					sw.toString()
				}
			}
			logger.d("Stack trace extracted successfully")

			// Save crash information to persistent storage
			try {
				// Build directory path
				val externalDataFolderPath = getText(R.string.text_default_aio_download_folder_path)
				val directoryPath = "$externalDataFolderPath${getText(R.string.title_aio_others)}"
				val dir = File(directoryPath)

				// Ensure directory exists
				if (!dir.exists()) {
					dir.mkdirs()
					logger.d("Crash log directory created at: ${dir.absolutePath}")
				}

				// Create crash log file
				val crashLogFile = File(dir, ".aio_crash_log.txt")

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
			logger.d("Crash flag updated in aioSettings")

		} catch (error: Exception) {
			// Log any errors that occur while handling the original crash
			logger.d("Error while handling uncaught exception: ${error.message}")
		}
	}
}