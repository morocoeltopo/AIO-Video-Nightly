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
import app.ui.main.fragments.settings.dialogs.DownloadLocation
import app.ui.others.information.UserFeedbackActivity
import app.ui.others.startup.LanguagePickerDialog
import com.aio.R
import kotlinx.coroutines.delay
import lib.device.ShareUtility
import lib.networks.URLUtility.ensureHttps
import lib.networks.URLUtility.isValidDomain
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
 */
class SettingsOnClickLogic(private val settingsFragment: SettingsFragment) {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to avoid leaks
	private val safeSettingsFragmentRef = WeakReference(settingsFragment).get()

	/** Show a dialog to select default download location */
	fun setDefaultDownloadLocationPicker() {
		logger.d("Opening Download Location Picker")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivity ->
			DownloadLocation(baseActivity = safeMotherActivity).show()
		}
	}

	/** Launches the language picker and restarts the app upon change */
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
					logger.d("Language change confirmed → restarting app")
					close()
					LanguagePickerDialog(safeMotherActivityRef).apply {
						getDialogBuilder().setCancelable(true)
						onApplyListener = {
							close()
							restartApp(shouldKillProcess = true)
						}
					}.show()
				}
			}?.show()
		}
	}

	fun changeDefaultContentRegion() {
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			doSomeVibration(50)
			showToast(msgId = R.string.text_feature_isnt_available_yet)
		}
	}

	fun enableDailyContentSuggestions() {
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			doSomeVibration(50)
			showToast(msgId = R.string.text_feature_isnt_available_yet)
		}
	}

	/** Displays a dialog to set the default homepage for the in-app browser */
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
					logger.d("User entered homepage: $userEnteredURL")
					if (isValidDomain(userEnteredURL)) {
						val finalNormalizedURL = ensureHttps(userEnteredURL) ?: userEnteredURL
						aioSettings.browserDefaultHomepage = finalNormalizedURL
						aioSettings.updateInStorage()
						logger.d("Homepage updated: $finalNormalizedURL")
						dialogBuilder.close()
						showToast(msgId = R.string.title_successful)
					} else {
						logger.d("Invalid homepage URL entered")
						safeActivityRef.doSomeVibration(50)
						showToast(msgId = R.string.text_invalid_url)
					}
				}
				dialogBuilder.show()
				delay(200, object : OnTaskFinishListener {
					override fun afterDelay() {
						editTextURL.requestFocus()
						showOnScreenKeyboard(safeActivityRef, editTextURL)
					}
				})
			}
		} catch (error: Exception) {
			logger.d("Error setting homepage: ${error.message}")
			error.printStackTrace()
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	fun toggleBrowserBrowserAdBlocker() {
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

	/** Toggle popup blocker setting in browser */
	fun toggleBrowserPopupAdBlocker() {
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

	fun toggleBrowserWebImages() {
		try {
			aioSettings.browserEnableImages = !aioSettings.browserEnableImages
			aioSettings.updateInStorage()
			logger.d("Popup browser enable images: ${aioSettings.browserEnableImages}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.d("Error toggling browser enable images: ${error.message}")
			error.printStackTrace()
		}
	}

	/** Toggle video grabber feature in browser */
	fun toggleBrowserVideoGrabber() {
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

	fun changeDefaultDownloadFolder(){
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			doSomeVibration(50)
			showToast(msgId = R.string.text_feature_isnt_available_yet)
		}
	}

	/** Toggle visibility of download notification */
	fun toggleHideDownloadNotification() {
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

	/** Toggle "Wi-Fi only" mode for downloads */
	fun toggleWifiOnlyDownload() {
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

	/** Toggle sound played when a download completes */
	fun toggleDownloadNotificationSound() {
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

	/** Opens advanced settings placeholder (currently shows not available message). */
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
		}
	}

	/** Opens advanced settings placeholder (currently shows not available message). */
	fun openAdvanceBrowserSettings() {
		logger.d("Opening Advanced Settings For Browser")
		safeSettingsFragmentRef?.safeMotherActivityRef
			?.openActivity(
				activity = AdvBrowserSettingsActivity::class.java,
				shouldAnimate = true
			)
	}

	/** Navigate to Terms & Conditions screen */
	fun showTermsConditionActivity() {
		logger.d("Opening Terms & Conditions")
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_terms_conditions_url).toString()
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Terms: ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		}
	}

	/** Navigate to Privacy Policy screen */
	fun showPrivacyPolicyActivity() {
		logger.d("Opening Privacy Policy")
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_privacy_policy_url).toString()
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Privacy Policy: ${error.message}")
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		}
	}

	/** Navigate to user feedback screen */
	fun openUserFeedbackActivity() {
		logger.d("Opening User Feedback Activity")
		safeSettingsFragmentRef?.safeMotherActivityRef?.openActivity(
			UserFeedbackActivity::class.java, shouldAnimate = false
		)
	}

	/** Open application details screen in Android settings */
	fun openApplicationInformation() {
		logger.d("Opening Application Info")
		safeSettingsFragmentRef?.safeBaseActivityRef?.openAppInfoSetting()
	}

	/** Share the app with others via system sharing intent */
	fun shareApplicationWithFriends() {
		logger.d("Sharing application")
		ShareUtility.shareText(
			context = safeSettingsFragmentRef?.safeMotherActivityRef,
			title = getText(R.string.text_share_with_others),
			text = getShareText(AIOApp.INSTANCE)
		)
	}

	/** Constructs the Play Store sharing message */
	private fun getShareText(context: Context): String {
		val appName = context.getString(R.string.title_aio_video_downloader)
		val githubOfficialPage = context.getString(R.string.text_aio_official_page_url)
		return context.getString(R.string.text_sharing_app_msg, appName, githubOfficialPage)
			.trimIndent()
	}

	/** Check for new version update */
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
		}
	}

	/** Update the end icon of each setting option based on current settings */
	fun updateSettingStateUI() {
		logger.d("Updating Setting State UI")
		safeSettingsFragmentRef?.safeFragmentLayoutRef?.let { layout ->
			listOf(
				SettingViewConfig(
					viewId = R.id.txt_play_notification_sound,
					isEnabled = aioSettings.downloadPlayNotificationSound
				),
				SettingViewConfig(
					viewId = R.id.txt_wifi_only_downloads,
					isEnabled = aioSettings.downloadWifiOnly
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
		}
	}

	/** Update the end drawable (checkmark or empty circle) based on the setting state */
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
	 * Restarts the application after user confirmation.
	 * Useful for applying major changes or recovering from unexpected states.
	 */
	fun restartApplication() {
		logger.d("Restart Application dialog opened")
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
			)?.apply { setOnClickForPositiveButton { processedToRestart() } }?.show()
		}
	}

	/** Executes the actual restart by relaunching the main activity and exiting process */
	private fun processedToRestart() {
		logger.d("Processing application restart")
		val context = AIOApp.INSTANCE
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		Runtime.getRuntime().exit(0)
	}

	/** Holds mapping of setting TextView and its state */
	data class SettingViewConfig(@field:IdRes val viewId: Int, val isEnabled: Boolean)
}
