package app.core

import app.core.AIOApp.Companion.aioBackend
import app.core.AIOApp.Companion.aioSettings
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * [CrashHandler] is a global [Thread.UncaughtExceptionHandler] implementation.
 *
 * Its purpose is to intercept uncaught exceptions across the app,
 * record useful debugging information, and persist it for later inspection.
 *
 * Key responsibilities:
 * - Capture stack traces from uncaught exceptions.
 * - Persist crash details into a local text file for offline debugging.
 * - Mark in [aioSettings] that the app experienced a recent crash.
 * - Update stored settings so crash state survives app restarts.
 *
 * By centralizing crash handling, this class makes error diagnostics easier
 * and improves resilience after unexpected failures.
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

	private val logger = LogHelperUtils.from(javaClass)

	/** Global application context reference. */
	private val appInstance = AIOApp.INSTANCE.applicationContext

	/**
	 * Handles uncaught exceptions in any application thread.
	 *
	 * Flow:
	 * 1. Logs thread and exception details.
	 * 2. Extracts and saves the full stack trace to a file.
	 * 3. Updates [aioSettings] to mark that a crash occurred.
	 *
	 * @param thread    The thread where the uncaught exception occurred.
	 * @param exception The uncaught [Throwable].
	 */
	override fun uncaughtException(thread: Thread, exception: Throwable) {
		try {
			logger.d("Uncaught exception in thread: ${thread.name}")

			// Extract full stack trace into a string
			val stackTrace = StringWriter().use { sw ->
				PrintWriter(sw).use { pw ->
					exception.printStackTrace(pw)
					sw.toString()
				}
			}
			logger.d("Stack trace successfully captured")

			// Attempt to persist crash log
			try {
				val externalDataFolderPath = getText(R.string.text_default_aio_download_folder_path)
				val directoryPath = "$externalDataFolderPath/${getText(R.string.title_aio_others)}/.configs"
				val dir = File(directoryPath)

				// Ensure directory exists
				if (!dir.exists()) {
					dir.mkdirs()
					logger.d("Crash log directory created at: ${dir.absolutePath}")
				}

				// Write stack trace to log file
				val crashLogFile = File(dir, ".aio_crash_log.txt")
				crashLogFile.writeText(stackTrace)

				logger.d("Crash log written at: ${crashLogFile.absolutePath}")
			} catch (error: Exception) {
				logger.d("Failed to save crash log: ${error.message}")
				error.printStackTrace()
			}

			// Save crash information for later inspection
			aioBackend.saveAppCrashedInfo(stackTrace)

			// Mark crash state in settings
			aioSettings.hasAppCrashedRecently = true
			aioSettings.updateInStorage()
			logger.d("Crash flag persisted in aioSettings")

		} catch (error: Exception) {
			// Catch secondary failures in the crash handling flow
			logger.d("Secondary error during crash handling: ${error.message}")
		}
	}
}
