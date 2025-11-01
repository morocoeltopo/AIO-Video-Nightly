package app.core.engines.downloader

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import app.core.AIOApp
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.fragments.active.ActiveTasksFragment
import app.ui.main.fragments.downloads.fragments.finished.FinishedTasksFragment
import com.aio.R
import lib.process.AsyncJobUtils
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility

/**
 * Responsible for managing and updating the user interface for download tasks.
 *
 * The `DownloadUIManager` acts as the bridge between the underlying download system
 * and the UI fragments displaying active and finished downloads. It ensures that
 * the UI remains synchronized with the current state of each download,
 * including progress, completion status, and user interactions.
 *
 * Responsibilities include:
 * 1. Creating and initializing UI rows for new downloads.
 * 2. Updating existing download UI elements as progress or status changes.
 * 3. Removing UI rows for downloads that are cancelled, completed, or deleted.
 * 4. Ensuring all UI updates are thread-safe, using background-to-main thread coordination.
 * 5. Handling user interactions such as clicks and long clicks on download rows.
 *
 * Collaborates with:
 * - [ActiveTasksFragment]: Displays downloads that are currently in progress.
 * - [FinishedTasksFragment]: Displays downloads that have completed.
 * - [DownloadSystem]: Provides the active and finished download data models.
 * - [ThreadsUtility]: Facilitates thread-safe updates to the UI.
 * - [LogHelperUtils]: Provides structured logging for debugging UI operations.
 *
 * Thread Safety:
 * All UI operations are executed in a synchronized manner and dispatched
 * appropriately between background and main threads to prevent race conditions.
 *
 * Usage Example:
 * ```
 * val uiManager = DownloadUIManager(downloadSystem)
 * uiManager.redrawEverything()   // Refresh all active download UI rows
 * uiManager.addNewActiveUI(downloadDataModel)   // Add a new download row
 * uiManager.updateActiveUI(downloadDataModel)   // Update an existing row
 * uiManager.removeActiveUI(downloadDataModel)   // Remove a completed or cancelled row
 * ```
 *
 * @property downloadSystem The core system that maintains download states and data models.
 * @author shiba prasad
 */
class DownloadUIManager(private val downloadSystem: DownloadSystem) {

	/**
	 * Logger utility to log debug, info, and error messages.
	 *
	 * Provides structured logging with class context for easier debugging.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Reference to the parent activity that hosts this manager.
	 *
	 * Used for UI interactions that require activity context,
	 * such as triggering vibrations or accessing system services.
	 */
	var safeMotherActivity: MotherActivity? = null

	/**
	 * Fragment responsible for displaying active (in-progress) download tasks.
	 *
	 * This fragment contains the container where active download rows are
	 * dynamically added, updated, or removed.
	 */
	var activeTasksFragment: ActiveTasksFragment? = null

	/**
	 * Fragment responsible for displaying finished (completed) download tasks.
	 *
	 * This fragment is updated when downloads complete or are removed from
	 * the active list.
	 */
	var finishedTasksFragment: FinishedTasksFragment? = null

	/**
	 * TextView displayed on the Home Screen (or startup screen)
	 * to indicate the progress of loading download models during
	 * a cold start of the application.
	 *
	 * Shows feedback such as "Loading X of Y downloads…" instead
	 * of a generic infinite loader to improve user experience.
	 */
	var loadingDownloadModelTextview: TextView? = null

	/**
	 * Redraws all active download items in the UI.
	 *
	 * This method clears the active tasks container and recreates UI rows for each
	 * active download from the download system. UI updates are performed on the main
	 * thread, while the method itself is thread-safe and executed asynchronously
	 * to avoid blocking the UI.
	 *
	 * Synchronization ensures that multiple redraw requests do not interfere
	 * with each other.
	 */
	@Synchronized
	fun redrawEverything() {
		logger.d("Redrawing all active download UI elements")
		ThreadsUtility.executeInBackground(codeBlock = {
			ThreadsUtility.executeOnMain(codeBlock = {
				logger.d("Clearing active tasks container")
				activeTasksFragment?.activeTasksListContainer?.removeAllViews()
				downloadSystem.activeDownloadDataModels.forEach { downloadDataModel ->
					logger.d("Adding UI for active download: ${downloadDataModel.fileName}")
					addNewActiveUI(downloadDataModel)
				}
			})
		})
	}

