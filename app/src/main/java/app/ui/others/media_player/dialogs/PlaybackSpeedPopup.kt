package app.ui.others.media_player.dialogs

import android.view.View
import android.widget.CheckBox
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import com.aio.R.layout
import lib.process.LogHelperUtils
import lib.ui.builders.PopupBuilder
import java.lang.ref.WeakReference

@UnstableApi
class PlaybackSpeedPopup(
	private val mediaPlayerActivity: MediaPlayerActivity?,
	private val anchorView: View
) {
	private val logger = LogHelperUtils.from(javaClass)
	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()
	private lateinit var popupBuilder: PopupBuilder
	private lateinit var exoPlayer: ExoPlayer

	private lateinit var checkbox075: CheckBox
	private lateinit var checkbox050: CheckBox
	private lateinit var checkbox025: CheckBox
	private lateinit var checkbox01: CheckBox
	private lateinit var checkbox125: CheckBox
	private lateinit var checkbox150: CheckBox
	private lateinit var checkbox175: CheckBox
	private lateinit var checkbox2: CheckBox

	init {
		setupPopupBuilder()
		setupClickEvents()
	}

	fun show() {
		refreshCheckboxes()
		popupBuilder.show()
	}

	fun close() = popupBuilder.close()

	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { act ->
			exoPlayer = act.player
			popupBuilder = PopupBuilder(
				activityInf = act,
				popupLayoutId = layout.activity_player_6_speed_1,
				popupAnchorView = anchorView
			)

			with(popupBuilder.getPopupView()) {
				checkbox075 = findViewById(R.id.checkbox_playback_speed_0_75x)
				checkbox050 = findViewById(R.id.checkbox_playback_speed_0_5x)
				checkbox025 = findViewById(R.id.checkbox_playback_speed_0_25x)
				checkbox01 = findViewById(R.id.checkbox_playback_speed_1x)
				checkbox125 = findViewById(R.id.checkbox_playback_speed_1_25x)
				checkbox150 = findViewById(R.id.checkbox_playback_speed_1_5x)
				checkbox175 = findViewById(R.id.checkbox_playback_speed_1_75x)
				checkbox2 = findViewById(R.id.check_playback_speed_2x)
			}
		} ?: logger.e("Activity reference null while setting up popup.")
	}

	private fun setupClickEvents() {
		safePlayerActivityRef?.let {
			with(popupBuilder.getPopupView()) {
				mapOf(
					R.id.bnt_playback_speed_0_25x to 0.25f,
					R.id.bnt_playback_speed_0_5x to 0.5f,
					R.id.bnt_playback_speed_0_75x to 0.75f,
					R.id.bnt_playback_speed_1x to 1.0f,
					R.id.bnt_playback_speed_1_25x to 1.25f,
					R.id.bnt_playback_speed_1_5x to 1.5f,
					R.id.bnt_playback_speed_1_75x to 1.75f,
					R.id.bnt_playback_speed_2x to 2.0f
				).forEach { (id, speed) ->
					setClickListener(id) {
						changePlaybackSpeed(speed)
						close()
					}
				}
			}
		} ?: logger.e("Activity reference null while binding click events.")
	}

	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener { action() }
	}

	private fun changePlaybackSpeed(speed: Float) {
		logger.d("Setting playback speed to $speed")
		exoPlayer.playbackParameters = PlaybackParameters(speed)
		refreshCheckboxes()
	}

	private fun refreshCheckboxes() {
		val speed = exoPlayer.playbackParameters.speed
		listOf(
			checkbox025 to 0.25f,
			checkbox050 to 0.5f,
			checkbox075 to 0.75f,
			checkbox01 to 1.0f,
			checkbox125 to 1.25f,
			checkbox150 to 1.5f,
			checkbox175 to 1.75f,
			checkbox2 to 2.0f
		).forEach { (box, value) ->
			box.isChecked = (speed == value)
		}
	}
}
