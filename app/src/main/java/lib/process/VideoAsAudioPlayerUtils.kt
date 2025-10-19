package lib.process

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.lang.ref.WeakReference

class VideoAsAudioPlayerUtils(private val context: Context?) : AudioPlayerUtils(context) {

	fun playFromUri(uri: Uri) {
		stop() // Stop existing playback

		WeakReference(context).get()?.let { safeContext ->
			mediaPlayer = MediaPlayer().apply {
				setDataSource(safeContext, uri)
				setOnPreparedListener { start() }
				setOnCompletionListener {
					completionListener?.invoke()
					stop()
				}
				setOnErrorListener { _, what, extra ->
					errorListener?.invoke(what, extra)
					stop()
					false
				}
				prepareAsync()
			}
		}
	}
}
