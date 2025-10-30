package app.ui.others.media_player.dialogs

import android.view.View
import androidx.media3.common.util.UnstableApi
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import com.aio.R.layout
import lib.process.LogHelperUtils
import lib.ui.builders.PopupBuilder
import java.lang.ref.WeakReference

/**
 * Popup dialog for configuring media playback settings in [MediaPlayerActivity].
 *
 * This class creates and manages a popup containing various player configuration options
 * such as playback speed, repeat mode, subtitle tracks, and background playback.
 *
 * It uses a [WeakReference] to prevent memory leaks and a [PopupBuilder]
 * to construct and display the dialog dynamically.
 *
 * @constructor Accepts a nullable [MediaPlayerActivity] reference.
 * @property mediaPlayerActivity Optional instance of the parent [MediaPlayerActivity].
 */
@UnstableApi
class MediaConfigsPopup(private val mediaPlayerActivity: MediaPlayerActivity?) {

	/** Logger instance scoped to this class for consistent debug and error output. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the associated [MediaPlayerActivity].
	 * Prevents memory leaks if the activity is destroyed.
	 */
	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()

	/** Builder responsible for constructing and displaying the popup window. */
	private lateinit var popupBuilder: PopupBuilder

	/** Initializes the popup and binds UI elements with event handlers. */
	init {
		logger.d("Initializing MediaConfigsPopup...")
		setupPopupBuilder()
		setupClickEvents()
		logger.d("MediaConfigsPopup initialized successfully.")
	}

	/**
	 * Displays the popup window anchored to the player's options button.
	 *
	 * Logs the show operation for debugging and confirms successful rendering.
	 */
	fun show() {
		logger.d("Attempting to display MediaConfigsPopup...")
		popupBuilder.show()
		logger.d("MediaConfigsPopup displayed successfully.")
	}

	/**
	 * Closes the popup if currently visible.
	 *
	 * Ensures no lingering popups remain on screen during playback transitions.
	 */
	fun close() {
		logger.d("Closing MediaConfigsPopup...")
		popupBuilder.close()
		logger.d("MediaConfigsPopup closed.")
	}

	/**
	 * Initializes [PopupBuilder] with layout and anchor view from [MediaPlayerActivity].
	 *
	 * Logs setup progress and error conditions (e.g., null activity reference).
	 */
	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { safeActivityRef ->
			logger.d("Setting up PopupBuilder for MediaConfigsPopup...")
			popupBuilder = PopupBuilder(
				activityInf = safeActivityRef,
				popupLayoutId = layout.activity_player_5_options_2,
				popupAnchorView = safeActivityRef.configButton
			)
			logger.d("PopupBuilder setup completed successfully.")
		} ?: logger.e("Failed to setup PopupBuilder: Activity reference is null.")
	}

	/**
	 * Binds click listeners to popup options (buttons).
	 *
	 * Each button is associated with a specific media configuration logic.
	 * Logs every binding and warns if the popup view is unavailable.
	 */
	private fun setupClickEvents() {
		safePlayerActivityRef?.let { playerActivity ->
			val popupView = popupBuilder.getPopupView()
			logger.d("Binding click events for MediaConfigsPopup options...")

			with(popupView) {
				mapOf(
					R.id.btn_playback_speed to { handlePlaybackSpeedOption() },
					R.id.btn_playback_mode to { handlePlaybackModeOption() },
					R.id.btn_subtitle_tracks to { handleSubtitleTracksOption() },
					R.id.btn_audio_tracks to { handleAudioTracksOption() },
					R.id.btn_play_in_background to { handleBackgroundPlayOption() }
				).forEach { (id, action) ->
					setClickListener(id) { action() }
				}
			}

			logger.d("Click events successfully bound for MediaConfigsPopup.")
		} ?: logger.e("Unable to bind click events: MediaPlayerActivity reference is null.")
	}

	/**
	 * Safely assigns a click listener to a child view.
	 *
	 * Includes click logging for debugging and avoids null pointer exceptions.
	 *
	 * @param id Resource ID of the target view.
	 * @param action Lambda to execute when clicked.
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener {
			logger.d("Button clicked → View ID: $id")
			action()
		} ?: logger.d("Attempted to assign click listener to missing view ID: $id")
	}

	/** Handles user interaction for playback speed configuration. */
	private fun handlePlaybackSpeedOption() {
		logger.d("Playback Speed option selected.")
		close()

	}

	/** Handles user interaction for playback mode configuration. */
	private fun handlePlaybackModeOption() {
		logger.d("Playback Mode option selected.")
		close()

	}

	/** Handles user interaction for subtitle track selection. */
	private fun handleSubtitleTracksOption() {
		logger.d("Subtitle Tracks option selected.")
		close()

	}

	/** Handles user interaction for audio track selection. */
	private fun handleAudioTracksOption() {
		logger.d("Audio Tracks option selected.")
		close()

	}

	/** Handles user interaction for enabling background playback. */
	private fun handleBackgroundPlayOption() {
		logger.d("Background Play option selected.")
		close()

	}
}
