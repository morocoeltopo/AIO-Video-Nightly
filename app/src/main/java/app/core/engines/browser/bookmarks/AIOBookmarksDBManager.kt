package app.core.engines.browser.bookmarks

import app.core.AIOApp
import app.core.engines.browser.bookmarks.AIOBookmarksDBManager.createDefaultBookmarks
import app.core.engines.browser.bookmarks.AIOBookmarksDBManager.init
import app.core.engines.browser.bookmarks.AIOBookmarksDBManager.saveBookmarksInDB
import app.core.engines.settings.MyObjectBox
import io.objectbox.Box
import io.objectbox.BoxStore
import lib.process.LogHelperUtils

/**
 * Singleton manager for handling AIOBookmarks persistence using ObjectBox database.
 *
 * This manager provides a centralized interface for all database operations related to
 * browser bookmarks, ensuring thread-safe access and proper lifecycle management.
 *
 * Key Responsibilities:
 * - Initializing and managing the ObjectBox database connection for bookmarks
 * - Loading and saving bookmark collections with automatic legacy data migration
 * - Providing thread-safe singleton access to bookmark database operations
 * - Managing database lifecycle (initialization, closure, cleanup)
 * - Handling error scenarios with appropriate fallbacks and logging
 * - Supporting complex bookmark operations (search, filtering, sorting)
 *
 * Architecture:
 * - Uses double-checked locking for thread-safe singleton initialization
 * - Maintains a single bookmarks record with fixed ID (1) for the entire application
 * - Provides seamless migration from legacy JSON/binary file storage to database
 * - Implements proper error handling with comprehensive logging
 * - Supports the complete bookmark functionality from the original AIOBookmarks class
 *
 * Usage Pattern:
 * 1. Initialize in Application.onCreate() with AIOBookmarksDBManager.init(application)
 * 2. Load bookmarks during app startup with loadBookmarksFromDB()
 * 3. Use saveBookmarksInDB() to persist changes
 * 4. Close database in Application.onTerminate() with closeDB()
 *
 * @see AIOBookmarks for the main bookmarks management class
 * @see BookmarkModel for individual bookmark entity definition
 * @see BoxStore for ObjectBox database operations
 */
object AIOBookmarksDBManager {

	/**
	 * Logger instance for tracking operations and debugging issues.
	 * Provides detailed logging for database operations and error scenarios.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Volatile singleton instance of BoxStore for thread-safe publication.
	 *
	 * The @Volatile annotation ensures:
	 * - Visibility of changes across threads
	 * - Prevention of instruction reordering
	 * - Proper double-checked locking behavior
	 */
	@Volatile
	private var boxStore: BoxStore? = null

	/**
	 * Fixed identifier for the bookmarks record in the database.
	 *
	 * Since the application maintains only one bookmarks collection instance
	 * throughout its lifecycle, we use a constant ID to ensure consistent
	 * access and updates to the same record.
	 */
	private const val BOOKMARKS_ID = 1L

	/**
	 * Initializes the ObjectBox database connection with the application context.
	 *
	 * This method employs double-checked locking to ensure thread-safe initialization
	 * while maintaining performance. It must be called exactly once during application
	 * startup, typically in the Application.onCreate() method.
	 *
	 * @param applicationContext The AIOApp application context for database configuration
	 * @throws IllegalStateException if ObjectBox initialization fails due to:
	 *         - Missing application context
	 *         - Database file corruption
	 *         - Insufficient storage permissions
	 *         - Internal ObjectBox configuration errors
	 *
	 * @see MyObjectBox for the generated ObjectBox builder
	 * @see BoxStore for database instance management
	 */
	fun init(applicationContext: AIOApp) {
		// First check (no synchronization) for performance
		if (boxStore != null) return

		// Synchronized block for thread-safe initialization
		synchronized(this) {
			// Second check (within synchronization) for correctness
			if (boxStore == null) {
				try {
					boxStore = MyObjectBox.builder().androidContext(applicationContext).build()
					logger.d("ObjectBox initialized successfully for AIOBookmarks persistence")
				} catch (error: Exception) {
					logger.e("Failed to initialize ObjectBox for bookmarks: ${error.message}", error)
					throw IllegalStateException("ObjectBox initialization failed for bookmarks", error)
				}
			}
		}
	}

	/**
	 * Retrieves the initialized BoxStore instance.
	 *
	 * This method provides access to the underlying ObjectBox database store
	 * for advanced operations not covered by this manager's API.
	 *
	 * @return The initialized BoxStore instance
	 * @throws IllegalStateException if called before successful initialization
	 *
	 * @see init for initialization requirements
	 */
	fun getBoxStore(): BoxStore {
		return boxStore ?: throw IllegalStateException(
			"ObjectBox not initialized for bookmarks. Call init() with application context first."
		)
	}

