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
 * Class responsible for handling logic when interacting with Settings options.
 */
class SettingsOnClickLogic(private val settingsFragment: SettingsFragment) {

	private val logger = LogHelperUtils.from(javaClass)

	// Keeps a weak reference to the fragment to avoid memory leaks.
	private val safeSettingsFragmentRef = WeakReference(settingsFragment).get()

	/** Show a dialog to select default download location */
	fun setDefaultDownloadLocationPicker() {
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { safeMotherActivity ->
			DownloadLocation(baseActivity = safeMotherActivity).show()
		}
	}

	/** Launches the language picker and restarts the app upon change */
	fun showApplicationLanguageChanger() {
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

	/** Displays a dialog to set the default homepage for the in-app browser */
	fun setBrowserDefaultHomepage() {
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
					if (isValidDomain(userEnteredURL)) {
						val finalNormalizedURL = ensureHttps(userEnteredURL) ?: userEnteredURL
						aioSettings.browserDefaultHomepage = finalNormalizedURL
						aioSettings.updateInStorage()
						dialogBuilder.close()
						showToast(msgId = R.string.title_successful)
					} else {
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
			error.printStackTrace()
			showToast(msgId = R.string.text_something_went_wrong)
		}
	}

	/** Toggle popup blocker setting in browser */
	fun toggleBrowserPopupAdBlocker() {
		try {
			aioSettings.browserEnablePopupBlocker = !aioSettings.browserEnablePopupBlocker
			aioSettings.updateInStorage()
			updateSettingStateUI()
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/** Toggle video grabber feature in browser */
	fun toggleBrowserVideoGrabber() {
		try {
			aioSettings.browserEnableVideoGrabber = !aioSettings.browserEnableVideoGrabber
			aioSettings.updateInStorage()
			updateSettingStateUI()
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/** Toggle visibility of download notification */
	fun toggleHideDownloadNotification() {
		try {
			aioSettings.downloadHideNotification = !aioSettings.downloadHideNotification
			aioSettings.updateInStorage()
			updateSettingStateUI()
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/** Toggle "Wi-Fi only" mode for downloads */
	fun toggleWifiOnlyDownload() {
		try {
			aioSettings.downloadWifiOnly = !aioSettings.downloadWifiOnly
			aioSettings.updateInStorage()
			updateSettingStateUI()
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/** Toggle sound played when a download completes */
	fun toggleDownloadNotificationSound() {
		try {
			aioSettings.downloadPlayNotificationSound =
				!aioSettings.downloadPlayNotificationSound
			aioSettings.updateInStorage()
			updateSettingStateUI()
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Opens advanced settings placeholder (currently shows "not available" message).
	 */
	fun openAdvanceApplicationSettings() {
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

	/** Navigate to Terms & Conditions screen */
	fun showTermsConditionActivity() {
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_terms_conditions_url).toString()
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		}
	}

	/** Navigate to Privacy Policy screen */
	fun showPrivacyPolicyActivity() {
		safeSettingsFragmentRef?.safeBaseActivityRef?.apply {
			try {
				val uri = getText(R.string.text_aio_official_privacy_policy_url).toString()
				startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				error.printStackTrace()
				showToast(msgId = R.string.text_please_install_web_browser)
			}
		}
	}

	/** Navigate to user feedback screen */
	fun openUserFeedbackActivity() {
		safeSettingsFragmentRef?.safeMotherActivityRef?.openActivity(
			UserFeedbackActivity::class.java, shouldAnimate = false
		)
	}

	/** Open application details screen in Android settings */
	fun openApplicationInformation() {
		safeSettingsFragmentRef?.safeBaseActivityRef?.openAppInfoSetting()
	}

	/** Share the app with others via system sharing intent */
	fun shareApplicationWithFriends() {
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

				ThreadsUtility.executeOnMain { waitingDialog?.close() }
				if (AIOUpdater().isNewUpdateAvailable()) {
					safeBaseActivityRef.openApplicationOfficialSite()
				} else {
					ThreadsUtility.executeOnMain {
						safeBaseActivityRef.doSomeVibration(50)
						showToast(msgId = R.string.text_you_are_using_the_latest_version)
					}
				}
			}, errorHandler = {
				safeBaseActivityRef.doSomeVibration(50)
				showToast(msgId = R.string.text_something_went_wrong)
			})
		}
	}

	/** Update the end icon of each setting option based on current settings */
	fun updateSettingStateUI() {
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
					viewId = R.id.txt_enable_video_grabber,
					isEnabled = aioSettings.browserEnableVideoGrabber
				),
				SettingViewConfig(
					viewId = R.id.txt_enable_popup_blocker,
					isEnabled = aioSettings.browserEnablePopupBlocker
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
	 * Restarts the application. This can be useful when something goes wrong,
	 * providing users with a quick way to recover from unexpected behavior.
	 */
	fun restartApplication() {
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

	/**
	 * Function that execute the actual restarting application by the exiting the system process id.
	 */
	private fun processedToRestart() {
		val context = AIOApp.INSTANCE
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		Runtime.getRuntime().exit(0) // Forcefully exits to ensure full restart
	}

	/** Data class representing a view ID and whether the setting is enabled */
	data class SettingViewConfig(@field:IdRes val viewId: Int, val isEnabled: Boolean)
}
