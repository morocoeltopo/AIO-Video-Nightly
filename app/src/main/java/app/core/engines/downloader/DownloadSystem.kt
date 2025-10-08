package app.core.engines.downloader

import app.core.AIOApp.Companion.aioBackend
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.services.AIOForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.files.FileSystemUtility.updateMediaStore
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility

/**
 * Core implementation of the download management system.
 *
 * This class handles:
 * - Download task lifecycle (add/start/pause/resume/delete)
 * - Parallel download management
 * - Task queue management
 * - Status updates and notifications
 * - UI coordination
 * - Automatic cleanup of completed downloads
 *
 * Implements both DownloadSysInf interface for system operations and
 * DownloadTaskListener for task status updates.
 */
class DownloadSystem : AIOTimerListener, DownloadSysInf, DownloadTaskListener {

	// Logger instance for tracking events
	private val logger = LogHelperUtils.from(javaClass)

	// System state flags
	/** Indicates whether the download system is currently initializing. */
	override var isInitializing: Boolean = false

	// Notification handler
	/** Handles download-related notifications to the user. */
	override val downloadNotification: DownloadNotification = DownloadNotification()

	// Data collections
	/** List of downloads currently being processed or queued for processing. */
	override val activeDownloadDataModels: ArrayList<DownloadDataModel> = ArrayList()

	/** List of downloads that have completed processing. */
	override val finishedDownloadDataModels: ArrayList<DownloadDataModel> = ArrayList()

	/** List of currently running download tasks. */
	override val runningDownloadTasks: ArrayList<DownloadTaskInf> = ArrayList()

	/** List of download tasks waiting to be started. */
	override val waitingDownloadTasks: ArrayList<DownloadTaskInf> = ArrayList()

	// UI coordination
	/** Manages UI updates related to downloads. */
	override val downloadsUIManager: DownloadUIManager = DownloadUIManager(this)

	/** Listeners that are notified when a download finishes. */
	override var downloadOnFinishListeners: ArrayList<DownloadFinishUIListener> = ArrayList()

	init {
		// Initialize system and register with timer for periodic operations
		initSystem()
		aioTimer.register(this)
		logger.d("DownloadSystem initialized & timer registered.")
	}

	/**
	 * Timer callback invoked at regular intervals.
	 * Processes pending tasks and updates the foreground service.
	 *
	 * @param loopCount The number of times this callback has been triggered.
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		downloadMonitor(loopCount)
	}

	@Synchronized
	private fun downloadMonitor(loopCount: Double) {
		logger.d("Timer tick received. Loop count: $loopCount")
		CoroutineScope(Dispatchers.IO).launch {
			logger.d("Starting pending tasks from waiting list.")
			startPendingTasksFromWaitingList()
			logger.d("Updating foreground service.")
			AIOForegroundService.updateService()
		}
	}

	/**
	 * Adds a new download to the system.
	 *
	 * Checks if the download is already active; if so, it resumes it.
	 * Otherwise, creates a new download task, adds it to the waiting list,
	 * and updates the UI accordingly.
	 *
	 * @param downloadModel The download data model to add
	 * @param onAdded Callback invoked after the download is successfully added
	 */
	override fun addDownload(downloadModel: DownloadDataModel, onAdded: () -> Unit) {
		logger.d("Attempting to add download: ${downloadModel.fileName}")
		ThreadsUtility.executeInBackground(codeBlock = {
			if (existsInActiveDownloadDataModelsList(downloadModel)) {
				logger.d("Download already active, resuming: ${downloadModel.fileName}")
				resumeDownload(downloadModel)
				return@executeInBackground
			}

			generateDownloadTask(downloadModel).let { downloadTask ->
				if (!waitingDownloadTasks.contains(downloadTask)) {
					waitingDownloadTasks.add(downloadTask)
					addAndSortActiveDownloadModelList(downloadModel)
					logger.d("Download task added to waiting list: ${downloadModel.fileName}")

					ThreadsUtility.executeOnMain {
						onAdded()
						logger.d("onAdded callback executed for: ${downloadModel.fileName}")
					}

					downloadsUIManager.addNewActiveUI(
						downloadModel = downloadModel,
						position = activeDownloadDataModels.indexOf(downloadModel)
					)
					logger.d("UI updated for new download: ${downloadModel.fileName}")
				}
			}
		}, errorHandler = {
			logger.e("Failed to add download: ${downloadModel.fileName}", it)
		})
	}

