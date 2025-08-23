package app.core.engines.browser.bookmarks

import android.content.Context.MODE_PRIVATE
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.FSTBuilder.fstConfig
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
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
		 * Default filename for JSON formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_JSON: String = "aio_bookmarks.json"

		/**
		 * Default filename for binary formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_BINARY: String = "aio_bookmarks.dat"
	}

	/** List containing all recorded bookmark entries. */
	@SerializedName("bookmarkLibrary")
	private var bookmarkLibrary: ArrayList<BookmarkModel> = ArrayList()

	/**
	 * Reads bookmarks from persistent storage.
	 *
	 * - Attempts to load from binary format first (faster, smaller).
	 * - If binary is missing, invalid, or bypassed → fall back to JSON.
	 * - Chooses optimal reading strategy (small/medium/large) based on file size.
	 * - Ensures aioBookmark is always initialized (either loaded or preloaded).
	 *
	 * @param bypassBinaryFormat If true, binary file is ignored and deleted, forcing JSON load.
	 */
	fun readObjectFromStorage(bypassBinaryFormat: Boolean = false) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val bookmarkBinaryDataFile = internalDir.findFile(AIO_BOOKMARKS_FILE_NAME_BINARY)

				// --- 1. Try to load from binary format ---
				if (!bypassBinaryFormat) {
					if (bookmarkBinaryDataFile != null && bookmarkBinaryDataFile.exists()) {
						logger.d("Found binary bookmarks file, attempting load")

						val absolutePath = bookmarkBinaryDataFile.getAbsolutePath(INSTANCE)
						val objectInMemory = loadFromBinary(File(absolutePath))

						if (objectInMemory != null) {
							// Successfully restored bookmarks from binary
							logger.d("Successfully loaded bookmarks from binary format")
							aioBookmark = objectInMemory
							aioBookmark.updateInStorage()
							isBinaryFileValid = true
						} else {
							// Corrupted or unreadable binary
							logger.d("Failed to load bookmarks from binary format")
						}
					}
				} else {
					// If bypass flag is true → force delete binary file
					if (bookmarkBinaryDataFile != null && bookmarkBinaryDataFile.exists()) {
						bookmarkBinaryDataFile.delete()
						logger.d("Bypassed and deleted binary bookmarks file")
					}
				}

				// --- 2. Fall back to JSON format if binary was invalid or bypassed ---
				if (!isBinaryFileValid) {
					logger.d("Attempting to load bookmarks from JSON format")
					val configFile = File(INSTANCE.filesDir, AIO_BOOKMARKS_FILE_NAME_JSON)

					if (!configFile.exists()) {
						// No JSON file available → start with preloaded defaults
						aioBookmark.bookmarkLibrary = getPreloadedBookmarks()
						logger.d("No bookmarks file found, starting with preloaded library")
						return@executeInBackground
					}

					// Log file size and decide which reader to use
					val fileSizeMb = configFile.length().toDouble() / (1024 * 1024)
					logger.d("Bookmarks file size: ${"%.2f".format(fileSizeMb)} MB")

					val json = when {
						fileSizeMb <= 0.5 -> readSmallFile(configFile)   // Simple load
						fileSizeMb <= 5.0 -> readMediumFile(configFile) // Buffered load
						else -> readLargeFile(configFile)               // Streamed load
					}

					// Deserialize JSON → Kotlin class
					if (json.isNotEmpty()) {
						convertJSONStringToClass(json).let { bookmarkClass ->
							logger.d("Successfully loaded ${bookmarkClass.bookmarkLibrary.size} bookmarks")
							aioBookmark = bookmarkClass
							aioBookmark.updateInStorage()
						}
					} else {
						logger.d("Bookmarks JSON file was empty")
					}
				}
			} catch (error: Exception) {
				// Catch-all safeguard to prevent crashes
				logger.d("Error reading bookmarks: ${error.message}")
				error.printStackTrace()
			}
		})
	}

	/**
	 * Creates and returns a list of preloaded bookmarks.
	 *
	 * @return ArrayList of BookmarkModel with 50+ well-known sites.
	 */
	fun getPreloadedBookmarks(): ArrayList<BookmarkModel> {
		val bookmarks = ArrayList<BookmarkModel>()

		val sites = listOf(
			// 🌍 General / Search
			"Google" to "https://google.com/",
			"Bing" to "https://bing.com/",
			"Yahoo" to "https://yahoo.com/",
			"DuckDuckGo" to "https://duckduckgo.com/",
			"Wikipedia" to "https://wikipedia.org/",

			// 🎵 Music
			"PagalNew" to "https://pagalnew.com/",
			"Mr-Jatt" to "https://mr-jatt.im/",
			"DJMaza" to "https://djmaza.my/",
			"DJPunjab" to "https://djpunjab.fm/",
			"Raag.fm" to "https://raag.fm/",
			"JioSaavn" to "https://jiosaavn.com/",
			"Wynk Music" to "https://wynk.in/music",
			"Gaana" to "https://gaana.com/",

			// 🎬 Movie Download Sites
			"MoviesWap" to "https://movieswap.cam/",
			"TamilYogi" to "https://tamilyogi.cool/",
			"9xMovies" to "https://9xmovies.team/",
			"7StarHD" to "https://7starhd.run/",
			"Khatrimaza" to "https://khatrimaza.uno/",
			"WorldFree4U" to "https://worldfree4u.lol/",
			"MoviesFlix" to "https://themoviesflix.org/",
			"HDMoviesHub" to "https://hdmovieshub.ltd/",
			"BollywoodHungama" to "https://bollywoodhungama.com/movies/",
			"SSRMovies" to "https://ssrmovies.rest/",
			"SkyMoviesHD" to "https://skymovieshd.work/",
			"KatMovieHD" to "https://katmoviehd.run/",
			"HDHub4U" to "https://hdhub4u.day/",
			"RdxHD" to "https://rdxhd.com/",
			"Okjatt" to "https://okjatt.team/",
			"VegaMovies" to "https://vegamovies.boo/",
			"KuttyMovies" to "https://kuttymovies.day/",
			"MLWBD" to "https://mlwbd.world/",
			"JioRockers" to "https://jiorockers.link/",
			"Extramovies" to "https://extramovies.wiki/",
			"MoviesBaba" to "https://moviesbaba.run/",
			"DesiFilmy" to "https://desifilmy.me/",
			"DownloadHub" to "https://downloadhub.baby/",
			"MovieMad" to "https://moviemad.run/",
			"KatMovies" to "https://katmovieshd.surf/",

			// 🔞 Adult (18+)
			"Pornhub" to "https://pornhub.com/",
			"UncutClips" to "https://uncutclips.org/",
			"XVideos" to "https://xvideos.com/",
			"XNXX" to "https://xnxx.com/",
			"RedTube" to "https://redtube.com/",
			"YouPorn" to "https://youporn.com/",
			"Eporner" to "https://eporner.com/",
			"SpankBang" to "https://spankbang.com/",
			"xHamster" to "https://xhamster.com/",
			"Tube8" to "https://tube8.com/",
			"Porn.com" to "https://porn.com/",
			"Thumbzilla" to "https://thumbzilla.com/",
			"Tnaflix" to "https://tnaflix.com/",
			"Beeg" to "https://beeg.com/",
			"DrTuber" to "https://drtuber.com/",
			"SunPorno" to "https://sunporno.com/",
			"PornTrex" to "https://porntrex.com/",
			"XTube" to "https://xtube.com/",
			"Slutload" to "https://slutload.com/",
			"PornHD" to "https://pornhd.com/",
			"BravoPorn" to "https://bravoporn.com/",
			"Fantasti.cc" to "https://fantasti.cc/",
			"XTubePorn" to "https://xtubeporn.com/",
			"HotMovies" to "https://hotmovies.com/",
			"XMovie8" to "https://xmovie8.com/",
			"PornOne" to "https://pornone.com/",
			"NuBay" to "https://nubay.com/",
			"Motherless" to "https://motherless.com/",
			"RedGifs" to "https://redgifs.com/",
			"CamSoda" to "https://camsoda.com/",
			"Chaturbate" to "https://chaturbate.com/",
			"MyFreeCams" to "https://myfreecams.com/",
			"LiveJasmin" to "https://livejasmin.com/",
			"Stripchat" to "https://stripchat.com/",
			"BongaCams" to "https://bongacams.com/",
			"Cam4" to "https://cam4.com/",
			"XConfessions" to "https://xconfessions.com/",
			"Sex.com" to "https://sex.com/",
			"HClips" to "https://hclips.com/",
			"HQPorner" to "https://hqporner.com/",
			"XXNX" to "https://xxnx.com/",
			"4Tube" to "https://4tube.com/",
			"Fapster" to "https://fapster.xxx/",
			"FapVid" to "https://fapvid.com/",
			"FapNation" to "https://fapnxx.com/",
			"YesPornPlease" to "https://yespornplease.to/",
			"DaftSex" to "https://daftsex.com/",
			"Xozilla" to "https://xozilla.com/",
			"XFreeHD" to "https://xfreehd.com/",
			"Porndig" to "https://porndig.com/",
			"WatchMyGF" to "https://watchmygf.xxx/",
			"PornMegaLoad" to "https://pornmegaload.com/",
			"Analdin" to "https://analdin.com/",
			"Fux" to "https://fux.com/",
			"XXXBunker" to "https://xxxbunker.com/",
			"BravoTube" to "https://bravotube.net/",
			"PornDoe" to "https://porndoe.com/",
			"PornHat" to "https://pornhat.com/",
			"JizzBunker" to "https://jizzbunker.com/",
			"ExtremeTube" to "https://extremetube.com/",
			"Spankwire" to "https://spankwire.com/",
			"YourPorn" to "https://youporn.xxx/",
			"PornXO" to "https://pornxo.com/",
			"SexVid" to "https://sexvid.xxx/",
			"FapTube" to "https://faptube.com/"
		)

		for ((name, url) in sites) {
			val bookmark = BookmarkModel().apply {
				bookmarkName = name
				bookmarkUrl = url
				bookmarkCreationDate = Date()
			}
			bookmarks.add(bookmark)
		}

		return bookmarks
	}

	/**
	 * Saves bookmarks to binary format.
	 * @param fileName Name of the binary file to save to
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving bookmarks to binary file: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				fos.write(bytes)
				logger.d("Bookmarks saved successfully to binary format")
			}
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
			val bytes = bookmarksBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded bookmarks from binary format")
			} as AIOBookmarks
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
		// Parse the wrapper first
		val bookmarks = aioGSONInstance.fromJson(data, AIOBookmarks::class.java)

		// Parse the list properly with type info
		val type = object : TypeToken<ArrayList<BookmarkModel>>() {}.type
		val bookmarksLibraryJSON = JSONObject(data).getString("bookmarkLibrary")
		val parsedList: ArrayList<BookmarkModel> =
			aioGSONInstance.fromJson(bookmarksLibraryJSON, type)

		bookmarks.bookmarkLibrary = parsedList
		return bookmarks
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
	fun searchBookmarksNormal(query: String): List<BookmarkModel> {
		return bookmarkLibrary.filter {
			it.bookmarkName.contains(query, ignoreCase = true) ||
					it.bookmarkUrl.contains(query, ignoreCase = true)
		}
	}

	/**
	 * Fuzzy search bookmarks by name or URL.
	 * - Case-insensitive.
	 * - Returns results ranked by similarity (lower distance = higher rank).
	 * - Matches both bookmark name and URL.
	 *
	 * @param query Search term
	 * @return List of matching bookmarks ranked by relevance
	 */
	fun searchBookmarksFuzzy(query: String): List<BookmarkModel> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty()) return emptyList()

		return bookmarkLibrary
			.map { bookmark ->
				val nameScore = levenshteinDistance(
					normalizedQuery.lowercase(),
					bookmark.bookmarkName.lowercase()
				)
				val urlScore = levenshteinDistance(
					normalizedQuery.lowercase(),
					bookmark.bookmarkUrl.lowercase()
				)
				// Pick the best score between name and URL
				val score = minOf(nameScore, urlScore)
				bookmark to score
			}
			// Sort so best matches come first
			.sortedBy { it.second }
			.map { it.first }
	}

	/**
	 * Computes Levenshtein edit distance between two strings.
	 * Smaller = more similar.
	 */
	private fun levenshteinDistance(a: String, b: String): Int {
		if (a == b) return 0
		if (a.isEmpty()) return b.length
		if (b.isEmpty()) return a.length

		val dp = Array(a.length + 1) { IntArray(b.length + 1) }

		for (i in 0..a.length) dp[i][0] = i
		for (j in 0..b.length) dp[0][j] = j

		for (i in 1..a.length) {
			for (j in 1..b.length) {
				val cost = if (a[i - 1] == b[j - 1]) 0 else 1
				dp[i][j] = minOf(
					dp[i - 1][j] + 1,        // deletion
					dp[i][j - 1] + 1,        // insertion
					dp[i - 1][j - 1] + cost  // substitution
				)
			}
		}
		return dp[a.length][b.length]
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