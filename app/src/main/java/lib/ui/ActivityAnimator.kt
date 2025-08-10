@file:Suppress("DEPRECATION")

package lib.ui

import android.app.Activity
import androidx.core.app.ActivityOptionsCompat
import com.aio.R
import java.lang.ref.WeakReference

object ActivityAnimator {

	@JvmStatic
	fun animActivityFade(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_fade_enter,
			R.anim.anim_fade_exit
		)
	}

	@JvmStatic
	fun animActivityInAndOut(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_in_out_enter,
			R.anim.anim_in_out_exit
		)
	}

	@JvmStatic
	fun animActivitySlideDown(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_slide_down_enter,
			R.anim.anim_slide_down_exit
		)
	}

	@JvmStatic
	fun animActivitySlideLeft(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_slide_left_enter,
			R.anim.anim_slide_left_exit
		)
	}

	@JvmStatic
	fun animActivitySwipeRight(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_swipe_right_enter,
			R.anim.anim_swipe_right_exit
		)
	}

	@JvmStatic
	fun animActivitySlideUp(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_slide_up_enter,
			R.anim.anim_slide_up_exit
		)
	}

	@JvmStatic
	fun animActivitySwipeLeft(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_swipe_left_enter,
			R.anim.anim_swipe_left_exit
		)
	}

	@JvmStatic
	fun animActivitySlideRight(activity: Activity?) {
		WeakReference(activity).get()?.overridePendingTransition(
			R.anim.anim_slide_in_left,
			R.anim.anim_slide_out_right
		)
	}

	@JvmStatic
	fun getMaterialSlideOptions(activity: Activity?): ActivityOptionsCompat? {
		return WeakReference(activity).get()?.let { safeContextRef ->
			ActivityOptionsCompat.makeCustomAnimation(
				safeContextRef,
				android.R.anim.slide_in_left,
				android.R.anim.slide_out_right
			)
		} ?: run { null }
	}
}