	/**
	 * Resumes a paused download.
	 *
	 * Generates the download task again and adds it to the waiting list if not already present.
	 * Updates the UI and invokes the onResumed callback.
	 *
	 * @param downloadModel The download data model to resume
	 * @param onResumed Callback invoked after the download is successfully resumed
	 */
	override fun resumeDownload(downloadModel: DownloadDataModel, onResumed: () -> Unit) {
		logger.d("Attempting to resume download: ${downloadModel.fileName}")
		ThreadsUtility.executeInBackground(codeBlock = {
			generateDownloadTask(downloadModel).let { downloadTask ->
				if (!waitingDownloadTasks.contains(downloadTask)) {
					waitingDownloadTasks.add(downloadTask)
					logger.d("Download task added to waiting list for resume: ${downloadModel.fileName}")

					downloadsUIManager.updateActiveUI(downloadModel)
					logger.d("UI updated for resumed download: ${downloadModel.fileName}")

					onResumed()
					logger.d("onResumed callback executed for: ${downloadModel.fileName}")
				}
			}
		}, errorHandler = {
			logger.e("Failed to resume download: ${downloadModel.fileName}", it)
		})
	}

	/**
	 * Forced resumes a failing download.
	 *
	 * If the download is already active, it pauses it first and then resumes after a delay.
	 * Skips resuming if the problem message contains "login".
	 *
	 * @param downloadModel The download data model to resume forcibly
	 */
	suspend fun forceResumeDownload(downloadModel: DownloadDataModel) {
		logger.d("Attempting to force resume download: ${downloadModel.fileName}")

		// If already active, pause first then resume after delay
		if (searchActiveDownloadTaskWith(downloadModel) != null) {
			ThreadsUtility.executeOnMain {
				logger.d("Forced pause initiated for download: ${downloadModel.fileName}")
				pauseDownload(downloadModel)
			}
			delay(1000)
		}

		ThreadsUtility.executeOnMain {
			if (!downloadModel.ytdlpProblemMsg.contains("login", true)) {
				// Normal resume operation
				logger.d("Forced resume initiated for download: ${downloadModel.fileName}")
				resumeDownload(downloadModel = downloadModel)
			} else {
				logger.d("Skipping forced resume due to login issue: ${downloadModel.fileName}")
			}
		}
	}

	/**
	 * Pauses an active download.
	 *
	 * Cancels the download, removes it from running and waiting lists,
	 * updates the UI, and invokes the onPaused callback.
	 *
	 * @param downloadModel The download data model to pause
	 * @param onPaused Callback invoked after successful pause
	 */
	override fun pauseDownload(downloadModel: DownloadDataModel, onPaused: () -> Unit) {
		logger.d("Attempting to pause download: ${downloadModel.fileName}")
		CoroutineScope(Dispatchers.IO).launch {
			try {
				if (canDownloadTaskBePaused(downloadModel)) {
					val resultedTask = searchActiveDownloadTaskWith(downloadModel)

					resultedTask?.let {
						it.cancelDownload()
						runningDownloadTasks.remove(it)
						waitingDownloadTasks.remove(it)
						onPaused()
						logger.d("Paused download successfully: ${downloadModel.fileName}")
					}

					downloadsUIManager.updateActiveUI(downloadModel)
					logger.d("UI updated after pause for download: ${downloadModel.fileName}")
				} else {
					logger.d("Download cannot be paused: ${downloadModel.fileName}")
				}
			} catch (error: Exception) {
				logger.e("Failed to pause download: ${downloadModel.fileName}", error)
			}
		}
	}

