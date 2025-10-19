package app.ui.main.fragments.settings

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.net.toUri
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.settings.AIOSettings.Companion.AIO_SETTING_DARK_MODE_FILE_NAME
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
import lib.process.IntentHelperUtils.openInstagramApp
import lib.process.LogHelperUtils
import lib.process.OSProcessUtils.restartApp
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils
import lib.ui.ViewUtility
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File
import java.lang.ref.WeakReference

/**
 * Handles click logic for all settings options within SettingsFragment.
 *
 * All functional and toggle events (like changing settings, launching dialogs,
 * updating preferences, opening legal docs, launching browser, etc.) are managed here.
 * This class is also responsible for updating UI state on user action.
 *
 * Logging in this class is handled via logger.d (for event tracking) and logger.e
 * (for errors) for maintainable, searchable logs.
 *
 * @param settingsFragment Primary reference to the parent SettingsFragment for context and UI access.
 */
class SettingsOnClickLogic(private val settingsFragment: SettingsFragment) {

	/** Logger instance for diagnostics. */
	private val logger = LogHelperUtils.from(javaClass)

	/** Safe reference to prevent leaks across lifecycle changes. */
	private val settingsFragmentRef = WeakReference(settingsFragment).get()

	/**
	 * Launches the username editor. Currently, only vibrates and shows feature upcoming dialog.
	 */
	fun showUsernameEditor() {
		settingsFragmentRef?.safeMotherActivityRef?.apply {
			doSomeVibration(50)
			showUpcomingFeatures()
		}
	}

	/**
	 * Opens login or registration dialog option. Currently, only vibrates and shows feature upcoming dialog.
	 */
	fun showLoginOrRegistrationDialog() {
		settingsFragmentRef?.safeMotherActivityRef?.apply {
			doSomeVibration(50)
			showUpcomingFeatures()
		}
	}

	/**
	 * Opens the dialog allowing user to pick or change their download location.
	 */
	fun showDownloadLocationPicker() {
		logger.d("→ Download Location Picker")
		settingsFragmentRef?.safeMotherActivityRef?.let { activity ->
			DownloadLocationSelector(baseActivity = activity).show()
		} ?: logger.d("× Picker failed: Activity null")
	}

	/**
	 * Launches language picker dialog with a preliminary "experimental" warning.
	 */
	fun showLanguageChanger() {
		logger.d("→ Language Picker")
		settingsFragmentRef?.safeMotherActivityRef?.let { activity ->
			MsgDialogUtils.getMessageDialog(
				baseActivityInf = activity,
				isTitleVisible = true,
				titleText = getText(R.string.title_experimental_feature),
				messageTextViewCustomize = { it.setText(R.string.text_feature_is_experimental_msg) },
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = {
					it.setText(R.string.title_proceed)
					it.setLeftSideDrawable(R.drawable.ic_button_arrow_next)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("✔ Proceeding to LanguagePickerDialog")
					close()
					LanguagePickerDialog(activity).apply {
						getDialogBuilder().setCancelable(true)
						onApplyListener = {
							logger.d("✔ Language selected → Restarting app")
							close()
							restartApp(shouldKillProcess = true)
						}
					}.show()
				}
			}?.show()
		} ?: logger.d("× Language Picker failed: Activity null")
	}

