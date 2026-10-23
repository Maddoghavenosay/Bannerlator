package com.winlator.star.store.xbox

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Bannerlator's own Xbox Cloud Gaming player: signs in with the store account (XboxCloudSession, no web
 * sign-in), starts the cloud session and does the signalling here, and runs the WebRTC client (Greenlight's
 * player, tools/xcloud-player) in a local page. The page never sees a token. Falls back to Microsoft's web
 * player when this account cannot stream the game this way.
 */
class XboxCloudPlayerActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var notice: LinearLayout? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val signalling = Executors.newCachedThreadPool()
    @Volatile private var session: XboxCloudSession.Session? = null
    @Volatile private var sessionPath: String? = null
    @Volatile private var titleId: String? = null
    @Volatile private var closed = false
    @Volatile private var webUrl: String? = null
    @Volatile private var settings = XboxCloudSettings()
    private var menu: androidx.compose.ui.platform.ComposeView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val requested = intent.getStringExtra("product").orEmpty()
        showNotice("Signing in to Xbox Cloud Gaming…", withButtons = false)
        worker.execute {
            val result = runCatching {
                val account = XboxAccountStore.signedInAs(applicationContext) ?: error("Sign in to the Xbox store first.")
                // A PC game whose console edition streams (one card in the library) streams that edition.
                val cached = XboxLibraryCache.read(applicationContext, account, Xodus.market())?.games?.firstOrNull { it.id == requested }
                val product = cached?.cloudId ?: requested
                webUrl = (cached ?: XboxGame(requested, intent.getStringExtra("title").orEmpty())).cloudUrl
                val active = XboxCloudSession.get(applicationContext, account)
                val title = XboxCloudSession.titles(active)[product] ?: error("This game can't be streamed with this account here.")
                settings = XboxCloudSettings.load(applicationContext)
                session = active.forStream(settings.region, settings.osName); titleId = title
                XboxDiag.log(applicationContext, "cloud player: $product -> title $title, region ${java.net.URI(session!!.baseUri).host}, " +
                    "transfer=${active.userToken != null}, settings ${settings.toJson()}")
            }
            runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { openPlayer() }.onFailure {
                    XboxDiag.log(applicationContext, "cloud player not started: ${it.javaClass.simpleName}: ${it.message?.take(160)}")
                    showNotice(it.message ?: "Xbox Cloud Gaming could not start.", withButtons = true)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlayer() {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val view = WebView(this)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        view.setBackgroundColor(Color.BLACK)
        // The Gamepad API only sees controllers while the WebView has input focus.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.addJavascriptInterface(Bridge(), "BLCloud")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("XboxCloudPlayer", "console ${message.messageLevel()}: ${message.message().take(300)}")
                return true
            }
        }
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        notice?.let { root.removeView(it) }
        notice = null
        view.requestFocus()
        view.loadUrl("https://appassets.androidplatform.net/assets/xcloud/player.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, op: String, arg: String) {
            signalling.execute {
                val reply = runCatching { handle(op, arg) }.getOrElse { XboxCloudSession.Reply(0, it.javaClass.simpleName) }
                if (reply.status !in 200..299) XboxDiag.log(applicationContext, "cloud player $op: ${reply.status} ${reply.body.take(300)}")
                else if (op != "state" && op != "keepalive") XboxDiag.log(applicationContext, "cloud player $op: ${reply.status}")
                runOnUiThread { web?.evaluateJavascript("window.__blResolve($id, ${reply.status}, ${JSONObject.quote(reply.body)})", null) }
            }
        }

        @JavascriptInterface
        fun status(text: String) { android.util.Log.i("XboxCloudPlayer", "status: ${text.take(120)}") }

        @JavascriptInterface
        fun playing() {
            XboxDiag.log(applicationContext, "cloud player streaming")
            runOnUiThread { web?.requestFocus() }
        }

        @JavascriptInterface
        fun settings(): String = this@XboxCloudPlayerActivity.settings.toJson()

        @JavascriptInterface
        fun log(text: String) { XboxDiag.log(applicationContext, "cloud player page: ${text.take(240)}") }

        @JavascriptInterface
        fun failed(stage: String, detail: String) {
            XboxDiag.log(applicationContext, "cloud player failed at ${stage.take(40)}: ${detail.take(300)}")
            runOnUiThread { if (!closed) showNotice("The stream could not start ($stage).", withButtons = true) }
        }
    }

    private fun handle(op: String, arg: String): XboxCloudSession.Reply {
        val active = session ?: error("no session")
        val path = sessionPath
        return when (op) {
            "start" -> {
                val body = JSONObject().put("clientSessionId", "").put("titleId", titleId).put("systemUpdateGroup", "")
                    .put("settings", JSONObject().put("nanoVersion", "V3;WebrtcTransport.dll").put("enableOptionalDataCollection", false)
                        .put("enableTextToSpeech", false).put("highContrast", 0).put("locale", settings.language.ifEmpty { java.util.Locale.getDefault().toLanguageTag() })
                        .put("useIceConnection", false).put("timezoneOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
                        .put("sdkType", "web").put("osName", settings.osName))
                    .put("serverId", "").put("fallbackRegionNames", JSONArray())
                XboxCloudSession.request(active, "POST", "/v5/sessions/cloud/play", body.toString()).also { reply ->
                    if (reply.status in 200..299) sessionPath = JSONObject(reply.body).getString("sessionPath").trim('/')
                }
            }
            "state" -> XboxCloudSession.request(active, "GET", "/$path/state")
            "connect" -> {
                val token = active.userToken ?: return XboxCloudSession.Reply(0, "no cloud transfer token for this account")
                XboxCloudSession.request(active, "POST", "/$path/connect", JSONObject().put("userToken", token).toString())
            }
            "sdp" -> {
                val body = JSONObject().put("messageType", "offer").put("sdp", arg).put("requestId", "1").put("configuration", JSONObject()
                    .put("chatConfiguration", JSONObject().put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                        .put("format", JSONObject().put("codec", "opus").put("container", "webm")).put("numChannels", 1).put("sampleFrequencyHz", 24000))
                    .put("chat", version(1, 1)).put("control", version(1, 3)).put("input", version(1, 9)).put("message", version(1, 1))
                    .put("reliableinput", version(9, 9)).put("unreliableinput", version(9, 9)))
                val posted = XboxCloudSession.request(active, "POST", "/$path/sdp", body.toString())
                if (posted.status !in 200..299) posted else poll(active, "/$path/sdp")
            }
            "ice" -> {
                val posted = XboxCloudSession.request(active, "POST", "/$path/ice", JSONObject().put("candidates", JSONArray(arg)).toString())
                if (posted.status !in 200..299) posted else poll(active, "/$path/ice")
            }
            "keepalive" -> XboxCloudSession.request(active, "POST", "/$path/keepalive", "{}")
            else -> XboxCloudSession.Reply(0, "unknown request")
        }
    }

    private fun version(min: Int, max: Int) = JSONObject().put("minVersion", min).put("maxVersion", max)

    /** The exchange answers 204 until the console has replied. */
    private fun poll(active: XboxCloudSession.Session, path: String): XboxCloudSession.Reply {
        repeat(60) {
            val reply = XboxCloudSession.request(active, "GET", path)
            if (reply.status != 204) return reply
            Thread.sleep(500)
        }
        return XboxCloudSession.Reply(0, "no answer from the cloud console")
    }

    private fun showNotice(text: String, withButtons: Boolean) {
        notice?.let { root.removeView(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (withButtons) Color.argb(230, 0, 0, 0) else Color.TRANSPARENT)
        }
        box.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; this.text = text; setPadding(32, 16, 32, 16) })
        if (withButtons) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            (webUrl ?: intent.getStringExtra("webUrl"))?.let { url ->
                row.addView(Button(this).apply { this.text = "Open in Microsoft's player"; setOnClickListener { startActivity(XboxCloudActivity.intent(this@XboxCloudPlayerActivity, url)); finish() } })
            }
            row.addView(Button(this).apply { this.text = "Close"; setOnClickListener { finish() } })
            box.addView(row)
        }
        notice = box
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

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

    private val menuButton by lazy { com.winlator.star.ui.components.GlobalControllerPrefs.getMenuButtonKeyCode(this) }
    private var nexusHeld = false
    private var backDownAt = 0L

    /**
     * The Xbox button in the stream: a controller's Guide button, and the menu button chosen in Input
     * Controls › Device (the one that opens the side menu in Wine games). Same rules as the game session:
     * never B, never the Back Android makes out of an unhandled B. A Back chosen as the menu button opens
     * the Xbox menu on a short press; holding it asks to leave.
     */
    private fun nexusKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_B) return false
        val isMenuButton = menuButton > 0 && code == menuButton
        if (code != KeyEvent.KEYCODE_BUTTON_MODE && !isMenuButton) return false
        if (code == KeyEvent.KEYCODE_BACK && ((event.flags and KeyEvent.FLAG_FALLBACK) != 0 || event.scanCode in 0x130..0x13f)) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDownAt = event.eventTime
            if (event.action == KeyEvent.ACTION_DOWN && event.eventTime - backDownAt >= 600 && !nexusHeld) { backDownAt = Long.MAX_VALUE; openMenu() }
            if (event.action == KeyEvent.ACTION_UP && backDownAt != Long.MAX_VALUE) pressNexus(1).also { web?.postDelayed({ pressNexus(0) }, 150) }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { nexusHeld = true; pressNexus(1) }
        if (event.action == KeyEvent.ACTION_UP && nexusHeld) { nexusHeld = false; pressNexus(0) }
        return true
    }

    private fun pressNexus(value: Int) {
        web?.evaluateJavascript("window.__blButton && window.__blButton('Nexus', $value)", null)
    }

    // Back (the Pocket FIT's back button, B the stream did not take, or touch back) opens the player menu, so a
    // stray press never ends a stream; Back again closes it. With an error notice up, Back leaves.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menu != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) closeMenu(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (notice == null && nexusKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { if (notice == null) openMenu() else finish() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** The player menu: Resume / Xbox button / Leave, and every cloud setting (picture, stats and sound change live). */
    private fun openMenu() {
        if (menu != null || web == null) return
        web?.evaluateJavascript("window.__blInput && window.__blInput(false)", null)
        val regions = session?.regionList?.map { it.name }.orEmpty()
        val view = androidx.compose.ui.platform.ComposeView(this)
        view.setContent {
            com.winlator.star.ui.theme.WinlatorTheme {
                var current by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(settings) }
                val resume = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { resume.requestFocus() } }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.fillMaxHeight().width(460.dp)
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 12.dp),
                    ) {
                        androidx.compose.material3.Text(intent.getStringExtra("title").orEmpty().ifEmpty { "Xbox Cloud Gaming" },
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp))
                        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(onClick = { closeMenu() },
                                modifier = androidx.compose.ui.Modifier.focusRequester(resume)) { androidx.compose.material3.Text("Resume") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                closeMenu(); web?.evaluateJavascript("window.__blNexus && window.__blNexus()", null)
                            }) { androidx.compose.material3.Text("Xbox button") }
                            androidx.compose.material3.OutlinedButton(onClick = { finish() }) { androidx.compose.material3.Text("Leave") }
                        }
                        androidx.compose.material3.Text("View + Menu together, Guide or your menu button also press the Xbox button.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp))
                        XboxCloudSettingsPanel(current, regions, live = true) { changed ->
                            current = changed
                            settings = changed
                            XboxCloudSettings.save(this@XboxCloudPlayerActivity, changed)
                            web?.evaluateJavascript("window.__blApply && window.__blApply(${JSONObject.quote(changed.toJson())})", null)
                        }
                    }
                }
            }
        }
        menu = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.requestFocus()
    }

    private fun closeMenu() {
        menu?.let { root.removeView(it) }
        menu = null
        web?.evaluateJavascript("window.__blInput && window.__blInput(true)", null)
        web?.requestFocus()
    }

    override fun onDestroy() {
        closed = true
        web?.let { it.evaluateJavascript("window.__blStop && window.__blStop()", null); root.removeView(it); it.destroy() }
        web = null
        val active = session; val path = sessionPath
        if (active != null && path != null) Thread({
            runCatching { XboxCloudSession.request(active, "DELETE", "/$path") }
                .onSuccess { XboxDiag.log(applicationContext, "cloud player session closed: ${it.status}") }
        }, "xcloud-stop").start()
        worker.shutdownNow(); signalling.shutdownNow()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, game: XboxGame): Intent = Intent(context, XboxCloudPlayerActivity::class.java)
            .putExtra("product", game.id).putExtra("title", game.title).putExtra("webUrl", game.cloudUrl)

        /** Stream an Xbox shortcut's game (launch method "Stream"); the player resolves the cloud edition. */
        fun intent(context: Context, productId: String, title: String): Intent = Intent(context, XboxCloudPlayerActivity::class.java)
            .putExtra("product", productId.uppercase()).putExtra("title", title)

        /** Whether the cached Xbox library says this product streams (off the main thread: it reads the cache). */
        fun streams(context: Context, productId: String): Boolean = runCatching {
            val app = context.applicationContext
            val account = XboxAccountStore.signedInAs(app) ?: return false
            XboxLibraryCache.read(app, account, Xodus.market())?.games?.firstOrNull { it.id == productId.uppercase() }?.cloud == true
        }.getOrDefault(false)
    }
}