	/**
	 * Clears a download by removing it from the system while keeping its files.
	 *
	 * This method pauses the download, marks it as removed, deletes its metadata from disk,
	 * removes it from active download lists, updates notifications and UI, and finally invokes the callback.
	 *
	 * @param downloadModel The download data model to clear
	 * @param onCleared Callback invoked after successful clear
	 */
	override fun clearDownload(downloadModel: DownloadDataModel, onCleared: () -> Unit) {
		logger.d("Attempting to clear download: ${downloadModel.fileName}")
		CoroutineScope(Dispatchers.IO).launch {
			try {
				pauseDownload(downloadModel)
				downloadModel.isRemoved = true
				downloadModel.deleteModelFromDisk()
				activeDownloadDataModels.remove(downloadModel)
				logger.d("Removed download from active list: ${downloadModel.fileName}")

				searchActiveDownloadTaskWith(downloadModel)?.let { downloadTask ->
					runningDownloadTasks.remove(downloadTask)
					waitingDownloadTasks.remove(downloadTask)
					logger.d("Removed download from running and waiting tasks: ${downloadModel.fileName}")
				}

				downloadNotification.updateNotification(downloadModel)
				downloadsUIManager.updateActiveUI(downloadModel)
				onCleared()
				logger.d("Cleared download successfully: ${downloadModel.fileName}")
			} catch (error: Exception) {
				logger.e("Failed to clear download: ${downloadModel.fileName}", error)
			}
		}
	}

	/**
	 * Deletes a download and its associated files from the system.
	 *
	 * Clears the download metadata, deletes the model and destination file,
	 * and invokes the callback after completion.
	 *
	 * @param downloadModel The download data model to delete
	 * @param onDone Callback invoked after successful deletion
	 */
	override fun deleteDownload(downloadModel: DownloadDataModel, onDone: () -> Unit) {
		logger.d("Attempting to delete download: ${downloadModel.fileName}")
		CoroutineScope(Dispatchers.IO).launch {
			try {
				clearDownload(downloadModel) {}
				downloadModel.isDeleted = true
				downloadModel.deleteModelFromDisk()
				downloadModel.getDestinationFile().delete()
				logger.d("Deleted associated files for download: ${downloadModel.fileName}")
				onDone()
				logger.d("Deleted download successfully: ${downloadModel.fileName}")
			} catch (error: Exception) {
				logger.e("Failed to delete download: ${downloadModel.fileName}", error)
			}
		}
	}

	// Bulk operations

	/**
	 * Resumes all active downloads in the system.
	 */
	override fun resumeAllDownloads() {
		logger.d("Resuming all downloads")
		activeDownloadDataModels.forEach { resumeDownload(it) }
	}

	/**
	 * Pauses all active downloads in the system.
	 */
	override fun pauseAllDownloads() {
		logger.d("Pausing all downloads")
		activeDownloadDataModels.forEach { pauseDownload(it) }
	}

	/**
	 * Clears all active downloads in the system while keeping their files.
	 */
	override fun clearAllDownloads() {
		logger.d("Clearing all downloads")
		activeDownloadDataModels.forEach { clearDownload(it) }
	}

	/**
	 * Deletes all active downloads and their associated files from the system.
	 */
	override fun deleteAllDownloads() {
		logger.d("Deleting all downloads")
		activeDownloadDataModels.forEach { deleteDownload(it) }
	}

