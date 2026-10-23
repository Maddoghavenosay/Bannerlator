package com.winlator.star.store.xbox

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Xbox Cloud Gaming test: Microsoft's web player (xbox.com/play) in a full-screen WebView. Runs in its own
 * process (":xboxcloud") with its own WebView data directory, so its sign-in cookies persist and stay apart
 * from the store sign-in and the other stores' web logins. Extras "url" and "ua" override the start page
 * and the user agent for testing.
 */
class XboxCloudActivity : ComponentActivity() {
    private var web: WebView? = null
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { WebView.setDataDirectorySuffix("xboxcloud") }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        WebView.setWebContentsDebuggingEnabled(true)
        Log.i(TAG, "WebView ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")

        val view = WebView(this)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = intent.getStringExtra("ua") ?: chromeUserAgent(userAgentString)
        }
        // No "X-Requested-With: <package>" header: it marks the page as embedded in an app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        Log.i(TAG, "UA ${view.settings.userAgentString}")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"
            override fun onPageFinished(view: WebView, url: String) { Log.i(TAG, "page ${url.substringBefore('?')}") }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "console ${message.messageLevel()}: ${message.message().take(300)}")
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.i(TAG, "permission request ${request.resources.joinToString()} denied")
                request.deny()
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                custom?.let { root.removeView(it) }
                custom = view; customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                hideBars()
            }
            override fun onHideCustomView() {
                custom?.let { root.removeView(it) }
                custom = null; customCallback = null
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (savedInstanceState != null) view.restoreState(savedInstanceState)
        else view.loadUrl(intent.getStringExtra("url") ?: START_URL)
    }

    /** The WebView's own agent without the "; wv" and "Version/4.0" markers, so the page sees Chrome for Android. */
    private fun chromeUserAgent(default: String): String =
        default.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // A controller's B button can arrive as BACK when the page does not use it; only a real back press
    // (not from a gamepad) leaves fullscreen, goes back a page, or closes the player.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                custom != null -> customCallback?.onCustomViewHidden()
                web?.canGoBack() == true -> web?.goBack()
                else -> finish()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    override fun onDestroy() {
        web?.let { root.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XboxCloud"
        const val PROCESS_SUFFIX = ":xboxcloud"
        const val START_URL = "https://www.xbox.com/play"

        fun intent(context: Context): Intent = Intent(context, XboxCloudActivity::class.java)

        /** True in the cloud-play process (BannerlatorApp skips its start-up work there). */
        fun isCloudProcess(): Boolean = runCatching {
            val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
                else java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
            name.endsWith(PROCESS_SUFFIX)
        }.getOrDefault(false)
    }
}
