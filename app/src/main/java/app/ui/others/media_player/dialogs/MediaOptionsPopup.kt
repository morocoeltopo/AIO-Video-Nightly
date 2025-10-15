package app.ui.others.media_player.dialogs

import android.content.Intent
import android.view.View
import android.widget.CheckBox
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

@UnstableApi
class MediaOptionsPopup(private val mediaPlayerActivity: MediaPlayerActivity?) {

	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()
	private lateinit var popupBuilder: PopupBuilder

	init {
		setupPopupBuilder()
		setupClickEvents()
	}

	fun show() {
		refreshPrivateSession()
		popupBuilder.show()
	}

	fun close() {
		popupBuilder.close()
	}

	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { safeActivityRef ->
			popupBuilder = PopupBuilder(
				activityInf = safeActivityRef,
				popupLayoutId = layout.activity_player_5_options,
				popupAnchorView = safeActivityRef.optionsButton
			)
		}
	}

	private fun setupClickEvents() {
		safePlayerActivityRef?.let { _ ->
			with(popupBuilder.getPopupView()) {
				mapOf(
					R.id.btn_delete_file to { close(); deleteFile() },
					R.id.btn_convert_to_audio to { close(); convertAudio() },
					R.id.btn_open_in_another to { close(); openMediaFile() },
					R.id.btn_media_info to { close(); openMediaFileInfo() },
					R.id.btn_private_session to { close(); togglePrivateSession() },
					R.id.btn_discover_video to { close(); discoverMore() }
				).forEach { (id, action) ->
					setClickListener(id) { action() }
				}
			}
		}
	}

	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener { action() }
	}

	private fun deleteFile() {
		safePlayerActivityRef?.let { safeActivityRef ->
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
					messageTextViewCustomize = {
						val stringId = string.text_delete_stream_media_unavailable
						it.setText(stringId)
					}
				)
				return
			}

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
		safePlayerActivityRef?.let { playerActivity ->
			if (playerActivity.isPrivateSessionAllowed) {
				playerActivity.isPrivateSessionAllowed = false
				playerActivity.hasMadeDecisionOverPrivateAccess = false
			} else playerActivity.enablePrivateSession()
			refreshPrivateSession()
		}
	}

	private fun refreshPrivateSession() {
		safePlayerActivityRef?.let { playerActivity ->
			val popupView = popupBuilder.getPopupView()
			val checkBox = popupView.findViewById<CheckBox>(R.id.checkbox_private_session)
			checkBox.isChecked = playerActivity.isPrivateSessionAllowed
		}
	}

	private fun openMediaFileInfo() {
		safePlayerActivityRef?.openMediaFileInfo()
	}

	private fun discoverMore() {
		safePlayerActivityRef?.let { playerActivity ->
			if (playerActivity.isPlayingStreamingVideo()) {
				showMessageDialog(
					baseActivityInf = playerActivity,
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
				)
				return
			}

			val candidate = playerActivity.getCurrentPlayingDownloadModel()
			candidate?.siteReferrer?.let { referrer ->
				try {
					val intent = Intent(Intent.ACTION_VIEW, referrer.toUri())
					playerActivity.startActivity(intent)
				} catch (error: Exception) {
					error.printStackTrace()
					val msgId = string.title_no_app_can_handle_this_request
					showToast(playerActivity, msgId = msgId)
				}
			}
		}
	}

	private fun convertAudio() {
		showMp4ToAudioConverterDialog(
			safePlayerActivityRef,
			safePlayerActivityRef?.getCurrentPlayingDownloadModel()
		)
	}

	private fun openMediaFile() {
		safePlayerActivityRef?.openMediaFile()
	}
}