	/**
	 * Adds a new download item to the active downloads UI.
	 *
	 * This method generates a new row for the given download model and configures
	 * it with the current data. The row is either appended to the end of the container
	 * or inserted at the specified position. UI updates are executed on the main thread,
	 * while the method itself runs asynchronously in the background and is thread-safe.
	 *
	 * @param downloadModel The download model to display in the UI.
	 * @param position Optional position to insert the row (-1 to append at the end).
	 */
	@Synchronized
	fun addNewActiveUI(downloadModel: DownloadDataModel, position: Int = -1) {
		logger.d("Adding new active UI for: ${downloadModel.fileName}, position=$position")
		AsyncJobUtils.executeOnMainThread {
			val rowUI = generateActiveUI(downloadModel)
			logger.d("Generated new row UI for: ${downloadModel.fileName}")
			configureActiveUI(rowUI, downloadModel)
			val activeDownloadsListContainer = activeTasksFragment?.activeTasksListContainer
			if (position != -1) {
				logger.d("Inserting row at position: $position")
				activeDownloadsListContainer?.addView(rowUI, position)
			} else {
				logger.d("Appending row at end")
				activeDownloadsListContainer?.addView(rowUI)
			}
		}
	}

	/**
	 * Updates the UI for an existing active download item.
	 *
	 * This method searches for the view corresponding to the given download model.
	 * If found, it reconfigures the view to reflect the latest download data.
	 * The update runs asynchronously in the background but ensures UI changes
	 * occur on the main thread. Thread-safe via synchronization.
	 *
	 * @param downloadModel The download model whose UI needs updating.
	 */
	@Synchronized
	fun updateActiveUI(downloadModel: DownloadDataModel) {
		logger.d("Updating active UI for: ${downloadModel.fileName}")

		// Execute non-UI operations in a background thread
		AsyncJobUtils.executeOnMainThread {
			// Get the container holding all active download rows
			val activeDownloadsListContainer = activeTasksFragment?.activeTasksListContainer

			// Try to find the existing row corresponding to this download
			val resultedRow = activeDownloadsListContainer?.findViewById<View>(downloadModel.downloadId)

			if (resultedRow != null) {
				// Row exists: configure it with updated download data
				logger.d("Found existing row, configuring UI for: ${downloadModel.fileName}")
				configureActiveUI(resultedRow, downloadModel)
			} else {
				// Row does not exist: log info
				logger.d("No existing row found for: ${downloadModel.fileName}")
			}
		}
	}

	/**
	 * Creates and configures a new view representing an active download item.
	 *
	 * This method inflates the layout for a single download row and sets up
	 * the basic properties such as ID, clickability, click and long-click listeners,
	 * and layout margins. The view is prepared to be added to the active downloads container.
	 *
	 * @param downloadModel The download model for which to generate the UI row.
	 * @return A fully initialized View representing the download item.
	 */
	@SuppressLint("InflateParams")
	private fun generateActiveUI(downloadModel: DownloadDataModel): View {
		logger.d("Generating UI for download model: ${downloadModel.fileName}")

		// Inflate the row layout depending on whether the safeMotherActivity is available
		val rowUI = if (safeMotherActivity == null) {
			// Use application context when MotherActivity is null
			val inflater = LayoutInflater.from(AIOApp.INSTANCE)
			inflater.inflate(R.layout.frag_down_3_active_1_row_1, null)
		} else {
			// Wrap the activity with its theme to properly apply styles
			val themedCtx = ContextThemeWrapper(safeMotherActivity, R.style.style_application)
			val inflater = LayoutInflater.from(themedCtx)
			inflater.inflate(R.layout.frag_down_3_active_1_row_1, null)
		}

		rowUI.apply {
			// Assign unique ID to the row for easy reference later
			id = downloadModel.downloadId
			isClickable = true

			// Click listener to handle normal clicks
			setOnClickListener {
				logger.d("Row clicked for: ${downloadModel.fileName}")
				activeTasksFragment?.onDownloadUIItemClick(downloadModel)
			}

			// Long click listener to provide haptic feedback and trigger actions
			setOnLongClickListener {
				logger.d("Row long-clicked for: ${downloadModel.fileName}")
				activeTasksFragment?.safeMotherActivityRef?.doSomeVibration(50)
				activeTasksFragment?.onDownloadUIItemClick(downloadModel); true
			}

			// Calculate bottom margin (currently 0dp, can be adjusted if needed)
			val dpValue = 0f
			val pixels = dpValue * context.resources.displayMetrics.density
			val layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
			layoutParams.apply { setMargins(0, 0, 0, pixels.toInt()) }

			// Apply layout parameters to the row
			this.layoutParams = layoutParams
		}

		return rowUI
	}

