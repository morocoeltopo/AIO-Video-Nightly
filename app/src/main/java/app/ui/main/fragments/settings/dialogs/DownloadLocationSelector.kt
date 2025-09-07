package app.ui.main.fragments.settings.dialogs

import android.view.View
import android.widget.ImageView
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import com.aio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.files.FileSystemUtility
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView
import java.lang.ref.WeakReference

/**
 * Dialog class for selecting the default download location.
 *
 * Provides a UI to choose between:
 * - App's private folder
 * - System gallery
 *
 * The selected option is stored in [aioSettings.defaultDownloadLocation] and persisted.
 * If the dialog is canceled or dismissed without applying changes, the original setting is restored.
 *
 * @property baseActivity The activity context required for building the dialog.
 */
class DownloadLocationSelector(private val baseActivity: BaseActivity) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the base activity */
	private val safeBaseActivity = WeakReference(baseActivity).get()

	/** Flag to track whether user has applied a new setting */
	private var hasSettingApplied = false

	/** Store the original location to restore if changes are not applied */
	private val originalLocation = aioSettings.defaultDownloadLocation

	/**
	 * Lazily-initialized dialog for selecting download location.
	 *
	 * Provides options to choose the app's private folder or the system gallery,
	 * and applies the selected location only if the "Apply" button is pressed.
	 */
	private val dialog by lazy {
		logger.d("Initializing Download Location dialog")
		DialogBuilder(safeBaseActivity).apply {
			setView(R.layout.dialog_default_location_1)
			setCancelable(true)

			view.apply {
				val privateBtn = findViewById<View>(R.id.button_app_private_folder)
				val galleryBtn = findViewById<View>(R.id.btn_system_gallery)
				val applyBtn = findViewById<View>(R.id.btn_dialog_positive_container)
				val privateRadio = findViewById<ImageView>(R.id.btn_app_private_folder_radio)
				val galleryRadio = findViewById<ImageView>(R.id.button_system_gallery_radio)

				updateRadioButtons(privateRadio, galleryRadio)

				privateBtn.setOnClickListener {
					logger.d("User selected: Private Folder")
					aioSettings.defaultDownloadLocation = PRIVATE_FOLDER
					updateRadioButtons(privateRadio, galleryRadio)
				}

				galleryBtn.setOnClickListener {
					logger.d("User selected: System Gallery")
					if (FileSystemUtility.hasFullFileSystemAccess(INSTANCE)) {
						aioSettings.defaultDownloadLocation = SYSTEM_GALLERY
						updateRadioButtons(privateRadio, galleryRadio)
					} else {
						safeBaseActivity?.doSomeVibration(50)
						MsgDialogUtils.getMessageDialog(
							baseActivityInf = safeBaseActivity,
							titleText = getText(R.string.title_permission_needed),
							isTitleVisible = true,
							isNegativeButtonVisible = false,
							messageTextViewCustomize = {
								it.setText(R.string.text_app_dont_have_write_permission_msg)
							}
						)?.let { msgDialogBuilder ->
							msgDialogBuilder.setOnClickForPositiveButton {
								CoroutineScope(Dispatchers.Main).launch {
									msgDialogBuilder.close()
									delay(500)
									safeBaseActivity?.let {
										FileSystemUtility.openAllFilesAccessSettings(it)
									}
								}
							}
						}?.show()
					}
				}

				applyBtn.setOnClickListener {
					logger.d("Apply clicked, saving setting")
					hasSettingApplied = true
					aioSettings.updateInStorage()
					safeBaseActivity?.doSomeVibration(50)
					ToastView.showToast(msgId = R.string.text_setting_applied)
					close()
				}
			}

			dialog.setOnCancelListener {
				logger.d("Dialog cancelled, restoring original setting if needed")
				restoreIfNotApplied()
			}
			dialog.setOnDismissListener {
				logger.d("Dialog dismissed, restoring original setting if needed")
				restoreIfNotApplied()
			}
		}
	}

	/**
	 * Updates the radio button icons to reflect the current selection.
	 *
	 * @param privateRadio ImageView for the private folder radio icon.
	 * @param galleryRadio ImageView for the system gallery radio icon.
	 */
	private fun updateRadioButtons(privateRadio: ImageView, galleryRadio: ImageView) {
		val isPrivate = aioSettings.defaultDownloadLocation == PRIVATE_FOLDER
		logger.d("Updating radio buttons: current = ${if (isPrivate) "Private Folder" else "System Gallery"}")
		privateRadio.setImageResource(
			if (isPrivate) R.drawable.ic_button_checked_circle
			else R.drawable.ic_button_unchecked_circle
		)
		galleryRadio.setImageResource(
			if (isPrivate) R.drawable.ic_button_unchecked_circle
			else R.drawable.ic_button_checked_circle
		)
	}

	/**
	 * Restores the original download location if no setting was applied.
	 */
	private fun restoreIfNotApplied() {
		if (!hasSettingApplied) {
			logger.d("Restoring original location: $originalLocation")
			aioSettings.defaultDownloadLocation = originalLocation
			aioSettings.updateInStorage()
		} else {
			logger.d("Setting already applied, no restore needed")
		}
	}

	/**
	 * Shows the dialog if it is not already showing.
	 */
	fun show() = takeIf { !dialog.isShowing }?.run {
		logger.d("Showing dialog")
		dialog.show()
	}

	/**
	 * Closes the dialog if it is currently showing.
	 */
	fun close() = takeIf { dialog.isShowing }?.run {
		logger.d("Closing dialog")
		dialog.close()
	}
}
