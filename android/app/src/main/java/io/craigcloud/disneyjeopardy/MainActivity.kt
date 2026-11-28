package io.craigcloud.disneyjeopardy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fullscreen WebView kiosk for the Disney Jeopardy TV board. All game logic lives on the server; this app only
 * hosts the page, forwards the remote's keys, and exposes a small `window.DJ_TV` bridge (see README).
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private lateinit var theme: ThemePack
    private lateinit var overlay: FrameLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayMessage: TextView
    private lateinit var overlaySpinner: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var settingsButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var mainFrameError = false
    private var retryAttempt = 0
    private val probeSeq = AtomicInteger()
    private var pageTopLevel = false
    private var lastBackAt = 0L
    private var okLongPressed = false
    private var okDownEvent: KeyEvent? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var settingsDialog: AlertDialog? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private class Speech(val id: String, val text: String, val rate: Float, val pitch: Float)
    private var pendingSpeech: Speech? = null
    @Volatile private var currentUtterance: String? = null
    private val utteranceSeq = AtomicInteger()

    private val prefs by lazy { getSharedPreferences("dj", Context.MODE_PRIVATE) }
    /** Set by a launch intent's `url` extra; lasts for this process only so other apps can't repoint the kiosk for good. */
    private var launchUrl: String? = null
    private val serverUrl: String get() = launchUrl ?: prefs.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_URL

    private val longPressRunnable = Runnable {
        okLongPressed = true
        currentFocus?.isPressed = false
        openSettings()
    }

    /** Quiet background check while the error screen is up: the screen is only touched once the server answers. */
    private val retryTick = Runnable { if (mainFrameError) probe(quiet = true) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (mainFrameError) {
                    handler.removeCallbacks(retryTick)
                    handler.postDelayed(retryTick, 2000)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        root = FrameLayout(this).apply { setBackgroundColor(BOARD_BLUE) }
        setContentView(root)
        theme = ThemePack(assets)
        buildOverlay()
        createWebView()
        intent?.let { applyIntentUrl(it) }
        tts = TextToSpeech(applicationContext) { status -> handler.post { onTtsInit(status) } }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback)
        else cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
        loadBoard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntentUrl(intent)) loadBoard()
    }

    /** `adb shell am start -n io.craigcloud.disneyjeopardy/.MainActivity -e url http://host/tv` points this run at a URL. */
    private fun applyIntentUrl(intent: Intent): Boolean {
        val url = intent.getStringExtra("url")?.trim().orEmpty()
        if (url.isEmpty() || !isHttpUrl(url)) return false
        launchUrl = url
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        enterImmersive()
    }

    override fun onPause() {
        webView?.onPause()
        tts?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        }
        tts?.shutdown()
        webView?.let { root.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        val wv = WebView(this)
        wv.setBackgroundColor(BOARD_BLUE)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isLongClickable = false
        wv.isHapticFeedbackEnabled = false
        wv.setOnLongClickListener { true }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString DisneyJeopardyTV/${BuildConfig.VERSION_NAME}"
        }
        wv.addJavascriptInterface(Bridge(), "DJ_TV_NATIVE")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, bridgeScript(), setOf("*"))
        }
        wv.webViewClient = BoardClient()
        wv.webChromeClient = BoardChromeClient()
        root.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.requestFocus()
        webView = wv
    }

    private fun bridgeScript(): String = """
        (function(){
          if (window.DJ_TV && window.DJ_TV.isTV) return;
          var N = window.DJ_TV_NATIVE; if (!N) return;
          window.DJ_TV = {
            isTV: true,
            version: ${jsString(BuildConfig.VERSION_NAME)},
            versionCode: ${BuildConfig.VERSION_CODE},
            exit: function(){ N.exit(); },
            setTopLevel: function(b){ N.setTopLevel(!!b); },
            speak: function(t, rate, pitch){ return N.speak(String(t == null ? '' : t), Number(rate) || 1, Number(pitch) || 1); },
            stopSpeaking: function(){ N.stopSpeaking(); },
            hasTTS: function(){ return N.hasTTS(); },
            openSettings: function(){ N.openSettings(); },
            reload: function(){ N.reload(); }
          };
          try { window.dispatchEvent(new Event('djtvready')); } catch (e) {}
        })();
    """.trimIndent()

    private fun boardUrl(): String {
        val uri = Uri.parse(serverUrl)
        val b = uri.buildUpon()
        if (uri.getQueryParameter("tv") == null) b.appendQueryParameter("tv", "1")
        if (uri.getQueryParameter("app") == null) b.appendQueryParameter("app", BuildConfig.VERSION_NAME)
        return b.build().toString()
    }

    /** Checks the server first so a dead backend never shows a raw proxy error page; on the error screen only the status line changes. */
    private fun loadBoard() {
        handler.removeCallbacks(retryTick)
        if (mainFrameError) {
            overlayMessage.text = "Checking…"
            overlaySpinner.visibility = View.VISIBLE
        } else {
            showOverlay(loading = true)
        }
        probe(quiet = false)
    }

    private fun probe(quiet: Boolean) {
        val seq = probeSeq.incrementAndGet()
        val target = healthUrl()
        Thread {
            val ok = runCatching {
                val c = java.net.URL(target).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = PROBE_TIMEOUT_MS
                c.readTimeout = PROBE_TIMEOUT_MS
                c.useCaches = false
                try { c.responseCode in 200..299 } finally { c.disconnect() }
            }.getOrDefault(false)
            handler.post {
                if (seq != probeSeq.get() || isFinishing) return@post
                if (ok) startWebLoad()
                else if (quiet) scheduleRetry()
                else showError("health check failed")
            }
        }.start()
    }

    private fun healthUrl(): String {
        val u = Uri.parse(serverUrl)
        return Uri.Builder().scheme(u.scheme).encodedAuthority(u.encodedAuthority).path("/healthz").build().toString()
    }

    private fun startWebLoad() {
        handler.removeCallbacks(retryTick)
        mainFrameError = false
        pageTopLevel = false
        showOverlay(loading = true)
        val wv = webView ?: return
        val url = boardUrl()
        Log.i(TAG, "loading $url")
        wv.stopLoading()
        wv.loadUrl(url)
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryTick)
        val delay = RETRY_BACKOFF_S[retryAttempt.coerceAtMost(RETRY_BACKOFF_S.size - 1)] * 1000L
        retryAttempt++
        handler.postDelayed(retryTick, delay)
    }

    private fun showError(reason: String) {
        Log.w(TAG, "main frame error: $reason")
        val wasShowing = mainFrameError
        mainFrameError = true
        pageTopLevel = true
        overlaySpinner.visibility = View.INVISIBLE
        overlayMessage.text = "We'll keep checking in the background and jump right in when it's back."
        if (!wasShowing) {
            webView?.let { it.stopLoading(); it.loadUrl("about:blank") }
            overlayTitle.text = "The server can't be reached"
            retryButton.visibility = View.VISIBLE
            settingsButton.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            retryButton.requestFocus()
        }
        scheduleRetry()
    }

    private fun showOverlay(loading: Boolean) {
        overlayTitle.text = "Disney Jeopardy"
        overlayMessage.text = if (loading) "Warming up the board…" else ""
        overlaySpinner.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        settingsButton.visibility = View.GONE
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        retryAttempt = 0
        overlay.visibility = View.GONE
        webView?.requestFocus()
    }

    private inner class BoardClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            theme.intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageTopLevel = false
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(bridgeScript(), null)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (!mainFrameError) hideOverlay()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(bridgeScript(), null)
            if (!mainFrameError) hideOverlay()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError("${error.errorCode} ${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) {
                view.stopLoading()
                showError("HTTP ${response.statusCode}")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "renderer gone (crash=${Build.VERSION.SDK_INT >= 26 && detail.didCrash()}), recreating WebView")
            root.removeView(view)
            view.destroy()
            webView = null
            createWebView()
            loadBoard()
            return true
        }
    }

    private inner class BoardChromeClient : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.d(TAG, "console: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            return true
        }

        // WebView otherwise draws a grey "play" placeholder over <video> elements before the first frame.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            view.requestFocus()
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customViewCallback = null
            webView?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- Remote keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (okLongPressed && event.action == KeyEvent.ACTION_UP && isOkKey(event.keyCode)) {
            okLongPressed = false
            okDownEvent = null
            return true
        }
        if (settingsDialog?.isShowing == true) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_UP) openSettings()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (customView != null) return super.dispatchKeyEvent(event)
                if (overlay.visibility == View.VISIBLE) {
                    trackOverlayLongPress(event)
                    return super.dispatchKeyEvent(event)
                }
                return handleOk(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** On the loading/error screen OK still clicks the focused button, but holding it opens settings. */
    private fun trackOverlayLongPress(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                okLongPressed = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            } else if (event.isLongPress && !okLongPressed) {
                handler.removeCallbacks(longPressRunnable)
                longPressRunnable.run()
            }
            KeyEvent.ACTION_UP -> handler.removeCallbacks(longPressRunnable)
        }
    }

    /**
     * OK is held back until release so a long press can open settings without the page also seeing a click.
     * A short press is replayed to the WebView as a normal down/up pair.
     */
    private fun handleOk(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                okLongPressed = false
                okDownEvent = KeyEvent(event)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            } else if (event.isLongPress && !okLongPressed && okDownEvent != null) {
                handler.removeCallbacks(longPressRunnable)
                longPressRunnable.run()
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val down = okDownEvent
                okDownEvent = null
                if (!okLongPressed && down != null) {
                    val wv = webView ?: return true
                    val now = SystemClock.uptimeMillis()
                    wv.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, now, 0))
                    wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, down.keyCode, 0, down.metaState, down.deviceId, down.scanCode, down.flags, down.source))
                }
                okLongPressed = false
            }
        }
        return true
    }

    private fun onBack() {
        if (customView != null) { customViewCallback?.onCustomViewHidden(); return }
        val atTop = pageTopLevel || mainFrameError || overlay.visibility == View.VISIBLE
        if (!atTop) {
            webView?.evaluateJavascript(ESCAPE_JS, null)
            lastBackAt = 0
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Settings dialog

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return
        val pad = dp(20)
        val input = EditText(this).apply {
            setText(serverUrl)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textSize = 18f
        }
        val info = TextView(this).apply {
            text = "App ${BuildConfig.VERSION_NAME} · default ${BuildConfig.DEFAULT_URL}"
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(input)
            addView(info)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_DisneyJeopardy_Dialog)
            .setTitle("Game server URL")
            .setView(box)
            .setPositiveButton("Save & reload") { _, _ ->
                val url = input.text.toString().trim()
                if (isHttpUrl(url)) {
                    launchUrl = null
                    prefs.edit().putString(KEY_URL, url).apply()
                    loadBoard()
                } else {
                    Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Reset to default") { _, _ ->
                launchUrl = null
                prefs.edit().remove(KEY_URL).apply()
                loadBoard()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            settingsDialog = null
            enterImmersive()
            if (overlay.visibility == View.VISIBLE && retryButton.visibility == View.VISIBLE) retryButton.requestFocus() else webView?.requestFocus()
        }
        settingsDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------------------------------------------------------------- Text to speech

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            engine.shutdown()
            tts = null
            pendingSpeech?.let { pendingSpeech = null; notifySpeakDone(it.id) }
            return
        }
        val voice = runCatching { pickVoice(engine.voices.orEmpty()) }.getOrNull()
        if (voice != null) engine.voice = voice else engine.setLanguage(Locale.US)
        Log.i(TAG, "TTS ready, voice=${engine.voice?.name}")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ttsReady = true
        pendingSpeech?.let { pendingSpeech = null; speakNow(it) }
    }

    private fun pickVoice(voices: Set<Voice>): Voice? {
        val us = voices.filter { it.locale.language == "en" && isUs(it.locale) && !it.isNetworkConnectionRequired &&
            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
        val pool = us.ifEmpty { voices.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired } }
        return pool.maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { -it.latency })
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId == null || utteranceId != currentUtterance) return
        currentUtterance = null
        notifySpeakDone(utteranceId)
    }

    private fun notifySpeakDone(utteranceId: String?) {
        handler.post {
            webView?.evaluateJavascript("window.onDJSpeakDone && window.onDJSpeakDone(${jsString(utteranceId)});", null)
        }
    }

    private fun speakNow(s: Speech) {
        val engine = tts ?: return notifySpeakDone(s.id)
        currentUtterance = s.id
        engine.setSpeechRate(s.rate.coerceIn(0.25f, 4f))
        engine.setPitch(s.pitch.coerceIn(0.25f, 4f))
        if (engine.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, s.id) != TextToSpeech.SUCCESS) finished(s.id)
    }

    private inner class Bridge {
        @JavascriptInterface fun isTV(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun exit() { runOnUiThread { finish() } }
        @JavascriptInterface fun setTopLevel(top: Boolean) { runOnUiThread { pageTopLevel = top } }
        @JavascriptInterface fun openSettings() { runOnUiThread { this@MainActivity.openSettings() } }
        @JavascriptInterface fun reload() { runOnUiThread { loadBoard() } }
        @JavascriptInterface fun hasTTS(): Boolean = ttsReady

        @JavascriptInterface
        fun speak(text: String, rate: Double, pitch: Double): String {
            val s = Speech("dj-${utteranceSeq.incrementAndGet()}", text, rate.toFloat(), pitch.toFloat())
            runOnUiThread {
                when {
                    text.isBlank() -> notifySpeakDone(s.id)
                    ttsReady -> speakNow(s)
                    tts != null -> pendingSpeech = s
                    else -> notifySpeakDone(s.id)
                }
            }
            return s.id
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread {
                currentUtterance = null
                pendingSpeech = null
                tts?.stop()
            }
        }
    }

    // ---------------------------------------------------------------- Loading / error overlay

    private fun buildOverlay() {
        val display = font("fonts/LuckiestGuy-Regular.ttf") ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val body = font("fonts/Nunito.ttf") ?: Typeface.SANS_SERIF
        overlay = FrameLayout(this).apply {
            setBackgroundColor(BOARD_BLUE)
            isClickable = true
        }
        bundledImage(BACKDROP)?.let { bg ->
            overlay.addView(ImageView(this).apply {
                setImageBitmap(bg)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        overlay.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#330A0F3C"), Color.parseColor("#990A0F3C"), Color.parseColor("#E60A0F3C"))
            )
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        overlayTitle = TextView(this).apply {
            textSize = 46f
            setTextColor(GOLD)
            typeface = display
            letterSpacing = 0.03f
            setShadowLayer(16f, 0f, 6f, Color.BLACK)
            gravity = Gravity.CENTER
        }
        overlaySpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(GOLD)
        }
        overlayMessage = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = body
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(dp(40), dp(8), dp(40), dp(24))
        }
        retryButton = pillButton("Retry", display, primary = true).apply { setOnClickListener { loadBoard() } }
        settingsButton = pillButton("Server settings", body, primary = false).apply { setOnClickListener { openSettings() } }
        bundledImage(LOGO)?.let { logo ->
            val h = minOf(dp(200), resources.displayMetrics.heightPixels / 3)
            val w = (h * logo.width / logo.height.coerceAtLeast(1)).coerceAtMost(dp(640))
            content.addView(ImageView(this).apply {
                setImageBitmap(logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(12) })
        }
        content.addView(overlayTitle)
        content.addView(overlaySpinner, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(16) })
        content.addView(overlayMessage)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) })
        buttons.addView(settingsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(buttons)
        overlay.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun pillButton(label: String, face: Typeface, primary: Boolean) = Button(this).apply {
        text = label
        textSize = if (primary) 26f else 18f
        typeface = face
        isAllCaps = false
        setTextColor(if (primary) BOARD_BLUE else GOLD)
        background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            if (primary) setColor(GOLD) else { setColor(Color.parseColor("#660A0F3C")); setStroke(dp(2), GOLD) }
        }
        setPadding(dp(if (primary) 56 else 32), dp(14), dp(if (primary) 56 else 32), dp(14))
        isFocusable = true
        stateListAnimator = null
        setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(120).start()
            v.alpha = if (f || primary) 1f else 0.8f
        }
        visibility = View.GONE
    }

    private fun font(path: String): Typeface? = runCatching { Typeface.createFromAsset(assets, path) }.getOrNull()

    private fun bundledImage(name: String): Bitmap? {
        if (!theme.has(name)) return null
        return runCatching { theme.open(name).use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DisneyJeopardy"
        private const val KEY_URL = "url"
        private const val LOGO = "logo.png"
        private const val BACKDROP = "bg-castle.webp"
        private val RETRY_BACKOFF_S = intArrayOf(15, 30, 60, 120)
        private const val PROBE_TIMEOUT_MS = 6000
        private const val LONG_PRESS_MS = 700L
        private const val EXIT_WINDOW_MS = 2000L
        private val BOARD_BLUE = Color.parseColor("#0A0F3C")
        private val GOLD = Color.parseColor("#FFD45A")

        private const val ESCAPE_JS = "(function(){var t=document.activeElement||document.body||document;" +
            "['keydown','keyup'].forEach(function(n){t.dispatchEvent(new KeyboardEvent(n,{key:'Escape',code:'Escape'," +
            "keyCode:27,which:27,bubbles:true,cancelable:true}));});})();"

        private fun isUs(l: Locale) = l.country == "US" || runCatching { l.isO3Country }.getOrNull() == "USA"

        private fun isOkKey(code: Int) =
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

        private fun isHttpUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")

        private fun jsString(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
                '<' -> sb.append("\\u003c")
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}
