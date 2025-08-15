package app.core.engines.browser.bookmarks

import android.content.Context.MODE_PRIVATE
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.AIOApp.Companion.kryo
import com.anggrayudi.storage.file.getAbsolutePath
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.DefaultSerializers.StringSerializer
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.Serializable
import java.nio.channels.FileChannel
import java.util.Date
import java.util.Locale

/**
 * Manages browser bookmarks including storage, retrieval, and various operations.
 *
 * Features:
 * - Persistent storage using both JSON and binary serialization
 * - Efficient file reading strategies based on file size
 * - Thread-safe operations through background execution
 * - Advanced bookmark filtering and sorting capabilities
 * - Support for bookmark metadata (ratings, tags, favorites, etc.)
 * - Automatic duplicate detection
 * - Archive functionality
 *
 * Storage Strategy:
 * - Small files (<0.5MB): Simple read operation
 * - Medium files (0.5-5MB): Buffered read
 * - Large files (>5MB): Memory-mapped I/O with fallback to line-by-line reading
 *
 * All file operations are performed asynchronously to avoid blocking the UI thread.
 */
class AIOBookmarks : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	companion object {
		/**
		 * Serialization ID for binary format compatibility
		 */
		const val AIO_BOOKMARKS_SERIALIZABLE_ID = 1

		/**
		 * Default filename for JSON formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_JSON: String = "aio_bookmarks.json"

		/**
		 * Default filename for binary formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_BINARY: String = "aio_bookmarks.dat"
	}

	// Main bookmark storage
	private var bookmarkLibrary: ArrayList<BookmarkModel> = ArrayList()

	/**
	 * Reads bookmarks from persistent storage.
	 * Attempts binary format first, falls back to JSON if binary is invalid.
	 * Automatically selects optimal reading strategy based on file size.
	 */
	fun readObjectFromStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val bookmarkBinaryDataFile = internalDir.findFile(AIO_BOOKMARKS_FILE_NAME_BINARY)

				if (bookmarkBinaryDataFile != null && bookmarkBinaryDataFile.exists()) {
					logger.d("Found binary bookmarks file, attempting load")
					val absolutePath = bookmarkBinaryDataFile.getAbsolutePath(INSTANCE)
					val objectInMemory = loadFromBinary(File(absolutePath))
					if (objectInMemory != null) {
						logger.d("Successfully loaded bookmarks from binary format")
						aioBookmark = objectInMemory
						aioBookmark.updateInStorage()
						isBinaryFileValid = true
					} else {
						logger.d("Failed to load bookmarks from binary format")
					}
				}

