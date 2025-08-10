package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_TEXT
import android.content.Intent.createChooser
import android.view.View
import android.webkit.CookieManager
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioHistory
import app.core.engines.browser.bookmarks.BookmarkModel
import app.core.engines.browser.history.HistoryModel
import com.aio.R
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference
import java.util.Date

class HistoryOptionPopup(
	private val historyActivity: HistoryActivity?,
	private val historyModel: HistoryModel,
	private val listView: View
) {
	private val safeHistoryActivityRef = WeakReference(historyActivity).get()
	private val safeHistoryListView = WeakReference(listView).get()
	private var popupBuilder: PopupBuilder? = null

	init {
		safeHistoryActivityRef?.let { activityRef ->
			safeHistoryListView?.let { listViewRef ->
				popupBuilder = PopupBuilder(
					activityInf = activityRef,
					popupLayoutId = R.layout.activity_browser_history_1_option_1,
					popupAnchorView = listViewRef.findViewById(R.id.img_open_indicator)
				)

				popupBuilder?.let { builder ->
					builder.getPopupView().apply {
						val buttonActions = mapOf(
							R.id.btn_open_history to { close(); openHistoryInBrowser() },
							R.id.btn_share_history to { close(); shareHistoryLink() },
							R.id.btn_copy_history to { close(); copyHistoryToClipboard() },
							R.id.btn_add_to_bookmark to { close(); addHistoryToBookmark() },
							R.id.btn_delete_history to { close(); deleteHistoryFromLibrary() }
						)

						buttonActions.forEach { (id, action) ->
							findViewById<View>(id).setOnClickListener { action() }
						}
					}
				}
			}
		}
	}

	fun show() {
		popupBuilder?.show()
	}

	fun close() {
		popupBuilder?.close()
	}

	private fun copyHistoryToClipboard() {
		copyTextToClipboard(safeHistoryActivityRef, historyModel.historyUrl)
		showToast(msgId = R.string.text_copied_url_to_clipboard)
	}

	private fun openHistoryInBrowser() {
		safeHistoryActivityRef?.onHistoryItemClick(historyModel)
	}

	private fun deleteHistoryFromLibrary() {
		try {
			aioHistory.getHistoryLibrary().remove(historyModel)
			safeHistoryActivityRef?.updateHistoryListAdapter()
			aioHistory.updateInStorage()

			val cookieManager = CookieManager.getInstance()
			cookieManager.removeAllCookies(null)
			cookieManager.flush()

			showToast(msgId = R.string.title_successful)
		} catch (error: Exception) {
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	private fun shareHistoryLink() {
		try {
			val shareIntent = Intent(ACTION_SEND).apply {
				putExtra(EXTRA_TEXT, historyModel.historyUrl)
				type = "text/plain"
			}

			val titleText = getText(R.string.title_share_with_others)
			safeHistoryActivityRef?.startActivity(createChooser(shareIntent, titleText))
		} catch (error: Exception) {
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	private fun addHistoryToBookmark() {
		try {
			val bookmarkModel = BookmarkModel().apply {
				bookmarkCreationDate = Date()
				bookmarkModifiedDate = Date()
				bookmarkUrl = historyModel.historyUrl
				bookmarkName = historyModel.historyTitle.ifEmpty { getText(R.string.title_unknown) }
			}

			aioBookmark.getBookmarkLibrary().add(0, bookmarkModel)
			aioBookmark.updateInStorage()
			showToast(msgId = R.string.text_bookmark_saved)
		} catch (error: Exception) {
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}
}