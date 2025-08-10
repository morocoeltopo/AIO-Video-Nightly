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
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

class BookmarkOptionPopup(
    private val bookmarksActivity: BookmarksActivity,
    private val bookmarkModel: BookmarkModel,
    private val listView: View
) : DefaultLifecycleObserver {

    private val safeBookmarksActivityRef = WeakReference(bookmarksActivity).get()
    private val safeBookmarkListViewRef = WeakReference(listView).get()
    private var popupBuilder: PopupBuilder? = null

    init {
        safeBookmarksActivityRef?.lifecycle?.addObserver(this)
        initializePopup()
    }

    fun show() {
        popupBuilder?.show()
    }

    fun close() {
        popupBuilder?.close()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        cleanup()
    }

    private fun initializePopup() {
        safeBookmarksActivityRef?.let { activityRef ->
            safeBookmarkListViewRef?.let { listViewRef ->
                popupBuilder = PopupBuilder(
                    activityInf = activityRef,
                    popupLayoutId = R.layout.activity_bookmarks_1_option_1,
                    popupAnchorView = listViewRef.findViewById(R.id.bookmark_url_open_indicator)
                ).apply { initializePopupButtons(getPopupView()) }
            }
        }
    }

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
        }
    }

    private fun closeAndCleanup(action: (() -> Unit)? = null) {
        close(); action?.invoke(); cleanup()
    }

    private fun copyBookmarkInClipboard() {
        safeBookmarksActivityRef?.let { activity ->
            copyTextToClipboard(activity, bookmarkModel.bookmarkUrl)
            showToast(msgId = R.string.text_copied_url_to_clipboard)
        }
    }

    private fun openBookmarkInBrowser() {
        safeBookmarksActivityRef?.onBookmarkClick(bookmarkModel)
    }

    private fun deleteBookmarkFromLibrary() {
        safeBookmarksActivityRef?.let { safeMotherActivityRef ->
            try {
                aioBookmark.getBookmarkLibrary().remove(bookmarkModel)
                aioBookmark.updateInStorage()
                safeMotherActivityRef.updateBookmarkListAdapter()
                showToast(msgId = R.string.title_successful)
            } catch (error: Exception) {
                safeMotherActivityRef.doSomeVibration(20)
                showToast(msgId = R.string.text_something_went_wrong)
            }
        }
    }

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
            } catch (error: Exception) {
                error.printStackTrace()
                safeMotherActivityRef.doSomeVibration(20)
                showToast(msgId = R.string.text_something_went_wrong)
            }
        }
    }

    private fun cleanup() {
        popupBuilder = null
    }
}