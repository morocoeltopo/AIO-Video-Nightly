package app.ui.main.fragments.browser.activities

import android.view.View
import android.widget.EditText
import app.core.AIOApp.Companion.aioBookmark
import app.core.engines.browser.history.HistoryModel
import com.aio.R
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

class UpdateHistoryDialog(
	private val historyActivity: HistoryActivity,
	private val onApply: (Boolean) -> Unit = {}
) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the activity */
	private val safeHistoryActivity = WeakReference(historyActivity).get()
	private var historyModel: HistoryModel? = null

	/** Lazily created dialog with input fields for editing bookmark name & URL */
	private val dialog by lazy {
		DialogBuilder(safeHistoryActivity).apply {
			setView(R.layout.dialog_browser_history_edit_1)
			setCancelable(true)

			view.apply {
				val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
				val editFieldName = findViewById<EditText>(R.id.edit_field_name)

				// Request focus for URL input field
				findViewById<View>(R.id.container_edit_field_url).setOnClickListener {
					logger.d("URL container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldUrl)
				}

				// Request focus for Name input field
				findViewById<View>(R.id.container_edit_field_name).setOnClickListener {
					logger.d("Name container clicked, requesting focus.")
					requestFocusOnEditTextField(editFieldName)
				}

				// Save / Apply button
				findViewById<View>(R.id.btn_dialog_positive_container).setOnClickListener {
					logger.d("Apply button clicked, validating inputs.")

					val enteredHistoryName = editFieldName.text.toString()
					if (enteredHistoryName.isEmpty()) {
						logger.d("Validation failed: bookmark name is empty.")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(msgId = R.string.text_bookmark_name_can_not_be_empty)
						return@setOnClickListener
					}

					val enteredHistoryUrl = editFieldUrl.text.toString()
					if (enteredHistoryUrl.isEmpty()) {
						logger.d("Validation failed: bookmark URL is empty.")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(msgId = R.string.text_bookmark_url_can_not_be_empty)
						return@setOnClickListener
					}

					if (!isValidURL(enteredHistoryUrl)) {
						logger.d("Validation failed: invalid bookmark URL = $enteredHistoryUrl")
						safeHistoryActivity?.doSomeVibration(50)
						showToast(msgId = R.string.text_bookmark_url_not_valid)
						return@setOnClickListener
					}

					// Apply changes to the bookmark model
					logger.d("Updating bookmark: name='$enteredHistoryName', url='$enteredHistoryUrl'")
					historyModel?.historyTitle = enteredHistoryName
					historyModel?.historyUrl = enteredHistoryUrl
					aioBookmark.updateInStorage()

					// Close dialog and notify caller
					close()
					onApply.invoke(true)
				}
			}
		}
	}

	/**
	 * Requests focus and shows keyboard for the given EditText field.
	 */
	private fun requestFocusOnEditTextField(editFieldUrl: EditText?) {
		logger.d("Requesting focus on EditText field: $editFieldUrl")
		editFieldUrl?.focusable
		editFieldUrl?.selectAll()
		showOnScreenKeyboard(safeHistoryActivity, editFieldUrl)
	}

	/**
	 * Displays the dialog for editing a given bookmark.
	 *
	 * @param historyModel The bookmark to edit.
	 */
	fun show(historyModel: HistoryModel) {
		try {
			logger.d("Preparing to show UpdateBookmarkDialog for: ${historyModel.historyUrl}")
			setBookmarkModel(historyModel)
			if (dialog.isShowing == false &&
				safeHistoryActivity?.isActivityRunning() == true
			) {
				logger.d("Showing UpdateBookmarkDialog.")
				dialog.show()
			}
		} catch (error: Exception) {
			logger.e("Can't show Bookmark Update Dialog:", error)
			safeHistoryActivity?.doSomeVibration(50)
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	/**
	 * Closes the dialog if visible.
	 */
	fun close() {
		try {
			if (dialog.isShowing &&
				safeHistoryActivity?.isActivityRunning() == true
			) {
				logger.d("Closing UpdateBookmarkDialog.")
				dialog.close()
			}
		} catch (error: Exception) {
			logger.e("Can't close bookmark update dialog:", error)
			safeHistoryActivity?.doSomeVibration(50)
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	/**
	 * Initializes the dialog fields with the provided bookmark data.
	 *
	 * @param historyModel Bookmark to edit
	 */
	fun setBookmarkModel(historyModel: HistoryModel) {
		logger.d("Setting bookmark model in dialog: ${historyModel.historyUrl}")
		this.historyModel = historyModel
		this.dialog.view.apply {
			val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
			val editFieldName = findViewById<EditText>(R.id.edit_field_name)
			editFieldName.setText(historyModel.historyTitle)
			editFieldUrl.setText(historyModel.historyUrl)
		}
	}
}