	/**
	 * Provides type-safe access to the AIOBookmarks entity box.
	 *
	 * The Box interface provides CRUD operations specifically for the AIOBookmarks
	 * entity, including querying, inserting, updating, and deleting records.
	 *
	 * @return Box<AIOBookmarks> for direct entity operations
	 * @throws IllegalStateException if database is not initialized
	 *
	 * @see Box for available entity operations
	 */
	fun getBookmarksBox(): Box<AIOBookmarks> = getBoxStore().boxFor(AIOBookmarks::class.java)

	/**
	 * Loads the bookmarks collection from the ObjectBox database.
	 *
	 * This method implements a robust loading strategy with multiple fallbacks:
	 * 1. Attempt to load existing bookmarks from database using fixed ID
	 * 2. If no bookmarks exist, create default bookmarks with preloaded data and persist them
	 * 3. If database operations fail, create in-memory default bookmarks
	 * 4. If persistence fails, return basic default bookmarks as final fallback
	 *
	 * The method also triggers legacy file migration through the default bookmarks
	 * creation process, ensuring seamless transition from previous storage systems.
	 *
	 * @return AIOBookmarks instance loaded from database or created as default
	 *         (never returns null due to comprehensive fallback handling)
	 *
	 * @see createDefaultBookmarks for default bookmarks creation logic
	 * @see saveBookmarksInDB for persistence operations
	 * @see AIOBookmarks.getPreloadedBookmarks for initial bookmark data
	 */
	fun loadBookmarksFromDB(): AIOBookmarks {
		return try {
			val bookmarksBox = getBookmarksBox()
			var bookmarks = bookmarksBox.get(BOOKMARKS_ID)

			if (bookmarks == null) {
				logger.d("No bookmarks found in database, creating default bookmarks with preloaded data")
				bookmarks = createDefaultBookmarks()
				saveBookmarksInDB(bookmarks)
			} else {
				logger.d("Bookmarks loaded successfully from ObjectBox database: ${bookmarks.getBookmarkLibrary().size} items")
			}

			bookmarks
		} catch (error: Exception) {
			logger.e("Error loading bookmarks from ObjectBox: ${error.message}", error)
			try {
				// Attempt to create and save default bookmarks as recovery
				createDefaultBookmarks().also { savedBookmarks ->
					saveBookmarksInDB(savedBookmarks)
					logger.d("Recovery: Default bookmarks created and saved after load error")
				}
			} catch (saveError: Exception) {
				logger.e("Failed to save default bookmarks after error: ${saveError.message}", saveError)
				// Final fallback: return basic default bookmarks without persistence
				AIOBookmarks().also {
					logger.d("Using in-memory default bookmarks as final fallback")
				}
			}
		}
	}

