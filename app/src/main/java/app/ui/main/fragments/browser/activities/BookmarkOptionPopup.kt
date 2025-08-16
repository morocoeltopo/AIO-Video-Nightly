package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_TEXT
import android.content.Intent.createChooser
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.core.AIOApp.Companion.aioBookmark
import app.core.engines.browser.bookmarks.BookmarkModel
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * [BookmarkOptionPopup] provides a contextual popup menu for bookmark items.
 *
 * The popup allows the user to:
 * - Open the bookmark in the browser
 * - Share the bookmark link
 * - Copy the bookmark URL to clipboard
 * - Delete the bookmark from the library
 *
 * This class also observes the lifecycle of the parent activity to ensure
 * proper cleanup of resources when destroyed.
 */
class BookmarkOptionPopup(
	private val bookmarksActivity: BookmarksActivity,
	private val bookmarkModel: BookmarkModel,
	private val listView: View
) : DefaultLifecycleObserver {

	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to the activity hosting the bookmarks */
	private val safeBookmarksActivityRef = WeakReference(bookmarksActivity).get()

	/** Weak reference to the bookmark list view item */
	private val safeBookmarkListViewRef = WeakReference(listView).get()

	/** Holds the popup builder instance */
	private var popupBuilder: PopupBuilder? = null

	init {
		safeBookmarksActivityRef?.lifecycle?.addObserver(this)
		initializePopup()
		logger.d("BookmarkOptionPopup initialized for: ${bookmarkModel.bookmarkUrl}")
	}

	/** Show the popup menu */
	fun show() {
		popupBuilder?.show()
		logger.d("Popup shown for bookmark: ${bookmarkModel.bookmarkUrl}")
	}

	/** Close the popup menu */
	fun close() {
		popupBuilder?.close()
		logger.d("Popup closed for bookmark: ${bookmarkModel.bookmarkUrl}")
	}

	/** Cleanup when lifecycle owner is destroyed */
	override fun onDestroy(owner: LifecycleOwner) {
		logger.d("Lifecycle destroyed, cleaning up popup resources")
		cleanup()
	}

	/** Initializes the popup UI and attaches it to the bookmark item */
	private fun initializePopup() {
		safeBookmarksActivityRef?.let { activityRef ->
			safeBookmarkListViewRef?.let { listViewRef ->
				popupBuilder = PopupBuilder(
					activityInf = activityRef,
					popupLayoutId = R.layout.activity_bookmarks_1_option_1,
					popupAnchorView = listViewRef.findViewById(R.id.bookmark_url_open_indicator)
				).apply { initializePopupButtons(getPopupView()) }
				logger.d("Popup initialized for bookmark: ${bookmarkModel.bookmarkUrl}")
			}
		}
	}

	/** Configures popup buttons with their corresponding click actions */
	private fun initializePopupButtons(popupView: View?) {
		popupView?.apply {
			mapOf(
				R.id.btn_open_bookmark to ::openBookmarkInBrowser,
				R.id.btn_share_bookmark to ::shareBookmarkLink,
				R.id.btn_copy_bookmark to ::copyBookmarkInClipboard,
				R.id.btn_delete_bookmark to ::deleteBookmarkFromLibrary
			).forEach { (id, action) ->
				findViewById<View>(id).setOnClickListener { closeAndCleanup { action() } }
			}
			logger.d("Popup buttons initialized")
		}
	}

	/** Helper to close popup, perform action, and cleanup */
	private fun closeAndCleanup(action: (() -> Unit)? = null) {
		close()
		action?.invoke()
		cleanup()
		logger.d("Popup cleaned up after action")
	}

	/** Copies bookmark URL to clipboard */
	private fun copyBookmarkInClipboard() {
		safeBookmarksActivityRef?.let { activity ->
			copyTextToClipboard(activity, bookmarkModel.bookmarkUrl)
			showToast(msgId = R.string.text_copied_url_to_clipboard)
			logger.d("Copied bookmark to clipboard: ${bookmarkModel.bookmarkUrl}")
		}
	}

	/** Opens the bookmark in the browser */
	private fun openBookmarkInBrowser() {
		safeBookmarksActivityRef?.onBookmarkClick(bookmarkModel)
		logger.d("Opened bookmark in browser: ${bookmarkModel.bookmarkUrl}")
	}

	/** Deletes the bookmark from the library */
	private fun deleteBookmarkFromLibrary() {
		safeBookmarksActivityRef?.let { safeMotherActivityRef ->
			try {
				aioBookmark.getBookmarkLibrary().remove(bookmarkModel)
				aioBookmark.updateInStorage()
				safeMotherActivityRef.updateBookmarkListAdapter()
				showToast(msgId = R.string.title_successful)
				logger.d("Deleted bookmark: ${bookmarkModel.bookmarkUrl}")
			} catch (error: Exception) {
				safeMotherActivityRef.doSomeVibration(20)
				showToast(msgId = R.string.text_something_went_wrong)
				logger.d("Failed to delete bookmark: ${error.message}")
			}
		}
	}

	/** Shares the bookmark link via system share intent */
	private fun shareBookmarkLink() {
		safeBookmarksActivityRef?.let { safeMotherActivityRef ->
			try {
				val bookmarkUrl = bookmarkModel.bookmarkUrl
				val shareIntent = Intent().apply {
					action = ACTION_SEND
					putExtra(EXTRA_TEXT, bookmarkUrl)
					type = "text/plain"
				}
				val titleString = getText(R.string.title_share_with_others)
				val intentChooser = createChooser(shareIntent, titleString)
				safeMotherActivityRef.startActivity(intentChooser)
				logger.d("Shared bookmark link: $bookmarkUrl")
			} catch (error: Exception) {
				error.printStackTrace()
				safeMotherActivityRef.doSomeVibration(20)
				showToast(msgId = R.string.text_something_went_wrong)
				logger.d("Failed to share bookmark: ${error.message}")
			}
		}
	}

	/** Cleans up popup resources */
	private fun cleanup() {
		popupBuilder = null
		logger.d("Popup resources cleaned up")
	}
}