	/**
	 * Handles status updates from download tasks.
	 *
	 * Updates running tasks list, UI, notifications, and logs based on the current status of the download task.
	 *
	 * @param downloadTaskInf The task reporting the status change
	 */
	override suspend fun onStatusUpdate(downloadTaskInf: DownloadTaskInf) {
		logger.d("Status update received for: ${downloadTaskInf.downloadDataModel.fileName}")
		try {
			val downloadDataModel = downloadTaskInf.downloadDataModel

			// Add to running tasks if currently downloading
			if (downloadDataModel.isRunning && downloadDataModel.status == DOWNLOADING) {
				addToRunningDownloadTasksList(downloadTaskInf)
				logger.d("Added to running tasks: ${downloadDataModel.fileName}")
			}

			// Remove from running tasks if closed
			if (!downloadDataModel.isRunning && downloadDataModel.status == CLOSE) {
				removeFromRunningDownloadTasksList(downloadTaskInf)
				logger.d("Removed from running tasks: ${downloadDataModel.fileName}")
			}

			// Handle completed download
			if (downloadDataModel.isComplete && downloadDataModel.status == COMPLETE) {
				logger.d("Download completed: ${downloadDataModel.fileName}")
				aioBackend.saveDownloadLog(downloadDataModel)

				removeFromActiveDownloadDataModelsList(downloadDataModel)
				withContext(Dispatchers.Main) {
					downloadsUIManager.updateActiveUI(downloadDataModel)
					logger.d("Updated UI for completed download: ${downloadDataModel.fileName}")
				}

				updateFinishedDownloadDataModelsList(downloadDataModel)
				withContext(Dispatchers.Main) {
					downloadOnFinishListeners.forEach { finishUIListener ->
						finishUIListener.onFinishUIDownload(downloadDataModel)
					}
					logger.d("Notified listeners of completion: ${downloadDataModel.fileName}")
				}
			}

			updateUIAndNotification(downloadDataModel)
			logger.d("UI and notification updated for: ${downloadDataModel.fileName}")
		} catch (error: Exception) {
			logger.e("Error in status update for: ${downloadTaskInf.downloadDataModel.fileName}", error)
		}
	}

	/**
	 * Adds a download task to the running tasks list if not already present.
	 *
	 * @param downloadTaskInf The task to add
	 */
	private fun addToRunningDownloadTasksList(downloadTaskInf: DownloadTaskInf) {
		if (!runningDownloadTasks.contains(downloadTaskInf)) {
			runningDownloadTasks.add(downloadTaskInf)
			logger.d("Task added to running list: ${downloadTaskInf.downloadDataModel.fileName}")
		}
	}

	/**
	 * Removes a download task from the running tasks list if present.
	 *
	 * @param downloadTaskInfo The task to remove
	 */
	private fun removeFromRunningDownloadTasksList(downloadTaskInfo: DownloadTaskInf) {
		if (runningDownloadTasks.contains(downloadTaskInfo)) {
			runningDownloadTasks.remove(downloadTaskInfo)
			logger.d("Task removed from running list: ${downloadTaskInfo.downloadDataModel.fileName}")
		}
	}

	/**
	 * Removes a download data model from the active downloads list if present.
	 *
	 * @param downloadDataModel The model to remove
	 */
	private fun removeFromActiveDownloadDataModelsList(downloadDataModel: DownloadDataModel) {
		if (activeDownloadDataModels.contains(downloadDataModel)) {
			activeDownloadDataModels.remove(downloadDataModel)
			logger.d("Download removed from active list: ${downloadDataModel.fileName}")
		}
	}

	/**
	 * Starts pending downloads from the waiting list according to parallel download limits.
	 * Ensures that the number of running tasks does not exceed the allowed maximum.
	 */
	private suspend fun startPendingTasksFromWaitingList() {
		logger.d("Starting pending tasks from waiting list.")
		verifyLeftoverRunningTasks()

		val maxAllowedParallelDownloads = aioSettings.downloadDefaultParallelConnections
		if (numberOfRunningTasks() >= maxAllowedParallelDownloads) {
			logger.d("Max parallel downloads reached. Skipping.")
			return
		}

		if (waitingDownloadTasks.isEmpty()) {
			logger.d("No pending tasks to start.")
			return
		}

		val queuedTask = waitingDownloadTasks.removeAt(0)
		try {
			logger.d("Starting queued task: ${queuedTask.downloadDataModel.fileName}")
			startDownloadTask(queuedTask)
		} catch (error: Exception) {
			logger.e("Failed to start queued task", error)
			queuedTask.updateDownloadStatus(status = CLOSE)
		}
	}

