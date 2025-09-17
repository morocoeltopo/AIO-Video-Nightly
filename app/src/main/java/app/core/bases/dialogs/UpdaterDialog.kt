package app.core.bases.dialogs

import android.text.Html
import android.text.Html.FROM_HTML_MODE_COMPACT
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import app.core.bases.BaseActivity
import app.core.engines.updater.AIOUpdater.UpdateInfo
import com.aio.R
import lib.device.ShareUtility.openApkFile
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

/**
 * A dialog that notifies the user when a new version of the app is available
 * and provides an option to install it.
 *
 * This dialog shows:
 * - The latest version available.
 * - The currently installed version.
 * - A changelog link (clickable).
 *
 * When the user accepts, the downloaded APK is opened for installation.
 *
 * @param baseActivity The hosting [BaseActivity], held weakly to prevent leaks.
 * @param latestVersionApkFile The downloaded APK file for the latest version.
 * @param versionInfo Metadata about the new update (version, changelog URL, etc.).
 */
class UpdaterDialog(
	private val baseActivity: BaseActivity?,
	private val latestVersionApkFile: File,
	private val versionInfo: UpdateInfo
) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity to prevent memory leaks
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	// Builder for creating and managing the update dialog
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let {
			logger.d("Initializing UpdaterDialog")

			// Configure dialog layout
			dialogBuilder.setView(R.layout.dialog_new_version_updater_1)
			dialogBuilder.setCancelable(false)

			// Populate message text with styled HTML and enable clickable links
			dialogBuilder.view.apply {
				findViewById<TextView>(R.id.txt_dialog_message).let { textView ->
					val htmlMsg = """
                        <b>Latest version:</b> ${versionInfo.latestVersion}                   
                    """.trimIndent()

					textView.text = Html.fromHtml(htmlMsg, FROM_HTML_MODE_COMPACT)
					textView.movementMethod = LinkMovementMethod.getInstance()
				}
			}

			// Setup button click listeners
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("UpdaterDialog initialization skipped — activity reference is null")
	}

	/**
	 * Handles click events for dialog buttons.
	 *
	 * @param button The view that was clicked.
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Button clicked with id=${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("Positive button clicked — attempting to install update")
				close()
				safeBaseActivityRef?.let { activity ->
					val authority = "${activity.packageName}.provider"
					openApkFile(activity, latestVersionApkFile, authority)
				} ?: showToast(msgId = R.string.title_something_went_wrong)
			}
		}
	}

	/**
	 * Shows the update dialog if it's not already visible.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing UpdaterDialog")
			dialogBuilder.show()
		} else {
			logger.d("UpdaterDialog already showing — skipping show()")
		}
	}

	/**
	 * Closes the update dialog if it's currently visible.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing UpdaterDialog")
			dialogBuilder.close()
		} else {
			logger.d("UpdaterDialog already closed — skipping close()")
		}
	}
}
