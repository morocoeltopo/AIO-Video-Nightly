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
	 * @param activity The target activity class to open.
	 * @param shouldAnimate Whether to apply transition animation after launching the activity.
	 */
	override fun openActivity(activity: Class<*>, shouldAnimate: Boolean) {
		logger.d("openActivity() called — activity=${activity.simpleName}, shouldAnimate=$shouldAnimate")

		safeBaseActivityRef?.let { safeActivityRef ->
			logger.d("Launching activity: ${activity.simpleName}")

			// Prepare intent with flags to avoid multiple instances
			val intent = Intent(safeActivityRef, activity).apply {
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
			}

			startActivity(intent)
			logger.d("Activity ${activity.simpleName} started successfully")

			// Apply fade animation if requested
			if (shouldAnimate) {
				logger.d("Applying fade transition animation")
				animActivityFade(safeActivityRef)
			}
		} ?: logger.d("openActivity() skipped — safeBaseActivityRef is null")
	}

	/**
	 * Closes the current activity with swipe-right animation if enabled.
	 *
	 * @param shouldAnimate Whether to show transition animation.
	 */
	override fun closeActivityWithSwipeAnimation(shouldAnimate: Boolean) {
		logger.d("closeActivityWithSwipeAnimation() called — shouldAnimate=$shouldAnimate")

		safeBaseActivityRef?.apply {
			logger.d("Finishing current activity: ${this::class.java.simpleName}")
			finish()

			// Apply swipe-right animation if requested
			if (shouldAnimate) {
				logger.d("Applying swipe-right exit animation")
				animActivitySwipeRight(this)
			}
		} ?: logger.d("closeActivityWithSwipeAnimation() skipped — safeBaseActivityRef is null")
	}

	/**
	 * Closes the current activity with fade-out animation if enabled.
	 *
	 * @param shouldAnimate Whether to show transition animation.
	 */
	override fun closeActivityWithFadeAnimation(shouldAnimate: Boolean) {
		logger.d("closeActivityWithFadeAnimation() called — shouldAnimate=$shouldAnimate")

		safeBaseActivityRef?.apply {
			logger.d("Finishing current activity: ${this::class.java.simpleName}")
			finish()

			// Apply fade-out animation if requested
			if (shouldAnimate) {
				logger.d("Applying fade exit animation")
				animActivityFade(this)
			}
		} ?: logger.d("closeActivityWithFadeAnimation() skipped — safeBaseActivityRef is null")
	}

	/**
	 * Handles double-back-press logic to exit the activity.
	 *
	 * Behavior:
	 * - On first back press, shows a toast prompting the user to press again to exit.
	 * - On second back press (within 2 seconds), closes the activity with a swipe animation.
	 */
	override fun exitActivityOnDoubleBackPress() {
		logger.d("exitActivityOnDoubleBackPress() called — currentState=$isBackButtonEventFired")

		if (isBackButtonEventFired == 0) {
			logger.d("First back press detected — showing toast prompt to user")

			// Show exit prompt toast
			showToast(
				activity = safeBaseActivityRef,
				msgId = R.string.title_press_back_button_to_exit
			)

			// Set flag to indicate back button pressed once
			isBackButtonEventFired = 1

			// Reset flag after 2 seconds if user doesn't press again
			delay(2000, object : OnTaskFinishListener {
				override fun afterDelay() {
					logger.d("Resetting back press flag after timeout")
					isBackButtonEventFired = 0
				}
			})
		} else if (isBackButtonEventFired == 1) {
			logger.d("Second back press detected — exiting activity")
			isBackButtonEventFired = 0
			closeActivityWithSwipeAnimation(true)
		}
	}

	/**
	 * Force quits the entire application.
	 * This method kills the process and exits the JVM.
	 * Use carefully as it does not follow normal activity lifecycle.
	 */
	override fun forceQuitApplication() {
		logger.d("forceQuitApplication() called — terminating the process")

		// Kill the current process
		Process.killProcess(Process.myPid())
		logger.d("Process killed successfully")

		// Exit the JVM
		exitProcess(0)
	}

	/**
	 * Opens the application's App Info screen in device settings.
	 * Allows the user to manage permissions, clear cache, etc.
	 */
	override fun openAppInfoSetting() {
		logger.d("openAppInfoSetting() called")

		val packageName = this.packageName
		logger.d("Target package: $packageName")

		// Prepare intent to open App Info screen
		val uri = "package:$packageName".toUri()
		val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS, uri)

		startActivity(intent)
		logger.d("App Info settings screen opened successfully")
	}

	/**
	 * Opens the official site or Play Store page of the application.
	 * If no browser is available, shows a toast prompting the user to install one.
	 */
	override fun openApplicationOfficialSite() {
		logger.d("openApplicationOfficialSite() called")

		try {
			// Get the official site URL from resources
			val uri = getText(R.string.text_aio_official_page_url).toString()
			logger.d("Opening official site URL: $uri")

			startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			logger.d("Official site opened successfully in browser or Play Store")
		} catch (error: Exception) {
			logger.d("Failed to open official site: ${error.message}")
			error.printStackTrace()

			// Show fallback message
			showToast(
				activity = safeBaseActivityRef,
				msgId = R.string.title_please_install_web_browser
			)
			logger.d("Displayed toast: Please install a web browser")
		}
	}

	/**
	 * Retrieves the current time zone ID.
	 *
	 * @return The device's default time zone ID as a string.
	 */
	override fun getTimeZoneId(): String {
		val timeZoneId = TimeZone.getDefault().id
		logger.d("getTimeZoneId() called — current time zone: $timeZoneId")
		return timeZoneId
	}

	/**
	 * Returns the current active [BaseActivity] instance.
	 *
	 * @return The current activity instance or null if not available.
	 */
	override fun getActivity(): BaseActivity? {
		logger.d("getActivity() called — returning current activity reference")
		return safeBaseActivityRef
	}

	/**
	 * Clears the weak reference to the current activity to prevent memory leaks.
	 * Typically called by the application lifecycle manager.
	 */
	open fun clearWeakActivityReference() {
		logger.d("clearWeakActivityReference() called — clearing activity references")

		weakBaseActivityRef?.clear()
		logger.d("Weak reference cleared")

		safeBaseActivityRef = null
		logger.d("Safe activity reference set to null")
	}

	/**
	 * Triggers a short vibration on the device.
	 *
	 * @param timeInMillis Duration of the vibration in milliseconds.
	 */
	override fun doSomeVibration(timeInMillis: Int) {
		logger.d("doSomeVibration() called — duration=${timeInMillis}ms")

		if (vibrator.hasVibrator()) {
			logger.d("Device supports vibration — triggering vibration")

			vibrator.vibrate(
				VibrationEffect.createOneShot(
					/* milliseconds = */ timeInMillis.toLong(),
					/* amplitude = */ VibrationEffect.DEFAULT_AMPLITUDE
				)
			)

			logger.d("Vibration triggered successfully for ${timeInMillis}ms")
		} else {
			logger.d("Device does not support vibration — skipping")
		}
	}

	/**
	 * Provides standard intent flags for launching activities in single-top mode.
	 *
	 * @return Combined flags for single-top launch mode.
	 */
	override fun getSingleTopIntentFlags(): Int {
		val flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
		logger.d("getSingleTopIntentFlags() called — returning flags=$flags")
		return flags
	}

	/**
	 * Displays a dialog to inform the user that the selected feature
	 * is not yet implemented or available.
	 */
	fun showUpcomingFeatures() {
		logger.d("showUpcomingFeatures() called — displaying upcoming feature dialog")

		// Trigger short vibration for haptic feedback
		doSomeVibration(50)

		safeBaseActivityRef?.let { safeActivityRef ->
			logger.d("Safe activity reference found — preparing dialog")

			showMessageDialog(
				baseActivityInf = safeActivityRef,
				isTitleVisible = true,
				titleText = getString(R.string.title_feature_isnt_implemented),
				isNegativeButtonVisible = false,
				positiveButtonText = getString(R.string.title_okay),

				// Customize message text view
				messageTextViewCustomize = { messageTextView ->
					logger.d("Setting message text for upcoming features")
					messageTextView.setText(R.string.text_feature_isnt_available_yet)
				},

				// Customize title text view with green color
				titleTextViewCustomize = { titleTextView ->
					val colorResId = R.color.color_green
					val color = safeActivityRef.resources.getColor(colorResId, null)
					titleTextView.setTextColor(color)
					logger.d("Title text color set to green")
				},

				// Customize positive button with an icon
				positiveButtonTextCustomize = { positiveButton: TextView ->
					val drawable = getDrawable(applicationContext, R.drawable.ic_okay_done)
					drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
					positiveButton.setCompoundDrawables(drawable, null, null, null)
					logger.d("Positive button customized with drawable icon")
				}
			)

			logger.d("Upcoming feature dialog displayed successfully")
		} ?: logger.d("showUpcomingFeatures() skipped — safeBaseActivityRef is null")
	}

	/**
	 * Requests runtime permissions if required by the app and not yet granted.
	 *
	 * Uses a short delay to avoid overlapping with activity startup.
	 * Delegates result handling to [permissionCheckListener].
	 */
	private fun requestForPermissionIfRequired() {
		logger.d("requestForPermissionIfRequired() called")

		safeBaseActivityRef?.let { safeActivityRef ->
			if (!isUserPermissionCheckingActive) {
				logger.d("Permission check not active — scheduling delayed permission request")

				// Add a delay to ensure activity UI is ready
				delay(1000, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Delayed permission check triggered")

						// Skip permission check for OpeningActivity
						if (safeActivityRef is OpeningActivity) {
							logger.d("Activity is OpeningActivity — skipping permission request")
							return
						}

						val permissions = getRequiredPermissionsBySDKVersion()
						logger.d("Permissions required by SDK: $permissions")

						// Check if permission is already granted
						if (permissions.isNotEmpty() && !isGranted(safeActivityRef, permissions[0])) {
							logger.d("Permission not granted — launching permission request")
							launchPermissionRequest(getRequiredPermissionsBySDKVersion())
						} else {
							logger.d("All required permissions are already granted")
							permissionCheckListener?.onPermissionResultFound(
								isGranted = true,
								grantedList = permissions,
								deniedList = null
							)
						}
					}
				})
			} else {
				logger.d("Permission check already active — skipping duplicate request")
			}
		} ?: logger.d("Activity reference is null — cannot request permissions")
	}

	/**
	 * Indicates whether the activity is currently running (in foreground).
	 *
	 * @return true if the activity is running, false otherwise.
	 */
	fun isActivityRunning(): Boolean {
		logger.d("isActivityRunning() called — result=$isActivityRunning")
		return isActivityRunning
	}

	/**
	 * Determines which permissions are required based on the current SDK version.
	 *
	 * @return A list of required permission strings.
	 */
	fun getRequiredPermissionsBySDKVersion(): ArrayList<String> {
		logger.d("getRequiredPermissionsBySDKVersion() called")

		val permissions = ArrayList<String>()

		// POST_NOTIFICATIONS is required for Android 13+ (Tiramisu)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			logger.d("Android version >= 13 — adding POST_NOTIFICATIONS permission")
			permissions.add(POST_NOTIFICATIONS)
		} else {
			logger.d("Android version < 13 — adding WRITE_EXTERNAL_STORAGE permission")
			permissions.add(WRITE_EXTERNAL_STORAGE)
		}

		logger.d("Permissions determined: $permissions")
		return permissions
	}

	/**
	 * Configures the system bars (status bar and navigation bar) for light theme.
	 *
	 * Displays dark icons on light backgrounds.
	 */
	fun setLightSystemBarTheme() {
		logger.d("setLightSystemBarTheme() called — applying light system bar appearance")

		setSystemBarsColors(
			statusBarColorResId = R.color.color_surface,
			navigationBarColorResId = R.color.color_surface,
			isLightStatusBar = true,
			isLightNavigationBar = true
		)

		logger.d("Light system bar theme applied successfully")
	}

	/**
	 * Applies the app’s theme appearance based on user preferences or system defaults.
	 *
	 * Mode options:
	 * - -1: Follow system (auto)
	 * -  1: Force Dark mode
	 * -  2: Force Light mode
	 */
	fun setThemeAppearance() {
		logger.d("setThemeAppearance() called — applying user-selected or system theme")

		safeBaseActivityRef?.let {
			ViewUtility.changesSystemTheme(it)
			logger.d("Theme applied via ViewUtility.changesSystemTheme()")
		} ?: logger.d("No active activity reference — theme appearance not applied")
	}

	/**
	 * Checks whether the system’s dark mode is currently active.
	 *
	 * Uses the device’s [Configuration.uiMode] to detect active theme.
	 *
	 * @return true if dark mode is enabled, false otherwise.
	 */
	fun isDarkModeActive(): Boolean {
		val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
		val isDark = currentNightMode == Configuration.UI_MODE_NIGHT_YES
		logger.d("isDarkModeActive() called — result=$isDark")
		return isDark
	}

	/**
	 * Configures the system bars (status bar and navigation bar) for dark theme.
	 *
	 * Displays light icons on dark backgrounds.
	 */
	fun setDarkSystemBarTheme() {
		logger.d("setDarkSystemBarTheme() called — applying dark system bar appearance")

		setSystemBarsColors(
			statusBarColorResId = R.color.color_primary,
			navigationBarColorResId = R.color.color_primary,
			isLightStatusBar = false,
			isLightNavigationBar = false
		)

		logger.d("Dark system bar theme applied successfully")
	}

	/**
	 * Enables immersive edge-to-edge fullscreen mode by hiding system bars.
	 *
	 * Adjusts UI flags for pre-Android R devices and uses [WindowInsetsControllerCompat]
	 * for modern versions.
	 */
	fun setEdgeToEdgeFullscreen() {
		logger.d("setEdgeToEdgeFullscreen() called — enabling immersive fullscreen mode")

		// Disable default window fitting to enable edge-to-edge layout
		WindowCompat.setDecorFitsSystemWindows(window, false)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			logger.d("Android version >= R — using InsetsController API for fullscreen")

			window.insetsController?.let {
				it.hide(WindowInsets.Type.systemBars())
				it.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			}
		} else {
			logger.d("Android version < R — using legacy system UI flags")

			window.decorView.systemUiVisibility = (SYSTEM_UI_FLAG_LAYOUT_STABLE
					or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					or SYSTEM_UI_FLAG_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_FULLSCREEN
					or SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
		}

		// Apply edge-to-edge behavior using compatibility controller
		WindowCompat.getInsetsController(window, window.decorView).let { controller ->
			controller.hide(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior =
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			logger.d("Fullscreen mode activated with swipe-to-show system bars")
		}

		logger.d("Edge-to-edge fullscreen setup completed successfully")
	}

	/**
	 * Disables edge-to-edge fullscreen mode and restores standard system UI layout.
	 */
	fun disableEdgeToEdge() {
		logger.d("disableEdgeToEdge() called — restoring default system UI layout")

		// Re-enable window fitting for system windows
		WindowCompat.setDecorFitsSystemWindows(window, true)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.insetsController?.let {
				it.show(WindowInsets.Type.systemBars())
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
				}
				logger.d("InsetsController used to show system bars and reset behavior")
			}
		} else {
			// Legacy approach for older Android versions
			val flags = (SYSTEM_UI_FLAG_LAYOUT_STABLE
					or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
			window.decorView.systemUiVisibility = flags
			logger.d("Legacy system UI flags applied for pre-R devices")
		}

		// Apply compatibility controller for consistency
		WindowCompat.getInsetsController(window, window.decorView).let { controller ->
			controller.show(WindowInsetsCompat.Type.systemBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
			logger.d("InsetsControllerCompat used to show system bars with default behavior")
		}
	}

	/**
	 * Enables edge-to-edge fullscreen mode with a custom cutout (notch) color.
	 *
	 * @param color The color to apply to status bar and navigation bar cutout areas.
	 */
	fun setEdgeToEdgeCustomCutoutColor(@ColorInt color: Int) {
		logger.d("setEdgeToEdgeCustomCutoutColor() called — color=${color}")

		WindowCompat.setDecorFitsSystemWindows(window, false)
		window.statusBarColor = color
		window.navigationBarColor = color

		// Allow content to extend into display cutouts on short edges
		window.attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.setBackgroundDrawable(color.toDrawable())
			logger.d("Applied custom cutout color to window background (API >= R)")
		}
	}

	/**
	 * Checks if the application is ignoring battery optimizations.
	 *
	 * @return `true` if the app is excluded from battery optimization, `false` otherwise.
	 */
	fun isBatteryOptimizationIgnored(): Boolean {
		val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
		val isIgnored = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
		logger.d("isBatteryOptimizationIgnored() called — result=$isIgnored")
		return isIgnored
	}

	/**
	 * Prompts the user to disable battery optimization for the app.
	 *
	 * Conditions:
	 * - Only if at least one successful download has occurred.
	 * - Only if the current activity is [MotherActivity].
	 * - Only if battery optimization is not already ignored.
	 *
	 * Displays a custom dialog explaining the need for disabling battery optimization.
	 * If the user agrees, launches the system settings to allow manual exclusion.
	 */
	fun requestForDisablingBatteryOptimization() {
		logger.d("requestForDisablingBatteryOptimization() called")

		// Guard clauses
		if (aioSettings.totalNumberOfSuccessfulDownloads < 1) {
			logger.d("Skipping — no successful downloads yet")
			return
		}
		if (safeBaseActivityRef !is MotherActivity) {
			logger.d("Skipping — current activity is not MotherActivity")
			return
		}
		if (isBatteryOptimizationIgnored()) {
			logger.d("Skipping — battery optimization already ignored")
			return
		}

		// Show custom message dialog to prompt user
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
				logger.d("User agreed — launching battery optimization settings")
				val intent = Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
				startActivity(intent)
			}
		}?.show()

		logger.d("Battery optimization dialog displayed (if conditions met)")
	}

	/**
	 * Checks for the latest available update in the background.
	 *
	 * Steps performed:
	 * 1. Verifies if a new update is available via [AIOUpdater].
	 * 2. Fetches the latest APK URL and update metadata ([UpdateInfo]).
	 * 3. Downloads the APK silently to a local file.
	 * 4. Validates the downloaded APK against SHA256 hash.
	 * 5. If valid, launches [UpdaterDialog] on the main thread for user installation.
	 *
	 * Handles nulls, invalid downloads, and hash mismatches gracefully, with logging.
	 */
	fun checkForLatestUpdate() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			logger.d("Starting checkForLatestUpdate()")

			// Execute update check in a background thread
			ThreadsUtility.executeInBackground(codeBlock = {

				val updater = AIOUpdater()
				logger.d("AIOUpdater initialized")

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
					logger.d("Fetched update info: version=${updateInfo.latestVersion}, hash=${updateInfo.versionHash}")

					val latestAPKFile = updater.downloadUpdateApkSilently(latestAPKUrl)
					if (latestAPKFile != null &&
						latestAPKFile.exists() &&
						latestAPKFile.isFile &&
						getFileExtension(latestAPKFile.name)?.contains("apk", true) == true
					) {
						logger.d("Downloaded latest APK successfully at ${latestAPKFile.absolutePath}")
						val fileHash = getFileSha256(latestAPKFile)

						if (fileHash != updateInfo.versionHash) {
							logger.d("SHA256 mismatch! Expected=${updateInfo.versionHash}, Got=$fileHash — deleting APK")
							latestAPKFile.delete()
							return@executeInBackground
						}
						logger.d("APK hash verified successfully — proceeding to show updater dialog")

						// Launch updater dialog on the main thread
						ThreadsUtility.executeOnMain(codeBlock = {
							logger.d("Executing on main thread to show UpdaterDialog")
							if (!isActivityRunning) {
								logger.d("Activity not running — skipping UpdaterDialog")
								return@executeOnMain
							}

							if (safeBaseActivityRef is MotherActivity) {
								UpdaterDialog(
									baseActivity = safeBaseActivityRef,
									latestVersionApkFile = latestAPKFile,
									versionInfo = updateInfo
								).show()
								logger.d("UpdaterDialog launched for version=${updateInfo.latestVersion}")
							}
						})
					} else {
						logger.d("Failed to download latest APK or file invalid: ${latestAPKFile?.absolutePath}")
					}
				} else {
					logger.d("No new update available — skipping update check")
				}
			})
		} ?: logger.d("safeBaseActivityRef is null — cannot perform update check")
	}
}