	/**
	 * Toggles the dark mode UI setting using a flag file.
	 * Handles creation/deletion of the config file and triggers UI refresh.
	 */
	fun togglesDarkModeUISettings() {
		logger.d("Toggling Dark Mode UI setting")
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val tempFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)
				if (tempFile.exists()) tempFile.delete() else tempFile.createNewFile()
				aioSettings.updateInStorage()
				ThreadsUtility.executeOnMain {
					updateSettingStateUI()
					settingsFragmentRef?.safeMotherActivityRef?.apply {
						ViewUtility.changesSystemTheme(this)
						logger.d("Dark Mode UI is now: ${tempFile.exists()}")
					}
				}
			} catch (error: Exception) {
				logger.e("Error toggling Dark Mode UI: ${error.message}", error)
			}
		})
	}

	/**
	 * Opens a region selector dialog and restarts the app after new region is chosen.
	 */
	fun changeDefaultContentRegion() {
		logger.d("→ Content Region Selector")
		settingsFragmentRef?.safeMotherActivityRef?.apply {
			ContentRegionSelector(this).apply {
				getDialogBuilder().setCancelable(true)
				onApplyListener = {
					logger.d("✔ Region selected → Restarting app")
					close()
					restartApp(shouldKillProcess = true)
				}
			}.show()
		} ?: logger.d("× Failed: Activity null (Region Selector)")
	}

	/**
	 * Toggles daily content suggestion notifications.
	 */
	fun toggleDailyContentSuggestions() {
		logger.d("→ Toggle Daily Suggestions")
		settingsFragmentRef?.safeMotherActivityRef?.apply {
			try {
				val contentSuggestion = aioSettings.enableDailyContentSuggestion
				aioSettings.enableDailyContentSuggestion = !contentSuggestion
				aioSettings.updateInStorage()
				logger.d("✔ Daily suggestions: $contentSuggestion")
				updateSettingStateUI()
			} catch (error: Exception) {
				logger.e("× Error toggling suggestions: ${error.message}", error)
			}
		} ?: logger.d("× Failed: Activity null (Daily Suggestions)")
	}

	/**
	 * Launches a folder picker dialog for download location selection.
	 */
	fun changeDefaultDownloadFolder() {
		logger.d("→ Custom Download Folder Selector")
		settingsFragmentRef?.safeMotherActivityRef?.apply {
			CustomDownloadFolderSelector(this).show()
		} ?: logger.d("× Failed: Activity null (Folder Selector)")
	}

	/**
	 * Toggles whether download notifications should be displayed or suppressed.
	 */
	fun toggleHideDownloadNotification() {
		logger.d("→ Toggle Download Notification")
		try {
			val hideNotification = aioSettings.downloadHideNotification
			aioSettings.downloadHideNotification = !hideNotification
			aioSettings.updateInStorage()
			logger.d("✔ Notifications hidden: $hideNotification")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("× Error toggling notifications: ${error.message}", error)
		}
	}

	/**
	 * Toggles Wi-Fi-only downloads.
	 */
	fun toggleWifiOnlyDownload() {
		logger.d("→ Toggle Wi-Fi Only Mode")
		try {
			aioSettings.downloadWifiOnly = !aioSettings.downloadWifiOnly
			aioSettings.updateInStorage()
			logger.d("✔ Wi-Fi only: ${aioSettings.downloadWifiOnly}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("× Error toggling Wi-Fi only: ${error.message}", error)
		}
	}

	/**
	 * Toggles single-click-to-open-file download behavior.
	 */
	fun toggleSingleClickToOpenFile() {
		logger.d("Toggling single-click open file setting")
		try {
			val singleClickOpen = aioSettings.openDownloadedFileOnSingleClick
			aioSettings.openDownloadedFileOnSingleClick = !singleClickOpen
			aioSettings.updateInStorage()
			logger.d("Single-click open file is now: $singleClickOpen")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling single-click open file: ${error.message}", error)
		}
	}

	/**
	 * Toggles notification sound for download completion.
	 */
	fun toggleDownloadNotificationSound() {
		logger.d("Toggling Download Notification Sound")
		try {
			aioSettings.downloadPlayNotificationSound = !aioSettings.downloadPlayNotificationSound
			aioSettings.updateInStorage()
			logger.d("Download Sound Enabled: ${aioSettings.downloadPlayNotificationSound}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling download sound: ${error.message}", error)
		}
	}

	/**
	 * Displays a not-yet-implemented advanced download settings dialog.
	 */
	fun openAdvanceDownloadsSettings() {
		logger.d("Opening Advanced Downloads Settings (not implemented)")
		settingsFragmentRef?.safeMotherActivityRef.let {
			it?.doSomeVibration(50)
			MsgDialogUtils.showMessageDialog(
				baseActivityInf = it,
				isTitleVisible = true,
				titleText = getText(R.string.title_feature_isnt_implemented),
				messageTextViewCustomize = { msgTextView ->
					msgTextView.setText(R.string.text_feature_isnt_available_yet)
				}, isNegativeButtonVisible = false
			)
		} ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Prompts user to enter a browser homepage URL and validates input before saving.
	 */
	fun setBrowserDefaultHomepage() {
		logger.d("Opening Browser Homepage dialog")
		try {
			settingsFragmentRef?.safeMotherActivityRef?.let { activityRef ->
				val dialogBuilder = DialogBuilder(activityRef)
				dialogBuilder.setView(R.layout.dialog_browser_homepage_1)

				val dialogLayout = dialogBuilder.view
				val stringResId = R.string.title_current_homepage
				val formatArgs = aioSettings.browserDefaultHomepage
				val homepageString = activityRef.getString(stringResId, formatArgs)

				dialogLayout.findViewById<TextView>(R.id.txt_current_homepage).text = homepageString
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
						showToast(activityInf = activityRef, msgId = R.string.title_successful)
					} else {
						logger.d("Invalid homepage URL entered: $userEnteredURL")
						activityRef.doSomeVibration(50)
						showToast(activityInf = activityRef, msgId = R.string.title_invalid_url)
					}
				}
				dialogBuilder.show()
				delay(200, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Showing on-screen keyboard for URL input")
						editTextURL.requestFocus()
						showOnScreenKeyboard(activityRef, editTextURL)
					}
				})
			} ?: run { logger.d("Failed: null activity") }
		} catch (error: Exception) {
			logger.e("Error setting browser homepage: ${error.message}", error)
			showToast(
				activityInf = settingsFragmentRef?.safeMotherActivityRef,
				msgId = R.string.title_something_went_wrong
			)
		}
	}

	/**
	 * Toggles ad blocker preference for the browser.
	 */
	fun toggleBrowserBrowserAdBlocker() {
		logger.d("Toggling Browser Ad Blocker")
		try {
			val browserEnableAdblocker = aioSettings.browserEnableAdblocker
			aioSettings.browserEnableAdblocker = !browserEnableAdblocker
			aioSettings.updateInStorage()
			logger.d("Browser Ad Blocker toggled: $browserEnableAdblocker")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling browser ad blocker: ${error.message}", error)
		}
	}

	/**
	 * Toggles popup blocker preference for the browser.
	 */
	fun toggleBrowserPopupAdBlocker() {
		logger.d("Toggling Browser Popup Blocker")
		try {
			val browserEnablePopupBlocker = aioSettings.browserEnablePopupBlocker
			aioSettings.browserEnablePopupBlocker = !browserEnablePopupBlocker
			aioSettings.updateInStorage()
			logger.d("Popup Blocker toggled: $browserEnablePopupBlocker")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling popup blocker: ${error.message}", error)
		}
	}

	/**
	 * Toggles whether images are allowed to display in the browser.
	 */
	fun toggleBrowserWebImages() {
		logger.d("Toggling Browser Web Images")
		try {
			aioSettings.browserEnableImages = !aioSettings.browserEnableImages
			aioSettings.updateInStorage()
			logger.d("Browser enable images: ${aioSettings.browserEnableImages}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling browser enable images: ${error.message}", error)
		}
	}

	/**
	 * Toggles video grabber option for the browser.
	 */
	fun toggleBrowserVideoGrabber() {
		logger.d("Toggling Browser Video Grabber")
		try {
			val enableVideoGrabber = aioSettings.browserEnableVideoGrabber
			aioSettings.browserEnableVideoGrabber = !enableVideoGrabber
			aioSettings.updateInStorage()
			logger.d("Video Grabber toggled: $enableVideoGrabber")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling video grabber: ${error.message}", error)
		}
	}

	/**
	 * Opens advanced browser settings activity.
	 */
	fun openAdvanceBrowserSettings() {
		logger.d("Opening Advanced Settings For Browser")
		settingsFragmentRef?.safeMotherActivityRef
			?.openActivity(
				activity = AdvBrowserSettingsActivity::class.java,
				shouldAnimate = true
			) ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Initiates an intent to share the app with other users.
	 */
	fun shareApplicationWithFriends() {
		logger.d("Sharing application with friends")
		settingsFragmentRef?.safeMotherActivityRef?.let { activityRef ->
			ShareUtility.shareText(
				context = activityRef,
				title = getText(R.string.title_share_with_others),
				text = getApplicationShareText(activityRef)
			)
		} ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Opens the feedback activity for collecting user comments.
	 */
	fun openUserFeedbackActivity() {
		logger.d("Opening User Feedback Activity")
		settingsFragmentRef?.safeMotherActivityRef?.openActivity(
			UserFeedbackActivity::class.java, shouldAnimate = false
		) ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Opens app's detailed info page in system settings.
	 */
	fun openApplicationInformation() {
		logger.d("Opening Application Info in system settings")
		val safeBaseActivityRef = settingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.openAppInfoSetting() ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Launches the privacy policy in a browser if possible, with fallback error handling.
	 */
	fun showPrivacyPolicyActivity() {
		logger.d("Opening Privacy Policy in browser")
		val safeBaseActivityRef = settingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.let { activityRef ->
			try {
				val urlResId = R.string.text_aio_official_privacy_policy_url
				val uri = getText(urlResId)
				logger.d("Privacy Policy URL: $uri")
				activityRef.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Privacy Policy: ${error.message}")
				error.printStackTrace()
				val toastMsgId = R.string.title_please_install_web_browser
				showToast(activityInf = activityRef, msgId = toastMsgId)
			}
		} ?: run { logger.d("Failed: null activity") }
	}

	/**
	 * Launches the terms and conditions page in a browser.
	 */
	fun showTermsConditionActivity() {
		logger.d("Opening Terms & Conditions in browser")
		val safeBaseActivityRef = settingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.let { activityRef ->
			try {
				val urlResId = R.string.text_aio_official_terms_conditions_url
				val uri = getText(urlResId)
				logger.d("Terms & Conditions URL: $uri")
				activityRef.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.e("Failed to open Terms: ${error.message}", error)
				val toastMsgId = R.string.title_please_install_web_browser
				showToast(activityInf = activityRef, msgId = toastMsgId)
			}
		} ?: run {
			logger.d("Failed: null activity")
		}
	}

	/**
	 * Checks for an available update, shows loading UI, then shows site or toast as appropriate.
	 */
	fun checkForNewApkVersion() {
		logger.d("Check for APK update")
		settingsFragmentRef?.safeBaseActivityRef?.let { activityRef ->
			ThreadsUtility.executeInBackground(codeBlock = {
				var waitingDialog: WaitingDialog? = null
				ThreadsUtility.executeOnMain {
					waitingDialog = WaitingDialog(
						baseActivityInf = activityRef,
						loadingMessage = getText(R.string.title_checking_for_new_update),
						isCancelable = false,
					)
					waitingDialog.show()
					delay(1000)
				}

				if (AIOUpdater().isNewUpdateAvailable()) {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("Update found → opening site")
					activityRef.openApplicationOfficialSite()
				} else {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("Already latest version")
					ThreadsUtility.executeOnMain {
						activityRef.doSomeVibration(50)
						val msgResId = R.string.title_you_using_latest_version
						showToast(activityInf = activityRef, msgId = msgResId)
					}
				}
			}, errorHandler = {
				logger.d("Update check failed: ${it.message}")
				activityRef.doSomeVibration(50)
				val toastMsgId = R.string.title_something_went_wrong
				showToast(activityInf = activityRef, msgId = toastMsgId)
			})
		} ?: run { logger.d("Update check failed: null activity") }
	}

	/**
	 * Refreshes settings UI by updating enabled/disabled indicator icons.
	 */
	fun updateSettingStateUI() {
		logger.d("Update settings UI")
		val darkModeTempConfigFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)
		settingsFragmentRef?.safeFragmentLayoutRef?.let { layout ->
			listOf(
				SettingViewConfig(R.id.txt_dark_mode_ui, darkModeTempConfigFile.exists()),
				SettingViewConfig(R.id.txt_daily_suggestions, aioSettings.enableDailyContentSuggestion),
				SettingViewConfig(R.id.txt_play_notification_sound, aioSettings.downloadPlayNotificationSound),
				SettingViewConfig(R.id.txt_wifi_only_downloads, aioSettings.downloadWifiOnly),
				SettingViewConfig(R.id.txt_single_click_open, aioSettings.openDownloadedFileOnSingleClick),
				SettingViewConfig(R.id.txt_hide_task_notifications, aioSettings.downloadHideNotification),
				SettingViewConfig(R.id.txt_enable_adblock, aioSettings.browserEnableAdblocker),
				SettingViewConfig(R.id.txt_enable_popup_blocker, aioSettings.browserEnablePopupBlocker),
				SettingViewConfig(R.id.txt_show_image_on_web, aioSettings.browserEnableImages),
				SettingViewConfig(R.id.txt_enable_video_grabber, aioSettings.browserEnableVideoGrabber),
			).forEach { config ->
				layout.findViewById<TextView>(config.viewId)?.updateEndDrawable(config.isEnabled)
			}
		} ?: run {
			logger.d("UI update failed")
		}
	}

	/**
	 * Shows a restart confirmation dialog and restarts the app if confirmed.
	 */
	fun restartApplication() {
		logger.d("Show restart dialog")
		settingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivityRef ->
			val msgResId = R.string.text_cation_msg_of_restarting_application
			MsgDialogUtils.getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isTitleVisible = true,
				titleText = getText(R.string.title_are_you_sure_about_this),
				messageTextViewCustomize = { it.setText(msgResId) },
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = {
					it.setLeftSideDrawable(R.drawable.ic_button_exit)
					it.setText(R.string.title_restart_application)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("Restart confirmed")
					restartApplicationProcess()
				}
			}?.show()
		} ?: run { logger.d("Restart dialog failed") }
	}

	/**
	 * Attempts to launch the Instagram app to the developer's page.
	 */
	fun followDeveloperAtInstagram() {
		try {
			settingsFragmentRef?.safeMotherActivityRef?.let {
				openInstagramApp(it, "https://www.instagram.com/shibafoss/")
			}
		} catch (error: Exception) {
			logger.e("Instagram open failed", error)
		}
	}

	/**
	 * Updates a TextView's end drawable to indicate the enabled/disabled state.
	 *
	 * @param isEnabled true if checked/enabled, false for unchecked/disabled.
	 */
	private fun TextView.updateEndDrawable(isEnabled: Boolean) {
		val endDrawableRes = if (isEnabled) R.drawable.ic_button_checked_circle_small
		else R.drawable.ic_button_unchecked_circle_small
		val current = compoundDrawables
		val checkedDrawable = getDrawable(context, endDrawableRes)
		setCompoundDrawablesWithIntrinsicBounds(current[0], current[1], checkedDrawable, current[3])
	}

	/**
	 * Builds localized text for sharing the application's Play Store or homepage details.
	 *
	 * @param context Context for fetching string resources.
	 * @return A shareable message string.
	 */
	private fun getApplicationShareText(context: Context): String {
		val appName = context.getString(R.string.title_aio_video_downloader)
		val githubOfficialPage = context.getString(R.string.text_aio_official_page_url)
		return context.getString(R.string.text_sharing_app_msg, appName, githubOfficialPage)
			.trimIndent()
	}

	/**
	 * Restarts the application process by launching the main intent and killing process.
	 */
	private fun restartApplicationProcess() {
		val context = INSTANCE
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		Runtime.getRuntime().exit(0)
	}

	/**
	 * Holds view configuration used for batch updating UI state.
	 */
	data class SettingViewConfig(@field:IdRes val viewId: Int, val isEnabled: Boolean)
}