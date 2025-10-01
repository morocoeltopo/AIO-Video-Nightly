@file:Suppress("DEPRECATION")

package app.core.bases

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
import android.view.MotionEvent
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
import android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
import android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat.getColor
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioAdblocker
import app.core.AIOApp.Companion.aioLanguage
import app.core.AIOApp.Companion.aioSettings
import app.core.CrashHandler
import app.core.bases.dialogs.UpdaterDialog
import app.core.bases.interfaces.BaseActivityInf
import app.core.bases.interfaces.PermissionsResult
import app.core.bases.language.LanguageAwareActivity
import app.core.engines.backend.AIOSelfDestruct.shouldSelfDestructApplication
import app.core.engines.services.AIOForegroundService
import app.core.engines.updater.AIOUpdater
import app.ui.main.MotherActivity
import app.ui.others.startup.OpeningActivity
import com.aio.R
import com.anggrayudi.storage.SimpleStorageHelper
import com.permissionx.guolindev.PermissionX
import com.permissionx.guolindev.PermissionX.isGranted
import lib.files.FileSystemUtility.getFileExtension
import lib.files.FileSystemUtility.getFileSha256
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.ActivityAnimator.animActivitySwipeRight
import lib.ui.MsgDialogUtils
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.Thread.setDefaultUncaughtExceptionHandler
import java.lang.ref.WeakReference
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * Base activity class that provides common functionality for all activities in the application.
 *
 * Features include:
 * - Lifecycle management
 * - Permission handling
 * - Activity transitions and animations
 * - System UI customization (status bar, navigation bar)
 * - Vibration feedback
 * - Crash handling
 * - Language support
 * - Ad integration
 * - Storage management
 *
 * Implements [BaseActivityInf] interface for common activity operations.
 */
abstract class BaseActivity : LanguageAwareActivity(), BaseActivityInf {

	// Logger instance for debugging and tracing lifecycle events
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the activity instance.
	 * Helps prevent memory leaks when referencing the activity
	 * in callbacks or background operations.
	 */
	private var weakBaseActivityRef: WeakReference<BaseActivity>? = null

	/**
	 * A safe (non-leaking) reference to the current activity,
	 * retrieved from [weakBaseActivityRef].
	 */
	private var safeBaseActivityRef: BaseActivity? = null

	/** Flag to track if a user permission check is currently in progress. */
	private var isUserPermissionCheckingActive = false

	/** Flag to indicate whether the activity is currently running (visible/resumed). */
	private var isActivityRunning = false

	/** Counter for handling double-back press to exit or similar behavior. */
	private var isBackButtonEventFired = 0

	/** Helper for handling scoped storage permissions and operations. */
	open var scopedStorageHelper: SimpleStorageHelper? = null

	/** Listener for permission check results to propagate callbacks to subclasses. */
	open var permissionCheckListener: PermissionsResult? = null

