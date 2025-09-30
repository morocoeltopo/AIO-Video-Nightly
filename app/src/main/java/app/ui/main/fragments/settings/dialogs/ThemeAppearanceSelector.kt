package app.ui.main.fragments.settings.dialogs

import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

class ThemeAppearanceSelector(private val baseActivity: BaseActivity) {

	private val logger = LogHelperUtils.from(javaClass)
	private val safeBaseActivityRef by lazy { WeakReference(baseActivity).get() }

	private val themeAppearanceSelectionDialog by lazy {
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_dark_theme_picker_1)
		}
	}

	private val systemDefaultUIRadioBtn by lazy {
		val layout = themeAppearanceSelectionDialog.view
		layout.findViewById<RadioButton>(R.id.btn_system_default)
	}

	private val darkModeUIRadioBtn by lazy {
		val layout = themeAppearanceSelectionDialog.view
		layout.findViewById<RadioButton>(R.id.btn_dark_mode_ui)
	}

	private val lightModeUIRadioBtn by lazy {
		val layout = themeAppearanceSelectionDialog.view
		layout.findViewById<RadioButton>(R.id.btn_light_mode_ui)
	}

	/** Callback triggered when user confirms region selection */
	var onApplyListener: () -> Unit? = {}

	init {
		logger.d("Initializing ContentRegionSelector dialog.")
		themeAppearanceSelectionDialog.view.apply {
			setButtonOnClickListeners(this)
		}
	}

	/** Returns the underlying dialog builder */
	fun getDialogBuilder(): DialogBuilder {
		return themeAppearanceSelectionDialog
	}

	/** Closes the dialog if visible */
	fun close() {
		if (themeAppearanceSelectionDialog.isShowing) {
			logger.d("Closing content region selection dialog.")
			themeAppearanceSelectionDialog.close()
		}
	}

	/** Shows the dialog if not already visible */
	fun show() {
		if (!themeAppearanceSelectionDialog.isShowing) {
			logger.d("Showing content region selection dialog.")
			themeAppearanceSelectionDialog.show()
		}
	}

	/** Returns true if the dialog is currently visible */
	fun isShowing(): Boolean {
		return themeAppearanceSelectionDialog.isShowing
	}

	/** Sets up the action button listeners inside the dialog */
	private fun setButtonOnClickListeners(dialogLayoutView: View) {
		dialogLayoutView.findViewById<View>(R.id.btn_dialog_positive_container).apply {
			setOnClickListener { applySelectedApplicationTheme(dialogLayoutView) }
		}
	}

	/** Returns the RadioGroup container from the dialog layout */
	private fun getRegionsRadioGroupView(view: View): RadioGroup {
		return view.findViewById(R.id.theme_options_container)
	}

	/**
	 * Applies the region selected by the user and persists it into storage.
	 * Triggers the onApplyListener callback after successful save.
	 */
	private fun applySelectedApplicationTheme(dialogLayoutView: View) {
		val contentRegionRadioGroup = getRegionsRadioGroupView(dialogLayoutView)
		val selectedRegionId = contentRegionRadioGroup.checkedRadioButtonId

		if (selectedRegionId == -1) {
			logger.d("No region selected. Skipping apply.")
			return // No selection
		}

		if (systemDefaultUIRadioBtn.isChecked) {
			aioSettings.themeAppearance = -1
		} else if (darkModeUIRadioBtn.isChecked) {
			aioSettings.themeAppearance = 1
		} else if (lightModeUIRadioBtn.isChecked) {
			aioSettings.themeAppearance = 2
		}

		aioSettings.updateInStorage()

		close()             // Close the dialog
		onApplyListener()   // Notify listener
	}
}