	/**
	 * Removes the UI element corresponding to a specific active download.
	 *
	 * This method performs a safe removal of the download row:
	 * - Finds the row by download ID
	 * - Removes it from its parent ViewGroup if present
	 * - Ensures the row is fully removed from the activeTasksListContainer
	 * - Handles thread safety by using background and main thread execution
	 *
	 * Execution is synchronized to prevent race conditions during UI updates.
	 *
	 * @param downloadModel The download data model whose UI should be removed
	 */
	@Synchronized
	fun removeActiveUI(downloadModel: DownloadDataModel) {
		logger.d("Removing active UI for: ${downloadModel.fileName}")

		val activeDownloadListContainer = activeTasksFragment?.activeTasksListContainer

		activeDownloadListContainer?.let {
			// Attempt to find the row corresponding to this download
			val resultedRow = activeDownloadListContainer.findViewById<View>(downloadModel.downloadId)

			if (resultedRow != null) {
				logger.d("Found row to remove for: ${downloadModel.fileName}")

				// Remove from parent ViewGroup if it has one
				if (resultedRow.parent != null) {
					val parent = resultedRow.parent as ViewGroup
					parent.removeView(resultedRow)
					logger.d("Removed row from parent view group")
				}

				// Remove from the container
				activeDownloadListContainer.removeView(resultedRow)
				logger.d("Removed row from activeTasksListContainer")

				// Background task to ensure no leftover views remain
				ThreadsUtility.executeInBackground(codeBlock = {
					ThreadsUtility.executeOnMain {
						val view = activeDownloadListContainer.findViewById<View>(downloadModel.downloadId)
						if (view != null) {
							logger.d("Cleaning up remaining view for: ${downloadModel.fileName}")
							if (view.parent != null) {
								val parent = view.parent as ViewGroup
								parent.removeView(view)
							}
							activeTasksFragment?.activeTasksListContainer?.removeView(view)
						}
					}
				})
			} else {
				// No row found for this download ID
				logger.d("No row found to remove for: ${downloadModel.fileName}")
			}
		}
	}

	/**
	 * Configures or updates an existing UI row with the provided download data.
	 *
	 * This method ensures that each row representing a download is correctly
	 * initialized and reflects the current state of the download.
	 *
	 * Steps performed:
	 * 1. Checks if the row already has a DownloaderRowUI instance in its tag.
	 *    - If not, creates a new DownloaderRowUI to manage the row's child views.
	 * 2. Updates the DownloaderRowUI with the latest data from the DownloadDataModel.
	 * 3. Ensures thread-safe UI updates by executing background work first,
	 *    then switching to the main thread for view operations.
	 *
	 * @param rowUI The view representing a single download item
	 * @param downloadModel The data model containing the current download state
	 */
	@Synchronized
	private fun configureActiveUI(rowUI: View, downloadModel: DownloadDataModel) {
		logger.d("Configuring UI for: ${downloadModel.fileName}")

		// Execute on background thread to avoid blocking UI
		AsyncJobUtils.executeOnMainThread {
			if (rowUI.tag == null) {
				logger.d("Creating new DownloaderRowUI for: ${downloadModel.fileName}")
				rowUI.tag = DownloaderRowUIManager(rowUI) // Initialize row UI manager
			}

			// Update the row UI with the latest download data
			(rowUI.tag as DownloaderRowUIManager).apply {
				logger.d("Updating DownloaderRowUI for: ${downloadModel.fileName}")
				updateView(downloadModel)
			}
		}
	}
}