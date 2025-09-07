package app.ui.main.fragments.settings

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.net.toUri
import app.core.AIOApp
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.updater.AIOUpdater
import app.ui.main.fragments.settings.activities.browser.AdvBrowserSettingsActivity
import app.ui.main.fragments.settings.dialogs.ContentRegionSelector
import app.ui.main.fragments.settings.dialogs.CustomDownloadFolderSelector
import app.ui.main.fragments.settings.dialogs.DownloadLocationSelector
import app.ui.others.information.UserFeedbackActivity
import app.ui.others.startup.LanguagePickerDialog
import com.aio.R
import kotlinx.coroutines.delay
import lib.device.ShareUtility
import lib.networks.URLUtility.ensureHttps
import lib.networks.URLUtility.isValidURL
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.OSProcessUtils.restartApp
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.lang.ref.WeakReference

/**
 * Handles the logic for Settings screen interactions (toggles, dialogs, navigation).
 * Uses [logger] for debugging flow across actions.
 *
 * This class manages all user interactions within the Settings fragment including:
 * - Application settings management
 * - Download configuration
 * - Browser preferences
 * - Customer service features
 * - UI state updates
 */
class SettingsOnClickLogic(private val settingsFragment: SettingsFragment) {

	// Logger instance for debugging and tracking user interactions
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to avoid memory leaks with the fragment
	private val safeSettingsFragmentRef = WeakReference(settingsFragment).get()

	// Application settings section

	/**
	 * Launches the application sign-in or register prompt dialog to the user.
	 */
	fun showLoginOrRegistrationDialog() {

	}

