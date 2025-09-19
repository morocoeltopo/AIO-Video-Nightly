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

/**
 * A popup menu for handling history item options such as:
 * - Open in browser
 * - Share link
 * - Copy link
 * - Add to bookmarks
 * - Delete from history
 *
 * Uses [PopupBuilder] for UI, references to avoid memory leaks,
 * and logs actions via [LogHelperUtils].
 */
class HistoryOptionPopup(
	private val historyActivity: HistoryActivity?,
	private val historyModel: HistoryModel,
	private val listView: View
) {
	private val logger = LogHelperUtils.from(javaClass)
	private val safeHistoryActivityRef = WeakReference(historyActivity).get()
	private val safeHistoryListView = WeakReference(listView).get()
	private var popupBuilder: PopupBuilder? = null

	init {
		logger.d("Initializing HistoryOptionPopup for URL: ${historyModel.historyUrl}")
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

	/** Show the popup */
	fun show() {
		logger.d("Showing HistoryOptionPopup for ${historyModel.historyUrl}")
		popupBuilder?.show()
	}

	/** Close the popup */
	fun close() {
		logger.d("Closing HistoryOptionPopup")
		popupBuilder?.close()
	}

	/** Copy history link to clipboard */
	private fun copyHistoryToClipboard() {
		logger.d("Copying history URL to clipboard: ${historyModel.historyUrl}")
		copyTextToClipboard(safeHistoryActivityRef, historyModel.historyUrl)
		showToast(msgId = R.string.title_copied_url_to_clipboard)
	}

	/** Open history item in browser */
	private fun openHistoryInBrowser() {
		logger.d("Opening history URL in browser: ${historyModel.historyUrl}")
		safeHistoryActivityRef?.onHistoryItemClick(historyModel)
	}

	/** Delete history item from storage and clear cookies */
	private fun deleteHistoryFromLibrary() {
		try {
			logger.d("Deleting history item: ${historyModel.historyUrl}")
			aioHistory.getHistoryLibrary().remove(historyModel)
			safeHistoryActivityRef?.updateHistoryListAdapter()
			aioHistory.updateInStorage()

			val cookieManager = CookieManager.getInstance()
			cookieManager.removeAllCookies(null)
			cookieManager.flush()

			showToast(msgId = R.string.title_successful)
		} catch (error: Exception) {
			logger.d("Error deleting history: ${error.message}")
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.title_something_went_wrong)
		}
	}

	/** Share history link via Android share sheet */
	private fun shareHistoryLink() {
		try {
			logger.d("Sharing history URL: ${historyModel.historyUrl}")
			val shareIntent = Intent(ACTION_SEND).apply {
				putExtra(EXTRA_TEXT, historyModel.historyUrl)
				type = "text/plain"
			}

			val titleText = getText(R.string.title_share_with_others)
			safeHistoryActivityRef?.startActivity(createChooser(shareIntent, titleText))
		} catch (error: Exception) {
			logger.d("Error sharing history: ${error.message}")
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.title_something_went_wrong)
		}
	}

	/** Add history item as a bookmark */
	private fun addHistoryToBookmark() {
		try {
			logger.d("Adding history to bookmarks: ${historyModel.historyUrl}")
			val bookmarkModel = BookmarkModel().apply {
				bookmarkCreationDate = Date()
				bookmarkModifiedDate = Date()
				bookmarkUrl = normalizeEncodedUrl(historyModel.historyUrl)
				bookmarkName = historyModel.historyTitle.ifEmpty { getText(R.string.title_unknown) }
			}

			aioBookmark.getBookmarkLibrary().add(0, bookmarkModel)
			aioBookmark.updateInStorage()
			showToast(msgId = R.string.text_bookmark_saved)
		} catch (error: Exception) {
			logger.d("Error adding bookmark: ${error.message}")
			error.printStackTrace()
			safeHistoryActivityRef?.doSomeVibration(20)
			showToast(msgId = R.string.title_something_went_wrong)
		}
	}
}