package app.core

import android.app.Activity
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromFile
import androidx.lifecycle.LifecycleObserver
import app.core.bases.BaseActivity
import app.core.bases.language.LanguageAwareApplication
import app.core.engines.backend.AIOBackend
import app.core.engines.backend.AppUsageTimer
import app.core.engines.browser.bookmarks.AIOBookmarks
import app.core.engines.browser.bookmarks.AIOBookmarksDBManager
import app.core.engines.browser.history.AIOHistory
import app.core.engines.browser.history.AIOHistoryDBManager
import app.core.engines.caches.AIOAdBlocker
import app.core.engines.caches.AIOFavicons
import app.core.engines.caches.AIORawFiles
import app.core.engines.downloader.DownloadModelBinaryMerger
import app.core.engines.downloader.DownloadModelsDBManager
import app.core.engines.downloader.DownloadSystem
import app.core.engines.objectbox.ObjectBoxManager
import app.core.engines.objectbox.ObjectBoxManager.initializeObjectBoxDB
import app.core.engines.settings.AIOSettings
import app.core.engines.settings.AIOSettingsDBManager
import app.core.engines.video_parser.parsers.YoutubeVidParser
import com.anggrayudi.storage.file.DocumentFileCompat.fromPublicFolder
import com.anggrayudi.storage.file.PublicDirectory.DOWNLOADS
import com.dslplatform.json.DslJson
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness.LENIENT
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import lib.networks.HttpClientProvider
import lib.networks.URLUtilityKT.isInternetConnected
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground

/**
 * Main Application class for the AIO (All-In-One) application.
 *
 * This class serves as the central hub for the entire application, responsible for:
 * - Managing the global application context and lifecycle
 * - Initializing and coordinating all core systems and engines
 * - Providing centralized access to global singletons and managers
 * - Implementing priority-based startup sequencing for optimal performance
 * - Handling application-level configuration and feature flags
 *
 * ## Architecture Overview:
 * - **Singleton Pattern**: Provides global access to application components
 * - **Layered Initialization**: Priority-based startup with critical path optimization
 * - **Dependency Centralization**: Single source of truth for all core engines
 * - **Lifecycle Awareness**: Proper resource management and cleanup
 * - **Error Resilience**: Graceful degradation with comprehensive fallback strategies
 *
 * ## Key Features:
 * - Multi-stage startup process with priority-based task execution
 * - Centralized access to settings, bookmarks, history, and download systems
 * - Integrated ObjectBox database management for local persistence
 * - YouTube-DL and FFmpeg integration for media processing
 * - Comprehensive logging and debugging capabilities
 * - Memory-efficient resource management with weak references
 *
 * ## Startup Sequence:
 * 1. **Critical Tasks** (Blocking): Settings, HTTP client, core resources
 * 2. **High Priority Tasks** (Concurrent): Bookmarks, history, lifecycle management
 * 3. **Background Tasks** (IO-bound): YouTube-DL initialization, usage tracking
 *
 * @see LanguageAwareApplication for internationalization support
 * @see LifecycleObserver for Android lifecycle integration
 * @see StartupManager for the priority-based initialization system
 */
class AIOApp : LanguageAwareApplication(), LifecycleObserver {

	/**
	 * Logger instance for comprehensive application lifecycle tracking.
	 * Provides detailed insights into startup sequencing, system initialization,
	 * and operational metrics throughout the application lifecycle.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	companion object {
		/**
		 * Global application instance for context access throughout the app.
		 * Uses volatile publication for thread-safe initialization.
		 */
		@Volatile
		lateinit var INSTANCE: AIOApp

		// =============================================
		// APPLICATION CONFIGURATION AND FEATURE FLAGS
		// =============================================

		/**
		 * Debug mode flag controlling development features and verbose logging.
		 * When enabled, provides enhanced debugging capabilities and detailed logs.
		 */
		const val IS_DEBUG_MODE_ON = true

		/**
		 * Cloud backup functionality flag for user data synchronization.
		 * When enabled, supports automatic backup and restore of user preferences and data.
		 */
		const val IS_CLOUD_BACKUP_ENABLED = false

