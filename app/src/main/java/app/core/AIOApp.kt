package app.core

import android.app.Activity
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromFile
import androidx.lifecycle.LifecycleObserver
import app.core.bases.BaseActivity
import app.core.bases.language.LanguageAwareApplication
import app.core.engines.browser.bookmarks.AIOBookmarks
import app.core.engines.browser.history.AIOHistory
import app.core.engines.caches.AIOAdBlocker
import app.core.engines.caches.AIOFavicons
import app.core.engines.caches.AIORawFiles
import app.core.engines.downloader.DownloadSystem
import app.core.engines.services.AIOForegroundService
import app.core.engines.settings.AIOSettings
import app.core.engines.video_parser.parsers.YoutubeVidParser
import com.aio.BuildConfig
import com.anggrayudi.storage.file.DocumentFileCompat.fromPublicFolder
import com.anggrayudi.storage.file.PublicDirectory.DOWNLOADS
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
import lib.networks.URLUtilityKT.isInternetConnected
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground

/**
 * AIOApp is the main Application class of the project.
 *
 * It handles the global app context, lazy loads singletons, initializes core systems,
 * and executes a priority-based startup flow using `StartupManager`.
 *
 * This class also centralizes access to global engines, settings, download system,
 * and ensures lifecycle-aware behavior.
 */
class AIOApp : LanguageAwareApplication(), LifecycleObserver {

	private val logger = LogHelperUtils.from(javaClass)

	companion object {

		@Volatile
		lateinit var INSTANCE: AIOApp

		// App mode flags
		const val IS_DEBUG_MODE_ON = BuildConfig.IS_DEBUG_MODE_ON
		const val IS_ULTIMATE_VERSION_UNLOCKED = true
		const val IS_PREMIUM_USER = true

		// Internal file paths
		val internalDataFolder: DocumentFile by lazy { fromFile(INSTANCE.filesDir) }
		val externalDataFolder: DocumentFile? by lazy {
			INSTANCE.getExternalFilesDir(null)?.let { fromFile(it) }
		}

		// Core modules initialized during startup
		lateinit var aioSettings: AIOSettings
		lateinit var aioBookmark: AIOBookmarks
		lateinit var aioHistory: AIOHistory
		lateinit var aioRawFiles: AIORawFiles
		lateinit var ytdlpInstance: YoutubeDL

		// Lazily loaded managers and utilities
		val aioFavicons: AIOFavicons by lazy { AIOFavicons() }
		val aioAdblocker: AIOAdBlocker by lazy { AIOAdBlocker() }

		val aioLanguage: AIOLanguage by lazy { AIOLanguage() }
		val aioGSONInstance: Gson by lazy { GsonBuilder().setStrictness(LENIENT).create() }

		// Persistent background service
		val idleForegroundService by lazy { AIOForegroundService().apply { updateService() } }

		// Download engines & manager
		val downloadSystem: DownloadSystem by lazy { DownloadSystem() }
		val youtubeVidParser: YoutubeVidParser by lazy { YoutubeVidParser() }

		// Global timer
		val aioTimer: AIOTimer by lazy { AIOTimer(3600000, 200).apply { start() } }
	}

	// Responsible for ordering and executing startup tasks
	private val startupManager = StartupManager()

