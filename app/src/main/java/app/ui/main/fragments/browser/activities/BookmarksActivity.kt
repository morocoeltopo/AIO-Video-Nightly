package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.view.View
import android.view.View.GONE
import android.widget.ListView
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.browser.bookmarks.BookmarkModel
import app.ui.main.MotherActivity.Companion.ACTIVITY_RESULT_KEY
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemClick
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemLongClick
import com.aio.R
import lib.process.CommonTimeUtils
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

class BookmarksActivity : BaseActivity(),
	AIOTimerListener, OnBookmarkItemClick, OnBookmarkItemLongClick {

	private val safeSelfReference = WeakReference(this)
	private val safeBookmarksActivityRef = safeSelfReference.get()

	private lateinit var emptyBookmarksIndicator: View
	private lateinit var bookmarkListView: ListView
	private lateinit var buttonLoadMoreBookmarks: View

	private var bookmarkOptionPopup: BookmarkOptionPopup? = null

	private val bookmarksAdapter by lazy {
		val activityRef = safeBookmarksActivityRef
		BookmarkAdapter(activityRef, activityRef, activityRef)
	}

	override fun onRenderingLayout(): Int {
		return R.layout.activity_bookmarks_1
	}

	override fun onAfterLayoutRender() {
		initializeViews()
		initializeViewsOnClickEvents()
	}

	override fun onResumeActivity() {
		super.onResumeActivity()
		registerToAIOTimer()
		updateBookmarkListAdapter()
	}

	override fun onPauseActivity() {
		super.onPauseActivity()
		unregisterToAIOTimer()
	}

	override fun onBackPressActivity() {
		closeActivityWithFadeAnimation(false)
	}

	override fun onDestroy() {
		unregisterToAIOTimer()
		bookmarkListView.adapter = null
		bookmarkOptionPopup?.close()
		bookmarkOptionPopup = null
		super.onDestroy()
	}

	override fun onAIOTimerTick(loopCount: Double) {
		updateLoadMoreButtonVisibility()
	}

	override fun onBookmarkClick(bookmarkModel: BookmarkModel) {
		openBookmarkInBrowser(bookmarkModel)
	}

	override fun onBookmarkLongClick(
		bookmarkModel: BookmarkModel,
		position: Int, listView: View
	) {
		safeBookmarksActivityRef?.let { safeBookmarkActivityRef ->
			try {
				bookmarkOptionPopup = null
				bookmarkOptionPopup = BookmarkOptionPopup(
					bookmarksActivity = safeBookmarkActivityRef,
					bookmarkModel = bookmarkModel,
					listView = listView
				).apply { show() }
			} catch (error: Exception) {
				error.printStackTrace()
				showToast(msgId = R.string.text_something_went_wrong)
			}
		}
	}

	override fun clearWeakActivityReference() {
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	private fun initializeViews() {
		safeBookmarksActivityRef?.let {
			emptyBookmarksIndicator = findViewById(R.id.empty_bookmarks_indicator)
			bookmarkListView = findViewById(R.id.list_bookmarks)
			buttonLoadMoreBookmarks = findViewById(R.id.btn_load_more_bookmarks)

			bookmarkListView.adapter = bookmarksAdapter
			bookmarkListView.visibility = GONE

			emptyBookmarksIndicator.visibility = GONE
			buttonLoadMoreBookmarks.visibility = GONE
			updateLoadMoreButtonVisibility()
		}
	}

	private fun initializeViewsOnClickEvents() {
		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener { onBackPressActivity() }

		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener { deleteAllBookmarks() }

		buttonLoadMoreBookmarks.setOnClickListener {
			bookmarksAdapter.loadMoreBookmarks()
			showToast(msgId = R.string.text_loaded_successfully)
		}
	}

	private fun deleteAllBookmarks() {
		MsgDialogUtils.getMessageDialog(
			baseActivityInf = safeBookmarksActivityRef,
			isTitleVisible = true,
			titleTextViewCustomize =
				{ it.setText(R.string.title_are_you_sure_about_this) },
			messageTxt = getText(R.string.text_delete_bookmarks_confirmation),
			isNegativeButtonVisible = false,
			positiveButtonTextCustomize = {
				it.setText(R.string.title_clear_now)
				it.setLeftSideDrawable(R.drawable.ic_button_clear)
			}
		)?.apply {
			setOnClickForPositiveButton {
				close()
				aioBookmark.getBookmarkLibrary().clear()
				aioBookmark.updateInStorage()
				bookmarksAdapter.resetBookmarkAdapter()
			}
		}?.show()
	}

	private fun registerToAIOTimer() {
		safeBookmarksActivityRef?.let { aioTimer.register(it) }
	}

	private fun unregisterToAIOTimer() {
		safeBookmarksActivityRef?.let { aioTimer.unregister(it) }
	}

	fun updateBookmarkListAdapter() {
		bookmarksAdapter.resetBookmarkAdapter()
		bookmarksAdapter.loadMoreBookmarks()
	}

	private fun updateLoadMoreButtonVisibility() {
		val bookmarkSize = aioBookmark.getBookmarkLibrary().size
		if (bookmarksAdapter.count >= bookmarkSize) hideView(buttonLoadMoreBookmarks, true)
		else showView(buttonLoadMoreBookmarks, true)

		if (bookmarksAdapter.isEmpty) showView(emptyBookmarksIndicator, true)
		else {
			hideView(emptyBookmarksIndicator, true).let {
				CommonTimeUtils.delay(500, object : OnTaskFinishListener {
					override fun afterDelay() {
						showView(bookmarkListView, true)
					}
				})
			}
		}
	}

	private fun openBookmarkInBrowser(bookmarkModel: BookmarkModel) {
		sendResultBackToBrowserFragment(bookmarkModel.bookmarkUrl)
		onBackPressActivity()
	}

	fun sendResultBackToBrowserFragment(result: String) {
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}
}