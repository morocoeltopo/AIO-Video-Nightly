package app.ui.others.information

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import androidx.lifecycle.lifecycleScope
import app.core.AIOApp
import app.core.AIOApp.Companion.IS_PREMIUM_USER
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOKeyStrings.DONT_PARSE_URL_ANYMORE
import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.webengine.SingleResolutionPrompter
import app.ui.main.fragments.downloads.intercepter.SharedVideoURLIntercept
import com.aio.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.IntentUtility.getIntentDataURI
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.lang.ref.WeakReference

/**
 * This activity intercepts external video URLs (usually from Share actions or browser) and attempts
 * to extract video metadata or redirect it properly based on user's premium status and URL type.
 *
 * For social media URLs, it attempts to grab the title and thumbnail to prompt the user for download.
 * For other URLs, it passes them through a shared video URL interceptor.
 * If user is not premium, it forwards the intent to MotherActivity.
 */
class IntentInterceptActivity : BaseActivity() {

	/**
	 * Logger instance for debugging and error reporting.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to avoid potential memory leaks by not holding a strong reference to the activity.
	 */
	private val weakSelfReference = WeakReference(this)

	/**
	 * Safe reference to the activity obtained from the weak reference.
	 * Used to prevent crashes if the activity is destroyed or unavailable.
	 */
	private val safeIntentInterceptActivityRef = weakSelfReference.get()

	/**
	 * Flag to signal cancellation of title parsing if the user exits or the activity finishes.
	 */
	private var isParsingTitleFromUrlAborted = false

	/**
	 * Provides the layout resource to render.
	 * In this case, the activity uses a transparent placeholder layout
	 * and applies a fade animation for a smooth appearance.
	 *
	 * @return The layout resource ID for this transparent activity.
	 */
	override fun onRenderingLayout(): Int {
		logger.d("onRenderingLayout: Applying fade animation to activity.")
		animActivityFade(safeIntentInterceptActivityRef)
		return R.layout.activity_transparent_1
	}

	/**
	 * Handles back button press.
	 * Ensures the activity closes with a fade-out animation for a better user experience.
	 */
	override fun onBackPressActivity() {
		logger.d("onBackPressActivity: User pressed back, closing with fade animation.")
		closeActivityWithFadeAnimation(true)
	}

	/**
	 * Called after the layout is set.
	 * This function drives the core logic of:
	 * - Validating the incoming shared URL
	 * - Handling premium/non-premium user flows
	 * - Parsing and analyzing social media URLs
	 * - Displaying prompts or forwarding intent as needed
	 */
	override fun onAfterLayoutRender() {
		// Handle premium user flow
		safeIntentInterceptActivityRef?.let { safeActivityRef ->
			logger.d("onAfterLayoutRender: Starting intent processing.")

			// Retrieve the shared URL from the incoming intent
			val intentUrl = getIntentDataURI(safeActivityRef)
			logger.d("onAfterLayoutRender: Retrieved intent URL = $intentUrl")

			// Handle invalid URLs
			if (isValidURL(intentUrl) == false) {
				logger.d("onAfterLayoutRender: Invalid URL detected.")
				doSomeVibration(50)
				showToast(safeActivityRef, msgId = R.string.title_invalid_url)
				onBackPressActivity()
				return
			}

			// If no URL is provided, exit the activity
			if (intentUrl.isNullOrEmpty()) {
				logger.d("onAfterLayoutRender: No URL found in the intent.")
				onBackPressActivity()
				return
			}

			// If user is not premium or ultimate version is not unlocked, forward to the main activity
			if (IS_PREMIUM_USER == false || IS_ULTIMATE_VERSION_UNLOCKED == false) {
				logger.d("onAfterLayoutRender: Non-premium user detected. Forwarding to main activity.")
				forwardIntentToMotherActivity()
				return
			}

			lifecycleScope.launch {
				val checkingDialog = WaitingDialog(
					isCancelable = false,
					baseActivityInf = safeActivityRef,
					loadingMessage = getString(R.string.title_wait_till_apps_loads_up),
				)

				checkingDialog.dialogBuilder?.setOnClickForPositiveButton {
					checkingDialog.close()
					closeActivityWithFadeAnimation(shouldAnimate = true)
				}

				// Wait loop with delay
				while (AIOApp.HAS_APP_LOADED_FULLY == false) {
					checkingDialog.show()
					delay(200)
				}

				checkingDialog.close()
				startParsingTheIntentURL(intentUrl, safeActivityRef)
			}
			return
		}
	}