	/**
	 * Vibrator instance for haptic feedback.
	 * Obtained lazily to optimize resource usage.
	 * Uses [VibratorManager] on Android 12 (S) and above.
	 */
	private val vibrator: Vibrator by lazy {
		logger.d("Initializing Vibrator instance")
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			logger.d("Using VibratorManager for Android 12+")
			val vmClass = VibratorManager::class.java
			val vibratorManager = getSystemService(vmClass)
			vibratorManager.defaultVibrator
		} else {
			logger.d("Using legacy Vibrator service")
			getSystemService(Vibrator::class.java)
		}
	}

	/**
	 * Called when the activity becomes visible to the user.
	 * Marks the activity as running.
	 */
	override fun onStart() {
		super.onStart()
		isActivityRunning = true
		logger.d("onStart() called — activity is now running")
	}

	/**
	 * Initializes the activity and sets up references, UI, and helpers.
	 */
	@SuppressLint("SourceLockedOrientationActivity")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		logger.d("onCreate() called — initializing activity")

		// Initialize weak reference to avoid memory leaks
		weakBaseActivityRef = WeakReference(this)
		safeBaseActivityRef = weakBaseActivityRef?.get()

		safeBaseActivityRef?.let { safeActivityRef ->
			logger.d("Safe activity reference acquired")

			// Set global crash handler for uncaught exceptions
			logger.d("Setting default uncaught exception handler")
			setDefaultUncaughtExceptionHandler(CrashHandler())

			// Configure theme, status bar, and other system UI aspects
			logger.d("Applying theme appearance")
			setThemeAppearance()

			// Initialize scoped storage helper for file access
			logger.d("Initializing ScopedStorageHelper")
			scopedStorageHelper = SimpleStorageHelper(safeActivityRef)

			// Apply user-selected language for localization
			logger.d("Applying user-selected language")
			aioLanguage.applyUserSelectedLanguage(safeActivityRef)

			// Lock activity orientation to portrait
			logger.d("Locking orientation to portrait")
			requestedOrientation = SCREEN_ORIENTATION_PORTRAIT

			// Set up custom back-press handling
			logger.d("Configuring back press handler")
			WeakReference(object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() = onBackPressActivity()
			}).get()?.let { onBackPressedDispatcher.addCallback(safeActivityRef, it) }

			// Inflate layout if provided by subclass
			val layoutId = onRenderingLayout()
			if (layoutId > -1) {
				logger.d("Setting content view with layoutId=$layoutId")
				setContentView(layoutId)
			} else {
				logger.d("No layout provided by subclass")
			}
		} ?: logger.d("Failed to acquire safe activity reference — initialization skipped")
	}

	/**
	 * Called after [onCreate]; often used for final UI setup.
	 */
	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		logger.d("onPostCreate() called — performing post-creation setup")

		// Allow subclasses to set up UI or logic after layout inflation
		logger.d("Calling onAfterLayoutRender() for additional UI setup")
		onAfterLayoutRender()

		// Check for app updates after rendering the layout
		logger.d("Checking for latest available app update")
		checkForLatestUpdate()
	}

	/**
	 * Called when the activity moves to the foreground.
	 * Re-establishes references, permissions, and service states.
	 */
	override fun onResume() {
		super.onResume()
		logger.d("onResume() called — preparing activity for interaction")

		// Reinitialize activity reference if needed
		if (safeBaseActivityRef == null) {
			logger.d("Re-initializing safe activity reference")
			safeBaseActivityRef = WeakReference(this).get()
		}

		safeBaseActivityRef?.let { safeActivityRef ->
			isActivityRunning = true
			logger.d("Activity marked as running")

			// Ensure permissions are granted or request if needed
			logger.d("Checking and requesting required permissions")
			requestForPermissionIfRequired()

			// Update the state of foreground services
			logger.d("Updating foreground service state")
			AIOForegroundService.updateService()

			// Validate user-selected folders for storage
			logger.d("Validating user-selected download folder")
			aioSettings.validateUserSelectedFolder()

			// Initialize YouTube-DLP for video downloading
			logger.d("Initializing YtDLP engine")
			INSTANCE.initializeYtDLP()

			// Invoke any subclass-specific resume logic
			logger.d("Calling subclass onResumeActivity()")
			onResumeActivity()

			// Handle language changes and restart if necessary
			logger.d("Checking for language changes")
			aioLanguage.closeActivityIfLanguageChanged(safeActivityRef)

			// Refresh ad-blocking filters
			logger.d("Fetching latest ad-blocker filters")
			aioAdblocker.fetchAdFilters()

			// Handle self-destruct mode if enabled
			logger.d("Checking self-destruct activation status")
			shouldSelfDestructApplication()
		} ?: logger.d("safeBaseActivityRef is null — skipping onResume tasks")
	}

	/**
	 * Called when the activity is about to move to the background.
	 * Updates flags and invokes subclass pause logic.
	 */
	override fun onPause() {
		super.onPause()
		isActivityRunning = false
		logger.d("onPause() called — activity moved to background")

		// Allow subclass to handle pause-specific behavior
		logger.d("Calling subclass onPauseActivity()")
		onPauseActivity()
	}

	/**
	 * Called when the activity is being destroyed.
	 * Cleans up resources such as vibration feedback.
	 */
	override fun onDestroy() {
		super.onDestroy()
		logger.d("onDestroy() called — cleaning up resources")

		isActivityRunning = false

		// Cancel ongoing vibrations to release hardware
		if (vibrator.hasVibrator()) {
			logger.d("Cancelling active vibration")
			vibrator.cancel()
		}
	}

	/**
	 * Called when the activity is paused.
	 * Subclasses can override this to implement custom pause logic.
	 */
	override fun onPauseActivity() {
		logger.d("onPauseActivity() called — default implementation does nothing")
		// Default implementation intentionally left blank
	}

	/**
	 * Called when the activity is resumed.
	 * Subclasses can override this to implement custom resume logic.
	 */
	override fun onResumeActivity() {
		logger.d("onResumeActivity() called — default implementation does nothing")
		// Default implementation intentionally left blank
	}

	/**
	 * Configures the appearance of system bars (status bar and navigation bar).
	 *
	 * This method allows customizing:
	 * - Colors for both status and navigation bars.
	 * - Whether to use light or dark icons on these bars.
	 *
	 * It handles both modern (Android R and above) and legacy versions of Android.
	 *
	 * @param statusBarColorResId Resource ID for the status bar color.
	 * @param navigationBarColorResId Resource ID for the navigation bar color.
	 * @param isLightStatusBar Whether to use dark icons on a light status bar background.
	 * @param isLightNavigationBar Whether to use dark icons on a light navigation bar background.
	 */
	override fun setSystemBarsColors(
		statusBarColorResId: Int,
		navigationBarColorResId: Int,
		isLightStatusBar: Boolean,
		isLightNavigationBar: Boolean,
	) {
		logger.d(
			"setSystemBarsColors() called with " +
					"statusBarColorResId=$statusBarColorResId, " +
					"navigationBarColorResId=$navigationBarColorResId, " +
					"isLightStatusBar=$isLightStatusBar, " +
					"isLightNavigationBar=$isLightNavigationBar"
		)

		val activityWindow = window

		// Apply colors to system bars
		activityWindow.statusBarColor = getColor(this, statusBarColorResId)
		activityWindow.navigationBarColor = getColor(this, navigationBarColorResId)
		logger.d("Applied status and navigation bar colors")

		val decorView = activityWindow.decorView

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			logger.d("Using WindowInsetsController for Android R and above")

			val insetsController = activityWindow.insetsController

			// Configure status bar icon appearance
			insetsController?.setSystemBarsAppearance(
				if (isLightStatusBar) APPEARANCE_LIGHT_STATUS_BARS else 0,
				APPEARANCE_LIGHT_STATUS_BARS
			)

			// Configure navigation bar icon appearance
			insetsController?.setSystemBarsAppearance(
				if (isLightNavigationBar) APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
				APPEARANCE_LIGHT_NAVIGATION_BARS
			)

			logger.d("Applied light/dark appearance for system bars (R+)")
		} else {
			logger.d("Using legacy systemUiVisibility flags for pre-R devices")

			// Legacy approach for status bar
			if (isLightStatusBar) {
				decorView.systemUiVisibility =
					decorView.systemUiVisibility or
							SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
			} else {
				decorView.systemUiVisibility =
					decorView.systemUiVisibility and
							SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
			}

			// Legacy approach for navigation bar
			if (isLightNavigationBar) {
				decorView.systemUiVisibility =
					decorView.systemUiVisibility or
							SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
			} else {
				decorView.systemUiVisibility =
					decorView.systemUiVisibility and
							SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
			}

			logger.d("Applied light/dark appearance for system bars (legacy mode)")
		}
	}

	/**
	 * Intercepts touch events to dismiss the keyboard when the user taps outside an EditText.
	 *
	 * @param motionEvent The motion event describing the user’s interaction.
	 * @return `true` if the event was handled, otherwise passes it to the superclass.
	 */
	override fun dispatchTouchEvent(motionEvent: MotionEvent): Boolean {
		if (motionEvent.action == MotionEvent.ACTION_DOWN) {
			val focusedView = currentFocus

			if (focusedView is EditText) {
				logger.d("dispatchTouchEvent(): User touched outside EditText — checking keyboard dismissal")

				val outRect = Rect()
				focusedView.getGlobalVisibleRect(outRect)

				if (!outRect.contains(motionEvent.rawX.toInt(), motionEvent.rawY.toInt())) {
					logger.d("Tapped outside EditText — hiding keyboard and clearing focus")

					// Clear focus and hide keyboard
					focusedView.clearFocus()
					val service = getSystemService(INPUT_METHOD_SERVICE)
					val imm = service as InputMethodManager
					imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
				}
			}
		}

		return super.dispatchTouchEvent(motionEvent)
	}

	/**
	 * Launches a permission request dialog for the specified permissions.
	 *
	 * Handles:
	 * - Showing an explanation dialog when permissions are denied.
	 * - Prompting the user to go to settings when permissions are permanently denied.
	 * - Returning the result to the [permissionCheckListener].
	 *
	 * @param permissions The list of permissions to request from the user.
	 */
	override fun launchPermissionRequest(permissions: ArrayList<String>) {
		logger.d("launchPermissionRequest() called with permissions=$permissions")

		safeBaseActivityRef?.let { safeActivityRef ->
			logger.d("Starting permission request flow")

			PermissionX.init(safeActivityRef)
				.permissions(permissions)

				// Show explanation dialog when permissions are denied
				.onExplainRequestReason { callback, deniedList ->
					logger.d("Showing explanation dialog for denied permissions: $deniedList")
					callback.showRequestReasonDialog(
						permissions = deniedList,
						message = getString(R.string.title_allow_the_permissions),
						positiveText = getString(R.string.title_allow_now)
					)
				}

				// Show dialog to redirect user to app settings
				.onForwardToSettings { scope, deniedList ->
					logger.d("Permissions permanently denied — forwarding to settings: $deniedList")
					scope.showForwardToSettingsDialog(
						permissions = deniedList,
						message = getString(R.string.text_allow_permission_in_setting),
						positiveText = getString(R.string.title_allow_now)
					)
				}

				// Handle final permission result
				.request { allGranted, grantedList, deniedList ->
					logger.d(
						"Permission request completed — " +
								"allGranted=$allGranted, granted=$grantedList, denied=$deniedList"
					)

					isUserPermissionCheckingActive = false
					permissionCheckListener?.onPermissionResultFound(
						isGranted = allGranted,
						grantedList = grantedList,
						deniedList = deniedList
					)
				}

			isUserPermissionCheckingActive = true
			logger.d("Permission request initiated — waiting for user response")
		} ?: logger.d("launchPermissionRequest() skipped — safeBaseActivityRef is null")
	}

	/**
	 * Opens another activity with optional animation.
	 *
	 * @param activity The activity class to open
	 * @param shouldAnimate Whether to show transition animation
	 */
	override fun openActivity(activity: Class<*>, shouldAnimate: Boolean) {
		safeBaseActivityRef?.let { safeActivityRef ->
			val intent = Intent(safeActivityRef, activity)
			intent.flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
			startActivity(intent)
			if (shouldAnimate) {
				animActivityFade(safeActivityRef)
			}
		}
	}

	/**
	 * Closes the current activity with swipe animation.
	 *
	 * @param shouldAnimate Whether to show transition animation
	 */
	override fun closeActivityWithSwipeAnimation(shouldAnimate: Boolean) {
		safeBaseActivityRef?.apply {
			finish(); if (shouldAnimate) animActivitySwipeRight(this)
		}
	}

	/**
	 * Closes the current activity with fade animation.
	 *
	 * @param shouldAnimate Whether to show transition animation
	 */
	override fun closeActivityWithFadeAnimation(shouldAnimate: Boolean) {
		safeBaseActivityRef?.apply {
			finish(); if (shouldAnimate) animActivityFade(this)
		}
	}

	/**
	 * Handles double back press to exit the activity.
	 * Shows toast on first press, exits on second press within 2 seconds.
	 */
	override fun exitActivityOnDoubleBackPress() {
		if (isBackButtonEventFired == 0) {
			showToast(
				activity = safeBaseActivityRef,
				msgId = R.string.title_press_back_button_to_exit
			)
			isBackButtonEventFired = 1
			delay(2000, object : OnTaskFinishListener {
				override fun afterDelay() {
					isBackButtonEventFired = 0
				}
			})
		} else if (isBackButtonEventFired == 1) {
			isBackButtonEventFired = 0
			closeActivityWithSwipeAnimation()
		}
	}

	/**
	 * Force quits the application.
	 */
	override fun forceQuitApplication() {
		Process.killProcess(Process.myPid())
		exitProcess(0)
	}

	/**
	 * Opens the app info settings screen.
	 */
	override fun openAppInfoSetting() {
		val packageName = this.packageName
		val uri = "package:$packageName".toUri()
		val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS, uri)
		startActivity(intent)
	}

	/**
	 * Opens the app in Play Store.
	 */
	override fun openApplicationOfficialSite() {
		try {
			val uri = getText(R.string.text_aio_official_page_url).toString()
			startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
		} catch (error: Exception) {
			error.printStackTrace()
			showToast(
				activity = safeBaseActivityRef,
				msgId = R.string.text_please_install_web_browser
			)
		}
	}

	/**
	 * Gets the current time zone ID.
	 *
	 * @return The time zone ID string
	 */
	override fun getTimeZoneId(): String {
		return TimeZone.getDefault().id
	}

	/**
	 * Gets the current activity instance.
	 *
	 * @return The current activity or null if not available
	 */
	override fun getActivity(): BaseActivity? {
		return safeBaseActivityRef
	}

	/**
	 * Clear the weak reference of the activity. Careful using the function,
	 * and should call by the application life cycle manager to automatically
	 * clear up the reference.
	 */
	open fun clearWeakActivityReference() {
		weakBaseActivityRef?.clear()
		safeBaseActivityRef = null
	}

	/**
	 * Triggers device vibration for the specified duration.
	 *
	 * @param timeInMillis Duration of vibration in milliseconds
	 */
	override fun doSomeVibration(timeInMillis: Int) {
		if (vibrator.hasVibrator()) {
			vibrator.vibrate(
				VibrationEffect.createOneShot(
					timeInMillis.toLong(), VibrationEffect.DEFAULT_AMPLITUDE
				)
			)
		}
	}

	/**
	 * Gets standard intent flags for single top activities.
	 *
	 * @return Combined intent flags
	 */
	override fun getSingleTopIntentFlags(): Int {
		return FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
	}

	/**
	 * Shows a dialog indicating upcoming features.
	 */
	fun showUpcomingFeatures() {
		doSomeVibration(50)
		safeBaseActivityRef?.let { safeActivityRef ->
			showMessageDialog(
				baseActivityInf = safeActivityRef,
				isTitleVisible = true,
				titleText = getString(R.string.title_feature_isnt_implemented),
				isNegativeButtonVisible = false,
				positiveButtonText = getString(R.string.title_okay),
				messageTextViewCustomize = { messageTextView ->
					messageTextView.setText(R.string.text_feature_isnt_available_yet)
				},
				titleTextViewCustomize = { titleTextView ->
					val colorResId = R.color.color_green
					val color = safeActivityRef.resources.getColor(colorResId, null)
					titleTextView.setTextColor(color)
				},
				positiveButtonTextCustomize = { positiveButton: TextView ->
					val drawable = getDrawable(applicationContext, R.drawable.ic_okay_done)
					drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
					positiveButton.setCompoundDrawables(drawable, null, null, null)
				}
			)
		}
	}

	/**
	 * Requests permissions if they haven't been granted yet.
	 */
	private fun requestForPermissionIfRequired() {
		safeBaseActivityRef?.let { safeActivityRef ->
			if (!isUserPermissionCheckingActive) {
				delay(1000, object : OnTaskFinishListener {
					override fun afterDelay() {
						if (safeActivityRef is OpeningActivity) return
						val permissions = getRequiredPermissionsBySDKVersion()
						if (permissions.isNotEmpty() && !isGranted(safeActivityRef, permissions[0]))
							launchPermissionRequest(getRequiredPermissionsBySDKVersion()) else
							permissionCheckListener?.onPermissionResultFound(
								isGranted = true,
								grantedList = permissions,
								deniedList = null
							)
					}
				})
			}
		}
	}

	/**
	 * Checks if activity is currently running.
	 *
	 * @return true if activity is running, false otherwise
	 */
	fun isActivityRunning(): Boolean {
		return isActivityRunning
	}

	/**
	 * Gets required permissions based on SDK version.
	 *
	 * @return List of required permissions
	 */
	fun getRequiredPermissionsBySDKVersion(): ArrayList<String> {
		val permissions: ArrayList<String> = ArrayList()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			permissions.add(POST_NOTIFICATIONS)
		else permissions.add(WRITE_EXTERNAL_STORAGE)
		return permissions
	}

	/**
	 * Sets light theme for system bars (light icons on dark background).
	 */
	fun setLightSystemBarTheme() {
		setSystemBarsColors(
			statusBarColorResId = R.color.color_surface,
			navigationBarColorResId = R.color.color_surface,
			isLightStatusBar = true,
			isLightNavigationBar = true
		)
	}

	/**
	 * Applies the app’s theme appearance based on user preference or system setting.
	 *
	 * -1 = Follow system (auto)
	 *  1 = Force Dark
	 *  2 = Force Light
	 */
	fun setThemeAppearance() {
		safeBaseActivityRef?.let { ViewUtility.changesSystemTheme(it) }
	}

	/**
	 * Checks whether the system’s Dark Mode is currently active.
	 *
	 * Uses the device’s current UI mode configuration to determine
	 * if the interface is set to use a dark theme.
	 *
	 * @return true if the current UI mode is Dark Mode, false otherwise.
	 */
	fun isDarkModeActive(): Boolean {
		val currentNightMode = resources.configuration.uiMode and
				Configuration.UI_MODE_NIGHT_MASK
		return currentNightMode == Configuration.UI_MODE_NIGHT_YES
	}

	/**
	 * Sets dark theme for system bars (dark icons on light background).
	 */
	fun setDarkSystemBarTheme() {
		setSystemBarsColors(
			statusBarColorResId = R.color.color_primary,
			navigationBarColorResId = R.color.color_primary,
			isLightStatusBar = false,
			isLightNavigationBar = false
		)
	}

	/**
	 * Configures edge-to-edge fullscreen mode.
	 */
	fun setEdgeToEdgeFullscreen() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.insetsController?.let {
				it.hide(WindowInsets.Type.systemBars())
				it.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			}
		} else {
			window.decorView.systemUiVisibility = (SYSTEM_UI_FLAG_LAYOUT_STABLE
					or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					or SYSTEM_UI_FLAG_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_FULLSCREEN
					or SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
		}

		WindowCompat.getInsetsController(window, window.decorView).let { controller ->
			controller.hide(WindowInsetsCompat.Type.systemBars())
			val barsBySwipe = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			controller.systemBarsBehavior = barsBySwipe
		}
	}

	/**
	 * Disables edge-to-edge mode.
	 */
	fun disableEdgeToEdge() {
		WindowCompat.setDecorFitsSystemWindows(window, true)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.insetsController?.let {
				it.show(WindowInsets.Type.systemBars())
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
				}
			}
		} else {
			val flags = (SYSTEM_UI_FLAG_LAYOUT_STABLE
					or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
			window.decorView.systemUiVisibility = flags
		}

		WindowCompat.getInsetsController(window, window.decorView).let { controller ->
			controller.show(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
		}
	}

	/**
	 * Configures edge-to-edge mode with custom cutout color.
	 *
	 * @param color The color to use for cutout areas
	 */
	fun setEdgeToEdgeCustomCutoutColor(@ColorInt color: Int) {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		window.statusBarColor = color
		window.navigationBarColor = color
		val shortEdges = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
		window.attributes.layoutInDisplayCutoutMode = shortEdges
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.setBackgroundDrawable(color.toDrawable())
		}
	}

	/**
	 * Checks if the app is currently ignoring battery optimizations.
	 *
	 * @return `true` if the app is excluded from battery optimizations, `false` otherwise.
	 */
	fun isBatteryOptimizationIgnored(): Boolean {
		val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
		return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
	}

	/**
	 * Prompts the user to manually disable battery optimizations for the app.
	 *
	 * This method should only be triggered after the user has successfully completed at least one download.
	 * It shows a custom dialog explaining why battery optimization should be disabled (useful for background
	 * operations such as large file downloads). If the user agrees, it launches the system settings screen
	 * where the user can manually disable battery optimizations for the app.
	 */
	fun requestForDisablingBatteryOptimization() {
		if (aioSettings.totalNumberOfSuccessfulDownloads < 1) return
		if (safeBaseActivityRef !is MotherActivity) return
		if (isBatteryOptimizationIgnored() == false) return

		MsgDialogUtils.getMessageDialog(
			baseActivityInf = safeBaseActivityRef,
			isTitleVisible = true,
			isNegativeButtonVisible = false,
			messageTextViewCustomize = { it.setText(R.string.text_battery_optimization_msg) },
			titleTextViewCustomize = { it.setText(R.string.title_turn_off_battery_optimization) },
			positiveButtonTextCustomize = {
				it.setText(R.string.title_disable_now)
				it.setLeftSideDrawable(R.drawable.ic_button_arrow_next)
			}
		)?.apply {
			setOnClickForPositiveButton {
				val intent = Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
				startActivity(intent)
			}
		}?.show()
	}

	/**
	 * Checks for the latest available update in the background.
	 *
	 * - Verifies if a new version is available.
	 * - Fetches update info and the latest APK URL.
	 * - Downloads the APK silently if available.
	 * - Launches [UpdaterDialog] on the main thread to prompt installation.
	 */
	fun checkForLatestUpdate() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			logger.d("Starting checkForLatestUpdate()")

			ThreadsUtility.executeInBackground(codeBlock = {
				val updater = AIOUpdater()

				if (updater.isNewUpdateAvailable()) {
					logger.d("New update available — fetching details")

					val latestAPKUrl = updater.getLatestApkUrl()
					if (latestAPKUrl.isNullOrEmpty()) {
						logger.d("Latest APK URL is null or empty — aborting update check")
						return@executeInBackground
					}
					logger.d("Latest APK URL: $latestAPKUrl")

					val updateInfo = updater.fetchUpdateInfo()
					if (updateInfo == null) {
						logger.d("UpdateInfo is null — aborting update check")
						return@executeInBackground
					}
					logger.d("Fetched update info: version=${updateInfo.latestVersion}")

					val latestAPKFile = updater.downloadUpdateApkSilently(latestAPKUrl)
					if (latestAPKFile != null &&
						latestAPKFile.exists() &&
						latestAPKFile.isFile &&
						getFileExtension(latestAPKFile.name)?.contains("apk", true) == true
					) {
						logger.d("Downloaded latest APK successfully at ${latestAPKFile.absolutePath}")

						if (getFileSha256(latestAPKFile) != updateInfo.versionHash) {
							logger.d(
								"SHA256 mismatch! Expected=${updateInfo.versionHash}, Got=${
									getFileSha256(
										latestAPKFile
									)
								}"
							)
							latestAPKFile.delete()
							return@executeInBackground
						}

						ThreadsUtility.executeOnMain(codeBlock = {
							logger.d("Launching UpdaterDialog for new version=${updateInfo.latestVersion}")
							if (isActivityRunning == false) return@executeOnMain
							if (safeBaseActivityRef is MotherActivity) {
								UpdaterDialog(
									baseActivity = safeBaseActivityRef,
									latestVersionApkFile = latestAPKFile,
									versionInfo = updateInfo
								).show()
							}
						})
					} else {
						logger.d("Failed to download latest APK or invalid file")
					}
				} else {
					logger.d("No new update available — skipping update check")

				}
			})
		} ?: logger.d("safeBaseActivityRef is null — cannot check for updates")
	}

}