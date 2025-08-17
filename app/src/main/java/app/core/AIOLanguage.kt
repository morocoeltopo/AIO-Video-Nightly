package app.core

import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.interfaces.BaseActivityInf
import app.core.bases.language.LocaleAwareManager
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LocalizationHelper
import lib.process.LogHelperUtils
import java.util.Locale

/**
 * [AIOLanguage] manages application-wide language preferences.
 *
 * It provides:
 * - Applying the user-selected UI language at runtime.
 * - Maintaining the list of supported languages.
 * - Gracefully restarting or finishing activities when a language
 *   change requires a UI refresh.
 *
 * The class coordinates with [LocaleAwareManager], [LocalizationHelper],
 * and app settings ([aioSettings]) to ensure a consistent user experience
 * across activities after language changes.
 */
open class AIOLanguage {

	private val logger = LogHelperUtils.from(javaClass)

	companion object {
		const val ENGLISH = "en"
		const val BENGALI = "bn"
		const val HINDI = "hi"
		const val TELUGU = "te"
		const val JAPANESE = "ja"
		const val DANISH = "da"
		const val GERMAN = "de"
	}

	/**
	 * List of supported languages represented as (language code, display name).
	 */
	val languagesList: List<Pair<String, String>> = listOf(
		ENGLISH to "English (Default)",
		HINDI to "Hindi (हिंदी)",
		TELUGU to "Telugu (తెలుగు)",
		BENGALI to "Bengali (বাংলা)",
		JAPANESE to "Japanese (日本語)",
		DANISH to "Danish (Dansk)",
		GERMAN to "German (Deutsch)"
	)

	/** Flag indicating whether the activity should be finished upon resume. */
	open var finishActivityOnResume = false

	/** Flag indicating whether the app should quit completely. */
	private var quitApplicationCommand = false

	/**
	 * Applies the language selected by the user in settings to both the current activity
	 * and the application context.
	 *
	 * @param baseActivityInf A reference to the activity implementing [BaseActivityInf].
	 * @param onComplete Callback invoked after the language has been applied.
	 */
	fun applyUserSelectedLanguage(baseActivityInf: BaseActivityInf?, onComplete: () -> Unit = {}) {
		baseActivityInf?.getActivity()?.let { safeActivityRef ->
			this.finishActivityOnResume = false
			val languageCode = aioSettings.userSelectedUILanguage
			val locale = Locale.forLanguageTag(languageCode)

			logger.d("Applying user-selected language: $languageCode")

			LocaleAwareManager(safeActivityRef).setNewLocale(safeActivityRef, languageCode)
			LocaleAwareManager(INSTANCE).setNewLocale(INSTANCE, languageCode)
			LocalizationHelper.setAppLocale(INSTANCE, locale)

			logger.d("Language applied successfully. Locale set to: $locale")
			onComplete()
		} ?: logger.d("applyUserSelectedLanguage called with null activity reference")
	}

	/**
	 * Closes the current activity if a language change was applied
	 * and an app restart is required for consistency.
	 *
	 * @param baseActivityInf A reference to the activity implementing [BaseActivityInf].
	 */
	fun closeActivityIfLanguageChanged(baseActivityInf: BaseActivityInf?) {
		baseActivityInf?.getActivity()?.let { safeActivityRef ->
			if (finishActivityOnResume) {
				logger.d("Language changed. Closing activity and scheduling application quit.")
				safeActivityRef.finishAffinity()
				quitApplicationCommand = true
				delay(300, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Executing delayed quitApplication()")
						quitApplication(safeActivityRef)
					}
				})
			} else {
				logger.d("No language change detected. Activity remains active.")
			}
		} ?: logger.d("closeActivityIfLanguageChanged called with null activity reference")
	}

	/**
	 * Quits the application by finishing all activities if [quitApplicationCommand] is set.
	 *
	 * @param baseActivityInf A reference to the activity implementing [BaseActivityInf].
	 */
	private fun quitApplication(baseActivityInf: BaseActivityInf?) {
		baseActivityInf?.getActivity()?.let { safeActivityRef ->
			if (quitApplicationCommand) {
				logger.d("Quitting application. Finishing all activities.")
				quitApplicationCommand = false
				safeActivityRef.finishAffinity()
			} else {
				logger.d("quitApplication invoked but quitApplicationCommand was false.")
			}
		} ?: logger.d("quitApplication called with null activity reference")
	}
}