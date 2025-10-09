package app.core.engines.downloader

import app.core.AIOApp
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.DownloadModelParser.getDownloadDataModels
import app.core.engines.downloader.DownloadStatus.COMPLETE
import com.aio.R
import kotlinx.coroutines.CoroutineScope
import lib.device.DateTimeUtils.getDaysPassedSince
import lib.process.LogHelperUtils.from
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText

/**
 * Central interface responsible for managing the application's download system.
 *
 * This interface defines the core mechanisms for controlling and monitoring all
 * download activities within the app. It handles task scheduling, progress tracking,
 * and communication between the download logic and the user interface.
 *
 * ### Key Responsibilities
 * - Manage active, paused, and completed downloads
 * - Control download lifecycle actions (start, pause, resume, delete)
 * - Maintain lists of running and queued download tasks
 * - Synchronize download states with the UI layer
 * - Perform cleanup of outdated or completed download data
 *
 * ### Maintained Collections
 * - **Active Downloads** — Downloads currently in progress or paused
 * - **Completed Downloads** — Successfully finished downloads
 * - **Running Tasks** — Active threads handling file transfers
 * - **Waiting Tasks** — Queued downloads pending execution
 */
interface DownloadSysInf {

	/**
	 * Indicates whether the download system is currently initializing.
	 *
	 * This flag helps prevent concurrent initialization routines from running in parallel.
	 * It is typically set to `true` during startup or when the download manager reloads
	 * persisted download data.
	 */
	var isInitializing: Boolean

	/**
	 * Reference to the main application context.
	 *
	 * Provides access to global application-level resources and services through [AIOApp].
	 * Useful for operations that require a context outside of an Activity or Service scope.
	 */
	val appContext: AIOApp
		get() = AIOApp.INSTANCE

	/**
	 * Manages and displays system notifications for downloads.
	 *
	 * Handles creation, updating, and removal of notification entries reflecting
	 * download progress, completion, or errors.
	 */
	val downloadNotification: DownloadNotification

	/**
	 * Collection of all currently active downloads.
	 *
	 * This includes downloads that are in-progress, paused, or pending retry.
	 * It serves as the central in-memory representation of all non-completed downloads.
	 */
	val activeDownloadDataModels: ArrayList<DownloadDataModel>

	/**
	 * Collection of all completed downloads.
	 *
	 * Holds download models that have finished successfully and can be displayed
	 * in the “Completed Downloads” section of the app.
	 */
	val finishedDownloadDataModels: ArrayList<DownloadDataModel>

	/**
	 * List of currently running download tasks.
	 *
	 * Represents download threads actively fetching data.
	 * Managed internally by the download scheduler or task controller.
	 */
	val runningDownloadTasks: ArrayList<DownloadTaskInf>

	/**
	 * Queue of download tasks waiting for execution.
	 *
	 * Tasks in this list are scheduled but not yet running, often due to concurrency limits
	 * or network constraints.
	 */
	val waitingDownloadTasks: ArrayList<DownloadTaskInf>

	/**
	 * Manages synchronization between download logic and the user interface.
	 *
	 * Ensures that progress updates, status changes, and UI refresh events
	 * are handled efficiently and safely on the main thread.
	 */
	val downloadsUIManager: DownloadUIManager

	/**
	 * List of listeners that are notified when a download finishes.
	 *
	 * Each listener implements [DownloadFinishUIListener] and can be used to update
	 * UI components or trigger actions when a download completes.
	 */
	var downloadOnFinishListeners: ArrayList<DownloadFinishUIListener>

	/**
	 * Adds a new download entry into the download manager system.
	 *
	 * This function is responsible for registering a new download in memory or persistence layer,
	 * preparing it for execution by the download service or scheduler.
	 *
	 * @param downloadModel The [DownloadDataModel] representing the download to be added.
	 * @param onAdded A callback invoked after the download has been successfully added.
	 */
	fun addDownload(downloadModel: DownloadDataModel, onAdded: () -> Unit = {}) {
		onAdded()
	}

