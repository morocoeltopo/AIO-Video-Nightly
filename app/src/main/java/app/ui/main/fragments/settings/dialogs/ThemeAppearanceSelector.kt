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

/**
 * Dialog controller for selecting the application's theme appearance
 * (System default / Dark mode / Light mode).
 *
 * Handles dialog creation, UI binding, logging of key actions,
 * and persistence of the selected theme into storage.
 */
class ThemeAppearanceSelector(private val baseActivity: BaseActivity) {

	/** Logger for tracking lifecycle and user actions. */
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to BaseActivity to prevent memory leaks. */
	private val safeBaseActivityRef by lazy { WeakReference(baseActivity).get() }

	/**
	 * DialogBuilder instance configured with the theme selection layout.
	 * Lazily created on first access.
	 */
	private val themeAppearanceSelectionDialog by lazy {
		logger.d("Creating ThemeAppearanceSelectionDialog with layout R.layout.dialog_dark_theme_picker_1")
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_dark_theme_picker_1)
		}
	}

	/** RadioButton for system default theme. */
	private val systemDefaultUIRadioBtn by lazy {
		themeAppearanceSelectionDialog.view.findViewById<RadioButton>(R.id.btn_system_default)
	}

	/** RadioButton for dark mode theme. */
	private val darkModeUIRadioBtn by lazy {
		themeAppearanceSelectionDialog.view
			.findViewById<RadioButton>(R.id.btn_dark_mode_ui)
	}

	/** RadioButton for light mode theme. */
	private val lightModeUIRadioBtn by lazy {
		themeAppearanceSelectionDialog.view
			.findViewById<RadioButton>(R.id.btn_light_mode_ui)
	}

	/**
	 * Callback triggered when the user applies the selected theme.
	 * Can be assigned externally to react after the theme is saved.
	 */
	var onApplyListener: () -> Unit? = {}

	init {
		logger.d("Initializing ThemeAppearanceSelector dialog.")
		themeAppearanceSelectionDialog.view.apply {
			setButtonOnClickListeners(this)
		}
	}

	/** Returns the underlying DialogBuilder for further customization. */
	fun getDialogBuilder(): DialogBuilder {
		logger.d("getDialogBuilder() called.")
		return themeAppearanceSelectionDialog
	}

	/** Closes the dialog if it is currently visible. */
	fun close() {
		if (themeAppearanceSelectionDialog.isShowing) {
			logger.d("Closing theme appearance dialog.")
			themeAppearanceSelectionDialog.close()
		} else {
			logger.d("close() called but dialog was not showing.")
		}
	}

	/** Shows the dialog if it is not already visible. */
	fun show() {
		if (!themeAppearanceSelectionDialog.isShowing) {
			when (aioSettings.themeAppearance) {
				-1 -> systemDefaultUIRadioBtn.isChecked = true
				1 -> darkModeUIRadioBtn.isChecked = true
				2 -> lightModeUIRadioBtn.isChecked = true
				else -> systemDefaultUIRadioBtn.isChecked = true
			}
			logger.d("Showing theme appearance dialog.")
			themeAppearanceSelectionDialog.show()
		} else {
			logger.d("show() called but dialog is already visible.")
		}
	}

	/** Returns true if the dialog is currently displayed. */
	fun isShowing(): Boolean {
		val showing = themeAppearanceSelectionDialog.isShowing
		logger.d("isShowing() -> $showing")
		return showing
	}

	/**
	 * Sets up action button listeners inside the dialog.
	 *
	 * @param dialogLayoutView The root view of the dialog layout.
	 */
	private fun setButtonOnClickListeners(dialogLayoutView: View) {
		logger.d("Setting up button click listeners.")
		dialogLayoutView.findViewById<View>(R.id.btn_dialog_positive_container).apply {
			setOnClickListener {
				logger.d("Positive button clicked.")
				applySelectedApplicationTheme(dialogLayoutView)
			}
		}
	}

	/**
	 * Retrieves the RadioGroup containing theme options.
	 *
	 * @param view The dialog layout view.
	 * @return RadioGroup instance for theme selection.
	 */
	private fun getRegionsRadioGroupView(view: View): RadioGroup {
		logger.d("Fetching theme options RadioGroup.")
		return view.findViewById(R.id.theme_options_container)
	}

	/**
	 * Applies the theme selected by the user, persists it into storage,
	 * and triggers the [onApplyListener] callback after saving.
	 *
	 * @param dialogLayoutView The dialog's root view.
	 */
	private fun applySelectedApplicationTheme(dialogLayoutView: View) {
		logger.d("applySelectedApplicationTheme() invoked.")

		val contentRegionRadioGroup = getRegionsRadioGroupView(dialogLayoutView)
		val selectedRegionId = contentRegionRadioGroup.checkedRadioButtonId

		if (selectedRegionId == -1) {
			logger.d("No theme option selected. Aborting apply.")
			return
		}

		when {
			systemDefaultUIRadioBtn.isChecked -> {
				logger.d("System Default theme selected.")
				aioSettings.themeAppearance = -1
			}

			darkModeUIRadioBtn.isChecked -> {
				logger.d("Dark Mode theme selected.")
				aioSettings.themeAppearance = 1
			}

			lightModeUIRadioBtn.isChecked -> {
				logger.d("Light Mode theme selected.")
				aioSettings.themeAppearance = 2
			}
		}

		logger.d("Persisting selected theme into storage.")
		aioSettings.updateInStorage()

		logger.d("Closing dialog after apply.")
		close()

		logger.d("Triggering onApplyListener callback.")
		onApplyListener()
	}
}