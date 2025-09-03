package app.ui.main.fragments.settings.dialogs

import app.core.bases.BaseActivity
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference

class CustomDownloadFolderSelector(private val baseActivity: BaseActivity) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the base activity */
	private val safeBaseActivity = WeakReference(baseActivity).get()

	fun show() {

	}

	fun close() {

	}

}