	/**
	 * Resumes a paused or interrupted download task.
	 *
	 * This function re-initializes the necessary download components, restores the coroutine context,
	 * and resumes downloading from the last saved progress if supported by the remote server.
	 *
	 * @param downloadModel The [DownloadDataModel] representing the download to be resumed.
	 * @param coroutineScope The [CoroutineScope] in which the download will run asynchronously.
	 * @param onResumed A callback invoked after the download successfully resumes.
	 */
	fun resumeDownload(
		downloadModel: DownloadDataModel,
		coroutineScope: CoroutineScope,
		onResumed: () -> Unit = {}
	) {
		onResumed()
	}

	/**
	 * Pauses an active download.
	 * @param downloadModel The download to pause
	 * @param onPaused Callback invoked after successful pause
	 */
	fun pauseDownload(downloadModel: DownloadDataModel, onPaused: () -> Unit = {}) {
		onPaused()
	}

	/**
	 * Clears a download (removes from active lists but keeps files).
	 * @param downloadModel The download to clear
	 * @param onCleared Callback invoked after successful clear
	 */
	fun clearDownload(downloadModel: DownloadDataModel, onCleared: () -> Unit = {}) {
		onCleared()
	}

	/**
	 * Deletes a download and its associated files.
	 * @param downloadModel The download to delete
	 * @param onDone Callback invoked after successful deletion
	 */
	fun deleteDownload(downloadModel: DownloadDataModel, onDone: () -> Unit = {}) {
		onDone()
	}

	/**
	 * Resumes all paused downloads.
	 */
	fun resumeAllDownloads() {}

	/**
	 * Pauses all active downloads.
	 */
	fun pauseAllDownloads() {}

	/**
	 * Clears all downloads (keeps files).
	 */
	fun clearAllDownloads() {}

	/**
	 * Deletes all downloads and their files.
	 */
	fun deleteAllDownloads() {}

	/**
	 * Initializes the download system by loading and syncing existing downloads.
	 */
	fun initSystem() {
		parseDownloadDataModelsAndSync()
	}

	/**
	 * Gets the count of currently running download tasks.
	 * @return Number of active downloads
	 */
	fun numberOfRunningTasks(): Int = runningDownloadTasks.size

	/**
	 * Checks if a download exists in the running tasks list.
	 * @param downloadModel The download to check
	 * @return true if the download is currently running
	 */
	fun existsInRunningTasksList(downloadModel: DownloadDataModel): Boolean {
		return runningDownloadTasks.any { it.downloadDataModel.id == downloadModel.id }
	}

	/**
	 * Checks if a download exists in the waiting tasks list.
	 * @param downloadModel The download to check
	 * @return true if the download is queued but not yet running
	 */
	fun existsInWaitingTasksList(downloadModel: DownloadDataModel): Boolean {
		return waitingDownloadTasks.any { it.downloadDataModel.id == downloadModel.id }
	}

	/**
	 * Checks if a download exists in the active downloads list.
	 * @param downloadModel The download to check
	 * @return true if the download is active (running or paused)
	 */
	fun existsInActiveDownloadDataModelsList(downloadModel: DownloadDataModel): Boolean {
		return activeDownloadDataModels.contains(downloadModel)
	}

	/**
	 * Finds a download task by its model.
	 * @param downloadModel The download model to search for
	 * @return The matching task if found (either running or waiting), null otherwise
	 */
	fun searchActiveDownloadTaskWith(downloadModel: DownloadDataModel): DownloadTaskInf? {
		return runningDownloadTasks.toList().find { it.downloadDataModel.id == downloadModel.id }
			?: waitingDownloadTasks.toList().find { it.downloadDataModel.id == downloadModel.id }
	}

	/**
	 * Checks if a download can be paused (is either running or waiting).
	 * @param downloadModel The download to check
	 * @return true if the download can be paused
	 */
	fun canDownloadTaskBePaused(downloadModel: DownloadDataModel): Boolean {
		return existsInRunningTasksList(downloadModel) || existsInWaitingTasksList(downloadModel)
	}

