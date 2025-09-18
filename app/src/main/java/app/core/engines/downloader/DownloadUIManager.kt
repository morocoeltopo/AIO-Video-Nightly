package app.core.engines.downloader

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import app.core.AIOApp
import app.ui.main.fragments.downloads.fragments.active.ActiveTasksFragment
import app.ui.main.fragments.downloads.fragments.finished.FinishedTasksFragment
import com.aio.R
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility

/**
 * Manages the UI components related to download operations.
 *
 * This class handles:
 * - Creating and updating download item views
 * - Synchronizing UI with download states
 * - Coordinating between download system and UI fragments
 * - Thread-safe UI updates
 *
 * Works in conjunction with:
 * - ActiveTasksFragment (for in-progress downloads)
 * - FinishedTasksFragment (for completed downloads)
 * - DownloadSystem (for state information)
 */
class DownloadUIManager(val downloadSystem: DownloadSystem) {

	private val logger = LogHelperUtils.from(javaClass)

	// References to UI fragments
	var activeTasksFragment: ActiveTasksFragment? = null
	var finishedTasksFragment: FinishedTasksFragment? = null

	/**
	 * Redraws all active download UI elements.
	 *
	 * This method:
	 * 1. Clears all existing views
	 * 2. Recreates views for each active download
	 * 3. Maintains thread safety with @Synchronized
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
	 * Adds a new download item to the UI.
	 *
	 * @param downloadModel The download to display
	 * @param position Optional position to insert at (-1 for end)
	 */
	@Synchronized
	fun addNewActiveUI(downloadModel: DownloadDataModel, position: Int = -1) {
		logger.d("Adding new active UI for: ${downloadModel.fileName}, position=$position")
		ThreadsUtility.executeInBackground(codeBlock = {
			ThreadsUtility.executeOnMain(codeBlock = {
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
			})
		})
	}

	/**
	 * Updates an existing download item in the UI.
	 *
	 * @param downloadModel The download to update
	 */
	@Synchronized
	fun updateActiveUI(downloadModel: DownloadDataModel) {
		logger.d("Updating active UI for: ${downloadModel.fileName}")
		ThreadsUtility.executeInBackground(codeBlock = {
			ThreadsUtility.executeOnMain(codeBlock = {
				val activeDownloadsListContainer = activeTasksFragment?.activeTasksListContainer
				val resultedRow = activeDownloadsListContainer?.findViewById<View>(downloadModel.id)
				if (resultedRow != null) {
					logger.d("Found existing row, configuring UI for: ${downloadModel.fileName}")
					configureActiveUI(resultedRow, downloadModel)
				} else {
					logger.d("No existing row found for: ${downloadModel.fileName}")
				}
			})
		})
	}

	/**
	 * Creates a new view for a download item.
	 *
	 * @param downloadModel The download to create a view for
	 * @return Configured View ready for display
	 */
	@SuppressLint("InflateParams")
	private fun generateActiveUI(downloadModel: DownloadDataModel): View {
		logger.d("Generating UI for download model: ${downloadModel.fileName}")
		val inflater = LayoutInflater.from(AIOApp.INSTANCE)
		val rowUI = inflater.inflate(R.layout.frag_down_3_active_1_row_1, null)
		rowUI.apply {
			id = downloadModel.id
			isClickable = true
			setOnClickListener {
				logger.d("Row clicked for: ${downloadModel.fileName}")
				activeTasksFragment?.onDownloadUIItemClick(downloadModel)
			}
			setOnLongClickListener {
				logger.d("Row long-clicked for: ${downloadModel.fileName}")
				activeTasksFragment?.safeMotherActivityRef?.doSomeVibration(50)
				activeTasksFragment?.onDownloadUIItemClick(downloadModel); true
			}
			val dpValue = 0f
			val pixels = dpValue * context.resources.displayMetrics.density
			val layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
			layoutParams.apply { setMargins(0, 0, 0, pixels.toInt()) }
			this.layoutParams = layoutParams
		}; return rowUI
	}

	/**
	 * Removes a download item from the UI.
	 *
	 * @param downloadModel The download to remove
	 */
	@Synchronized
	fun removeActiveUI(downloadModel: DownloadDataModel) {
		logger.d("Removing active UI for: ${downloadModel.fileName}")
		val activeDownloadListContainer = activeTasksFragment?.activeTasksListContainer
		activeDownloadListContainer?.let {
			val resultedRow = activeDownloadListContainer.findViewById<View>(downloadModel.id)
			if (resultedRow != null) {
				logger.d("Found row to remove for: ${downloadModel.fileName}")
				if (resultedRow.parent != null) {
					val parent = resultedRow.parent as ViewGroup
					parent.removeView(resultedRow)
					logger.d("Removed row from parent view group")
				}

				activeDownloadListContainer.removeView(resultedRow)
				logger.d("Removed row from activeTasksListContainer")

				ThreadsUtility.executeInBackground(codeBlock = {
					ThreadsUtility.executeOnMain {
						val view = activeDownloadListContainer.findViewById<View>(downloadModel.id)
						if (view != null) {
							logger.d("Cleaning up remaining view for: ${downloadModel.fileName}")
							if (view.parent != null) {
								val parent = view.parent as ViewGroup
								parent.removeView(view)
							}; activeTasksFragment?.activeTasksListContainer?.removeView(view)
						}
					}
				})
			} else {
				logger.d("No row found to remove for: ${downloadModel.fileName}")
			}
		}
	}

	/**
	 * Configures an existing view with download data.
	 *
	 * @param rowUI The view to configure
	 * @param downloadModel The download data to display
	 */
	@Synchronized
	private fun configureActiveUI(rowUI: View, downloadModel: DownloadDataModel) {
		logger.d("Configuring UI for: ${downloadModel.fileName}")
		ThreadsUtility.executeInBackground(codeBlock = {
			ThreadsUtility.executeOnMain {
				if (rowUI.tag == null) {
					logger.d("Creating new DownloaderRowUI for: ${downloadModel.fileName}")
					rowUI.tag = DownloaderRowUI(rowUI)
				}
				(rowUI.tag as DownloaderRowUI).apply {
					logger.d("Updating DownloaderRowUI for: ${downloadModel.fileName}")
					updateView(downloadModel)
				}
			}
		})
	}
}