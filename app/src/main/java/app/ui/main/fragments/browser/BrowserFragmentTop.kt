package app.ui.main.fragments.browser

import android.util.Patterns.WEB_URL
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.suggestions.SuggestionWatcher
import com.aio.R
import com.bumptech.glide.Glide
import lib.ui.ViewUtility.animateFadInOutAnim
import lib.ui.ViewUtility.closeAnyAnimation
import lib.ui.ViewUtility.hideOnScreenKeyboard
import lib.ui.ViewUtility.showOnScreenKeyboard
import java.net.URLEncoder.encode
import java.nio.charset.StandardCharsets.UTF_8

class BrowserFragmentTop(val browserFragment: BrowserFragment) {

    val safeMotherActivityRef = browserFragment.safeBaseActivityRef!! as MotherActivity
    val currentWebView by lazy { browserFragment.browserFragmentBody.webviewEngine.currentWebView }

    lateinit var webViewReloadButton: ImageView
    lateinit var webviewTitle: TextView
    lateinit var webViewFavicon: ImageView
    lateinit var webViewProgress: ProgressBar
    lateinit var webviewOptionPopup: View
    lateinit var browserOptionsPopup: BrowserOptionsPopup

    lateinit var browserTopTitleSection: View
    lateinit var browserUrlEditFieldContainer: View
    lateinit var browserUrlEditField: EditText

    init {
        initializeViews(browserFragment.safeFragmentLayoutRef)
        //setupWebSuggestions(browserFragment.safeFragmentLayoutRef)
        setupClicksEvents(browserFragment.safeFragmentLayoutRef)
    }

    fun animateDefaultFaviconLoading(shouldStopAnimation: Boolean = false) {
        if (shouldStopAnimation) {
            closeAnyAnimation(webViewFavicon)
            return
        }
        val defaultFaviconResId = R.drawable.ic_button_browser_favicon
        Glide.with(safeMotherActivityRef).load(defaultFaviconResId).into(webViewFavicon)
        animateFadInOutAnim(webViewFavicon)
    }

    private fun initializeViews(layoutView: View?) {
        layoutView?.let { _ ->
            browserTopTitleSection = layoutView.findViewById(R.id.top_actionbar_section)
            browserUrlEditFieldContainer = layoutView.findViewById(R.id.top_url_edit_section)
            browserUrlEditField = layoutView.findViewById(R.id.edit_field_url)
            webViewReloadButton = layoutView.findViewById(R.id.btn_browser_reload)
            webViewFavicon = layoutView.findViewById(R.id.img_browser_favicon)
            webviewTitle = layoutView.findViewById(R.id.edit_search_suggestion)
            webViewProgress = layoutView.findViewById(R.id.webview_progress_bar)
            webviewOptionPopup = layoutView.findViewById(R.id.btn_browser_options)
        }
    }

    private fun setupWebSuggestions(layoutView: View?) {
		layoutView?.let {
            SuggestionWatcher(browserUrlEditField, onClickItem = { searchQuery ->
                browserUrlEditField.setText(searchQuery)
                loadUrlToBrowser()
            })
        }
    }

    private fun setupClicksEvents(layoutView: View?) {
        val slideNavigation = safeMotherActivityRef.sideNavigation

        layoutView?.apply {
            val clickActions = mapOf(
                findViewById<View>(R.id.btn_actionbar_back) to { invisibleUrlEditSection() },
                findViewById<View>(R.id.btn_clear_url_edit_field) to { clearEditTextField() },
                findViewById<View>(R.id.btn_load_url_to_browser) to { loadUrlToBrowser() },
                findViewById<View>(R.id.btn_open_navigation) to { slideNavigation?.openDrawerNavigation() },
                findViewById<View>(R.id.container_edit_browser_url) to { visibleUrlEditSection() },
                findViewById<View>(R.id.btn_browser_reload) to { toggleWebviewLoading() },
                findViewById<View>(R.id.btn_browser_options) to { openBrowserPopupOptions() })

            clickActions.forEach { (view, clickAction) ->
                view.setOnClickListener { clickAction() }
            }
        }
    }

    private fun openBrowserPopupOptions() {
        if (!::browserOptionsPopup.isInitialized) {
            browserOptionsPopup = BrowserOptionsPopup(browserFragment)
        }; browserOptionsPopup.show()
    }

    private fun toggleWebviewLoading() {
        browserFragment.browserFragmentBody
            .webviewEngine.toggleCurrentWebViewLoading()
    }

    fun visibleUrlEditSection() {
        browserTopTitleSection.visibility = View.INVISIBLE
        browserUrlEditFieldContainer.visibility = View.VISIBLE

        val browserWebEngine = browserFragment.getBrowserWebEngine()
        val currentWebviewUrl = browserWebEngine.currentWebView?.url
        currentWebviewUrl?.let {
            browserUrlEditField.setText(currentWebviewUrl)
            focusEditTextFieldAndShowKeyboard()
            browserUrlEditField.selectAll()
        }

        browserUrlEditField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH
            ) {
                loadUrlToBrowser(); true
            } else false
        }
    }

    fun invisibleUrlEditSection() {
        browserTopTitleSection.visibility = View.VISIBLE
        browserUrlEditFieldContainer.visibility = View.GONE
    }

    fun focusEditTextFieldAndShowKeyboard() {
        browserUrlEditField.requestFocus()
        showOnScreenKeyboard(safeMotherActivityRef, browserUrlEditField)
    }

    private fun clearEditTextField() {
        browserUrlEditField.setText("")
        focusEditTextFieldAndShowKeyboard()
    }

    private fun loadUrlToBrowser() {
        hideOnScreenKeyboard(safeMotherActivityRef, browserUrlEditField)
        var urlToLoad = browserUrlEditField.text.toString()
        urlToLoad = (if (WEB_URL.matcher(urlToLoad).matches()) urlToLoad
        else "https://www.google.com/search?q=${encode(urlToLoad, UTF_8.toString())}")
        val browserWebEngine = browserFragment.getBrowserWebEngine()
        browserWebEngine.loadURLIntoCurrentWebview(urlToLoad)
        invisibleUrlEditSection()
    }
}