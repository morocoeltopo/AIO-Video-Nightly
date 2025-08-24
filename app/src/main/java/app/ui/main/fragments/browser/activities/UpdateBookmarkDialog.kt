package app.ui.main.fragments.browser.activities

import android.view.View
import android.widget.EditText
import app.core.AIOApp.Companion.aioBookmark
import app.core.engines.browser.bookmarks.BookmarkModel
import com.aio.R
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView
import java.lang.ref.WeakReference

class UpdateBookmarkDialog(
	private val bookmarkActivity: BookmarksActivity,
	private val onApply: (Boolean) -> Unit = {}
) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the base activity */
	private val safeBookmarkActivity = WeakReference(bookmarkActivity).get()
	private var bookmarkModel: BookmarkModel? = null

	private val dialog by lazy {
		DialogBuilder(safeBookmarkActivity).apply {
			setView(R.layout.dialog_bookmark_edit_1)
			setCancelable(true)

			view.apply {
				val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
				val editFieldName = findViewById<EditText>(R.id.edit_field_name)

				findViewById<View>(R.id.container_edit_field_url).setOnClickListener {
					requestFocusOnEditTextField(editFieldUrl)
				}

				findViewById<View>(R.id.container_edit_field_name).setOnClickListener {
					requestFocusOnEditTextField(editFieldName)
				}

				findViewById<View>(R.id.btn_dialog_positive_container).setOnClickListener {
					val enteredBookmarkName = editFieldName.text.toString()
					if (enteredBookmarkName.isEmpty()) {
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(msgId = R.string.text_bookmark_name_can_not_be_empty)
						return@setOnClickListener
					}

					val enteredBookmarkUrl = editFieldUrl.text.toString()
					if (enteredBookmarkUrl.isEmpty()) {
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(msgId = R.string.text_bookmark_url_can_not_be_empty)
						return@setOnClickListener
					}

					if (!isValidURL(enteredBookmarkUrl)) {
						safeBookmarkActivity?.doSomeVibration(50)
						ToastView.showToast(msgId = R.string.text_bookmark_url_not_valid)
						return@setOnClickListener
					}

					bookmarkModel?.bookmarkName = enteredBookmarkName
					bookmarkModel?.bookmarkUrl = enteredBookmarkUrl
					aioBookmark.updateInStorage()
					close()
					onApply.invoke(true)
				}
			}
		}
	}

	private fun requestFocusOnEditTextField(editFieldUrl: EditText?) {
		editFieldUrl?.focusable
		editFieldUrl?.selectAll()
		showOnScreenKeyboard(safeBookmarkActivity, editFieldUrl)
	}

	fun show(bookmarkModel: BookmarkModel) {
		try {
			setBookmarkModel(bookmarkModel)
			if (dialog.isShowing == false &&
				safeBookmarkActivity?.isActivityRunning() == true
			) dialog.show()
		} catch (error: Exception) {
			logger.e("Can't show Bookmark Update Dialog:", error)
			safeBookmarkActivity?.doSomeVibration(50)
			ToastView.showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	fun close() {
		try {
			if (dialog.isShowing &&
				safeBookmarkActivity?.isActivityRunning() == true
			) dialog.close()
		} catch (error: Exception) {
			logger.e("Can't close bookmark update dialog:", error)
			safeBookmarkActivity?.doSomeVibration(50)
			ToastView.showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	fun setBookmarkModel(bookmarkModel: BookmarkModel) {
		this.bookmarkModel = bookmarkModel
		this.dialog.view.apply {
			val editFieldUrl = findViewById<EditText>(R.id.edit_field_url)
			val editFieldName = findViewById<EditText>(R.id.edit_field_name)
			editFieldName.setText(bookmarkModel.bookmarkName)
			editFieldUrl.setText(bookmarkModel.bookmarkUrl)
		}
	}
}