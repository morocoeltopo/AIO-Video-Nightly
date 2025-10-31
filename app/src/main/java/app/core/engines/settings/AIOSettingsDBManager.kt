package app.core.engines.settings

import app.core.engines.objectbox.ObjectBoxManager
import app.core.engines.settings.AIOSettingsDBManager.createDefaultSettings
import app.core.engines.settings.AIOSettingsDBManager.saveSettingsInDB
import io.objectbox.Box
import io.objectbox.BoxStore
import lib.process.LogHelperUtils

/**
 * Singleton manager for handling AIOSettings persistence using ObjectBox database.
 *
 * This manager provides a centralized interface for all database operations related to
 * application settings, ensuring thread-safe access and proper lifecycle management.
 *
 * Key Responsibilities:
 * - Initializing and managing the ObjectBox database connection
 * - Loading and saving application settings with automatic default value handling
 * - Providing thread-safe singleton access to database operations
 * - Managing database lifecycle (initialization, closure, cleanup)
 * - Handling error scenarios with appropriate fallbacks and logging
 *
 * Architecture:
 * - Uses double-checked locking for thread-safe singleton initialization
 * - Maintains a single settings record with fixed ID (1) for the entire application
 * - Provides seamless migration from legacy file storage to database
 * - Implements proper error handling with comprehensive logging
 *
 * Usage Pattern:
 * 1. Initialize in Application.onCreate() with AIOSettingsDBManager.init(application)
 * 2. Load settings during app startup with loadSettingsFromDB()
 * 3. Use saveSettingsInDB() to persist changes
 * 4. Close database in Application.onTerminate() with closeDB()
 *
 * @see AIOSettings for the entity definition and property documentation
 * @see BoxStore for ObjectBox database operations
 */
object AIOSettingsDBManager {

	/**
	 * Logger instance for tracking operations and debugging issues.
	 * Marked as transient to exclude from any potential serialization.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Fixed identifier for the settings record in the database.
	 *
	 * Since the application maintains only one settings instance throughout its lifecycle,
	 * we use a constant ID to ensure consistent access and updates to the same record.
	 */
	const val APP_SETTINGS_DB_ID = -1L

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
	@JvmStatic
	fun getBoxStore(): BoxStore {
		return ObjectBoxManager.getBoxStore()
	}

	/**
	 * Provides type-safe access to the AIOSettings entity box.
	 *
	 * The Box interface provides CRUD operations specifically for the AIOSettings
	 * entity, including querying, inserting, updating, and deleting records.
	 *
	 * @return Box<AIOSettings> for direct entity operations
	 * @throws IllegalStateException if database is not initialized
	 *
	 * @see Box for available entity operations
	 */
	@JvmStatic
	fun getSettingsBox(): Box<AIOSettings> = getBoxStore().boxFor(AIOSettings::class.java)

	/**
	 * Loads the application settings from the ObjectBox database.
	 *
	 * This method implements a robust loading strategy with multiple fallbacks:
	 * 1. Attempt to load existing settings from database using fixed ID
	 * 2. If no settings exist, create default settings and persist them
	 * 3. If database operations fail, create in-memory default settings
	 * 4. If persistence fails, return basic default settings as final fallback
	 *
	 * The method also triggers legacy file migration through the default settings
	 * creation process, ensuring seamless transition from previous storage systems.
	 *
	 * @return AIOSettings instance loaded from database or created as default
	 *         (never returns null due to comprehensive fallback handling)
	 *
	 * @see createDefaultSettings for default settings creation logic
	 * @see saveSettingsInDB for persistence operations
	 */
	@JvmStatic
	fun loadSettingsFromDB(): AIOSettings {
		return try {
			val settingsBox = getSettingsBox()
			var appSettings = settingsBox.query()
				.equal(AIOSettings_.downloadDataModelId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()

			if (appSettings == null) {
				logger.d("No settings found in database, creating default settings")
				appSettings = createDefaultSettings()
				saveSettingsInDB(appSettings)
			} else {
				logger.d("Settings loaded successfully from ObjectBox database, id: ${appSettings.id}")
			}

			appSettings
		} catch (error: Exception) {
			logger.e("Error loading settings from ObjectBox: ${error.message}", error)
			try {
				// Attempt to create and save default settings as recovery
				createDefaultSettings().also { savedSettings ->
					saveSettingsInDB(savedSettings)
					logger.d("Recovery: Default settings created and saved after load error")
				}
			} catch (saveError: Exception) {
				logger.e("Failed to save default settings after error: ${saveError.message}", saveError)
				// Final fallback: return basic default settings without persistence
				AIOSettings().also {
					logger.d("Using in-memory default settings as final fallback")
				}
			}
		}
	}

	/**
	 * Persists the provided settings to the ObjectBox database.
	 *
	 * This method ensures the settings record maintains the fixed ID for consistent
	 * access patterns. It handles the insert-or-update logic automatically through
	 * ObjectBox's put() operation.
	 *
	 * Error handling strategy:
	 * - Logs errors comprehensively for debugging
	 * - Does not throw exceptions to prevent application crashes
	 * - Allows application to continue with in-memory settings on persistence failures
	 *
	 * @param settings The AIOSettings instance to persist
	 *
	 * @see Box.put for ObjectBox persistence operation
	 */
	@JvmStatic
	fun saveSettingsInDB(settings: AIOSettings) {
		try {
			settings.downloadDataModelId = APP_SETTINGS_DB_ID
			getSettingsBox().put(settings)
			logger.d("Settings saved successfully to ObjectBox database id:${settings.id}")
		} catch (error: Exception) {
			logger.e("Error saving settings to ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Creates a new AIOSettings instance with default values and legacy data migration.
	 *
	 * This method not only creates default settings but also triggers the migration
	 * of any existing settings from legacy file storage systems (JSON/binary files).
	 * The apply function calls readObjectFromStorage which handles the legacy file
	 * parsing and data transfer automatically.
	 *
	 * @return New AIOSettings instance with defaults and migrated legacy data
	 *
	 * @see AIOSettings.readObjectFromStorage for legacy data migration
	 */
	@JvmStatic
	private fun createDefaultSettings(): AIOSettings {
		return AIOSettings().apply(AIOSettings::readObjectFromStorage).also {
			logger.d("Default settings created with legacy data migration")
		}
	}

	/**
	 * Checks whether settings exist in the database.
	 *
	 * This method is useful for:
	 * - Determining if this is the first app launch
	 * - Validating database state during startup
	 * - Conditional migration logic
	 *
	 * @return true if settings record exists in database, false otherwise
	 *         (returns false on database errors as safe default)
	 */
	@JvmStatic
	fun hasSettingsInDB(): Boolean {
		return try {
			val appSettings = getSettingsBox().query()
				.equal(AIOSettings_.downloadDataModelId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()

			appSettings != null
		} catch (error: Exception) {
			logger.e("Error checking settings existence: ${error.message}", error)
			false
		}
	}

	/**
	 * Removes all settings from the database.
	 *
	 * Use cases for this method:
	 * - Application reset functionality
	 * - Debugging and testing scenarios
	 * - User account logout and data clearance
	 *
	 * Warning: This operation is irreversible and will remove all application
	 * settings, requiring the user to reconfigure the application.
	 */
	@JvmStatic
	fun clearSettingsFromDB() {
		try {
			val appSettings = getSettingsBox().query()
				.equal(AIOSettings_.downloadDataModelId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()
			if (appSettings == null) return
			getSettingsBox().remove(appSettings.id)
			logger.d("Settings cleared from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error clearing settings: ${error.message}", error)
		}
	}
}