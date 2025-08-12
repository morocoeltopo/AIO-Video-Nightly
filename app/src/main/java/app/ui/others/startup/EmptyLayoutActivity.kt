package app.ui.others.startup

import app.core.bases.BaseActivity
import java.lang.ref.WeakReference

class EmptyLayoutActivity : BaseActivity() {
    val safeSelfReference = WeakReference(this)
    private val safeEmptyLayoutActivityRef = safeSelfReference.get()

    override fun onRenderingLayout(): Int {
        return -1
    }

    override fun onAfterLayoutRender() {}

    override fun onBackPressActivity() {
        exitActivityOnDoubleBackPress()
    }

    override fun onDestroy() {
        safeSelfReference.clear()
        clearWeakActivityReference()
        super.onDestroy()
    }
}