				if (!isBinaryFileValid) {
					logger.d("Attempting to load bookmarks from JSON format")
					val configFile = File(INSTANCE.filesDir, AIO_BOOKMARKS_FILE_NAME_JSON)
					if (!configFile.exists()) {
						aioBookmark.bookmarkLibrary = ArrayList()
						logger.d("No bookmarks file found, starting with empty library")
						return@executeInBackground
					}

					val fileSizeMb = configFile.length().toDouble() / (1024 * 1024)
					logger.d("Bookmarks file size: ${"%.2f".format(fileSizeMb)} MB")

					val json = when {
						fileSizeMb <= 0.5 -> readSmallFile(configFile)
						fileSizeMb <= 5.0 -> readMediumFile(configFile)
						else -> readLargeFile(configFile)
					}

					if (json.isNotEmpty()) {
						convertJSONStringToClass(json).let { bookmarkClass ->
							logger.d("Successfully loaded ${bookmarkClass.bookmarkLibrary.size} bookmarks")
							aioBookmark = bookmarkClass
							aioBookmark.updateInStorage()
						}
					}
				}
			} catch (error: Exception) {
				logger.d("Error reading bookmarks: ${error.message}")
				error.printStackTrace()
			}
		})
	}

	/**
	 * Saves bookmarks to binary format.
	 * @param fileName Name of the binary file to save to
	 */
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving bookmarks to binary file: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				Output(fos).use { output ->
					registerKryoClasses()
					kryo.writeObject(output, this)
				}
			}
			logger.d("Bookmarks saved successfully to binary format")
		} catch (error: Exception) {
			logger.d("Error saving binary bookmarks: ${error.message}")
		}
	}

	/**
	 * Loads bookmarks from binary format.
	 * @param bookmarksBinaryFile File containing binary bookmarks data
	 * @return AIOBookmarks instance or null if loading fails
	 */
	private fun loadFromBinary(bookmarksBinaryFile: File): AIOBookmarks? {
		if (!bookmarksBinaryFile.exists()) {
			logger.d("Binary bookmarks file does not exist")
			return null
		}

		return try {
			logger.d("Loading bookmarks from binary file")
			FileInputStream(bookmarksBinaryFile).use { fis ->
				Input(fis).use { input ->
					registerKryoClasses()
					kryo.readObject(input, AIOBookmarks::class.java).also {
						logger.d("Successfully loaded ${it.bookmarkLibrary.size} bookmarks from binary")
					}
				}
			}
		} catch (error: Exception) {
			logger.d("Error loading binary bookmarks: ${error.message}")
			bookmarksBinaryFile.delete()
			error.printStackTrace()
			null
		}
	}

	/**
	 * Reads small files using simple file operation.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readSmallFile(file: File): String {
		logger.d("Using simple read for small file")
		return file.readText(Charsets.UTF_8)
	}

	/**
	 * Reads medium files using buffered reader.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readMediumFile(file: File): String {
		logger.d("Using buffered read for medium file")
		return BufferedReader(FileReader(file)).use { it.readText() }
	}

	/**
	 * Reads large files using memory-mapped I/O with fallback.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readLargeFile(file: File): String {
		return try {
			logger.d("Using memory-mapped I/O for large file")
			FileInputStream(file).channel.use { channel ->
				val buffer = channel.map(
					FileChannel.MapMode.READ_ONLY,
					0, channel.size()
				)
				Charsets.UTF_8.decode(buffer).toString()
			}
		} catch (error: OutOfMemoryError) {
			logger.d("Falling back to line-by-line reading")
			buildString {
				BufferedReader(FileReader(file)).forEachLine { line ->
					append(line)
				}
			}
		}
	}

	/**
	 * Serializes bookmarks to JSON.
	 * @return JSON string representation
	 */
	private fun convertClassToJSON(): String {
		logger.d("Converting bookmarks to JSON")
		return aioGSONInstance.toJson(this)
	}

	/**
	 * Deserializes JSON to AIOBookmarks instance.
	 * @param data JSON string to convert
	 * @return AIOBookmarks instance
	 */
	private fun convertJSONStringToClass(data: String): AIOBookmarks {
		logger.d("Converting JSON to bookmarks object")
		return aioGSONInstance.fromJson(data, AIOBookmarks::class.java)
	}

	/**
	 * Persists current bookmarks to storage.
	 * Saves both JSON and binary formats asynchronously.
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating bookmarks in storage")
				saveToBinary(AIO_BOOKMARKS_FILE_NAME_BINARY)
				saveStringToInternalStorage(
					fileName = AIO_BOOKMARKS_FILE_NAME_JSON,
					data = convertClassToJSON()
				)
				logger.d("Bookmarks successfully updated in storage")
			} catch (error: Exception) {
				logger.d("Error updating bookmarks: ${error.message}")
				error.printStackTrace()
			}
		})
	}

    /**
     * Registers all required classes with Kryo serializer.
     *
     * Must include all classes that will be serialized, including collections.
     * Called before both serialization and deserialization.
     */
	private fun registerKryoClasses() {
		kryo.isRegistrationRequired = true
		kryo.register(String::class.java, StringSerializer())
		kryo.register(emptyList<BookmarkModel>().javaClass)
		kryo.register(List::class.java)
		kryo.register(ArrayList::class.java)
		kryo.register(BookmarkModel::class.java)
		kryo.register(AIOBookmarks::class.java)
		kryo.register(Date::class.java)
		kryo.register(HashMap::class.java)
		kryo.register(HashSet::class.java)
	}

	/**
	 * Gets all bookmarks in the library.
	 * @return List of all bookmarks
	 */
	fun getBookmarkLibrary(): ArrayList<BookmarkModel> {
		return bookmarkLibrary
	}

	/**
	 * Adds a new bookmark to the library.
	 * @param bookmarkModel Bookmark to add
	 */
	fun insertNewBookmark(bookmarkModel: BookmarkModel) {
		getBookmarkLibrary().add(bookmarkModel)
	}

	/**
	 * Updates an existing bookmark.
	 * @param oldBookmark Bookmark to replace
	 * @param newBookmark New bookmark data
	 */
	fun updateBookmark(oldBookmark: BookmarkModel, newBookmark: BookmarkModel) {
		val index = bookmarkLibrary.indexOf(oldBookmark)
		if (index != -1) {
			bookmarkLibrary[index] = newBookmark
		}
	}

	/**
	 * Removes a bookmark from the library.
	 * @param bookmarkModel Bookmark to remove
	 */
	fun removeBookmark(bookmarkModel: BookmarkModel) {
		bookmarkLibrary.remove(bookmarkModel)
	}

	/**
	 * Searches bookmarks by name or URL.
	 * @param query Search term
	 * @return List of matching bookmarks (case-insensitive)
	 */
	fun searchBookmarks(query: String): List<BookmarkModel> {
		return bookmarkLibrary.filter {
			it.bookmarkName.contains(query, ignoreCase = true) ||
					it.bookmarkUrl.contains(query, ignoreCase = true)
		}
	}

	/**
	 * Finds duplicate bookmarks (same URL).
	 * @return List of duplicate bookmarks
	 */
	fun findDuplicateBookmarks(): List<BookmarkModel> {
		return bookmarkLibrary
			.groupBy { it.bookmarkUrl }
			.filter { it.value.size > 1 }
			.flatMap { it.value }
	}

	/**
	 * Sorts bookmarks by specified attribute.
	 * @param attribute Attribute to sort by ("name" or "date")
	 * @return Sorted list of bookmarks
	 */
	fun getBookmarksSortedBy(attribute: String): List<BookmarkModel> {
		return when (attribute.lowercase(Locale.ROOT)) {
			"name" -> bookmarkLibrary.sortedBy { it.bookmarkName }
			"date" -> bookmarkLibrary.sortedBy { it.bookmarkCreationDate }
			else -> bookmarkLibrary
		}
	}

	/**
	 * Filters bookmarks by minimum rating.
	 * @param minRating Minimum rating threshold
	 * @return List of bookmarks meeting the rating criteria
	 */
	fun filterBookmarksByRating(minRating: Float): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkRating >= minRating }
	}

	/**
	 * Gets all favorite bookmarks.
	 * @return List of favorite bookmarks
	 */
	fun getFavoriteBookmarks(): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkFavorite }
	}

	/**
	 * Filters bookmarks by tag.
	 * @param tag Tag to filter by
	 * @return List of bookmarks containing the specified tag
	 */
	fun filterBookmarksByTag(tag: String): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkTags.contains(tag) }
	}

	/**
	 * Filters bookmarks by minimum priority.
	 * @param minPriority Minimum priority threshold
	 * @return List of bookmarks meeting the priority criteria
	 */
	fun filterBookmarksByPriority(minPriority: Int): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkPriority >= minPriority }
	}

	/**
	 * Gets all archived bookmarks.
	 * @return List of archived bookmarks
	 */
	fun getArchivedBookmarks(): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkArchived }
	}

	/**
	 * Archives a bookmark.
	 * @param bookmarkModel Bookmark to archive
	 */
	fun archiveBookmark(bookmarkModel: BookmarkModel) {
		val index = bookmarkLibrary.indexOf(bookmarkModel)
		if (index != -1) {
			bookmarkLibrary[index].bookmarkArchived = true
		}
	}

	/**
	 * Gets bookmarks shared with specific user.
	 * @param user User identifier
	 * @return List of shared bookmarks
	 */
	fun getBookmarksSharedWithUser(user: String): List<BookmarkModel> {
		return bookmarkLibrary.filter { it.bookmarkSharedWith.contains(user) }
	}

	/**
	 * Clears all bookmarks from the library.
	 */
	fun clearAllBookmarks() {
		bookmarkLibrary.clear()
	}

	/**
	 * Gets total number of bookmarks.
	 * @return Count of bookmarks
	 */
	fun countBookmarks(): Int {
		return bookmarkLibrary.size
	}
}