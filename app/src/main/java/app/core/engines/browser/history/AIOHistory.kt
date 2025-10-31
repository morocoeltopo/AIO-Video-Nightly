package app.core.engines.browser.history

import android.content.Context.MODE_PRIVATE
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.AIOApp.Companion.aioHistory
import app.core.FSTBuilder.fstConfig
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.Serializable
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Manages browser history including storage, retrieval, and various operations.
 *
 * Features:
 * - Dual-format persistence (JSON and binary)
 * - Size-optimized file reading strategies
 * - Thread-safe background operations
 * - Comprehensive history management
 * - Advanced filtering and sorting
 * - Automatic duplicate detection
 * - Archive functionality
 *
 * Storage Strategy:
 * - Binary format preferred for performance
 * - JSON format maintained for readability
 * - File size-based reading optimization:
 *   - Small (<0.5MB): Direct read
 *   - Medium (0.5-5MB): Buffered read
 *   - Large (>5MB): Memory-mapped I/O with fallback
 */
@Entity
class AIOHistory : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Unique identifier for the bookmarks record in ObjectBox database.
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/** List containing all recorded history entries. */
	@SerializedName("historyModels")
	@Transient
	var historyModels: ArrayList<HistoryModel> = ArrayList()

	/**
	 * Loads history data from internal storage and deserializes it into this class.
	 * Uses different reading strategies based on file size to optimize performance.
	 *
	 * Priority:
	 *   1. Try binary format (fast, smaller, efficient).
	 *   2. If binary is missing/corrupted OR bypassed, fall back to JSON format.
	 *
	 * @param bypassBinaryFormat If true, binary history is ignored and deleted.
	 */
	fun readObjectFromStorage(bypassBinaryFormat: Boolean = false) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val historyBinaryDataFile = internalDir.findFile(AIO_HISTORY_FILE_NAME_BINARY)

				// --- 1. Try loading from binary format ---
				if (!bypassBinaryFormat) {
					if (historyBinaryDataFile != null && historyBinaryDataFile.exists()) {
						logger.d("Found binary history file, attempting load")

						val absolutePath = historyBinaryDataFile.getAbsolutePath(INSTANCE)
						val objectInMemory = loadFromBinary(File(absolutePath))

						if (objectInMemory != null) {
							// Successfully read history → keep it in memory & sync storage
							logger.d("Successfully loaded history from binary format")
							aioHistory = objectInMemory
							aioHistory.updateInStorage()
							isBinaryFileValid = true
						} else {
							logger.d("Failed to load history from binary format")
						}
					}
				} else {
					// If bypass requested → delete binary file for a clean slate
					if (historyBinaryDataFile != null && historyBinaryDataFile.exists()) {
						historyBinaryDataFile.delete()
						logger.d("Bypassed and deleted binary history file")
					}
				}

				// --- 2. Fallback: JSON format ---
				if (!isBinaryFileValid) {
					logger.d("Attempting to load history from JSON format")
					val configFile = File(INSTANCE.filesDir, AIO_HISTORY_FILE_NAME_JSON)

					if (!configFile.exists()) {
						// No JSON file either → start with empty library
						aioHistory.historyModels = ArrayList()
						logger.d("No history file found, starting with empty library")
						return@executeInBackground
					}

					// Log file size for debugging & select best reader
					val fileSizeMb = configFile.length().toDouble() / (1024 * 1024)
					logger.d("History file size: ${"%.2f".format(fileSizeMb)} MB")

					val json = when {
						fileSizeMb <= 0.5 -> readSmallFile(configFile)   // Quick read
						fileSizeMb <= 5.0 -> readMediumFile(configFile) // Balanced read
						else -> readLargeFile(configFile)               // Streaming read
					}

					if (json.isNotEmpty()) {
						val historyClass = convertJSONStringToClass(json)

						// Keep the loaded history in memory & resync storage
						logger.d("Successfully loaded ${historyClass.historyModels.size} history entries")
						aioHistory = historyClass
						aioHistory.updateInStorage()
					} else {
						logger.d("History JSON file was empty")
					}
				}
			} catch (error: Exception) {
				logger.d("Error reading history: ${error.message}")
				error.printStackTrace()
			}
		})
	}

	/**
	 * Saves the current history object to a binary file for fast loading later.
	 *
	 * - Uses FST (Fast Serialization) for efficient object-to-binary conversion.
	 * - Synchronized so multiple threads don’t write at the same time.
	 * - Binary format is preferred over JSON for performance.
	 *
	 * @param fileName The target file name inside app's internal storage.
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving history to binary file: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				fos.write(bytes)
				logger.d("History saved successfully to binary format")
			}
		} catch (error: Exception) {
			logger.d("Error saving binary history: ${error.message}")
		}
	}

	/**
	 * Loads history from binary format.
	 *
	 * @param historyBinaryFile File containing binary history data
	 * @return AIOHistory instance or null if loading fails
	 */
	private fun loadFromBinary(historyBinaryFile: File): AIOHistory? {
		if (!historyBinaryFile.exists()) {
			logger.d("Binary history file does not exist")
			return null
		}

		return try {
			logger.d("Loading history from binary file")
			val bytes = historyBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded history from binary format")
			} as AIOHistory
		} catch (error: Exception) {
			logger.d("Error loading binary history: ${error.message}")
			historyBinaryFile.delete()
			error.printStackTrace()
			null
		}
	}

	/**
	 * Direct file read for small history files
	 * @param file Source file to read
	 * @ return File content as String
	 */
	private fun readSmallFile(file: File): String {
		logger.d("Executing direct file read")
		return file.readText(Charsets.UTF_8)
	}

	/**
	 * Buffered read for medium history files
	 * @param file Source file to read
	 * @return File content as String
	 */
	private fun readMediumFile(file: File): String {
		logger.d("Executing buffered file read")
		return BufferedReader(FileReader(file)).use { it.readText() }
	}

	/**
	 * Memory-mapped read for large history files with fallback
	 * @param file Source file to read
	 * @return File content as String
	 */
	private fun readLargeFile(file: File): String {
		logger.d("Attempting memory-mapped file read")
		return try {
			FileInputStream(file).channel.use { channel ->
				val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
				Charsets.UTF_8.decode(buffer).toString()
			}
		} catch (error: OutOfMemoryError) {
			logger.d("Falling back to line-by-line read due to memory constraints")
			buildString {
				BufferedReader(FileReader(file)).forEachLine { line -> append(line) }
			}
		}
	}

	/**
	 * Serializes history to JSON string
	 * @return JSON representation of history
	 */
	private fun convertClassToJSON(): String {
		logger.d("Converting history to JSON")
		return aioGSONInstance.toJson(this)
	}

	/**
	 * Deserializes JSON to history object
	 * @param data JSON string to convert
	 * @return AIOHistory instance
	 */
	private fun convertJSONStringToClass(data: String): AIOHistory {
		logger.d("Converting JSON to history object")

		// Parse the wrapper first
		val history = aioGSONInstance.fromJson(data, AIOHistory::class.java)

		// Parse the list properly with type info
		val type = object : TypeToken<ArrayList<HistoryModel>>() {}.type
		val historyLibraryJSON = JSONObject(data).getString("historyLibrary")
		val parsedList: ArrayList<HistoryModel> = aioGSONInstance.fromJson(historyLibraryJSON, type)

		history.historyModels = parsedList
		return history
	}

	/**
	 * Persists current history to storage in both formats.
	 * Executes asynchronously in background.
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating history in storage")
				saveToBinary(AIO_HISTORY_FILE_NAME_BINARY)
				saveStringToInternalStorage(
					fileName = AIO_HISTORY_FILE_NAME_JSON,
					data = convertClassToJSON()
				)
				AIOHistoryDBManager.updateAllHistoryInDB(historyModels = getHistoryLibrary())
				logger.d("history successfully updated in storage")
			} catch (error: Exception) {
				logger.d("Error updating history: ${error.message}")
				error.printStackTrace()
			}
		})
	}

	/** @return Current history collection */
	fun getHistoryLibrary(): ArrayList<HistoryModel> {
		logger.d("Retrieving history library")
		return historyModels
	}

	/**
	 * Adds new history entry
	 * @param historyModel Entry to add
	 */
	fun insertNewHistory(historyModel: HistoryModel) {
		logger.d("Adding new history entry")
		historyModels.add(historyModel)
	}

	/**
	 * Updates existing history entry
	 * @param oldHistory Entry to replace
	 * @param newHistory New entry data
	 */
	fun updateHistory(oldHistory: HistoryModel, newHistory: HistoryModel) {
		logger.d("Updating history entry")
		val index = historyModels.indexOf(oldHistory)
		if (index != -1) {
			historyModels[index] = newHistory
		}
	}

	/**
	 * Searches history by title or URL
	 * @param query Search term
	 * @return Matching history entries
	 */
	fun searchHistory(query: String): List<HistoryModel> {
		logger.d("Searching history for: $query")
		return historyModels.filter {
			it.historyTitle.contains(query, ignoreCase = true) ||
					it.historyUrl.contains(query, ignoreCase = true)
		}
	}

	/** @return List of duplicate history entries */
	fun findDuplicateHistory(): List<HistoryModel> {
		logger.d("Finding duplicate history entries")
		return historyModels
			.groupBy { it.historyUrl }
			.filter { it.value.size > 1 }
			.flatMap { it.value }
	}

	/**
	 * Removes history entry
	 * @param historyModel Entry to remove
	 */
	fun removeHistory(historyModel: HistoryModel) {
		logger.d("Removing history entry")
		historyModels.remove(historyModel)
	}

	/**
	 * Returns sorted history
	 * @param attribute Sort key ("title" or "date")
	 * @return Sorted history entries
	 */
	fun getHistorySortedBy(attribute: String): List<HistoryModel> {
		logger.d("Sorting history by: $attribute")
		return when (attribute.lowercase(Locale.ROOT)) {
			"title" -> historyModels.sortedBy { it.historyTitle }
			"date" -> historyModels.sortedBy { it.historyVisitDateTime }
			else -> historyModels
		}
	}

	/** Clears all history entries */
	fun clearAllHistory() {
		logger.d("Clearing all history")
		historyModels.clear()
	}

	/** @return Total history entries count */
	fun countHistory(): Int {
		logger.d("Counting history entries")
		return historyModels.size
	}

	/**
	 * Returns recent history within specified days
	 * @param days Days to look back
	 * @return Recent history entries
	 */
	fun getRecentHistory(days: Int): List<HistoryModel> {
		logger.d("Getting recent history for last $days days")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
		val calendar = Calendar.getInstance()
		calendar.add(Calendar.DAY_OF_YEAR, -days)
		val cutoffDate = calendar.time

		val cutoffDateString = dateFormat.format(cutoffDate)
		val cutoffDateParsed = dateFormat.parse(cutoffDateString) ?: return emptyList()

		return historyModels.filter {
			val visitDate = dateFormat.parse(it.historyVisitDateTime.toString())
			val lastAccessedDate = dateFormat.parse(it.historyLastAccessed.toString())
			visitDate != null && visitDate.after(cutoffDateParsed) ||
					lastAccessedDate != null && lastAccessedDate.after(cutoffDateParsed)
		}
	}

	/**
	 * Filters history by minimum visit duration
	 * @param minDuration Minimum duration in ms
	 * @return Matching history entries
	 */
	fun filterHistoryByDuration(minDuration: Long): List<HistoryModel> {
		logger.d("Filtering history by duration >= $minDuration ms")
		return historyModels.filter { it.historyDuration >= minDuration }
	}

	/** @return Important history entries */
	fun getImportantHistory(): List<HistoryModel> {
		logger.d("Getting important history entries")
		return historyModels.filter { it.historyImportant }
	}

	/**
	 * Filters history by tag
	 * @param tag Tag to filter by
	 * @return Matching history entries
	 */
	fun filterHistoryByTag(tag: String): List<HistoryModel> {
		logger.d("Filtering history by tag: $tag")
		return historyModels.filter { it.historyTags.contains(tag) }
	}

	/**
	 * Archives history entry
	 * @param historyModel Entry to archive
	 */
	fun archiveHistory(historyModel: HistoryModel) {
		logger.d("Archiving history entry")
		val index = historyModels.indexOf(historyModel)
		if (index != -1) {
			historyModels[index].historyArchived = true
		}
	}

	/** @return Archived history entries */
	fun getArchivedHistory(): List<HistoryModel> {
		logger.d("Getting archived history entries")
		return historyModels.filter { it.historyArchived }
	}

	companion object {

		/**
		 * Default filename for JSON formatted history storage
		 */
		const val AIO_HISTORY_FILE_NAME_JSON: String = "browsing_history.json"

		/**
		 * Default filename for binary formatted history storage
		 */
		const val AIO_HISTORY_FILE_NAME_BINARY: String = "browsing_history.dat"
	}
}