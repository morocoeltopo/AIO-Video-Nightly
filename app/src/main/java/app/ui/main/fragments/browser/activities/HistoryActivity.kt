package app.ui.main.fragments.browser.activities

import android.content.Intent
import android.view.View
import android.view.View.GONE
import android.widget.ListView
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.browser.history.HistoryModel
import app.ui.main.MotherActivity.Companion.ACTIVITY_RESULT_KEY
import app.ui.main.fragments.browser.activities.HistoryAdapter.OnHistoryItemClick
import app.ui.main.fragments.browser.activities.HistoryAdapter.OnHistoryItemLongClick
import com.aio.R
import lib.process.CommonTimeUtils
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

class HistoryActivity : BaseActivity(),
	AIOTimerListener, OnHistoryItemClick, OnHistoryItemLongClick {

	private val safeSelfReference = WeakReference(this)
	private val safeHistoryActivityRef = safeSelfReference.get()

	private lateinit var emptyHistoryIndicator: View
	private lateinit var historyList: ListView
	private lateinit var buttonLoadMoreHistory: View

	private var historyOptionPopup: HistoryOptionPopup? = null

	private val arg = safeHistoryActivityRef
	private val historyAdapter by lazy { HistoryAdapter(arg, arg, arg) }

	override fun onRenderingLayout(): Int {
		return R.layout.activity_browser_history_1
	}

	override fun onAfterLayoutRender() {
		initializeViews()
		initializeViewsOnClickEvents()
	}

	override fun onResumeActivity() {
		super.onResumeActivity()
		registerToAIOTimer()
		updateHistoryListAdapter()
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
		historyList.adapter = null
		historyOptionPopup?.close()
		historyOptionPopup = null
		super.onDestroy()
	}

	override fun clearWeakActivityReference() {
		safeSelfReference.clear()
		super.clearWeakActivityReference()
	}

	override fun onAIOTimerTick(loopCount: Double) {
		updateLoadMoreButtonVisibility()
	}

	override fun onHistoryItemClick(historyModel: HistoryModel) {
		openHistoryInBrowser(historyModel)
	}

	override fun onHistoryItemLongClick(
		historyModel: HistoryModel,
		position: Int, listView: View
	) {
		safeHistoryActivityRef?.let { safeActivityRef ->
			try {
				historyOptionPopup = HistoryOptionPopup(
					historyActivity = safeActivityRef,
					historyModel = historyModel,
					listView = listView
				)
				historyOptionPopup?.show()
			} catch (error: Exception) {
				error.printStackTrace()
				showToast(msgId = R.string.text_something_went_wrong)
			}
		}
	}

	private fun initializeViews() {
		safeHistoryActivityRef?.let {
			emptyHistoryIndicator = findViewById(R.id.empty_history_indicator)
			historyList = findViewById(R.id.list_history)
			buttonLoadMoreHistory = findViewById(R.id.btn_load_more_history)

			historyList.adapter = historyAdapter
			historyList.visibility = GONE

			emptyHistoryIndicator.visibility = GONE
			buttonLoadMoreHistory.visibility = GONE
			updateLoadMoreButtonVisibility()
		}
	}

	private fun initializeViewsOnClickEvents() {
		findViewById<View>(R.id.btn_left_actionbar)
			.setOnClickListener { onBackPressActivity() }

		findViewById<View>(R.id.btn_right_actionbar)
			.setOnClickListener { deleteAllHistory() }

		buttonLoadMoreHistory.setOnClickListener {
			historyAdapter.loadMoreHistory()
			showToast(msgId = R.string.text_loaded_successfully)
		}
	}

	private fun deleteAllHistory() {
		MsgDialogUtils.getMessageDialog(
			baseActivityInf = safeHistoryActivityRef,
			isTitleVisible = true,
			titleTextViewCustomize = { it.setText(R.string.title_are_you_sure_about_this) },
			messageTxt = getText(R.string.text_delete_browsing_history_confirmation),
			isNegativeButtonVisible = false,
			positiveButtonTextCustomize = {
				it.setText(R.string.title_clear_now)
				it.setLeftSideDrawable(R.drawable.ic_button_clear)
			})?.apply {
			setOnClickForPositiveButton {
				close()
				aioHistory.getHistoryLibrary().clear()
				aioHistory.updateInStorage()
				historyAdapter.resetHistoryAdapter()
			}
		}?.show()
	}

	private fun registerToAIOTimer() {
		safeHistoryActivityRef?.let { aioTimer.register(it) }
	}

	private fun unregisterToAIOTimer() {
		safeHistoryActivityRef?.let { aioTimer.unregister(it) }
	}

	fun updateHistoryListAdapter() {
		historyAdapter.resetHistoryAdapter()
		historyAdapter.loadMoreHistory()
	}

	private fun updateLoadMoreButtonVisibility() {
		val historySize = aioHistory.getHistoryLibrary().size
		if (historyAdapter.count >= historySize) hideView(buttonLoadMoreHistory, true)
		else showView(buttonLoadMoreHistory, true)

		if (historyAdapter.isEmpty) showView(emptyHistoryIndicator, true)
		else hideView(emptyHistoryIndicator, true).let {
			CommonTimeUtils.delay(500, object : OnTaskFinishListener {
				override fun afterDelay() {
					showView(historyList, true)
				}
			})
		}
	}

	private fun openHistoryInBrowser(historyModel: HistoryModel) {
		sendResultBackToBrowserFragment(historyModel.historyUrl)
		onBackPressActivity()
	}

	fun sendResultBackToBrowserFragment(result: String) {
		setResult(RESULT_OK, Intent().apply {
			putExtra(ACTIVITY_RESULT_KEY, result)
		}).let { onBackPressActivity() }
	}
}