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
 * Displays a dialog prompting the user to install a new version of the app.
 *
 * The dialog provides:
 * - The **latest available version** of the app.
 * - The **currently installed version** (if included in [UpdateInfo]).
 * - A **clickable changelog link**.
 * - An **Install** button that opens the downloaded APK file.
 *
 * This dialog uses a [DialogBuilder] and a weak reference to the hosting
 * [BaseActivity] to avoid memory leaks.
 *
 * @property baseActivity The activity that hosts the dialog. Stored as a weak reference.
 * @property latestVersionApkFile The APK file for the new version to be installed.
 * @property versionInfo Metadata for the update (latest version string, changelog URL, etc.).
 */
class UpdaterDialog(
	private val baseActivity: BaseActivity?,
	private val latestVersionApkFile: File,
	private val versionInfo: UpdateInfo
) {
	/** Logger for debug and error messages. */
	private val logger = LogHelperUtils.from(javaClass)

	/** A safe reference to the parent activity to avoid leaks. */
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	/** Builder instance used to create and manage the dialog UI. */
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let { activity ->
			logger.d("Initializing UpdaterDialog")

			// Set up dialog UI
			dialogBuilder.setView(R.layout.dialog_new_version_updater_1)
			dialogBuilder.setCancelable(false)

			// Populate message text with version info
			dialogBuilder.view.apply {
				findViewById<TextView>(R.id.txt_dialog_message)?.let { textView ->
					val htmlMsg = activity.getString(
						/* resId = */ R.string.title_b_latest_version_b,
						/* ...formatArgs = */ versionInfo.latestVersion
					).trimIndent()

					textView.text = Html.fromHtml(htmlMsg, FROM_HTML_MODE_COMPACT)
					textView.movementMethod = LinkMovementMethod.getInstance()
				}
			}

			// Attach button click handler
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("UpdaterDialog initialization skipped — activity reference is null")
	}

	/**
	 * Handles user interactions with dialog buttons.
	 *
	 * @param button The clicked button view.
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
				} ?: showToast(activityInf = baseActivity, msgId = R.string.title_something_went_wrong)
			}
		}
	}

	/**
	 * Displays the dialog if it is not already visible.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing UpdaterDialog")
			dialogBuilder.show()
		} else {
			logger.d("UpdaterDialog already visible — skipping show()")
		}
	}

	/**
	 * Dismisses the dialog if it is currently visible.
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
