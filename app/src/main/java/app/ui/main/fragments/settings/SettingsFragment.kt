package app.ui.main.fragments.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import app.core.bases.BaseFragment
import app.ui.main.MotherActivity
import com.aio.R
import lib.device.AppVersionUtility.versionCode
import lib.device.AppVersionUtility.versionName
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import java.lang.ref.WeakReference

/**
 * Fragment responsible for displaying and managing the application settings UI.
 *
 * This fragment primarily handles:
 * - Displaying app version information (name and build code)
 * - Providing settings navigation (e.g., downloads, language, appearance)
 * - Managing toggle states for preferences (WiFi-only downloads, ad-block, etc.)
 * - Linking to legal and feedback sections
 * - Integrating update checking and restart options
 *
 * The fragment uses weak references to avoid memory leaks and works in coordination
 * with [SettingsOnClickLogic] for managing user click interactions.
 */
class SettingsFragment : BaseFragment() {

	/** Logger utility for internal debugging and event tracing. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the SettingsFragment instance to prevent memory leaks.
	 * This allows safe reference passing to external logic classes.
	 */
	val safeSettingsFragmentRef = WeakReference(this).get()

	/**
	 * Weak lazy reference to the parent [MotherActivity] to safely access activity-level
	 * functionality without risking context leaks.
	 */
	val safeMotherActivityRef by lazy { WeakReference(safeBaseActivityRef as MotherActivity).get() }

	/**
	 * Logic handler that defines and executes all click-based actions within the settings fragment.
	 */
	var settingsOnClickLogic: SettingsOnClickLogic? = null

	/**
	 * Provides the layout resource identifier associated with this fragment.
	 *
	 * @return The ID of the layout file to inflate for this fragment.
	 */
	override fun getLayoutResId(): Int {
		logger.d("Providing layout resource ID for SettingsFragment")
		return R.layout.frag_settings_1_main_1
	}

