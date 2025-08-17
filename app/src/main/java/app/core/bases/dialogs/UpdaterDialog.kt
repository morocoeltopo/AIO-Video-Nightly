package app.core.bases.dialogs

import android.view.View
import app.core.bases.BaseActivity
import com.aio.R
import lib.device.ShareUtility.openApkFile
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

class UpdaterDialog(private val baseActivity: BaseActivity?, private val latestVersionApkFile: File) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity to prevent memory leaks
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	// Dialog builder for creating and managing the tutorial dialog
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let { _ ->
			logger.d("Initializing FBDownloadGuide dialog")

			// Set up the dialog layout and properties
			dialogBuilder.setView(R.layout.dialog_new_version_updater_1)
			dialogBuilder.setCancelable(true)

			// Set up click listeners for dialog buttons
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("safeBaseActivityRef is null — cannot initialize FBDownloadGuide dialog")
	}

	/**
	 * Handles click events for dialog buttons.
	 * @param button The view that was clicked
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Button clicked with id=${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("Close button clicked in FBDownloadGuide")
				close()
				safeBaseActivityRef?.let { safeBaseActivityRef ->
					val authority = "${safeBaseActivityRef.packageName}.provider"
					openApkFile(safeBaseActivityRef, latestVersionApkFile, authority)
					return@let
				}; showToast(msgId = R.string.text_something_went_wrong)
			}
		}
	}

	/**
	 * Shows the Facebook download guide dialog if it's not already showing.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing FBDownloadGuide dialog")
			dialogBuilder.show()
		} else {
			logger.d("FBDownloadGuide dialog already showing — skipping show()")
		}
	}

	/**
	 * Closes the Facebook download guide dialog if it's currently showing.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing FBDownloadGuide dialog")
			dialogBuilder.close()
		} else {
			logger.d("FBDownloadGuide dialog already closed — skipping close()")
		}
	}
}