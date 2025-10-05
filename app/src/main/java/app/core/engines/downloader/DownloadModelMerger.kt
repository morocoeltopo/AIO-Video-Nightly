package app.core.engines.downloader

import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.FSTBuilder.fstConfig
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import lib.process.LogHelperUtils
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DownloadModelMerger - Merges and Maintains a Unified Binary of Download Models
 *
 * Responsibilities:
 * 1. Periodically checks individual JSON model files and merges them into a single binary file.
 * 2. Keeps the merged binary up-to-date by comparing modification timestamps.
 * 3. Provides fast retrieval of merged models to reduce I/O overhead.
 * 4. Runs in a background thread to avoid blocking the main thread.
 *
 * Features:
 * - Thread-safe operations using AtomicBoolean and @Synchronized
 * - Single-threaded executor to serialize merge operations
 * - Incremental updates based on file timestamps
 * - Avoids unnecessary writes if the merged file is already up-to-date
 */
class DownloadModelMerger {
	companion object {
		/** Filename used for storing merged binary of download models */
		const val MERGRED_DATA_MODEL_BINARY_FILENAME = "merged_data_binary.dat"
	}

	private val logger = LogHelperUtils.from(javaClass)

	/** Single-thread executor for running merge loop in background */
	private val executor = Executors.newSingleThreadExecutor()

	/** Ensures that only one merge operation runs at a time */
	private val isRunning = AtomicBoolean(false)

	/** Interval between merge checks in milliseconds */
	private val loopInterval = 5000L

	/**
	 * Attempts to load the merged binary data if it exists and is valid.
	 *
	 * @return List of DownloadDataModel or null if no valid merged file is available
	 */
	fun loadMergedDataModelIfPossible(): List<DownloadDataModel>? = loadMergedBinaryData()

	/**
	 * Starts the background loop that periodically merges modified download models.
	 * Runs indefinitely in a dedicated thread.
	 */
	fun startLoop() {
		executor.execute {
			while (true) {
				// Only proceed if no other merge is currently running
				if (isRunning.compareAndSet(false, true)) {
					try {
						mergeModifiedDownloadDataModels()
					} catch (error: Exception) {
						logger.e("Error in merging array list of download data models:", error)
					} finally {
						isRunning.set(false)
					}
				}
				try {
					Thread.sleep(loopInterval)
				} catch (error: InterruptedException) {
					logger.e("Error found in sleeping the background thread:", error)
					break
				}
			}
		}
	}

	/**
	 * Checks all model JSON files and merges them if any file is newer than the current merged binary.
	 */
	private fun mergeModifiedDownloadDataModels() {
		val internalDir = INSTANCE.filesDir
		val mergedBinaryFile = File(internalDir, MERGRED_DATA_MODEL_BINARY_FILENAME)

		// Map of JSON files to their last modified timestamp
		val fileTimestamps = loadFileModifiedTimestamps(internalDir)
		val mergedTimestamp = mergedBinaryFile.lastModified()

		// Determine if any file is newer than the merged file
		val needsUpdate = fileTimestamps.values.any { it > mergedTimestamp }
		if (!needsUpdate) return // No updates needed

		// Combine active and finished download models and remove duplicates
		val downloadSystem = AIOApp.downloadSystem
		val allDownloadModels = (
				downloadSystem.activeDownloadDataModels +
						downloadSystem.finishedDownloadDataModels)
			.distinctBy { it.id }
			.toMutableList()

		if (allDownloadModels.isEmpty()) return // Nothing to save
		saveToBinary(mergedBinaryFile, allDownloadModels)
	}

	/**
	 * Returns a map of valid JSON model files to their last modified timestamp.
	 *
	 * @param directory Directory containing download model JSON files
	 */
	private fun loadFileModifiedTimestamps(directory: File?): Map<File, Long> {
		val suffix = DOWNLOAD_MODEL_FILE_JSON_EXTENSION
		return directory?.takeIf { it.isDirectory }
			?.listFiles { file ->
				file.isFile && file.name.endsWith(suffix) && !file.name.contains("temp")
			}
			?.associateWith { it.lastModified() } ?: emptyMap()
	}

	/**
	 * Saves a list of DownloadDataModel to a binary file in a thread-safe manner.
	 *
	 * @param mergedBinaryFile The file to write the serialized data to
	 * @param data List of DownloadDataModel to serialize
	 */
	@Synchronized
	private fun saveToBinary(mergedBinaryFile: File, data: List<DownloadDataModel>) {
		try {
			val bytes = fstConfig.asByteArray(data)
			mergedBinaryFile.outputStream().use { it.write(bytes) }
			logger.d("Binary save successful: $mergedBinaryFile, size: ${bytes.size} bytes")
		} catch (error: Exception) {
			logger.e("Binary save error for file: $mergedBinaryFile", error)
		}
	}

	/**
	 * Loads the merged binary file if it exists and is newer than all JSON model files.
	 *
	 * @return List of DownloadDataModel or null if no valid merged file is found
	 */
	private fun loadMergedBinaryData(): List<DownloadDataModel>? {
		val internalDir = INSTANCE.filesDir
		val mergedBinaryFile = File(internalDir, MERGRED_DATA_MODEL_BINARY_FILENAME)
		if (!mergedBinaryFile.exists()) return null

		val fileTimestamps = loadFileModifiedTimestamps(internalDir)
		val mergedTimestamp = mergedBinaryFile.lastModified()

		// Only load if merged file is newer than all JSON files
		val isMergedNewer = fileTimestamps.values.all { it <= mergedTimestamp }
		if (!isMergedNewer) return null

		return try {
			val startFile = System.currentTimeMillis()
			val bytes = mergedBinaryFile.readBytes()
			val endFile = System.currentTimeMillis()

			val startFST = System.currentTimeMillis()
			@Suppress("UNCHECKED_CAST") val list =
				fstConfig.asObject(bytes) as List<DownloadDataModel>
			val endFST = System.currentTimeMillis()

			logger.d("Read file: ${endFile - startFile} ms, Deserialize: ${endFST - startFST} ms")
			return list
		} catch (error: Exception) {
			logger.e("Failed to load merged binary file", error)
			null
		}
	}
}
