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

class OpeningActivity : BaseActivity() {
    private val safeOpenActivityRef = WeakReference(this).get()

    override fun onRenderingLayout(): Int {
        setLightSystemBarTheme()
        return R.layout.activity_opening_1
    }

    override fun onAfterLayoutRender() {
        showApkVersionInfo()
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)
            launchMotherActivity()
        }
    }

    override fun onBackPressActivity() {
        exitActivityOnDoubleBackPress()
    }

    private fun showApkVersionInfo() {
        val versionName = AppVersionUtility.versionName
        "${getString(R.string.title_version)} : $versionName".apply {
            findViewById<TextView>(R.id.txt_version_info).text = this
        }
    }

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