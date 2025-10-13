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
import lib.networks.URLUtilityKT.normalizeEncodedUrl
import lib.process.LogHelperUtils
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
	private val logger = LogHelperUtils.from(javaClass)
	private val safeHistoryActivityRef = WeakReference(historyActivity).get()
	private val safeHistoryListView = WeakReference(listView).get()

	private val historyUrl = historyModel.historyUrl
	private val somethingWentWrongResId = R.string.title_something_went_wrong
	private var popupBuilder: PopupBuilder? = null

	init {
		logger.d("Initializing HistoryOptionPopup for URL: $historyUrl")
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
							R.id.btn_edit_history to { close(); editHistoryInfo() },
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
		logger.d("Showing HistoryOptionPopup for $historyUrl")
		popupBuilder?.show()
	}

	fun close() {
		logger.d("Closing HistoryOptionPopup")
		popupBuilder?.close()
	}

	private fun copyHistoryToClipboard() {
		logger.d("Copying history URL to clipboard: $historyUrl")
		copyTextToClipboard(safeHistoryActivityRef, historyUrl)
		showToast(safeHistoryActivityRef, msgId = R.string.title_copied_url_to_clipboard)
	}

	private fun openHistoryInBrowser() {
		logger.d("Opening history URL in browser: $historyUrl")
		safeHistoryActivityRef?.onHistoryItemClick(historyModel)
	}

	private fun editHistoryInfo() {
		safeHistoryActivityRef?.let { activityRef ->
			try {
				UpdateHistoryDialog(
					historyActivity = activityRef,
					onApply = { result ->
						if (result) {
							activityRef.updateHistoryListAdapter()
							showToast(activityRef, msgId = R.string.title_successful)
							logger.d("History updated successfully: $historyUrl")
						} else {
							activityRef.doSomeVibration(50)
							showToast(activityRef, msgId = somethingWentWrongResId)
							logger.d("History update failed for: $historyUrl")
						}
					}).show(historyModel)
			} catch (error: Exception) {
				logger.e("Exception while editing history: ${error.message}", error)
				activityRef.doSomeVibration(50)
				showToast(activityRef, msgId = somethingWentWrongResId)
			}
		}
	}

	private fun deleteHistoryFromLibrary() {
		try {
			logger.d("Deleting history item: $historyUrl")
			aioHistory.getHistoryLibrary().remove(historyModel)
			safeHistoryActivityRef?.updateHistoryListAdapter()
			aioHistory.updateInStorage()

			val cookieManager = CookieManager.getInstance()
			cookieManager.removeAllCookies(null)
			cookieManager.flush()

			showToast(safeHistoryActivityRef, msgId = R.string.title_successful)
		} catch (error: Exception) {
			logger.e("Error deleting history: ${error.message}", error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}

	private fun shareHistoryLink() {
		try {
			logger.d("Sharing history URL: $historyUrl")
			val shareIntent = Intent(ACTION_SEND).apply {
				putExtra(EXTRA_TEXT, historyUrl)
				type = "text/plain"
			}

			val titleText = getText(R.string.title_share_with_others)
			safeHistoryActivityRef?.startActivity(createChooser(shareIntent, titleText))
		} catch (error: Exception) {
			logger.e("Error sharing history: ${error.message}", error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}

	private fun addHistoryToBookmark() {
		try {
			logger.d("Adding history to bookmarks: $historyUrl")
			val bookmarkModel = BookmarkModel().apply {
				bookmarkCreationDate = Date()
				bookmarkModifiedDate = Date()
				bookmarkUrl = normalizeEncodedUrl(historyUrl)
				val unknownTextString = getText(R.string.title_unknown)
				bookmarkName = historyModel.historyTitle.ifEmpty { unknownTextString }
			}

			aioBookmark.getBookmarkLibrary().add(0, bookmarkModel)
			aioBookmark.updateInStorage()
			showToast(safeHistoryActivityRef, msgId = R.string.title_bookmark_saved)
		} catch (error: Exception) {
			logger.e("Error adding bookmark: ${error.message}",error)
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(safeHistoryActivityRef, msgId = somethingWentWrongResId)
		}
	}
}