	/**
	 * Initializes fragment logic after the layout is fully loaded and ready.
	 * This is the starting point for UI setup and click-binding operations.
	 *
	 * @param layoutView Inflated root view of the fragment.
	 * @param state Optional saved instance state bundle.
	 */
	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		logger.d("onAfterLayoutLoad() called: Initializing views and listeners")
		try {
			safeSettingsFragmentRef?.let { fragmentRef ->
				safeFragmentLayoutRef?.let { layoutRef ->
					registerSelfReferenceInMotherActivity()
					displayApplicationVersion(layoutRef)
					setupViewsOnClickEvents(fragmentRef, layoutRef)
				}
			}
		} catch (error: Exception) {
			logger.e("Exception during onAfterLayoutLoad()", error)
		}
	}

	/**
	 * Called when the fragment resumes. It refreshes UI states and ensures
	 * synchronization with the host activity.
	 */
	override fun onResumeFragment() {
		logger.d("onResumeFragment() called: Updating UI and re-registering references")
		registerSelfReferenceInMotherActivity()
		try {
			settingsOnClickLogic?.updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Exception while updating settings state UI", error)
		}
	}

	/**
	 * Lifecycle callback for when the fragment goes into the background.
	 * Currently left empty since no action is required.
	 */
	override fun onPauseFragment() {
		logger.d("onPauseFragment() called: No cleanup necessary")
	}

	/**
	 * Invoked before the fragment's view is destroyed.
	 * Cleans up weak references and releases any held resources.
	 */
	override fun onDestroyView() {
		logger.d("onDestroyView() called: Cleaning up fragment references")
		unregisterSelfReferenceInMotherActivity()
		super.onDestroyView()
	}

	/**
	 * Registers this fragment instance with its hosting [MotherActivity].
	 * This ensures the parent can identify and communicate with this fragment.
	 */
	private fun registerSelfReferenceInMotherActivity() {
		logger.d("Registering SettingsFragment reference with MotherActivity")
		try {
			safeMotherActivityRef?.settingsFragment = safeSettingsFragmentRef
			safeMotherActivityRef?.sideNavigation?.closeDrawerNavigation()
		} catch (error: Exception) {
			logger.e("Error while registering fragment with MotherActivity", error)
		}
	}

	/**
	 * Clears this fragment's reference from [MotherActivity] to prevent memory leaks.
	 */
	private fun unregisterSelfReferenceInMotherActivity() {
		logger.d("Unregistering SettingsFragment reference from MotherActivity")
		try {
			safeMotherActivityRef?.settingsFragment = null
		} catch (error: Exception) {
			logger.e("Error during fragment unregistration", error)
		}
	}
	/**
	 * Configures all onClick listeners for the settings controls.
	 * Each view triggers a corresponding handler in [SettingsOnClickLogic].
	 *
	 * @param settingsFragmentRef Reference to the current settings fragment.
	 * @param fragmentLayout Root view from which to resolve view IDs.
	 */
	private fun setupViewsOnClickEvents(settingsFragmentRef: SettingsFragment, fragmentLayout: View) {
		logger.d("Setting up onClick listeners for settings actions")
		try {
			settingsOnClickLogic = SettingsOnClickLogic(settingsFragmentRef)

			val clickActions = mapOf(
				// Application settings
				R.id.btn_user_info to { settingsOnClickLogic?.showUsernameEditor() },
				R.id.btn_login_register_to_cloud to { settingsOnClickLogic?.showLoginOrRegistrationDialog() },
				R.id.btn_default_download_location to { settingsOnClickLogic?.showDownloadLocationPicker() },
				R.id.btn_language_picker to { settingsOnClickLogic?.showLanguageChanger() },
				R.id.btn_dark_mode_ui to { settingsOnClickLogic?.togglesDarkModeUISettings() },
				R.id.btn_content_location to { settingsOnClickLogic?.changeDefaultContentRegion() },
				R.id.btn_daily_suggestions to { settingsOnClickLogic?.toggleDailyContentSuggestions() },

				// Download settings
				R.id.btn_default_download_folder to { settingsOnClickLogic?.changeDefaultDownloadFolder() },
				R.id.btn_hide_task_notifications to { settingsOnClickLogic?.toggleHideDownloadNotification() },
				R.id.btn_wifi_only_downloads to { settingsOnClickLogic?.toggleWifiOnlyDownload() },
				R.id.btn_single_click_open to { settingsOnClickLogic?.toggleSingleClickToOpenFile() },
				R.id.btn_play_notification_sound to { settingsOnClickLogic?.toggleDownloadNotificationSound() },
				R.id.btn_adv_downloads_settings to { settingsOnClickLogic?.openAdvanceDownloadsSettings() },

				// Browser settings
				R.id.btn_browser_homepage to { settingsOnClickLogic?.setBrowserDefaultHomepage() },
				R.id.btn_enable_adblock to { settingsOnClickLogic?.toggleBrowserBrowserAdBlocker() },
				R.id.btn_enable_popup_blocker to { settingsOnClickLogic?.toggleBrowserPopupAdBlocker() },
				R.id.btn_show_image_on_web to { settingsOnClickLogic?.toggleBrowserWebImages() },
				R.id.btn_enable_video_grabber to { settingsOnClickLogic?.toggleBrowserVideoGrabber() },
				R.id.btn_adv_browser_settings to { settingsOnClickLogic?.openAdvanceBrowserSettings() },

				// Custom services
				R.id.btn_share_with_friends to { settingsOnClickLogic?.shareApplicationWithFriends() },
				R.id.btn_open_feedback to { settingsOnClickLogic?.openUserFeedbackActivity() },
				R.id.btn_open_about_info to { settingsOnClickLogic?.openApplicationInformation() },
				R.id.btn_open_privacy_policy to { settingsOnClickLogic?.showPrivacyPolicyActivity() },
				R.id.btn_open_terms_condition to { settingsOnClickLogic?.showTermsConditionActivity() },

				// Updates and reset
				R.id.btn_check_new_update to { settingsOnClickLogic?.checkForNewApkVersion() },
				R.id.btn_restart_application to { settingsOnClickLogic?.restartApplication() },

				// Developer acknowledgements
				R.id.btn_follow_shibafoss to { settingsOnClickLogic?.followDeveloperAtInstagram() },
			)

			// Apply click actions to respective view elements
			clickActions.forEach { (id, action) ->
				fragmentLayout.setClickListener(id) {
					logger.d("Click triggered for viewId=$id")
					try {
						action()
					} catch (e: Exception) {
						logger.e("Error executing click action for viewId=$id", e)
					}
				}
			}
		} catch (error: Exception) {
			logger.e("Error during click listener setup", error)
		}
	}

	/**
	 * Displays application version details combining version name and version code.
	 *
	 * @param fragmentLayout Root layout used to find version TextView.
	 */
	private fun displayApplicationVersion(fragmentLayout: View) {
		logger.d("Setting version display: versionName=$versionName, versionCode=$versionCode")
		try {
			with(fragmentLayout) {
				findViewById<TextView>(R.id.txt_version_info)?.apply {
					val versionNameText = "${getString(R.string.title_version_number)} $versionName"
					val versionCodeText = "${getString(R.string.title_build_version)} $versionCode"
					text = fromHtmlStringToSpanned("${versionNameText}<br/>${versionCodeText}")
				}
			}
		} catch (error: Exception) {
			logger.e("Error initializing version info view", error)
		}
	}

	/**
	 * Simplifies setting click listeners to avoid redundant boilerplate.
	 *
	 * @param id ID of the view to which the onClick action will be attached.
	 * @param action Lambda to execute when the view is clicked.
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		try {
			findViewById<View>(id)?.setOnClickListener { action() }
		} catch (error: Exception) {
			logger.e("Error setting click listener for id=$id", error)
		}
	}
}