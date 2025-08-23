package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
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
 * BookmarksActivity manages the display and interaction with browser bookmarks.
 *
 * This activity provides a user interface for viewing, searching, and managing
 * saved browser bookmarks. It displays bookmarks in a list format and allows
 * users to open bookmarks, delete them, or perform bulk operations.
 */
class BookmarksActivity : BaseActivity(),
	AIOTimerListener, OnBookmarkItemClick, OnBookmarkItemLongClick {

	// Logger instance for tracking activity events
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to prevent memory leaks in timer callbacks
	private val safeSelfReference = WeakReference(this)
	private val safeBookmarksActivityRef = safeSelfReference.get()

	// UI components
	private lateinit var buttonActionbarSearch: View
	private lateinit var buttonActionbarSearchImg: ImageView
	private lateinit var containerSearchLayout: View
	private lateinit var containerSearchEditField: View
	private lateinit var editTextSearchField: EditText
	private lateinit var buttonSearchAction: View
	private lateinit var emptyBookmarksIndicator: View
	private lateinit var bookmarkListView: ListView
	private lateinit var buttonLoadMoreBookmarks: View

	// Popup menu for bookmark options on long press
	private var bookmarkOptionPopup: BookmarkOptionPopup? = null

	// Lazy initialized adapter for managing bookmark display
	private val bookmarksAdapter by lazy {
		logger.d("bookmarksAdapter: initializing bookmark adapter")
		val activityRef = safeBookmarksActivityRef
		BookmarkAdapter(activityRef, activityRef, activityRef)
	}

	/**
	 * Provides the layout resource ID for this activity.
	 *
	 * @return The layout resource ID (R.layout.activity_bookmarks_1)
	 */
	override fun onRenderingLayout(): Int {
		logger.d("onRenderingLayout: inflating activity_bookmarks_1 layout")
		return R.layout.activity_bookmarks_1
	}

	/**
	 * Called after the layout has been rendered.
	 * Initializes UI components and sets up event listeners.
	 */
	override fun onAfterLayoutRender() {
		logger.d("onAfterLayoutRender: initializing views and click events")
		initializeViews()
		initializeViewsOnClickEvents()
	}

	/**
	 * Called when the activity is resumed.
	 * Registers with AIOTimer and updates the bookmark list.
	 */
	override fun onResumeActivity() {
		super.onResumeActivity()
		logger.d("onResumeActivity: registering timer and updating adapter")
		registerToAIOTimer()
		updateBookmarkListAdapter()
	}

	/**
	 * Called when the activity is paused.
	 * Unregisters from AIOTimer to prevent unnecessary updates.
	 */
	override fun onPauseActivity() {
		super.onPauseActivity()
		logger.d("onPauseActivity: unregistering from timer")
		unregisterToAIOTimer()
	}

	/**
	 * Handles the back button press event.
	 * Closes search container if visible, otherwise closes the activity.
	 */
	override fun onBackPressActivity() {
		logger.d("onBackPressActivity: handling back press")
		if (containerSearchLayout.isVisible) {
			logger.d("onBackPressActivity: hiding search container")
			hideSearchContainer()
		} else {
			logger.d("onBackPressActivity: closing activity with fade animation")
			closeActivityWithFadeAnimation(false)
		}
	}

	/**
	 * Called when the activity is being destroyed.
	 * Performs cleanup of resources and references.
	 */
	override fun onDestroy() {
		logger.d("onDestroy: cleaning up resources")
		unregisterToAIOTimer()
		bookmarkListView.adapter = null
		bookmarkOptionPopup?.close()
		bookmarkOptionPopup = null
		super.onDestroy()
	}

	/**
	 * Called periodically by AIOTimer for UI updates.
	 *
	 * @param loopCount The current loop count from the timer
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("onAIOTimerTick: loopCount=$loopCount - updating load more button visibility")
		updateLoadMoreButtonVisibility()
	}

	/**
	 * Handles bookmark item click events.
	 * Opens the selected bookmark in the browser.
	 *
	 * @param bookmarkModel The bookmark model that was clicked
	 */
	override fun onBookmarkClick(bookmarkModel: BookmarkModel) {
		logger.d("onBookmarkClick: opening bookmark - ${bookmarkModel.bookmarkUrl}")
		openBookmarkInBrowser(bookmarkModel)
	}

	/**
	 * Handles bookmark item long-click events.
	 * Shows a popup menu with options for the selected bookmark.
	 *
	 * @param bookmarkModel The bookmark model that was long-clicked
	 * @param position The position of the item in the list
	 * @param listView The ListView containing the item
	 */
	override fun onBookmarkLongClick(
		bookmarkModel: BookmarkModel,
		position: Int, listView: View
	) {
		logger.d("onBookmarkLongClick: showing options for bookmark at position $position")
		safeBookmarksActivityRef?.let { safeBookmarkActivityRef ->
			try {
				bookmarkOptionPopup = BookmarkOptionPopup(
					bookmarksActivity = safeBookmarkActivityRef,
					bookmarkModel = bookmarkModel,
					listView = listView
				).apply { show() }
				logger.d("onBookmarkLongClick: options popup displayed successfully")
			} catch (error: Exception) {
				logger.d("onBookmarkLongClick: error displaying options - ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_something_went_wrong)
			}
		}
	}

	/**
	 * Clears the weak activity reference to prevent memory leaks.
	 */
	override fun clearWeakActivityReference() {
		logger.d("clearWeakActivityReference: clearing weak reference")
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	/**
	 * Initializes all UI components by finding views from the layout.
	 */
	private fun initializeViews() {
		logger.d("initializeViews: setting up UI components")
		safeBookmarksActivityRef?.let {
			buttonActionbarSearch = findViewById(R.id.btn_actionbar_search)
			buttonActionbarSearchImg = findViewById(R.id.btn_actionbar_search_img)
			containerSearchLayout = findViewById(R.id.top_searchbar_container)
			editTextSearchField = findViewById<EditText>(R.id.edit_search_keywords_text)
			containerSearchEditField = findViewById(R.id.edit_search_keywords_container)
			containerSearchLayout.visibility = GONE
			initSearchKeywordsWatcher(editTextSearchField)

			emptyBookmarksIndicator = findViewById(R.id.empty_bookmarks_indicator)
			bookmarkListView = findViewById(R.id.list_bookmarks)
			buttonLoadMoreBookmarks = findViewById(R.id.btn_load_more_bookmarks)

			bookmarkListView.adapter = bookmarksAdapter
			bookmarkListView.visibility = GONE
			emptyBookmarksIndicator.visibility = GONE
			buttonLoadMoreBookmarks.visibility = GONE

			updateLoadMoreButtonVisibility()
			logger.d("initializeViews: UI components initialized successfully")
		}
	}

	/**
	 * Sets up click event listeners for various UI components.
	 */
	private fun initializeViewsOnClickEvents() {
		logger.d("initializeViewsOnClickEvents: attaching click listeners")
		buttonActionbarSearch.setOnClickListener {
			logger.d("Search button clicked: toggling search field visibility")
			toggleSearchEditFieldVisibility()
		}

		containerSearchEditField.setOnClickListener {
			editTextSearchField.focusable
			editTextSearchField.selectAll()
			showOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
		}

		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener {
				logger.d("Left actionbar button clicked: navigating back")
				onBackPressActivity()
			}

		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener {
				logger.d("Right actionbar button clicked: initiating delete all bookmarks")
				deleteAllBookmarks()
			}

		buttonLoadMoreBookmarks.setOnClickListener {
			logger.d("Load More button clicked: loading additional bookmarks")
			bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
			showToast(msgId = R.string.text_loaded_successfully)
		}
	}

	private fun initSearchKeywordsWatcher(editTextSearchField: EditText) {
		editTextSearchField.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

			override fun afterTextChanged(editableField: Editable?) {
				val searchTerms = editableField?.toString()
				if (searchTerms.isNullOrEmpty()) {
					bookmarksAdapter.resetBookmarkAdapter()
					bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
					return
				}
				bookmarksAdapter.resetBookmarkAdapter()
				bookmarksAdapter.loadMoreBookmarks(searchTerms)
			}
		})
	}

	/**
	 * Toggles the visibility of the search container.
	 * Shows if hidden, hides if visible.
	 */
	private fun toggleSearchEditFieldVisibility() {
		if (containerSearchLayout.isVisible) {
			logger.d("toggleSearchEditFieldVisibility: hiding search container")
			hideSearchContainer()
		} else {
			logger.d("toggleSearchEditFieldVisibility: showing search container")
			showSearchContainer()
		}
	}

	/**
	 * Shows the search container and focuses the search field.
	 */
	private fun showSearchContainer() {
		logger.d("showSearchContainer: displaying search interface")
		buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_cancel)
		showView(containerSearchLayout, true)
		delay(200, object : OnTaskFinishListener {
			override fun afterDelay() {
				logger.d("showSearchContainer: showing on-screen keyboard")
				showOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
			}
		})
	}

	/**
	 * Hides the search container and dismisses the keyboard.
	 */
	private fun hideSearchContainer() {
		logger.d("hideSearchContainer: hiding search interface")
		editTextSearchField.setText("")
		hideView(containerSearchLayout, true)
		hideOnScreenKeyboard(safeBookmarksActivityRef, editTextSearchField)
		buttonActionbarSearchImg.setImageResource(R.drawable.ic_button_actionbar_search)
	}

	/**
	 * Shows a confirmation dialog for deleting all bookmarks.
	 * If confirmed, clears all bookmarks from storage.
	 */
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
				logger.d("deleteAllBookmarks: user confirmed deletion - clearing all bookmarks")
				close()
				aioBookmark.getBookmarkLibrary().clear()
				aioBookmark.updateInStorage()
				bookmarksAdapter.resetBookmarkAdapter()
			}
		}?.show()
	}

	/**
	 * Registers this activity with AIOTimer for periodic updates.
	 */
	private fun registerToAIOTimer() {
		logger.d("registerToAIOTimer: registering activity with timer")
		safeBookmarksActivityRef?.let { aioTimer.register(it) }
	}

	/**
	 * Unregisters this activity from AIOTimer to stop updates.
	 */
	private fun unregisterToAIOTimer() {
		logger.d("unregisterToAIOTimer: unregistering activity from timer")
		safeBookmarksActivityRef?.let { aioTimer.unregister(it) }
	}

	/**
	 * Refreshes the bookmark list adapter with current data.
	 */
	fun updateBookmarkListAdapter() {
		logger.d("updateBookmarkListAdapter: refreshing bookmark data")
		bookmarksAdapter.resetBookmarkAdapter()
		bookmarksAdapter.loadMoreBookmarks(/* searchTerms = */ null)
	}

	/**
	 * Updates the visibility of the Load More button and empty state indicator
	 * based on the current bookmark count and adapter state.
	 */
	private fun updateLoadMoreButtonVisibility() {
		val bookmarkSize = aioBookmark.getBookmarkLibrary().size
		logger.d("updateLoadMoreButtonVisibility: adapterCount=${bookmarksAdapter.count}, totalBookmarks=$bookmarkSize")

		if (bookmarksAdapter.count >= bookmarkSize) {
			logger.d("updateLoadMoreButtonVisibility: hiding load more button")
			hideView(buttonLoadMoreBookmarks, true)
		} else {
			logger.d("updateLoadMoreButtonVisibility: showing load more button")
			showView(buttonLoadMoreBookmarks, true)
		}

		if (bookmarksAdapter.isEmpty) {
			logger.d("updateLoadMoreButtonVisibility: showing empty state indicator")
			showView(emptyBookmarksIndicator, true)
		} else {
			logger.d("updateLoadMoreButtonVisibility: hiding empty state indicator")
			hideView(emptyBookmarksIndicator, true).let {
				delay(500, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("updateLoadMoreButtonVisibility: showing bookmark list view")
						showView(bookmarkListView, true)
					}
				})
			}
		}
	}

	/**
	 * Opens a bookmark in the browser and closes the activity.
	 *
	 * @param bookmarkModel The bookmark model to open
	 */
	private fun openBookmarkInBrowser(bookmarkModel: BookmarkModel) {
		logger.d("openBookmarkInBrowser: navigating to ${bookmarkModel.bookmarkUrl}")
		sendResultBackToBrowserFragment(bookmarkModel.bookmarkUrl)
		onBackPressActivity()
	}

	/**
	 * Sends the selected bookmark URL back to the calling browser fragment.
	 *
	 * @param result The URL string to send back
	 */
	fun sendResultBackToBrowserFragment(result: String) {
		logger.d("sendResultBackToBrowserFragment: returning result - $result")
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}
}