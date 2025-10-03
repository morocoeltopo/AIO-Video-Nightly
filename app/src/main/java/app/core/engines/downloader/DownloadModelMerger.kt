package app.core.engines.downloader

import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.FSTBuilder.fstConfig
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import lib.process.LogHelperUtils
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadModelMerger {
	companion object {
		const val MERGRED_DATA_MODEL_BINARY_FILENAME = "merged_data_binary.dat"
	}

	private val logger = LogHelperUtils.from(javaClass)
	private val executor = Executors.newSingleThreadExecutor()
	private val isRunning = AtomicBoolean(false)
	private val loopInterval = 5000L

	fun loadMergedDataModelIfPossible(): List<DownloadDataModel>? = loadMergedBinaryData()

	fun startLoop() {
		executor.execute {
			while (true) {
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

	private fun mergeModifiedDownloadDataModels() {
		val internalDir = INSTANCE.filesDir
		val mergedBinaryFile = File(internalDir, MERGRED_DATA_MODEL_BINARY_FILENAME)
		val fileTimestamps = loadFileModifiedTimestamps(internalDir)
		val mergedTimestamp = mergedBinaryFile.lastModified()

		// check if any file is newer than merged
		val needsUpdate = fileTimestamps.values.any { it > mergedTimestamp }
		if (needsUpdate == false) return

		// start updating the merging process
		val downloadSystem = AIOApp.downloadSystem
		val allDownloadModels = (
				downloadSystem.activeDownloadDataModels +
						downloadSystem.finishedDownloadDataModels)
			.distinctBy { it.id }
			.toMutableList()
		if (allDownloadModels.isEmpty()) return
		saveToBinary(mergedBinaryFile, allDownloadModels)
	}

	private fun loadFileModifiedTimestamps(directory: File?): Map<File, Long> {
		val suffix = DOWNLOAD_MODEL_FILE_JSON_EXTENSION
		return directory?.takeIf { it.isDirectory }
			?.listFiles { file ->
				file.isFile && file.name.endsWith(suffix) && !file.name.contains("temp")
			}
			?.associateWith { it.lastModified() } ?: emptyMap()
	}

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
			val bytes = mergedBinaryFile.readBytes()
			@Suppress("UNCHECKED_CAST")
			fstConfig.asObject(bytes) as List<DownloadDataModel>
		} catch (error: Exception) {
			logger.e("Failed to load merged binary file", error)
			null
		}
	}

}
