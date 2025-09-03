package app.ui.main.fragments.settings.dialogs

import app.core.bases.BaseActivity
import lib.files.FileSystemUtility
import lib.files.FileSystemUtility.hasFullFileSystemAccess
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference

class CustomDownloadFolderSelector(private val baseActivity: BaseActivity) {
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks with the base activity */
	private val safeBaseActivity = WeakReference(baseActivity).get()

	fun show() {
		safeBaseActivity?.let {
			if (!hasFullFileSystemAccess(safeBaseActivity)) {
				FileSystemUtility.openAllFilesAccessSettings(safeBaseActivity)
				return
			}
		}
	}

	fun close() {

	}

}