	/**
	 * Executes the download task in the background.
	 * Handles exceptions and updates task status accordingly.
	 *
	 * @param downloadTaskInf The task to start
	 */
	private fun startDownloadTask(downloadTaskInf: DownloadTaskInf) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Starting download task: ${downloadTaskInf.downloadDataModel.fileName}")
				downloadTaskInf.startDownload()
			} catch (error: Exception) {
				logger.e("Error starting task", error)
				downloadTaskInf.updateDownloadStatus(status = CLOSE)
			}
		})
	}

	/**
	 * Cleans up invalid or completed running tasks from the list.
	 */
	private fun verifyLeftoverRunningTasks() {
		try {
			val iterator = runningDownloadTasks.iterator()
			while (iterator.hasNext()) {
				val runningTask = iterator.next()
				val dataModel = runningTask.downloadDataModel
				if (!dataModel.isRunning && dataModel.status != DOWNLOADING) {
					logger.d("Removing leftover task: ${dataModel.fileName}")
					iterator.remove()
				}
			}
		} catch (error: Exception) {
			logger.e("Error verifying leftover running tasks", error)
		}
	}

	/**
	 * Creates a download task based on the file type.
	 * If video metadata is present, it creates a VideoDownloader; otherwise, a RegularDownloader.
	 *
	 * @param downloadDataModel The data model of the download
	 * @return The created download task
	 */
	private suspend fun generateDownloadTask(downloadDataModel: DownloadDataModel): DownloadTaskInf {
		logger.d("Generating download task for: ${downloadDataModel.fileName}")
		return if (downloadDataModel.videoInfo != null && downloadDataModel.videoFormat != null) {
			val videoDownloader = VideoDownloader(downloadDataModel)
			videoDownloader.downloadStatusListener = this
			videoDownloader.initiateDownload()
			videoDownloader
		} else {
			val regularDownloader = RegularDownloader(downloadDataModel)
			regularDownloader.downloadStatusListener = this@DownloadSystem
			regularDownloader.initiateDownload()
			regularDownloader
		}
	}

	/**
	 * Updates the UI and notification for a given download.
	 *
	 * @param downloadDataModel The data model to update
	 */
	private fun updateUIAndNotification(downloadDataModel: DownloadDataModel) {
		logger.d("Updating UI and notification for: ${downloadDataModel.fileName}")
		if (!downloadDataModel.isComplete) {
			downloadsUIManager.updateActiveUI(downloadDataModel)
		}

		downloadNotification.updateNotification(downloadDataModel)
	}

	/**
	 * Updates the finished downloads list based on cleanup settings.
	 *
	 * @param downloadDataModel The completed download to process
	 */
	private fun updateFinishedDownloadDataModelsList(downloadDataModel: DownloadDataModel) {
		logger.d("Updating finished downloads list for: ${downloadDataModel.fileName}")
		val setting = downloadDataModel.globalSettings
		if (!setting.downloadAutoRemoveTasks || setting.downloadAutoRemoveTaskAfterNDays != 0) {
			addToFinishDownloadList(downloadDataModel)
		}
	}

	/**
	 * Adds a completed download to the finished list and updates the UI.
	 *
	 * @param downloadDataModel The download to add
	 */
	private fun addToFinishDownloadList(downloadDataModel: DownloadDataModel) {
		logger.d("Adding to finished download list: ${downloadDataModel.fileName}")
		addAndSortFinishedDownloadDataModels(downloadDataModel)
		CoroutineScope(Dispatchers.Main).launch {
			downloadsUIManager.finishedTasksFragment
				?.finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
			updateMediaStore()
		}
	}

	/**
	 * Cleans up system resources by unregistering the timer.
	 */
	fun cleanUp() {
		logger.d("Cleaning up DownloadSystem.")
		aioTimer.unregister(this)
		logger.d("Timer unregistered.")
	}
}