	/**
	 * Shows a dialog to select the default download location
	 * This allows users to choose where downloaded files will be saved
	 */
	fun setDefaultDownloadLocationPicker() {
		logger.d("Opening Download Location Picker dialog")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivity ->
			DownloadLocationSelector(baseActivity = safeMotherActivity).show()
		} ?: run {
			logger.d("Failed to open Download Location Picker: safeMotherActivityRef is null")
		}
	}

	/**
	 * Launches the language picker dialog and restarts the app upon language change
	 * Shows a warning about experimental feature before proceeding
	 */
	fun showApplicationLanguageChanger() {
		logger.d("Opening Language Picker Dialog")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivityRef ->
			MsgDialogUtils.getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isTitleVisible = true,
				titleText = getText(R.string.title_experimental_feature),
				messageTextViewCustomize = {
					it.setText(R.string.text_feature_is_experimental_msg)
				},
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = {
					it.setText(R.string.title_proceed)
					it.setLeftSideDrawable(R.drawable.ic_button_arrow_next)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("Language change confirmed → showing LanguagePickerDialog")
					close()
					LanguagePickerDialog(safeMotherActivityRef).apply {
						getDialogBuilder().setCancelable(true)
						onApplyListener = {
							logger.d("Language selected → restarting application")
							close()
							restartApp(shouldKillProcess = true)
						}
					}.show()
				}
			}?.show()
		} ?: run {
			logger.d("Failed to open Language Picker: safeMotherActivityRef is null")
		}
	}

	/**
	 * Toggles the Dark Mode UI setting.
	 * When enabled, the app interface will switch between light and dark themes.
	 */
	fun togglesDarkModeUISettings() {
		logger.d("Toggling Dark Mode UI setting")
		try {
			aioSettings.enableDarkUIMode = !aioSettings.enableDarkUIMode
			aioSettings.updateInStorage()
			logger.d("Dark Mode UI is now: ${aioSettings.enableDarkUIMode}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling Dark Mode UI: ${error.message}", error)
			error.printStackTrace()
		}
	}

	/**
	 * Changes the default content region for the application
	 * Shows a region selector dialog and restarts the app upon selection
	 */
	fun changeDefaultContentRegion() {
		logger.d("Opening Content Region Selector")
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			ContentRegionSelector(this).apply {
				getDialogBuilder().setCancelable(true)
				onApplyListener = {
					logger.d("Content region selected → restarting application")
					close()
					restartApp(shouldKillProcess = true)
				}
			}.show()
		} ?: run {
			logger.d("Failed to open Content Region Selector: safeMotherActivityRef is null")
		}
	}

	/**
	 * Toggles the daily content suggestions feature
	 * Updates the setting in storage and refreshes the UI
	 */
	fun enableDailyContentSuggestions() {
		logger.d("Toggling Daily Content Suggestions")
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			try {
				aioSettings.enableDailyContentSuggestion = !aioSettings.enableDailyContentSuggestion
				aioSettings.updateInStorage()
				logger.d("Daily content suggestions: ${aioSettings.enableDailyContentSuggestion}")
				updateSettingStateUI()
			} catch (error: Exception) {
				logger.d("Error toggling daily content suggestions: ${error.message}")
				error.printStackTrace()
			}
		} ?: run {
			logger.d("Failed to toggle Daily Content Suggestions: safeMotherActivityRef is null")
		}
	}

	// Download settings section

	/**
	 * Changes the default download folder
	 * Shows a dialog to select a custom folder for downloads
	 */
	fun changeDefaultDownloadFolder() {
		logger.d("Opening Custom Download Folder Selector")
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			CustomDownloadFolderSelector(this).show()
		} ?: run {
			logger.d("Failed to open Custom Download Folder Selector: safeMotherActivityRef is null")
		}
	}

	/**
	 * Toggles the visibility of download notifications
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleHideDownloadNotification() {
		logger.d("Toggling Download Notification Visibility")
		try {
			aioSettings.downloadHideNotification = !aioSettings.downloadHideNotification
			aioSettings.updateInStorage()
			logger.d("Download Notification Hidden: ${aioSettings.downloadHideNotification}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling download notification: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Toggles "Wi-Fi only" mode for downloads
	 * When enabled, downloads will only occur when connected to Wi-Fi
	 */
	fun toggleWifiOnlyDownload() {
		logger.d("Toggling Wi-Fi Only Download mode")
		try {
			aioSettings.downloadWifiOnly = !aioSettings.downloadWifiOnly
			aioSettings.updateInStorage()
			logger.d("Wi-Fi only downloads: ${aioSettings.downloadWifiOnly}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling Wi-Fi only downloads: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Toggles Single-Click-Open file mode for downloads.
	 * When enabled, downloaded files will be opened instantly instead of showing the options dialog.
	 */
	fun toggleSingleClickToOpenFile() {
		logger.d("Toggling single-click open file setting")
		try {
			aioSettings.openDownloadedFileOnSingleClick =
				!aioSettings.openDownloadedFileOnSingleClick
			aioSettings.updateInStorage()
			logger.d("Single-click open file is now: ${aioSettings.openDownloadedFileOnSingleClick}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling single-click open file: ${error.message}", error)
			error.printStackTrace()
		}
	}

	/**
	 * Toggles the sound played when a download completes
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleDownloadNotificationSound() {
		logger.d("Toggling Download Notification Sound")
		try {
			aioSettings.downloadPlayNotificationSound = !aioSettings.downloadPlayNotificationSound
			aioSettings.updateInStorage()
			logger.d("Download Sound Enabled: ${aioSettings.downloadPlayNotificationSound}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling download sound: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Opens advanced download settings (placeholder implementation)
	 * Currently shows a "not available" message as this feature isn't implemented
	 */
	fun openAdvanceDownloadsSettings() {
		logger.d("Opening Advanced Downloads Settings (not implemented)")
		safeSettingsFragmentRef?.safeMotherActivityRef.let {
			it?.doSomeVibration(50)
			MsgDialogUtils.showMessageDialog(
				baseActivityInf = it,
				isTitleVisible = true,
				titleText = getText(R.string.text_feature_isnt_implemented),
				messageTextViewCustomize = { msgTextView ->
					msgTextView.setText(R.string.text_feature_isnt_available_yet)
				}, isNegativeButtonVisible = false
			)
		} ?: run {
			logger.d("Failed to show Advanced Downloads dialog: safeMotherActivityRef is null")
		}
	}

	// Browser settings section

	/**
	 * Displays a dialog to set the default homepage for the in-app browser
	 * Validates the URL input and updates the setting if valid
	 */
	fun setBrowserDefaultHomepage() {
		logger.d("Opening Browser Homepage dialog")
		try {
			safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeActivityRef ->
				val dialogBuilder = DialogBuilder(safeActivityRef)
				dialogBuilder.setView(R.layout.dialog_browser_homepage_1)

				val dialogLayout = dialogBuilder.view
				val currentBrowserHomepageString = safeActivityRef.getString(
					R.string.text_current_homepage, aioSettings.browserDefaultHomepage
				)

				dialogLayout.findViewById<TextView>(R.id.txt_current_homepage).text =
					currentBrowserHomepageString

				val editTextURL = dialogLayout.findViewById<EditText>(R.id.edit_field_url)

				dialogBuilder.setOnClickForPositiveButton {
					val userEnteredURL = editTextURL.text.toString()
					logger.d("User entered homepage URL: $userEnteredURL")
					if (isValidURL(userEnteredURL)) {
						val finalNormalizedURL = ensureHttps(userEnteredURL) ?: userEnteredURL
						aioSettings.browserDefaultHomepage = finalNormalizedURL
						aioSettings.updateInStorage()
						logger.d("Homepage updated to: $finalNormalizedURL")
						dialogBuilder.close()
						showToast(msgId = R.string.title_successful)
					} else {
						logger.d("Invalid homepage URL entered: $userEnteredURL")
						safeActivityRef.doSomeVibration(50)
						showToast(msgId = R.string.text_invalid_url)
					}
				}
				dialogBuilder.show()
				delay(200, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Showing on-screen keyboard for URL input")
						editTextURL.requestFocus()
						showOnScreenKeyboard(safeActivityRef, editTextURL)
					}
				})
			} ?: run {
				logger.d("Failed to open Browser Homepage dialog: safeActivityRef is null")
			}
		} catch (error: Exception) {
			logger.d("Error setting browser homepage: ${error.message}")
			error.printStackTrace()
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	/**
	 * Toggles the browser ad blocker feature
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleBrowserBrowserAdBlocker() {
		logger.d("Toggling Browser Ad Blocker")
		try {
			aioSettings.browserEnableAdblocker = !aioSettings.browserEnableAdblocker
			aioSettings.updateInStorage()
			logger.d("Browser Ad Blocker toggled: ${aioSettings.browserEnableAdblocker}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling browser ad blocker: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Toggles the browser popup blocker feature
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleBrowserPopupAdBlocker() {
		logger.d("Toggling Browser Popup Blocker")
		try {
			aioSettings.browserEnablePopupBlocker = !aioSettings.browserEnablePopupBlocker
			aioSettings.updateInStorage()
			logger.d("Popup Blocker toggled: ${aioSettings.browserEnablePopupBlocker}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling popup blocker: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Toggles web images loading in the browser
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleBrowserWebImages() {
		logger.d("Toggling Browser Web Images")
		try {
			aioSettings.browserEnableImages = !aioSettings.browserEnableImages
			aioSettings.updateInStorage()
			logger.d("Browser enable images: ${aioSettings.browserEnableImages}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling browser enable images: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Toggles the video grabber feature in the browser
	 * Updates the setting in storage and refreshes the UI
	 */
	fun toggleBrowserVideoGrabber() {
		logger.d("Toggling Browser Video Grabber")
		try {
			aioSettings.browserEnableVideoGrabber = !aioSettings.browserEnableVideoGrabber
			aioSettings.updateInStorage()
			logger.d("Video Grabber toggled: ${aioSettings.browserEnableVideoGrabber}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling video grabber: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Opens advanced browser settings activity
	 * Navigates to a dedicated screen for advanced browser configurations
	 */
	fun openAdvanceBrowserSettings() {
		logger.d("Opening Advanced Settings For Browser")
		safeSettingsFragmentRef?.safeMotherActivityRef
			?.openActivity(
				activity = AdvBrowserSettingsActivity::class.java,
				shouldAnimate = true
			) ?: run {
			logger.d("Failed to open Advanced Browser Settings: safeMotherActivityRef is null")
		}
	}

	// Customer service section

	/**
	 * Shares the app with others via system sharing intent
	 * Constructs a share message with app name and official page URL
	 */
	fun shareApplicationWithFriends() {
		logger.d("Sharing application with friends")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { context ->
			ShareUtility.shareText(
				context = context,
				title = getText(R.string.text_share_with_others),
				text = getApplicationShareText(context)
			)
		} ?: run {
			logger.d("Failed to share application: safeMotherActivityRef is null")
		}
	}

	/**
	 * Navigates to user feedback screen
	 * Opens the activity where users can provide feedback
	 */
	fun openUserFeedbackActivity() {
		logger.d("Opening User Feedback Activity")
		safeSettingsFragmentRef?.safeMotherActivityRef?.openActivity(
			UserFeedbackActivity::class.java, shouldAnimate = false
		) ?: run {
			logger.d("Failed to open User Feedback Activity: safeMotherActivityRef is null")
		}
	}

	/**
	 * Opens application details screen in Android settings
	 * Shows system app info for the current application
	 */
	fun openApplicationInformation() {
		logger.d("Opening Application Info in system settings")
		safeSettingsFragmentRef?.safeBaseActivityRef?.openAppInfoSetting() ?: run {
			logger.d("Failed to open Application Info: safeBaseActivityRef is null")
		}
	}

	/**
	 * Navigates to Privacy Policy screen
	 * Opens the privacy policy in a web browser
	 */
	fun showPrivacyPolicyActivity() {
		logger.d("Opening Privacy Policy in browser")
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_privacy_policy_url).toString()
				logger.d("Privacy Policy URL: $uri")
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Privacy Policy: ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		} ?: run {
			logger.d("Failed to open Privacy Policy: safeBaseActivityRef is null")
		}
	}

	/**
	 * Navigates to Terms & Conditions screen
	 * Opens the terms and conditions in a web browser
	 */
	fun showTermsConditionActivity() {
		logger.d("Opening Terms & Conditions in browser")
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_terms_conditions_url).toString()
				logger.d("Terms & Conditions URL: $uri")
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Terms & Conditions: ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		} ?: run {
			logger.d("Failed to open Terms & Conditions: safeBaseActivityRef is null")
		}
	}

	/**
	 * Checks for new application version updates
	 * Shows a waiting dialog while checking and provides appropriate feedback
	 */
	fun checkForNewApkVersion() {
		logger.d("Checking for new APK version")
		safeSettingsFragmentRef?.safeBaseActivityRef?.let { safeBaseActivityRef ->
			ThreadsUtility.executeInBackground(codeBlock = {
				var waitingDialog: WaitingDialog? = null
				ThreadsUtility.executeOnMain {
					waitingDialog = WaitingDialog(
						baseActivityInf = safeBaseActivityRef,
						loadingMessage = getText(R.string.text_checking_for_new_update),
						isCancelable = false,
					); waitingDialog.show()
					delay(1000)
				}

				if (AIOUpdater().isNewUpdateAvailable()) {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("New update available → opening official site")
					safeBaseActivityRef.openApplicationOfficialSite()
				} else {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("Already on latest version")
					ThreadsUtility.executeOnMain {
						safeBaseActivityRef.doSomeVibration(50)
						showToast(msgId = R.string.text_you_are_using_the_latest_version)
					}
				}
			}, errorHandler = {
				logger.d("Error while checking updates: ${it.message}")
				safeBaseActivityRef.doSomeVibration(50)
				showToast(msgId = R.string.text_something_went_wrong)
			})
		} ?: run {
			logger.d("Failed to check for updates: safeBaseActivityRef is null")
		}
	}

	/**
	 * Updates the end icon of each setting option based on current settings
	 * This refreshes the UI to reflect the current state of all toggleable settings
	 */
	fun updateSettingStateUI() {
		logger.d("Updating Setting State UI")
		safeSettingsFragmentRef?.safeFragmentLayoutRef?.let { layout ->
			listOf(
				SettingViewConfig(
					viewId = R.id.txt_dark_mode_ui,
					isEnabled = aioSettings.enableDarkUIMode
				),
				SettingViewConfig(
					viewId = R.id.txt_daily_suggestions,
					isEnabled = aioSettings.enableDailyContentSuggestion
				),
				SettingViewConfig(
					viewId = R.id.txt_play_notification_sound,
					isEnabled = aioSettings.downloadPlayNotificationSound
				),
				SettingViewConfig(
					viewId = R.id.txt_wifi_only_downloads,
					isEnabled = aioSettings.downloadWifiOnly
				),
				SettingViewConfig(
					viewId = R.id.txt_single_click_open,
					isEnabled = aioSettings.openDownloadedFileOnSingleClick
				),
				SettingViewConfig(
					viewId = R.id.txt_hide_task_notifications,
					isEnabled = aioSettings.downloadHideNotification
				),
				SettingViewConfig(
					viewId = R.id.txt_enable_adblock,
					isEnabled = aioSettings.browserEnableAdblocker
				),
				SettingViewConfig(
					viewId = R.id.txt_enable_popup_blocker,
					isEnabled = aioSettings.browserEnablePopupBlocker
				),
				SettingViewConfig(
					viewId = R.id.txt_show_image_on_web,
					isEnabled = aioSettings.browserEnableImages
				),
				SettingViewConfig(
					viewId = R.id.txt_enable_video_grabber,
					isEnabled = aioSettings.browserEnableVideoGrabber
				),
			).forEach { config ->
				layout.findViewById<TextView>(config.viewId)?.updateEndDrawable(config.isEnabled)
			}
		} ?: run {
			logger.d("Failed to update UI: safeFragmentLayoutRef is null")
		}
	}

	/**
	 * Restarts the application after user confirmation
	 * Useful for applying major changes or recovering from unexpected states
	 */
	fun restartApplication() {
		logger.d("Showing Restart Application confirmation dialog")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivityRef ->
			MsgDialogUtils.getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isTitleVisible = true,
				titleText = getText(R.string.title_are_you_sure_about_this),
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = { positiveButton ->
					positiveButton.setLeftSideDrawable(R.drawable.ic_button_exit)
					positiveButton.setText(R.string.title_restart_application)
				},
				messageTextViewCustomize = { msgTextView ->
					msgTextView.setText(R.string.text_cation_msg_of_restarting_application)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("User confirmed application restart")
					processedToRestart()
				}
			}?.show()
		} ?: run {
			logger.d("Failed to show restart dialog: safeMotherActivityRef is null")
		}
	}

	/**
	 * Updates the end drawable (checkmark or empty circle) based on the setting state
	 *
	 * @param isEnabled Whether the setting is enabled (show checkmark) or disabled (show empty circle)
	 */
	private fun TextView.updateEndDrawable(isEnabled: Boolean) {
		val endDrawableRes = if (isEnabled) {
			R.drawable.ic_button_checked_circle_small
		} else {
			R.drawable.ic_button_unchecked_circle_small
		}
		val current = compoundDrawables
		val checkedDrawable = getDrawable(context, endDrawableRes)
		setCompoundDrawablesWithIntrinsicBounds(current[0], current[1], checkedDrawable, current[3])
	}

	/**
	 * Constructs the Play Store sharing message
	 *
	 * @param context The application context
	 * @return The formatted share text containing app name and official page URL
	 */
	private fun getApplicationShareText(context: Context): String {
		val appName = context.getString(R.string.title_aio_video_downloader)
		val githubOfficialPage = context.getString(R.string.text_aio_official_page_url)
		return context.getString(R.string.text_sharing_app_msg, appName, githubOfficialPage)
			.trimIndent()
	}

	/**
	 * Executes the actual application restart
	 * Relaunches the main activity and exits the current process
	 */
	private fun processedToRestart() {
		logger.d("Processing application restart")
		val context = AIOApp.INSTANCE
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		Runtime.getRuntime().exit(0)
	}

	/**
	 * Data class holding mapping of setting TextView and its state
	 *
	 * @property viewId The resource ID of the TextView
	 * @property isEnabled Whether the setting is enabled or disabled
	 */
	data class SettingViewConfig(@field:IdRes val viewId: Int, val isEnabled: Boolean)
}