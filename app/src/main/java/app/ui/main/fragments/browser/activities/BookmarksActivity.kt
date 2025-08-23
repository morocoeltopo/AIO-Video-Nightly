package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.view.View
import android.view.View.GONE
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import androidx.core.view.isVisible
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOKeyStrings.ACTIVITY_RESULT_KEY
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.browser.bookmarks.BookmarkModel
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemClick
import app.ui.main.fragments.browser.activities.BookmarkAdapter.OnBookmarkItemLongClick
import com.aio.R
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.hideOnScreenKeyboard
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * BookmarksActivity manages the list of saved browser bookmarks.
 *
 * Responsibilities:
 * - Displays bookmarks in a ListView using [BookmarkAdapter].
 * - Handles user interactions: click to open, long-click for options.
 * - Supports deleting all bookmarks.
 * - Integrates with AIOTimer to update the UI periodically.
 */
class BookmarksActivity : BaseActivity(),
	AIOTimerListener, OnBookmarkItemClick, OnBookmarkItemLongClick {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to self, prevents memory leaks.
	private val safeSelfReference = WeakReference(this)
	private val safeBookmarksActivityRef = safeSelfReference.get()

	// UI elements
	private lateinit var buttonActionbarSearch: View
	private lateinit var buttonActionbarSearchImg: ImageView
	private lateinit var containerSearchLayout: View
	private lateinit var containerSearchEditField: View
	private lateinit var editTextSearchField: EditText
	private lateinit var buttonSearchAction: View
	private lateinit var emptyBookmarksIndicator: View
	private lateinit var bookmarkListView: ListView
	private lateinit var buttonLoadMoreBookmarks: View

	// Popup for bookmark item options
	private var bookmarkOptionPopup: BookmarkOptionPopup? = null

	// Adapter that manages bookmarks display
	private val bookmarksAdapter by lazy {
		val activityRef = safeBookmarksActivityRef
		BookmarkAdapter(activityRef, activityRef, activityRef)
	}

	/** Layout resource for this activity */
	override fun onRenderingLayout(): Int {
		logger.d("onRenderingLayout: inflating activity_bookmarks_1")
		return R.layout.activity_bookmarks_1
	}

	/** Called after layout is rendered; initializes views and click events */
	override fun onAfterLayoutRender() {
		logger.d("onAfterLayoutRender: initializing views")
		initializeViews()
		initializeViewsOnClickEvents()
	}

	/** Lifecycle: resume activity */
	override fun onResumeActivity() {
		super.onResumeActivity()
		logger.d("onResumeActivity: registering timer and updating adapter")
		registerToAIOTimer()
		updateBookmarkListAdapter()
	}

	/** Lifecycle: pause activity */
	override fun onPauseActivity() {
		super.onPauseActivity()
		logger.d("onPauseActivity: unregistering timer")
		unregisterToAIOTimer()
	}

	/** Handle back press */
	override fun onBackPressActivity() {
		logger.d("onBackPressActivity: closing with fade animation")
		closeActivityWithFadeAnimation(false)
	}

	/** Cleanup when activity is destroyed */
	override fun onDestroy() {
		logger.d("onDestroy: cleaning up resources")
		unregisterToAIOTimer()
		bookmarkListView.adapter = null
		bookmarkOptionPopup?.close()
		bookmarkOptionPopup = null
		super.onDestroy()
	}

	/** Called by AIOTimer periodically */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("onAIOTimerTick: loopCount=$loopCount -> updating UI")
		updateLoadMoreButtonVisibility()
	}

	/** User clicks on a bookmark */
	override fun onBookmarkClick(bookmarkModel: BookmarkModel) {
		logger.d("onBookmarkClick: opening bookmark ${bookmarkModel.bookmarkUrl}")
		openBookmarkInBrowser(bookmarkModel)
	}

	/** User long-clicks on a bookmark */
	override fun onBookmarkLongClick(
		bookmarkModel: BookmarkModel,
		position: Int, listView: View
	) {
		logger.d("onBookmarkLongClick: showing options for ${bookmarkModel.bookmarkUrl}")
		safeBookmarksActivityRef?.let { safeBookmarkActivityRef ->
			try {
				bookmarkOptionPopup = BookmarkOptionPopup(
					bookmarksActivity = safeBookmarkActivityRef,
					bookmarkModel = bookmarkModel,
					listView = listView
				).apply { show() }
			} catch (error: Exception) {
				logger.d("onBookmarkLongClick: error ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_something_went_wrong)
			}
		}
	}

	/** Clears weak self-reference */
	override fun clearWeakActivityReference() {
		logger.d("clearWeakActivityReference: clearing reference")
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	/** Initialize UI components */
	private fun initializeViews() {
		logger.d("initializeViews: setting up UI components")
		safeBookmarksActivityRef?.let {
			buttonActionbarSearch = findViewById(R.id.btn_actionbar_search)
			buttonActionbarSearchImg = findViewById(R.id.btn_actionbar_search_img)
			containerSearchLayout = findViewById(R.id.top_searchbar_container)
			editTextSearchField = findViewById<EditText>(R.id.edit_search_keywords_text)
			containerSearchEditField = findViewById(R.id.edit_search_keywords_container)
			containerSearchLayout.visibility = GONE

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

	/** Attach listeners to buttons */
	private fun initializeViewsOnClickEvents() {
		logger.d("initializeViewsOnClickEvents: attaching listeners")
		buttonActionbarSearch.setOnClickListener { toggleSearchEditFieldVisibility() }

		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener { onBackPressActivity() }

		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener { deleteAllBookmarks() }

		buttonLoadMoreBookmarks.setOnClickListener {
			logger.d("Load More button clicked")
			bookmarksAdapter.loadMoreBookmarks()
			showToast(msgId = R.string.text_loaded_successfully)
		}
	}

	/** Show search edit field on search icon button click */
	private fun toggleSearchEditFieldVisibility() {
		if (containerSearchLayout.isVisible) {
			hideView(containerSearchLayout, true)
			hideOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
			buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_search)
		} else {
			buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_cancel)
			showView(containerSearchLayout, true)
			delay(200, object : OnTaskFinishListener {
				override fun afterDelay() =
					showOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
			})
		}
	}

	/** Show confirmation dialog to delete all bookmarks */
	private fun deleteAllBookmarks() {
		logger.d("deleteAllBookmarks: showing confirmation dialog")
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
				logger.d("deleteAllBookmarks: user confirmed delete")
				close()
				aioBookmark.getBookmarkLibrary().clear()
				aioBookmark.updateInStorage()
				bookmarksAdapter.resetBookmarkAdapter()
			}
		}?.show()
	}

	/** Register this activity to AIOTimer */
	private fun registerToAIOTimer() {
		logger.d("registerToAIOTimer: registering activity")
		safeBookmarksActivityRef?.let { aioTimer.register(it) }
	}

	/** Unregister this activity from AIOTimer */
	private fun unregisterToAIOTimer() {
		logger.d("unregisterToAIOTimer: unregistering activity")
		safeBookmarksActivityRef?.let { aioTimer.unregister(it) }
	}

	/** Refresh bookmarks adapter with new data */
	fun updateBookmarkListAdapter() {
		logger.d("updateBookmarkListAdapter: resetting and loading bookmarks")
		bookmarksAdapter.resetBookmarkAdapter()
		bookmarksAdapter.loadMoreBookmarks()
	}

	/** Show or hide "Load More" button and empty view indicator */
	private fun updateLoadMoreButtonVisibility() {
		val bookmarkSize = aioBookmark.getBookmarkLibrary().size
		logger.d("updateLoadMoreButtonVisibility: adapter=${bookmarksAdapter.count}, total=$bookmarkSize")

		if (bookmarksAdapter.count >= bookmarkSize) hideView(buttonLoadMoreBookmarks, true)
		else showView(buttonLoadMoreBookmarks, true)

		if (bookmarksAdapter.isEmpty) {
			showView(emptyBookmarksIndicator, true)
		} else {
			hideView(emptyBookmarksIndicator, true).let {
				delay(500, object : OnTaskFinishListener {
					override fun afterDelay() {
						showView(bookmarkListView, true)
					}
				})
			}
		}
	}

	/** Open bookmark in browser and close activity */
	private fun openBookmarkInBrowser(bookmarkModel: BookmarkModel) {
		logger.d("openBookmarkInBrowser: sending result back for ${bookmarkModel.bookmarkUrl}")
		sendResultBackToBrowserFragment(bookmarkModel.bookmarkUrl)
		onBackPressActivity()
	}

	/** Send selected bookmark URL back to the calling fragment */
	fun sendResultBackToBrowserFragment(result: String) {
		logger.d("sendResultBackToBrowserFragment: result=$result")
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}
}