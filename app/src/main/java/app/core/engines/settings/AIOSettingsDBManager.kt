package app.core.engines.settings

import app.core.AIOApp
import io.objectbox.Box
import io.objectbox.BoxStore
import lib.process.LogHelperUtils

object AIOSettingsDBManager {

	private val logger = LogHelperUtils.from(javaClass)
	@Volatile private var boxStore: BoxStore? = null

	private const val SETTINGS_ID = 1L

	fun init(applicationContext: AIOApp) {
		if (boxStore != null) return // Already initialized

		synchronized(this) {
			if (boxStore == null) {
				try {
					boxStore = MyObjectBox.builder()
						.androidContext(applicationContext)
						.build()
					logger.d("ObjectBox initialized successfully")
				} catch (error: Exception) {
					logger.e("Failed to initialize ObjectBox: ${error.message}", error)
					throw IllegalStateException("ObjectBox initialization failed", error)
				}
			}
		}
	}

	fun getBoxStore(): BoxStore {
		return boxStore ?: throw IllegalStateException("ObjectBox not initialized. Call init() first.")
	}

	fun getSettingsBox(): Box<AIOSettings> = getBoxStore().boxFor(AIOSettings::class.java)

	fun loadSettingsFromDB(): AIOSettings {
		return try {
			val settingsBox = getSettingsBox()
			var settings = settingsBox.get(SETTINGS_ID)

			if (settings == null) {
				logger.d("No settings found in database, creating default settings")
				settings = createDefaultSettings()
				saveSettingsInDB(settings)
			} else {
				logger.d("Settings loaded successfully from ObjectBox")
			}

			settings
		} catch (error: Exception) {
			logger.e("Error loading settings from ObjectBox: ${error.message}", error)
			try {
				createDefaultSettings().also { saveSettingsInDB(it) }
			} catch (saveError: Exception) {
				logger.e("Failed to save default settings after error: ${saveError.message}", saveError)
				AIOSettings() // fallback
			}
		}
	}

	fun saveSettingsInDB(settings: AIOSettings) {
		try {
			settings.id = SETTINGS_ID
			getSettingsBox().put(settings)
			logger.d("Settings saved successfully to ObjectBox")
		} catch (error: Exception) {
			logger.e("Error saving settings to ObjectBox: ${error.message}", error)
		}
	}

	private fun createDefaultSettings(): AIOSettings {
		return  AIOSettings().apply(AIOSettings::readObjectFromStorage)
	}

	fun hasSettingsInDB(): Boolean {
		return try {
			getSettingsBox().get(SETTINGS_ID) != null
		} catch (error: Exception) {
			logger.e("Error checking settings existence: ${error.message}", error)
			false
		}
	}

	fun clearSettingsFromDB() {
		try {
			getSettingsBox().remove(SETTINGS_ID)
			logger.d("Settings cleared from ObjectBox")
		} catch (error: Exception) {
			logger.e("Error clearing settings: ${error.message}", error)
		}
	}

	fun closeDB() {
		synchronized(this) {
			try {
				boxStore?.close()
				boxStore = null
				logger.d("ObjectBox connection closed")
			} catch (error: Exception) {
				logger.e("Error closing ObjectBox: ${error.message}", error)
			}
		}
	}
}