	/**
	 * Loads and synchronizes download models from disk.
	 * Handles automatic cleanup of old downloads based on settings.
	 */
	fun parseDownloadDataModelsAndSync() {
		ThreadsUtility.executeInBackground(codeBlock = {
			isInitializing = true
			getDownloadDataModels().forEach {
				if (isValidCompletedDownloadModel(it)) {
					if (it.globalSettings.downloadAutoRemoveTasks) {
						if (it.globalSettings.downloadAutoRemoveTaskAfterNDays == 0) {
							it.deleteModelFromDisk(); return@forEach
						}

						val autoRemoveDaysSettings = aioSettings.downloadAutoRemoveTaskAfterNDays
						if (getDaysPassedSince(it.lastModifiedTimeDate) > autoRemoveDaysSettings) {
							it.deleteModelFromDisk(); return@forEach
						}
					}

					it.statusInfo = getText(R.string.title_completed)
					addAndSortFinishedDownloadDataModels(it)
				}

				if (isValidActiveDownloadModel(it)) {
					it.statusInfo = getText(R.string.title_paused)
					addAndSortActiveDownloadModelList(it)
				}
			}
			isInitializing = false
		}, errorHandler = {
			isInitializing = false
			error("Error found in parsing download model from disk.")
		})
	}

	/**
	 * Adds a download to the active list and sorts the collection.
	 * @param downloadModel The download to add
	 */
	fun addAndSortActiveDownloadModelList(downloadModel: DownloadDataModel) {
		if (!activeDownloadDataModels.contains(downloadModel)) {
			activeDownloadDataModels.add(downloadModel)
		}; sortActiveDownloadDataModels()
	}

	/**
	 * Sorts active downloads by start time (newest first).
	 */
	fun sortActiveDownloadDataModels() {
		activeDownloadDataModels.sortByDescending { it.startTimeDate }
	}

	/**
	 * Adds a download to the completed list and sorts the collection.
	 * @param downloadModel The download to add
	 */
	fun addAndSortFinishedDownloadDataModels(downloadModel: DownloadDataModel) {
		if (!finishedDownloadDataModels.contains(downloadModel)) {
			finishedDownloadDataModels.add(downloadModel)
		}; sortFinishedDownloadDataModels()
	}

	/**
	 * Sorts completed downloads by start time (newest first).
	 */
	fun sortFinishedDownloadDataModels() {
		finishedDownloadDataModels.sortByDescending { it.startTimeDate }
	}

	/**
	 * Validates if a download model represents an active (incomplete) download.
	 * @param downloadModel The model to validate
	 * @return true if the model represents a valid active download
	 */
	fun isValidActiveDownloadModel(downloadModel: DownloadDataModel): Boolean {
		val isValidActiveTask = (downloadModel.status != COMPLETE && !downloadModel.isComplete)
		return isValidActiveTask && !downloadModel.isRemoved && !downloadModel.isDeleted
				&& !downloadModel.isWentToPrivateFolder
	}

	/**
	 * Validates if a download model represents a successfully completed download.
	 * @param downloadModel The model to validate
	 * @return true if the model represents a valid completed download
	 */
	fun isValidCompletedDownloadModel(downloadModel: DownloadDataModel): Boolean {
		val isValid = (downloadModel.status == COMPLETE || downloadModel.isComplete) &&
				!downloadModel.isWentToPrivateFolder &&
				downloadModel.getDestinationFile().exists()
		val isExisted = downloadModel.getDestinationFile().exists()
		if (!isExisted) downloadModel.deleteModelFromDisk()
		return isValid && isExisted
	}

	/**
	 * Logs the current status of a download task.
	 * @param downloadModel The download to log
	 */
	fun logDownloadTaskStatus(downloadModel: DownloadDataModel) {
		from(javaClass).d("Download ${downloadModel.id} status: ${downloadModel.status}")
	}
}