	/**
	 * Invoked when the application is launched.
	 * Initializes critical, high-priority, and background components.
	 */
	override fun onCreate() {
		super.onCreate()
		logger.d("AIOApp onCreate() started")
		INSTANCE = this

		startupManager.apply {
			// Load essential settings and raw resources
			addCriticalTask {
				logger.d("Loading critical settings and resources")
				aioSettings = AIOSettings().apply(AIOSettings::readObjectFromStorage)
				aioRawFiles = AIORawFiles().apply(AIORawFiles::preloadLottieAnimation)
				youtubeVidParser.initSystem()
				logger.d("Critical settings and resources loaded")
			}

			// Load bookmarks/history and lifecycle tracking
			addHighPriorityTask {
				logger.d("Loading bookmarks and history")
				aioBookmark = AIOBookmarks().apply(AIOBookmarks::readObjectFromStorage)
				aioHistory = AIOHistory().apply(AIOHistory::readObjectFromStorage)
				manageActivityLifeCycle()
				logger.d("Bookmarks and history loaded")
			}

			// Load Youtube-DL and session tracker
			addBackgroundTask {
				logger.d("Starting YtDLP initialization")
				initializeYtDLP()
				logger.d("YtDLP initialization completed")
			}
		}

		// Execute blocking critical tasks first
		logger.d("Executing critical startup tasks")
		startupManager.executeCriticalTasks()

		// Launch remaining tasks concurrently
		logger.d("Starting background execution of high priority tasks")
		executeInBackground(codeBlock = {
			startupManager.executeHighPriorityTasks()
			startupManager.executeBackgroundTasks()
			logger.d("Background startup tasks completed")
		})
		logger.d("AIOApp onCreate() completed")
	}

	/**
	 * Registers a custom lifecycle callback handler.
	 * Used here to clean weak activity references.
	 */
	private fun manageActivityLifeCycle() {
		logger.d("Registering activity lifecycle callbacks")
		executeOnMainThread {
			registerActivityLifecycleCallbacks(object : AIOLifeCycle {
				override fun onActivityDestroyed(activity: Activity) {
					logger.d("Activity destroyed: ${activity.javaClass.simpleName}")
					if (activity is BaseActivity) {
						activity.clearWeakActivityReference()
					}
				}
			})
		}
	}

	/**
	 * Initializes YoutubeDL and FFmpeg libraries in the background.
	 * Also optionally updates the binary if internet is available.
	 */
	fun initializeYtDLP() {
		logger.d("Initializing YtDLP and FFmpeg")
		executeInBackground(codeBlock = {
			try {
				getInstance().init(this)
				FFmpeg.getInstance().init(this)
				logger.d("YtDLP and FFmpeg initialized successfully")

				executeInBackground(codeBlock = {
					if (isInternetConnected()) {
						logger.d("Internet available, updating YtDLP")
						getInstance().updateYoutubeDL(INSTANCE)
					} else {
						logger.d("No internet connection, skipping YtDLP update")
					}
				})
			} catch (error: Exception) {
				logger.d("Error initializing YtDLP: ${error.message}")
				error.printStackTrace()
			}; ytdlpInstance = getInstance()
		})
	}

	/**
	 * Called when the app is terminating.
	 * Flushes data and cancels long-running timers or downloads.
	 */
	override fun onTerminate() {
		logger.d("App termination started")
		executeInBackground(codeBlock = {
			logger.d("Pausing all downloads and canceling timer")
			downloadSystem.pauseAllDownloads()
			aioTimer.cancel()
			logger.d("Termination tasks completed")
		})
		super.onTerminate()
		logger.d("App termination completed")
	}

	/**
	 * Returns the internal data folder used by the application.
	 *
	 * This folder is private to the app and typically located in `/data/data/<package_name>/`.
	 */
	fun getInternalDataFolder(): DocumentFile {
		logger.d("Getting internal data folder")
		return internalDataFolder
	}

	/**
	 * Returns the external data folder specific to the app, if available.
	 *
	 * This is typically located in `/storage/emulated/0/Android/data/<package_name>/files/`.
	 * Returns `null` if the external files directory is not accessible.
	 */
	fun getExternalDataFolder(): DocumentFile? {
		logger.d("Getting external data folder")
		return getExternalFilesDir(null)?.let { fromFile(it) }
	}

	/**
	 * Returns the public downloads directory wrapped as a `DocumentFile`, if accessible.
	 *
	 * This refers to the shared `Downloads` folder accessible by all apps and the user.
	 * Requires appropriate permissions (e.g., SAF or runtime storage permission on Android 10+).
	 */
	fun getPublicDownloadDir(): DocumentFile? {
		logger.d("Getting public download directory")
		return fromPublicFolder(INSTANCE, DOWNLOADS)
	}