		/**
		 * Ultimate version unlock flag for premium feature access.
		 * Controls availability of advanced features and capabilities.
		 */
		const val IS_ULTIMATE_VERSION_UNLOCKED = true

		/**
		 * Premium user status flag for subscription-based features.
		 * Determines access to exclusive content and enhanced functionality.
		 */
		const val IS_PREMIUM_USER = true

		// =============================================
		// STORAGE AND FILE SYSTEM PATHS
		// =============================================

		/**
		 * Internal application data directory (private storage).
		 * Located at: `/data/data/<package_name>/files/`
		 * Accessible only by the application itself.
		 */
		val internalDataFolder: DocumentFile by lazy { fromFile(INSTANCE.filesDir) }

		/**
		 * External application-specific directory.
		 * Located at: `/storage/emulated/0/Android/data/<package_name>/files/`
		 * May be accessible by other applications with proper permissions.
		 */
		val externalDataFolder: DocumentFile? by lazy {
			INSTANCE.getExternalFilesDir(null)?.let { fromFile(it) }
		}

		// =============================================
		// CORE APPLICATION MODULES AND ENGINES
		// =============================================

		/**
		 * Global application settings and user preferences manager.
		 * Handles persistence and retrieval of all user-configurable options.
		 */
		lateinit var aioSettings: AIOSettings

		/**
		 * Browser bookmarks management system.
		 * Provides CRUD operations for user bookmarks with persistent storage.
		 */
		lateinit var aioBookmark: AIOBookmarks

		/**
		 * Browsing history tracking and management system.
		 * Maintains chronological record of user browsing sessions.
		 */
		lateinit var aioHistory: AIOHistory

		/**
		 * Raw file resources and asset management system.
		 * Handles loading and caching of application resources like animations.
		 */
		lateinit var aioRawFiles: AIORawFiles

		/**
		 * YouTube-DL instance for video extraction and processing.
		 * Provides capabilities for downloading and processing online video content.
		 */
		lateinit var ytdlpInstance: YoutubeDL

		// =============================================
		// LAZILY INITIALIZED MANAGERS AND UTILITIES
		// =============================================

		/**
		 * Website favicon (favorite icon) management and caching system.
		 * Handles fetching, caching, and retrieval of website icons.
		 */
		val aioFavicons: AIOFavicons by lazy { AIOFavicons() }

		/**
		 * Advertisement blocking and content filtering engine.
		 * Provides real-time ad blocking capabilities during browsing sessions.
		 */
		val aioAdblocker: AIOAdBlocker by lazy { AIOAdBlocker() }

		/**
		 * Backend service integration and API management system.
		 * Handles all network communications and remote service integrations.
		 */
		val aioBackend: AIOBackend by lazy { AIOBackend() }

		/**
		 * Internationalization and localization management system.
		 * Provides multi-language support and text resource management.
		 */
		val aioLanguage: AIOLanguage by lazy { AIOLanguage() }

		/**
		 * Google GSON instance configured for lenient JSON parsing.
		 * Handles serialization and deserialization of JSON data with flexible parsing.
		 */
		val aioGSONInstance: Gson by lazy { GsonBuilder().setStrictness(LENIENT).create() }

		/**
		 * High-performance DSL-JSON instance for efficient JSON processing.
		 * Provides optimized JSON serialization/deserialization for performance-critical operations.
		 */
		val aioDSLJsonInstance = DslJson<Any>()

		// =============================================
		// DOWNLOAD AND MEDIA PROCESSING SYSTEMS
		// =============================================

		/**
		 * Comprehensive download management and execution system.
		 * Handles all file download operations with progress tracking and resume capabilities.
		 */
		val downloadSystem: DownloadSystem by lazy { DownloadSystem() }

		/**
		 * Download model consolidation and data merging utility.
		 * Provides capabilities for combining and organizing download metadata.
		 */
		val downloadModelBinaryMerger: DownloadModelBinaryMerger by lazy { DownloadModelBinaryMerger() }

		/**
		 * YouTube-specific video parsing and metadata extraction engine.
		 * Specialized in processing YouTube video URLs and extracting relevant information.
		 */
		val youtubeVidParser: YoutubeVidParser by lazy { YoutubeVidParser() }