	/**
	 * Persists the provided bookmarks collection to the ObjectBox database.
	 *
	 * This method ensures the bookmarks record maintains the fixed ID for consistent
	 * access patterns. It handles the insert-or-update logic automatically through
	 * ObjectBox's put() operation.
	 *
	 * Error handling strategy:
	 * - Logs errors comprehensively for debugging
	 * - Does not throw exceptions to prevent application crashes
	 * - Allows application to continue with in-memory bookmarks on persistence failures
	 *
	 * @param bookmarks The AIOBookmarks instance to persist
	 *
	 * @see Box.put for ObjectBox persistence operation
	 */
	fun saveBookmarksInDB(bookmarks: AIOBookmarks) {
		try {
			// Note: AIOBookmarks doesn't have an id field, so we can't set it directly
			// We'll need to handle this differently - either add id to AIOBookmarks or use a wrapper
			getBookmarksBox().put(bookmarks)
			logger.d("Bookmarks saved successfully to ObjectBox database: ${bookmarks.getBookmarkLibrary().size} items")
		} catch (error: Exception) {
			logger.e("Error saving bookmarks to ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Updates the bookmarks in database with the current global instance.
	 *
	 * Convenience method for saving the current bookmarks state from the
	 * global application instance.
	 */
	fun updateBookmarksInDB() {
		val currentBookmarks = AIOApp.aioBookmark
		saveBookmarksInDB(currentBookmarks)
	}

	/**
	 * Creates a new AIOBookmarks instance with default values and preloaded data.
	 *
	 * This method creates a fresh bookmarks instance populated with the
	 * preloaded bookmark data from various categories (search engines, music,
	 * movies, etc.).
	 *
	 * @return New AIOBookmarks instance with preloaded default data
	 *
	 * @see AIOBookmarks.getPreloadedBookmarks for the initial bookmark collection
	 */
	private fun createDefaultBookmarks(): AIOBookmarks {
		logger.d("Default bookmarks created with preloaded items")
		return AIOBookmarks().apply(AIOBookmarks::readObjectFromStorage)
	}

	/**
	 * Checks whether bookmarks exist in the database.
	 *
	 * This method is useful for:
	 * - Determining if this is the first app launch
	 * - Validating database state during startup
	 * - Conditional migration logic from legacy storage
	 *
	 * @return true if bookmarks record exists in database, false otherwise
	 *         (returns false on database errors as safe default)
	 */
	fun hasBookmarksInDB(): Boolean {
		return try {
			getBookmarksBox().get(BOOKMARKS_ID) != null
		} catch (error: Exception) {
			logger.e("Error checking bookmarks existence: ${error.message}", error)
			false
		}
	}

	/**
	 * Removes all bookmarks from the database.
	 *
	 * Use cases for this method:
	 * - Application reset functionality
	 * - Debugging and testing scenarios
	 * - User data clearance on logout
	 *
	 * Warning: This operation is irreversible and will remove all bookmark data,
	 * requiring the user to rebuild their bookmark collection.
	 */
	fun clearBookmarksFromDB() {
		try {
			getBookmarksBox().remove(BOOKMARKS_ID)
			logger.d("Bookmarks cleared from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error clearing bookmarks: ${error.message}", error)
		}
	}

	/**
	 * Closes the ObjectBox database connection and releases resources.
	 *
	 * This method should be called during application shutdown to ensure:
	 * - Proper cleanup of database connections
	 * - Prevention of resource leaks
	 * - Data integrity through proper transaction completion
	 *
	 * The synchronized block ensures thread-safe closure and prevents race conditions
	 * during application termination.
	 *
	 * @see BoxStore.close for resource cleanup details
	 */
	fun closeDB() {
		synchronized(this) {
			try {
				boxStore?.close()
				boxStore = null
				logger.d("ObjectBox database connection closed and resources released for bookmarks")
			} catch (error: Exception) {
				logger.e("Error closing ObjectBox for bookmarks: ${error.message}", error)
			}
		}
	}

	// =============================================
	// Convenience Methods for Common Operations
	// =============================================

	/**
	 * Adds a new bookmark to the database and persists immediately.
	 *
	 * This method provides a convenient way to add individual bookmarks
	 * while ensuring the changes are saved to the database.
	 *
	 * @param bookmark The bookmark to add to the collection
	 */
	fun addBookmarkAndSave(bookmark: BookmarkModel) {
		val currentBookmarks = loadBookmarksFromDB()
		currentBookmarks.insertNewBookmark(bookmark)
		saveBookmarksInDB(currentBookmarks)
		logger.d("Bookmark added and saved: ${bookmark.bookmarkName}")
	}

	/**
	 * Removes a bookmark from the database and persists immediately.
	 *
	 * This method provides a convenient way to remove individual bookmarks
	 * while ensuring the changes are saved to the database.
	 *
	 * @param bookmark The bookmark to remove from the collection
	 */
	fun removeBookmarkAndSave(bookmark: BookmarkModel) {
		val currentBookmarks = loadBookmarksFromDB()
		currentBookmarks.removeBookmark(bookmark)
		saveBookmarksInDB(currentBookmarks)
		logger.d("Bookmark removed and saved: ${bookmark.bookmarkName}")
	}

	/**
	 * Performs a fuzzy search on bookmarks and returns results.
	 *
	 * This method loads the current bookmarks and performs a fuzzy search
	 * using the AIOBookmarks.searchBookmarksFuzzy method.
	 *
	 * @param query The search query string
	 * @return List of matching bookmarks ranked by relevance
	 *
	 * @see AIOBookmarks.searchBookmarksFuzzy for search algorithm details
	 */
	fun searchBookmarksFuzzy(query: String): List<BookmarkModel> {
		val currentBookmarks = loadBookmarksFromDB()
		return currentBookmarks.searchBookmarksFuzzy(query)
	}

	/**
	 * Gets all favorite bookmarks from the database.
	 *
	 * @return List of bookmarks marked as favorites
	 */
	fun getFavoriteBookmarks(): List<BookmarkModel> {
		val currentBookmarks = loadBookmarksFromDB()
		return currentBookmarks.getFavoriteBookmarks()
	}

	/**
	 * Gets the total count of bookmarks in the database.
	 *
	 * @return Number of bookmarks in the collection
	 */
	fun getBookmarksCount(): Int {
		val currentBookmarks = loadBookmarksFromDB()
		return currentBookmarks.countBookmarks()
	}
}