package app.core.bases.dialogs

import android.text.Html
import android.text.Html.FROM_HTML_MODE_COMPACT
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import app.core.bases.BaseActivity
import app.core.engines.updater.AIOUpdater.UpdateInfo
import com.aio.R
import lib.device.AppVersionUtility
import lib.device.ShareUtility.openApkFile
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

class UpdaterDialog(
	private val baseActivity: BaseActivity?,
	private val latestVersionApkFile: File,
	private val versionInfo: UpdateInfo
) {
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

			dialogBuilder.view.apply {
				findViewById<TextView>(R.id.txt_dialog_message).let {
					val htmlMsg = """
								<b>Latest version:</b> ${versionInfo.latestVersion}<br/>
								<b>Current version:</b> ${AppVersionUtility.versionName}<br/>
								<hr/>
								A new update for <b>AIO Video Downloader</b> is available! 🚀<br/><br/>
								This update includes important bug fixes, performance improvements, and exciting new features.<br/><br/>
								👉 You can read the full changelog <a href="${versionInfo.changelogUrl}">here</a>.
							""".trimIndent()
					val spannedText = Html.fromHtml(htmlMsg, FROM_HTML_MODE_COMPACT)

					// Set the styled text and enable links
					it.text = spannedText
					it.movementMethod = LinkMovementMethod.getInstance()
				}
			}

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