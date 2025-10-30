package app.core.engines.browser.bookmarks

import app.core.engines.objectbox.ObjectBoxManager
import io.objectbox.Box
import lib.process.LogHelperUtils

/**
 * Singleton manager for handling BookmarkModel entities in ObjectBox database.
 *
 * This manager stores individual BookmarkModel entities directly in ObjectBox
 * instead of trying to store the entire AIOBookmarks class with its ArrayList.
 *
 * Architecture:
 * - Each BookmarkModel is stored as a separate entity in ObjectBox
 * - The AIOBookmarks class acts as a service layer for operations
 * - All BookmarkModel entities are linked by application context (no parent-child relation needed)
 *
 * Benefits:
 * - Individual bookmark operations (add, remove, update)
 * - Efficient querying and filtering
 * - ObjectBox relations and indexing work properly
 * - Better performance for large bookmark collections
 */
object AIOBookmarksDBManager {

	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Gets the BookmarkModel box from the shared BoxStore.
	 */
	@JvmStatic
	fun getBookmarkBox(): Box<BookmarkModel> {
		return ObjectBoxManager.getBoxStore().boxFor(BookmarkModel::class.java)
	}

	@JvmStatic
	fun loadAIOBookmarksFromDB(): AIOBookmarks {
		val aioBookmarks = AIOBookmarks()
		val bookmarkModels = loadAllBookmarkModelsFromDB()
		if (bookmarkModels.isNotEmpty()) aioBookmarks.bookmarkLibrary = bookmarkModels
		else aioBookmarks.readObjectFromStorage()
		return aioBookmarks
	}

	/**
	 * Loads all bookmarks from the ObjectBox database.
	 *
	 * @return ArrayList<BookmarkModel> containing all bookmarks in the database
	 */
	@JvmStatic
	private fun loadAllBookmarkModelsFromDB(): ArrayList<BookmarkModel> {
		return try {
			val bookmarkBox = getBookmarkBox()
			val allBookmarks = bookmarkBox.all
			ArrayList(allBookmarks).also {
				logger.d("Loaded ${it.size} bookmarks from ObjectBox database")
			}
		} catch (error: Exception) {
			logger.e("Error loading bookmarks from ObjectBox: ${error.message}", error)
			ArrayList()
		}
	}

	/**
	 * Saves multiple bookmarks to the ObjectBox database in a single transaction.
	 *
	 * @param bookmarks List of BookmarkModel entities to save
	 */
	@JvmStatic
	fun updateAllBookmarksInDB(bookmarks: List<BookmarkModel>) {
		try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.removeAll()
			bookmarkBox.put(bookmarks)
			logger.d("Saved ${bookmarks.size} bookmarks to ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error saving bookmarks to ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Deletes a bookmark from the ObjectBox database.
	 *
	 * @param bookmark The BookmarkModel to delete
	 * @return true if deletion was successful, false otherwise
	 */
	@JvmStatic
	fun deleteBookmarkFromDB(bookmark: BookmarkModel): Boolean {
		return try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.remove(bookmark)
			logger.d("Bookmark deleted from ObjectBox: ${bookmark.bookmarkName}")
			true
		} catch (error: Exception) {
			logger.e("Error deleting bookmark from ObjectBox: ${error.message}", error)
			false
		}
	}

	/**
	 * Deletes all bookmarks from the ObjectBox database.
	 */
	@JvmStatic
	fun deleteAllBookmarksFromDB() {
		try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.removeAll()
			logger.d("All bookmarks deleted from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error deleting all bookmarks from ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Gets the total count of bookmarks in the database.
	 *
	 * @return Number of bookmarks in the database
	 */
	@JvmStatic
	fun getBookmarksCountsInDB(): Long {
		return try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.count()
		} catch (error: Exception) {
			logger.e("Error counting bookmarks in ObjectBox: ${error.message}", error)
			0
		}
	}
}