		// =============================================
		// TIMING AND USAGE TRACKING SYSTEMS
		// =============================================

		/**
		 * Application-level timer for scheduling periodic tasks.
		 * Configurable interval (1 hour) and initial delay (200ms) for background operations.
		 */
		val aioTimer: AIOTimer by lazy { AIOTimer(3600000, 200).apply { start() } }

		/**
		 * User engagement and application usage tracking system.
		 * Monitors and records user interaction patterns for analytics and optimization.
		 */
		val aioUsageTimer: AppUsageTimer by lazy { AppUsageTimer() }
	}

	/**
	 * Startup manager responsible for orchestrating the priority-based initialization sequence.
	 * Ensures optimal application startup performance through categorized task execution.
	 */
	private val startupManager = StartupManager()

	/**
	 * Application creation entry point - initializes all core systems and engines.
	 *
	 * This method implements a sophisticated multi-stage startup process:
	 *
	 * ## Startup Sequence:
	 * 1. **Foundation Setup**: Application instance registration and database initialization
	 * 2. **Critical Path**: Blocking initialization of essential systems (settings, HTTP client)
	 * 3. **High Priority**: Concurrent initialization of user-facing components (bookmarks, history)
	 * 4. **Background Tasks**: Non-blocking initialization of secondary systems (YouTube-DL, tracking)
	 *
	 * ## Error Handling Strategy:
	 * - Comprehensive try-catch blocks around each initialization step
	 * - Graceful fallback to legacy storage when database operations fail
	 * - Detailed logging of both successes and failures for debugging
	 * - Continued operation with degraded functionality when non-critical systems fail
	 *
	 * @see StartupManager for the task categorization and execution logic
	 * @see ObjectBoxManager for database initialization
	 */
	override fun onCreate() {
		super.onCreate()
		logger.d("AIOApp.onCreate() - Application startup sequence initiated")

		// Step 1: Foundation Setup
		INSTANCE = this
		initializeObjectBoxDB(INSTANCE)

		// Step 2-4: Configure and execute multi-stage startup sequence
		startupManager.apply {
			// CRITICAL TASKS: Essential systems that must complete immediately
			addCriticalTask {
				logger.d("[Startup] Phase 1: Initializing critical path systems...")

				// HTTP Client Preloading
				logger.d("[Startup] Preloading OkHttp client builder...")
				HttpClientProvider.initialize()
				logger.d("[Startup] OkHttp client builder preloaded successfully")

				// Application Settings
				logger.d("[Startup] Initializing application settings and preferences...")
				try {
					aioSettings = AIOSettingsDBManager.loadSettingsFromDB()
					logger.d("[Startup] Settings database initialized and loaded successfully")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to initialize settings database: ${error.message}", error)
					// Fallback to legacy file-based storage
					aioSettings = AIOSettings().apply(AIOSettings::readObjectFromStorage)
					logger.d("[Startup] Using legacy settings storage as fallback")
				}

				DownloadModelsDBManager.getAllDownloadsWithRelationsOptimized().let {
					downloadSystem.allDownloadModels.addAll(it)
					downloadSystem.initializeSystem()
				}

				// Resource Preloading
				logger.d("[Startup] Preloading application resources and animations...")
				try {
					aioRawFiles = AIORawFiles().apply(AIORawFiles::preloadLottieAnimation)
					logger.d("[Startup] Lottie animations preloaded successfully")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to preload Lottie animations: ${error.message}", error)
				}

				// Video Parser Initialization
				logger.d("[Startup] Initializing YouTube video parsing system...")
				try {
					youtubeVidParser.initSystem()
					logger.d("[Startup] YouTube video parser initialized successfully")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to initialize YouTube parser: ${error.message}", error)
				}

				logger.d("[Startup] Critical path systems initialization completed")
			}

			// HIGH PRIORITY TASKS: User-facing components that can load concurrently
			addHighPriorityTask {
				logger.d("[Startup] Phase 2: Loading user data and UI components...")

				// Bookmarks System
				try {
					logger.d("[Startup] Initializing bookmarks database manager...")
					aioBookmark = AIOBookmarksDBManager.loadAIOBookmarksFromDB()
					logger.d("[Startup] Bookmarks database initialized and loaded successfully")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to initialize bookmarks database: ${error.message}", error)
					// Fallback to legacy storage
					aioBookmark = AIOBookmarks().apply(AIOBookmarks::readObjectFromStorage)
					logger.d("[Startup] Using legacy bookmarks storage as fallback")
				}

				// Browsing History
				try {
					logger.d("[Startup] Loading browsing history from storage...")
					aioHistory = AIOHistoryDBManager.loadAIOHistoryFromDB()
					logger.d("[Startup] Browsing history loaded successfully")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to load browsing history: ${error.message}", error)
					// Fallback to legacy storage
					aioHistory = AIOHistory().apply(AIOHistory::readObjectFromStorage)
					logger.d("[Startup] Using legacy history storage as fallback")
				}

				// Activity Lifecycle Management
				try {
					logger.d("[Startup] Configuring activity lifecycle bindings...")
					manageActivityLifeCycle()
					logger.d("[Startup] Activity lifecycle management initialized")
				} catch (error: Exception) {
					logger.e("[Startup] Failed to manage activity lifecycle: ${error.message}", error)
				}

				logger.d("[Startup] User data and UI components loading completed")
			}

			// BACKGROUND TASKS: Non-essential systems that can initialize asynchronously
			addBackgroundTask {
				logger.d("[Startup] Phase 3: Initializing background services...")
				logger.d("[Startup] Starting YouTube-DL initialization...")
				initializeYtDLP()
				logger.d("[Startup] YouTube-DL initialization completed")

				logger.d("[Startup] Starting application usage tracking...")
				startAppUISessionTracking()
				logger.d("[Startup] Background services initialization completed")
			}
		}

		// Execute the configured startup sequence
		logger.d("[Startup] Executing critical path tasks (blocking)...")
		startupManager.executeCriticalTasks()

		logger.d("[Startup] Starting concurrent high-priority and background tasks...")
		executeInBackground(codeBlock = {
			startupManager.executeHighPriorityTasks()
			startupManager.executeBackgroundTasks()
			logger.d("[Startup] All startup tasks completed successfully")
		})

		logger.d("AIOApp.onCreate() - Application startup sequence completed")
	}

	/**
	 * Registers application-wide activity lifecycle callbacks for proper resource management.
	 *
	 * This method ensures that activity references are properly cleaned up to prevent memory leaks.
	 * It uses weak references and automatic cleanup when activities are destroyed.
	 *
	 * @see AIOLifeCycle for the custom lifecycle callback interface
	 * @see BaseActivity.clearWeakActivityReference for the cleanup implementation
	 */
	private fun manageActivityLifeCycle() {
		logger.d("Registering global activity lifecycle callbacks")
		executeOnMainThread {
			registerActivityLifecycleCallbacks(object : AIOLifeCycle {
				/**
				 * Called when any activity is destroyed, ensuring proper cleanup of references.
				 *
				 * @param activity The activity instance that is being destroyed
				 */
				override fun onActivityDestroyed(activity: Activity) {
					logger.d("Activity lifecycle: ${activity.javaClass.simpleName} destroyed")
					if (activity is BaseActivity) {
						activity.clearWeakActivityReference()
					}
				}
			})
		}
	}

	/**
	 * Starts the application usage tracking and analytics system.
	 *
	 * This method initiates the monitoring of user engagement patterns, session duration,
	 * and feature usage for analytical purposes and user experience optimization.
	 *
	 * @see AppUsageTimer.startTracking for the actual tracking implementation
	 */
	private fun startAppUISessionTracking() {
		logger.d("Starting application usage tracking system")
		executeOnMainThread { aioUsageTimer.startTracking() }
	}

	/**
	 * Initializes the YouTube-DL and FFmpeg libraries for video processing capabilities.
	 *
	 * This method performs the following operations:
	 * 1. Initializes YouTube-DL library with the application context
	 * 2. Initializes FFmpeg library for media processing
	 * 3. Optionally updates YouTube-DL binaries if internet connectivity is available
	 *
	 * The initialization runs in the background to avoid blocking the main thread.
	 *
	 * @see YoutubeDL.init for library initialization
	 * @see FFmpeg.init for media processing initialization
	 * @see YoutubeDL.updateYoutubeDL for binary updates
	 */
	fun initializeYtDLP() {
		logger.d("Initializing YouTube-DL and FFmpeg libraries")
		executeInBackground(codeBlock = {
			try {
				// Initialize core libraries
				getInstance().init(this)
				FFmpeg.getInstance().init(this)
				logger.d("YouTube-DL and FFmpeg libraries initialized successfully")

				// Conditional binary updates based on network availability
				executeInBackground(codeBlock = {
					if (isInternetConnected()) {
						logger.d("Internet connection available, updating YouTube-DL binaries")
						getInstance().updateYoutubeDL(INSTANCE)
					} else {
						logger.d("No internet connection, skipping YouTube-DL update")
					}
				})
			} catch (error: Exception) {
				logger.e("Error initializing YouTube-DL/FFmpeg: ${error.message}", error)
			}

			// Store the instance for global access
			ytdlpInstance = getInstance()
		})
	}

	/**
	 * Application termination handler - performs comprehensive cleanup and resource release.
	 *
	 * This method ensures graceful application shutdown by:
	 * 1. Pausing all active download operations
	 * 2. Performing download system cleanup and resource release
	 * 3. Stopping application timers and scheduled tasks
	 * 4. Closing database connections and releasing file handles
	 *
	 * All cleanup operations are performed in the background to avoid blocking the main thread.
	 *
	 * @see DownloadSystem.pauseAllDownloads for download management
	 * @see DownloadSystem.cleanUp for resource cleanup
	 * @see AIOTimer.cancel for timer termination
	 * @see ObjectBoxManager.closeObjectBoxDB for database connection cleanup
	 */
	override fun onTerminate() {
		logger.d("AIOApp.onTerminate() - Application shutdown sequence initiated")

		executeInBackground(codeBlock = {
			logger.d("Termination: Pausing active downloads and performing cleanup")
			downloadSystem.pauseAllDownloads()
			downloadSystem.cleanUp()

			logger.d("Termination: Stopping application timers")
			aioTimer.cancel()

			logger.d("Termination: Closing database connections")
			ObjectBoxManager.closeObjectBoxDB()

			logger.d("Termination: All cleanup operations completed")
		})

		super.onTerminate()
		logger.d("AIOApp.onTerminate() - Application shutdown sequence completed")
	}

	/**
	 * Retrieves the application's internal data directory.
	 *
	 * @return DocumentFile representing the internal private storage directory
	 */
	fun getInternalDataFolder(): DocumentFile {
		logger.d("Accessing internal data folder")
		return internalDataFolder
	}

	/**
	 * Retrieves the application's external data directory if available.
	 *
	 * @return DocumentFile representing the external storage directory, or null if unavailable
	 */
	fun getExternalDataFolder(): DocumentFile? {
		logger.d("Accessing external data folder")
		return getExternalFilesDir(null)?.let { fromFile(it) }
	}

	/**
	 * Retrieves the public downloads directory for user-accessible file storage.
	 *
	 * @return DocumentFile representing the public downloads directory, or null if inaccessible
	 */
	fun getPublicDownloadDir(): DocumentFile? {
		logger.d("Accessing public downloads directory")
		return fromPublicFolder(INSTANCE, DOWNLOADS)
	}

	/**
	 * Provides access to the global application settings and preferences.
	 *
	 * @return AIOSettings instance containing all user configurations
	 */
	fun getAIOSettings(): AIOSettings {
		logger.d("Accessing application settings")
		return aioSettings
	}

	/**
	 * Provides access to the main download management system.
	 *
	 * @return DownloadSystem instance for managing download operations
	 */
	fun getDownloadManager(): DownloadSystem {
		logger.d("Accessing download manager")
		return downloadSystem
	}

	/**
	 * Provides access to the browsing history management system.
	 *
	 * @return AIOHistory instance containing browsing session records
	 */
	fun getAIOHistory(): AIOHistory {
		logger.d("Accessing browsing history")
		return aioHistory
	}

	/**
	 * Provides access to the bookmarks management system.
	 *
	 * @return AIOBookmarks instance containing user bookmark collections
	 */
	fun getAIOBookmarks(): AIOBookmarks {
		logger.d("Accessing bookmarks manager")
		return aioBookmark
	}

	/**
	 * Provides access to the favicon management and caching system.
	 *
	 * @return AIOFavicons instance for website icon management
	 */
	fun getAIOFavicon(): AIOFavicons {
		logger.d("Accessing favicon manager")
		return aioFavicons
	}

	/**
	 * Provides access to the backend service integration system.
	 *
	 * @return AIOBackend instance for network and API operations
	 */
	fun getAIOBackend(): AIOBackend = aioBackend

	/**
	 * Priority-based startup task management system for optimal application initialization.
	 *
	 * This inner class orchestrates the multi-stage startup process by categorizing
	 * initialization tasks into three priority levels and executing them with appropriate
	 * concurrency strategies:
	 *
	 * ## Task Categories:
	 * 1. **Critical Tasks**: Blocking operations essential for basic functionality
	 * 2. **High Priority Tasks**: Concurrent operations for user-facing components
	 * 3. **Background Tasks**: IO-bound operations for non-essential systems
	 *
	 * ## Execution Strategy:
	 * - Critical tasks execute immediately on the calling thread
	 * - High priority tasks execute concurrently using Dispatchers.Default
	 * - Background tasks execute concurrently using Dispatchers.IO
	 *
	 * @see CoroutineScope for asynchronous task execution
	 * @see Dispatchers for thread pool management
	 */
	private class StartupManager {
		private val logger = LogHelperUtils.from(javaClass)

		/** Critical path tasks that must complete before application becomes functional */
		private val criticalTasks = mutableListOf<() -> Unit>()

		/** Important tasks that can execute concurrently but should complete quickly */
		private val highPriorityTasks = mutableListOf<() -> Unit>()

		/** Non-essential tasks that can execute in background without blocking UI */
		private val backgroundTasks = mutableListOf<() -> Unit>()

		/** Coroutine scope for managing concurrent task execution */
		private val scope = CoroutineScope(Dispatchers.Default)

		/**
		 * Registers a critical task that must execute immediately and block until completion.
		 *
		 * @param task Lambda representing the critical initialization operation
		 */
		fun addCriticalTask(task: () -> Unit) {
			logger.d("Registering critical startup task")
			criticalTasks.add(task)
		}

		/**
		 * Registers a high priority task that should execute concurrently as soon as possible.
		 *
		 * @param task Lambda representing the high priority initialization operation
		 */
		fun addHighPriorityTask(task: () -> Unit) {
			logger.d("Registering high priority startup task")
			highPriorityTasks.add(task)
		}

		/**
		 * Registers a background task for non-urgent initialization operations.
		 *
		 * @param task Lambda representing the background initialization operation
		 */
		fun addBackgroundTask(task: () -> Unit) {
			logger.d("Registering background startup task")
			backgroundTasks.add(task)
		}

		/**
		 * Executes all critical tasks sequentially on the current thread.
		 * This method blocks until all critical tasks complete.
		 */
		fun executeCriticalTasks() {
			logger.d("Executing ${criticalTasks.size} critical tasks sequentially")
			criticalTasks.forEachIndexed { index, task ->
				logger.d("Critical task ${index + 1}/${criticalTasks.size} starting")
				task()
				logger.d("Critical task ${index + 1}/${criticalTasks.size} completed")
			}
		}

		/**
		 * Executes all high priority tasks concurrently using the default dispatcher.
		 * This method returns immediately while tasks execute in the background.
		 */
		fun executeHighPriorityTasks() {
			logger.d("Executing ${highPriorityTasks.size} high priority tasks concurrently")
			scope.launch {
				highPriorityTasks.map { task ->
					async { task() }
				}.awaitAll()
			}
		}

		/**
		 * Executes all background tasks concurrently using the IO dispatcher.
		 * Suitable for file I/O, network operations, and other blocking tasks.
		 */
		fun executeBackgroundTasks() {
			logger.d("Executing ${backgroundTasks.size} background tasks on IO dispatcher")
			scope.launch {
				backgroundTasks.map { task ->
					async(Dispatchers.IO) { task() }
				}.awaitAll()
			}
		}
	}
}