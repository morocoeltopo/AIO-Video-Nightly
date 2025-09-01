package app.ui.main.fragments.settings.dialogs

import android.view.View
import android.view.View.inflate
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.RadioButton
import android.widget.RadioGroup
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import app.ui.main.fragments.settings.dialogs.ContentRegionsList.regionsList
import com.aio.R
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

class ContentRegionSelector(private val baseActivity: BaseActivity) {

	private val safeBaseActivityRef by lazy { WeakReference(baseActivity).get() }

	private val contentRegionSelectionDialog by lazy {
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_content_regions_1)
		}
	}

	var onApplyListener: () -> Unit? = {}

	init {
		contentRegionSelectionDialog.setCancelable(false)
		contentRegionSelectionDialog.view.apply {
			setAvailableRegions(this)
			setButtonOnClickListeners(this)
		}
	}

	fun getDialogBuilder(): DialogBuilder {
		return contentRegionSelectionDialog
	}

	fun close() {
		if (contentRegionSelectionDialog.isShowing) {
			contentRegionSelectionDialog.close()
		}
	}

	fun show() {
		if (!contentRegionSelectionDialog.isShowing) {
			contentRegionSelectionDialog.show()
		}
	}

	fun isShowing(): Boolean {
		return contentRegionSelectionDialog.isShowing
	}

	private fun setAvailableRegions(dialogLayoutView: View) {
		safeBaseActivityRef?.let { safeActivityRef ->
			removeAllRadioSelectionViews(dialogLayoutView)

			regionsList.forEachIndexed { index, (_, name) ->
				inflate(safeActivityRef, R.layout.dialog_content_regions_item_1, null).apply {
					(this as RadioButton).apply {
						id = index
						text = name

						// Set the height of the RadioButton
						val radioButtonHeight = resources.getDimensionPixelSize(R.dimen._40)
						layoutParams = LayoutParams(MATCH_PARENT, radioButtonHeight)

						// Set padding inside the RadioButton for visual spacing
						val horizontalPadding = resources.getDimensionPixelSize(R.dimen._5)
						val verticalPadding = resources.getDimensionPixelSize(R.dimen._5)
						setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
					}
					getRegionsRadioGroupView(dialogLayoutView).addView(this)
				}
			}

			// Highlight the currently selected language
			val currentRegionCode = aioSettings.userSelectedContentRegion
			val selectedIndex = regionsList.indexOfFirst { it.first == currentRegionCode }
			if (selectedIndex >= 0) {
				getRegionsRadioGroupView(dialogLayoutView)
					.findViewById<RadioButton>(selectedIndex)?.isChecked = true
			}
		}
	}

	private fun removeAllRadioSelectionViews(dialogLayoutView: View) {
		getRegionsRadioGroupView(dialogLayoutView).removeAllViews()
	}

	private fun getRegionsRadioGroupView(view: View): RadioGroup {
		return view.findViewById(R.id.content_region_options_container)
	}

	private fun setButtonOnClickListeners(dialogLayoutView: View) {
		dialogLayoutView.findViewById<View>(R.id.btn_dialog_positive_container).apply {
			setOnClickListener { applySelectedApplicationContentRegion(dialogLayoutView) }
		}
	}


	private fun applySelectedApplicationContentRegion(dialogLayoutView: View) {
		val contentRegionRadioGroup = getRegionsRadioGroupView(dialogLayoutView)
		val selectedRegionId = contentRegionRadioGroup.checkedRadioButtonId

		if (selectedRegionId == -1) return // No selection

		val (selectedRegionCode, _) = regionsList[selectedRegionId]

		// Save the new language preference and persist it
		aioSettings.userSelectedContentRegion = selectedRegionCode
		aioSettings.updateInStorage()

		close()             // Close the dialog
		onApplyListener()   // Notify listener
	}
}