	private fun startParsingTheIntentURL(intentUrl: String, activityRef: IntentInterceptActivity) {
		logger.d("onAfterLayoutRender: Premium user detected. Processing URL.")

		if (isSocialMediaUrl(intentUrl) == false) {
			logger.d("onAfterLayoutRender: Non-social media URL detected. Using generic interceptor.")
			interceptNonSocialMediaUrl(activityRef, intentUrl)
			return
		}

		logger.d("onAfterLayoutRender: Social media URL detected. Starting advanced parsing.")

		// Show "analyzing URL" waiting dialog
		val waitingDialog = WaitingDialog(
			isCancelable = false,
			baseActivityInf = activityRef,
			loadingMessage = getString(R.string.title_analyzing_url_please_wait),
		)
		// Setup waiting dialog "Okay" button click
		waitingDialog.dialogBuilder?.setOnClickForPositiveButton {
			logger.d("WaitingDialog: User cancelled analyzing process.")
			waitingDialog.close()
			isParsingTitleFromUrlAborted = true
			closeActivityWithFadeAnimation(shouldAnimate = true)
		}

		waitingDialog.show()
		logger.d("WaitingDialog: Displayed analyzing message.")

		// Perform parsing in the background
		ThreadsUtility.executeInBackground(codeBlock = {
			logger.d("Background Task: Fetching HTML content for URL.")
			val htmlBody = fetchWebPageContent(url = intentUrl, retry = true, numOfRetry = 3)

			logger.d("Background Task: Parsing thumbnail URL.")
			val thumbnailUrl = startParsingVideoThumbUrl(videoUrl = intentUrl, userGivenHtmlBody = htmlBody)

			logger.d("Background Task: Extracting webpage title or description.")
			getWebpageTitleOrDescription(
				websiteUrl = intentUrl, userGivenHtmlBody = htmlBody
			) { resultedTitle ->

				// Close the waiting dialog in UI thread
				logger.d("Background Task: Parsing completed. Closing waiting dialog.")
				executeOnMainThread { waitingDialog.close() }

				val validIntentUrl = !resultedTitle.isNullOrEmpty()
				logger.d(
					"Background Task: Parsing result: " +
							"validTitle=$validIntentUrl, cancelled=$isParsingTitleFromUrlAborted"
				)

				if (validIntentUrl && isParsingTitleFromUrlAborted == false) {
					// Show resolution prompter dialog in UI thread
					logger.d("Background Task: Showing SingleResolutionPrompter dialog.")
					executeOnMainThread {
						val resolutionName = getText(R.string.title_high_quality).toString()
						SingleResolutionPrompter(
							baseActivity = activityRef,
							isDialogCancelable = true,
							singleResolutionName = resolutionName,
							extractedVideoLink = intentUrl,
							currentWebUrl = intentUrl,
							videoTitle = resultedTitle,
							videoUrlReferer = intentUrl,
							dontParseFBTitle = true,
							thumbnailUrlProvided = thumbnailUrl,
							isSocialMediaUrl = true,
							isDownloadFromBrowser = false,
							closeActivityOnSuccessfulDownload = true
						).show()
					}
				} else {
					// Fallback for busy server or cancelled execution
					logger.d("Background Task: Invalid result or cancelled. Forwarding to browser.")
					executeOnMainThread {
						val activityRef = activityRef
						activityRef.doSomeVibration(50)

						val stringResId = R.string.title_server_busy_opening_browser
						showToast(activityInf = activityRef, msgId = stringResId)
						forwardIntentToMotherActivity(dontParseURLAnymore = true)
					}
				}
			}
		})
	}

	/**
	 * Handles interception of non-social media URLs.
	 * Uses a shared video URL interceptor to process the link.
	 *
	 * @param safeActivityRef Reference to the current activity.
	 * @param intentUrl The URL received from the intent.
	 */
	private fun interceptNonSocialMediaUrl(
		safeActivityRef: IntentInterceptActivity,
		intentUrl: String
	) {
		logger.d("interceptNonSocialMediaUrl: Starting interception for URL: $intentUrl")

		// Initialize the generic URL interceptor for non-social media links
		val interceptor = SharedVideoURLIntercept(
			baseActivity = safeActivityRef,
			closeActivityOnSuccessfulDownload = true,
			onOpeningBuiltInBrowser = {
				logger.d("interceptNonSocialMediaUrl: Opening browser as fallback.")
				forwardIntentToMotherActivity()
			}
		)

		// Process the given URL
		logger.d("interceptNonSocialMediaUrl: Passing URL to interceptor.")
		interceptor.interceptIntentURI(
			targetUrl = intentUrl, shouldOpenBrowserAsFallback = true
		)
		logger.d("interceptNonSocialMediaUrl: Interception process initiated.")
	}

	/**
	 * Clears the weak reference to this activity.
	 * Prevents memory leaks by allowing the garbage collector to reclaim the activity instance.
	 */
	override fun clearWeakActivityReference() {
		logger.d("clearWeakActivityReference: Clearing weak reference to activity.")
		weakSelfReference.clear()
		super.clearWeakActivityReference()
		logger.d("clearWeakActivityReference: Weak reference cleared successfully.")
	}

	/**
	 * Forwards the intercepted intent to the [MotherActivity].
	 * Preserves original intent data and action, ensuring seamless redirection.
	 *
	 * @param dontParseURLAnymore Flag to signal that no further parsing is required.
	 */
	private fun forwardIntentToMotherActivity(dontParseURLAnymore: Boolean = false) {
		logger.d(
			"forwardIntentToMotherActivity: Preparing to forward intent. " +
					"dontParseURLAnymore=$dontParseURLAnymore"
		)

		try {
			val originalIntent = intent
			logger.d(
				"forwardIntentToMotherActivity: Retrieved original intent: " +
						"action=${originalIntent.action}, data=${originalIntent.data}"
			)

			// Create intent for MotherActivity with necessary flags and extras
			val targetIntent = Intent(getActivity(), MotherActivity::class.java).apply {
				action = originalIntent.action
				setDataAndType(originalIntent.data, originalIntent.type)
				putExtras(originalIntent)
				putExtra(DONT_PARSE_URL_ANYMORE, dontParseURLAnymore)
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
			}

			// Launch MotherActivity
			logger.d("forwardIntentToMotherActivity: Launching MotherActivity with preserved intent.")
			startActivity(targetIntent)

			// Close current activity with fade animation
			closeActivityWithFadeAnimation(true)
			logger.d("forwardIntentToMotherActivity: Activity forwarded and closed with fade animation.")

		} catch (error: Exception) {
			logger.e("forwardIntentToMotherActivity: Error occurred while launching MotherActivity", error)

			// Fallback: open MotherActivity with default animation if forwarding fails
			openActivity(activity = MotherActivity::class.java, shouldAnimate = true)
			closeActivityWithFadeAnimation(shouldAnimate = true)
			logger.d("forwardIntentToMotherActivity: Fallback - opened MotherActivity and closed current activity.")
		}
	}
}