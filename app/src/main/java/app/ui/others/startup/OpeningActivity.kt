package app.ui.others.startup

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.widget.TextView
import app.core.bases.BaseActivity
import app.ui.main.MotherActivity
import com.aio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.AppVersionUtility
import lib.ui.ActivityAnimator.animActivityFade
import java.lang.ref.WeakReference

/**
 * OpeningActivity
 *
 * This is the splash/intro activity of the app.
 * - Displays the app version info.
 * - Waits briefly before navigating to [MotherActivity].
 *
 * Uses WeakReference to avoid leaking the activity context.
 */
class OpeningActivity : BaseActivity() {

    // Safe reference to the activity to prevent leaks
    private val safeOpenActivityRef = WeakReference(this).get()

    /**
     * Called when the activity layout should be rendered.
     * - Sets system bar colors for splash screen.
     * - Returns the layout resource for opening activity.
     *
     * @return [Int] Layout resource ID for this screen.
     */
    override fun onRenderingLayout(): Int {
        setSystemBarsColors(
            statusBarColorResId = R.color.color_background,
            navigationBarColorResId = R.color.color_background,
            isLightStatusBar = true,
            isLightNavigationBar = true
        )
        return R.layout.activity_opening_1
    }

    /**
     * Called after the layout has been rendered.
     * - Displays app version info.
     * - Waits 1 second, then launches [MotherActivity].
     */
    override fun onAfterLayoutRender() {
        showApkVersionInfo()
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000) // short splash delay
            launchMotherActivity()
        }
    }

    /**
     * Handles back press by requiring double press to exit.
     */
    override fun onBackPressActivity() {
        exitActivityOnDoubleBackPress()
    }

    /**
     * Displays current APK version on the splash screen.
     * Updates [R.id.txt_version_info] with version text.
     */
    private fun showApkVersionInfo() {
        val versionName = AppVersionUtility.versionName
        "${getString(R.string.title_version)} : $versionName".apply {
            findViewById<TextView>(R.id.txt_version_info).text = this
        }
    }

    /**
     * Navigates from splash screen to the main [MotherActivity].
     * Applies fade animation and finishes the current activity.
     */
    private fun launchMotherActivity() {
        safeOpenActivityRef?.let { context ->
            Intent(context, MotherActivity::class.java).apply {
                flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                startActivity(this)
                animActivityFade(getActivity())
                finish()
            }
        }
    }
}