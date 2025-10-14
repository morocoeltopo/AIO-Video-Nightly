package app.ui.others.media_player.dialogs

import android.content.Intent
import android.view.View
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.dialogs.Mp4ToAudioConverterDialog.showMp4ToAudioConverterDialog
import com.aio.R
import com.aio.R.layout
import com.aio.R.string
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setTextColorKT
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * A popup dialog that provides various media options for the currently playing media file.
 *
 * This class handles operations like:
 * - Deleting the media file
 * - Converting video to audio
 * - Opening the media in another app
 * - Showing media information
 * - Discovering related content
 *
 * @property mediaPlayerActivity The parent MediaPlayerActivity instance (held weakly to prevent leaks)
 */
@UnstableApi
class MediaOptionsPopup(private val mediaPlayerActivity: MediaPlayerActivity?) {

	// Weak reference to prevent memory leaks
	private val safeMediaPlayerActivityRef = WeakReference(mediaPlayerActivity).get()
	private lateinit var popupBuilder: PopupBuilder

	init {
		setupPopupBuilder()
		setupClickEvents()
	}

	/**
	 * Displays the media options popup.
	 */
	fun show() {
		popupBuilder.show()
	}

	/**
	 * Closes the media options popup.
	 */
	fun close() {
		popupBuilder.close()
	}

	/**
	 * Initializes the popup builder with the appropriate layout and anchor view.
	 */
	private fun setupPopupBuilder() {
		safeMediaPlayerActivityRef?.let { safeActivityRef ->
			popupBuilder = PopupBuilder(
				activityInf = safeActivityRef,
				popupLayoutId = layout.activity_player_5_options,
				popupAnchorView = safeActivityRef.buttonOptionActionbar
			)
		}
	}

	/**
	 * Sets up click listeners for all options in the popup.
	 */
	private fun setupClickEvents() {
		safeMediaPlayerActivityRef?.let { _ ->
			with(popupBuilder.getPopupView()) {
				// Map of view IDs to their corresponding actions
				mapOf(
					R.id.btn_delete_file to { close(); deleteFile() },
					R.id.btn_convert_to_audio to { close(); convertAudio() },
					R.id.btn_open_in_another to { close(); openMediaFile() },
					R.id.btn_media_info to { close(); openMediaFileInfo() },
					R.id.btn_private_session to { close(); togglePrivateSession() }
							R . id . btn_discover_video to { close(); discoverMore() }
				).forEach { (id, action) ->
					setClickListener(id) { action() }
				}
			}
		}
	}

	/**
	 * Helper function to set click listeners on views.
	 *
	 * @param id The view ID to set the listener on
	 * @param action The action to perform when clicked
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener { action() }
	}

	/**
	 * Handles the file deletion operation with confirmation dialog.
	 */
	private fun deleteFile() {
		safeMediaPlayerActivityRef?.let { safeActivityRef ->
			// Check if currently playing streaming video (cannot delete)
			if (safeActivityRef.isPlayingStreamingVideo()) {
				showMessageDialog(
					baseActivityInf = safeActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(R.color.color_error)
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(R.drawable.ic_okay_done)
						positiveButton.setText(string.title_okay)
					},
					messageTextViewCustomize = { it.setText(string.text_delete_stream_media_unavailable) }
				); return
			}

			// Show confirmation dialog for deletion
			val dialogBuilder = getMessageDialog(
				baseActivityInf = safeActivityRef,
				titleText = getText(string.title_are_you_sure_about_this),
				isTitleVisible = true,
				isNegativeButtonVisible = false,
				positiveButtonText = getText(string.title_delete_file),
				messageTextViewCustomize = { it.setText(string.text_are_you_sure_about_delete) },
				negativeButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_cancel) },
				positiveButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_delete) }
			)

			dialogBuilder?.setOnClickForPositiveButton {
				dialogBuilder.close()
				this@MediaOptionsPopup.close()
				safeActivityRef.deleteMediaFile()
			}

			dialogBuilder?.show()
		}
	}

	private fun togglePrivateSession() {

	}

	/**
	 * Opens the media file information dialog.
	 */
	private fun openMediaFileInfo() {
		safeMediaPlayerActivityRef?.openMediaFileInfo()
	}

	/**
	 * Handles the "discover more" action by opening the referrer URL.
	 */
	private fun discoverMore() {
		safeMediaPlayerActivityRef?.let { playerActivityRef ->
			// Check if currently playing streaming video (cannot discover)
			if (playerActivityRef.isPlayingStreamingVideo()) {
				showMessageDialog(
					baseActivityInf = playerActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(R.color.color_error)
					}, messageTextViewCustomize = { msgTextView ->
						msgTextView.setText(string.text_no_discovery_during_streaming)
					}, positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(R.drawable.ic_okay_done)
						positiveButton.setText(string.title_okay)
					}
				); return
			}

			// Try to open the referrer URL in a browser
			playerActivityRef.getCurrentPlayingDownloadModel()?.siteReferrer?.let { referrer ->
				try {
					val intent = Intent(Intent.ACTION_VIEW, referrer.toUri())
					playerActivityRef.startActivity(intent)
				} catch (error: Exception) {
					error.printStackTrace()
					showToast(activityInf = safeMediaPlayerActivityRef,
						msgId = string.title_no_app_can_handle_this_request)
				}
			}
		}
	}

	/**
	 * Converts the current video file to audio format.
	 */
	private fun convertAudio() {
		showMp4ToAudioConverterDialog(safeMediaPlayerActivityRef,
			safeMediaPlayerActivityRef?.getCurrentPlayingDownloadModel())
	}

	/**
	 * Opens the current media file in another application.
	 */
	private fun openMediaFile() {
		safeMediaPlayerActivityRef?.openMediaFile()
	}
}