	/**
	 * Returns the application's settings object that holds user preferences and configurations.
	 */
	fun getAIOSettings(): AIOSettings {
		logger.d("Getting AIO settings")
		return aioSettings
	}

	/**
	 * Returns the main download system responsible for managing download tasks.
	 */
	fun getDownloadManager(): DownloadSystem {
		logger.d("Getting download manager")
		return downloadSystem
	}

	/**
	 * Returns the application's download history handler for tracking past downloads.
	 */
	fun getAIOHistory(): AIOHistory {
		logger.d("Getting AIO history")
		return aioHistory
	}

	/**
	 * Returns the application's bookmark manager for storing user bookmarks.
	 */
	fun getAIOBookmarks(): AIOBookmarks {
		logger.d("Getting AIO bookmarks")
		return aioBookmark
	}

	/**
	 * Returns the favicon manager responsible for fetching and storing website icons.
	 */
	fun getAIOFavicon(): AIOFavicons {
		logger.d("Getting AIO favicon manager")
		return aioFavicons
	}

	/**
	 * Handles categorized task execution during app startup.
	 *
	 * Tasks are divided into three categories based on their importance and execution strategy:
	 * 1. **Critical Tasks**: Executed immediately and sequentially on the calling thread.
	 * 2. **High Priority Tasks**: Executed concurrently in the background using the default dispatcher.
	 * 3. **Background Tasks**: Executed concurrently using the IO dispatcher, intended for less urgent operations.
	 *
	 * Tasks can be registered before triggering their execution. Each task is a lambda with no input or output.
	 */
	private class StartupManager {
		private val logger = LogHelperUtils.from(javaClass)

		/** List of tasks that must be executed immediately during startup. */
		private val criticalTasks = mutableListOf<() -> Unit>()

		/** List of high-priority tasks that can run concurrently in the background. */
		private val highPriorityTasks = mutableListOf<() -> Unit>()

		/** List of background tasks that are less time-sensitive and can run on the IO dispatcher. */
		private val backgroundTasks = mutableListOf<() -> Unit>()

		/** Coroutine scope using the default dispatcher for launching asynchronous tasks. */
		private val scope = CoroutineScope(Dispatchers.Default)

		/**
		 * Registers a task that must be executed immediately in a blocking fashion.
		 * @param task A lambda representing the critical task.
		 */
		fun addCriticalTask(task: () -> Unit) {
			logger.d("Adding critical task")
			criticalTasks.add(task)
		}

		/**
		 * Registers a task that should be executed in parallel, as soon as possible.
		 * @param task A lambda representing the high-priority task.
		 */
		fun addHighPriorityTask(task: () -> Unit) {
			logger.d("Adding high priority task")
			highPriorityTasks.add(task)
		}

		/**
		 * Registers a task intended for IO or non-urgent work.
		 * @param task A lambda representing the background task.
		 */
		fun addBackgroundTask(task: () -> Unit) {
			logger.d("Adding background task")
			backgroundTasks.add(task)
		}

		/**
		 * Executes all critical tasks sequentially on the current thread.
		 * Should be called early in the app lifecycle to ensure critical setup is complete.
		 */
		fun executeCriticalTasks() {
			logger.d("Executing ${criticalTasks.size} critical tasks")
			criticalTasks.forEach { it() }
		}

		/**
		 * Executes all high-priority tasks concurrently on the default dispatcher.
		 * Ideal for tasks that should not block the main thread but need to complete quickly.
		 */
		fun executeHighPriorityTasks() {
			logger.d("Executing ${highPriorityTasks.size} high priority tasks")
			scope.launch {
				highPriorityTasks.map {
					async { it() }
				}.awaitAll()
			}
		}

		/**
		 * Executes all background tasks concurrently using the IO dispatcher.
		 * Suitable for less urgent operations such as preloading resources or cleanup tasks.
		 */
		fun executeBackgroundTasks() {
			logger.d("Executing ${backgroundTasks.size} background tasks")
			scope.launch {
				backgroundTasks.map {
					async(Dispatchers.IO) { it() }
				}.awaitAll()
			}
		}
	}
}