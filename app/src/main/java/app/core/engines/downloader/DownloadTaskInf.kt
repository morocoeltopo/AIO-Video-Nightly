package app.core.engines.downloader

/**
 * Interface defining the core operations for a download task.
 *
 * Implementations of this interface represent individual download operations
 * that can be managed by the download system. The interface provides:
 * - Lifecycle control (initiation, start, cancellation)
 * - Status reporting capabilities
 * - Access to underlying download model data
 *
 * All implementations should maintain thread safety and proper state management.
 */
interface DownloadTaskInf {
	
	/**
	 * The data model containing all information about this download.
	 * This provides access to:
	 * - Download progress and status
	 * - File metadata
	 * - Configuration settings
	 */
	val downloadDataModel: DownloadDataModel
	
	/**
	 * Listener for receiving status updates about the download progress.
	 * The implementing class should notify this listener about:
	 * - Status changes (started, paused, completed, failed)
	 * - Progress updates
	 * - Significant events during the download
	 */
	var downloadStatusListener: DownloadTaskListener?
	
	/**
	 * Prepares the download task for execution.
	 * This should:
	 * - Validate required parameters
	 * - Initialize any necessary resources
	 * - Set up the download environment
	 * - Not throw exceptions for initialization errors
	 */
	suspend fun initiateDownload()
	
	/**
	 * Begins the actual download operation.
	 * Implementations should:
	 * - Start the download process
	 * - Handle network operations
	 * - Update progress periodically
	 * - Manage file writing
	 * - Notify status changes
	 * - Handle errors gracefully
	 */
	suspend fun startDownload()

	/**
	 * Cancels an ongoing download operation.
	 *
	 * This function should be called when a download needs to be stopped due to
	 * either user intervention or an internal error. It ensures proper resource
	 * cleanup and consistent state management.
	 *
	 * @param cancelReason A human-readable message describing why the download was canceled.
	 *                     Useful for logging, debugging, and showing messages to the user.
	 * @param isCanceledByUser Indicates whether the cancellation was initiated by the user.
	 *                         This helps distinguish between user actions and system-triggered
	 *                         interruptions (e.g., network failures or file errors).
	 *
	 * Expected responsibilities of implementations:
	 * - Stop all active network transfers and background tasks.
	 * - Finalize or discard any partially written files to prevent corruption.
	 * - Update the download’s internal state and persistent storage to reflect cancellation.
	 * - Notify UI components, listeners, or observers about the cancellation event.
	 */
	suspend fun cancelDownload(cancelReason: String = "", isCanceledByUser: Boolean = false)

	/**
	 * Updates the status of the download task.
	 * @param statusInfo Optional detailed status message
	 * @param status The new status code (from DownloadStatus)
	 * Implementations should:
	 * - Update internal state
	 * - Validate status codes
	 * - Notify listeners of changes
	 * - Handle status transitions appropriately
	 */
	suspend fun updateDownloadStatus(
		statusInfo: String? = null,
		status: Int = downloadDataModel.status
	)
}