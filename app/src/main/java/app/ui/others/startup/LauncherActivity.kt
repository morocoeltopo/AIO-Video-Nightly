package app.ui.others.startup

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import app.ui.main.MotherActivity
import app.ui.others.information.UserFeedbackActivity
import app.ui.others.information.UserFeedbackActivity.FROM_CRASH_HANDLER
import app.ui.others.information.UserFeedbackActivity.WHERE_DIS_YOU_COME_FROM
import lib.ui.ActivityAnimator.animActivityFade
import java.lang.ref.WeakReference

/**
 * LauncherActivity
 *
 * This activity decides what to launch when the app starts:
 * - If the app crashed recently → launch feedback screen.
 * - Otherwise → launch the normal entry activity.
 *
 * Uses WeakReference to prevent memory leaks when handling context.
 */
class LauncherActivity : BaseActivity() {

    // Safe reference to this activity to avoid leaks
    private val safeLauncherActivityRef = WeakReference(this).get()

    /**
     * Skip rendering a layout since this activity acts as a launcher/router.
     * Returning -1 indicates no layout is set.
     */
    override fun onRenderingLayout(): Int {
        return -1
    }

    /**
     * Called after layout (if any) would be rendered.
     * Decides whether to show crash feedback or launch the main flow.
     */
    override fun onAfterLayoutRender() {
        if (aioSettings.hasAppCrashedRecently) launchFeedbackActivity()
        else launchOpeningActivity()
    }

    /**
     * Handles back button press by requiring a double press to exit.
     */
    override fun onBackPressActivity() {
        exitActivityOnDoubleBackPress()
    }

    /**
     * Launches feedback activity if app crashed recently.
     * Passes extras to indicate it came from crash handler.
     */
    private fun launchFeedbackActivity() {
        safeLauncherActivityRef?.let { context ->
            // Reset crash flag so feedback is not shown repeatedly
            aioSettings.hasAppCrashedRecently = false
            aioSettings.updateInStorage()

            Intent(context, UserFeedbackActivity::class.java).apply {
                flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                putExtra(WHERE_DIS_YOU_COME_FROM, FROM_CRASH_HANDLER)
                startActivity(this)
                finish()
                animActivityFade(context)
            }
        }
    }

    /**
     * Launches the main MotherActivity (main container of the app).
     */
    private fun launchMotherActivity() {
        safeLauncherActivityRef?.let { context ->
            Intent(context, MotherActivity::class.java).apply {
                flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                startActivity(this)
                finish()
                animActivityFade(getActivity())
            }
        }
    }

    /**
     * Launches the initial OpeningActivity (app intro/startup screen).
     */
    private fun launchOpeningActivity() {
        safeLauncherActivityRef?.let { context ->
            Intent(context, OpeningActivity::class.java).apply {
                flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                startActivity(this)
                finish()
                animActivityFade(getActivity())
            }
        }
    }
}