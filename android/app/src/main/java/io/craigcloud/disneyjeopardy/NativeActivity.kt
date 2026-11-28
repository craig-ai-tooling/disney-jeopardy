package io.craigcloud.disneyjeopardy

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import coil3.request.crossfade
import io.craigcloud.disneyjeopardy.tv.Controller
import io.craigcloud.disneyjeopardy.tv.Dpad
import io.craigcloud.disneyjeopardy.tv.Http
import io.craigcloud.disneyjeopardy.tv.MediaCache
import io.craigcloud.disneyjeopardy.tv.TvApp
import io.craigcloud.disneyjeopardy.tv.loadFonts
import io.craigcloud.disneyjeopardy.tv.originOf
import java.util.UUID

/**
 * Launcher activity: renders the TV show natively with Compose for TV. The server stays authoritative; this
 * activity only streams `/ws?role=tv` state and draws it. "Classic web mode" in settings hands off to [MainActivity].
 */
class NativeActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("dj", Context.MODE_PRIVATE) }
    private lateinit var ctl: Controller
    private var settingsDialog: AlertDialog? = null
    private var okDownAt = 0L
    private var okLong = false
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val serverUrl get() = prefs.getString(MainActivity.KEY_URL, null)?.takeIf(::validUrl) ?: BuildConfig.DEFAULT_URL

    private fun validUrl(u: String) = (u.startsWith("http://") || u.startsWith("https://")) && u.toHttpUrlOrNull() != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntentUrl(intent)
        if (prefs.getBoolean(MainActivity.KEY_CLASSIC, false)) return openClassic()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        loadFonts(assets)
        MediaCache.init(this)
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client(ctx) })) }
                // OkHttp's 512 MB HTTP cache honours the server's Cache-Control/Last-Modified; Coil's own disk cache
                // would keep files forever, so it only memory-caches decoded bitmaps.
                .diskCache(null as DiskCache?)
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, .3).build() }
                .crossfade(250)
                .build()
        }
        val tvId = prefs.getString("tvId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("tvId", it).apply() }
        ctl = Controller(this, tvId)
        setContent { TvApp(ctl, ::openSettings) }
        ctl.start(originOf(serverUrl))
        val cm = getSystemService(ConnectivityManager::class.java)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread { ctl.client.networkAvailable() }
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntentUrl(intent) && ::ctl.isInitialized) ctl.start(originOf(serverUrl))
    }

    /** `adb shell am start -n io.craigcloud.disneyjeopardy/.NativeActivity -e url http://10.0.2.2:8080/tv` (saved like the setting). */
    private fun applyIntentUrl(i: Intent?): Boolean {
        val url = i?.getStringExtra("url")?.trim().orEmpty()
        if (!validUrl(url)) return false
        prefs.edit().putString(MainActivity.KEY_URL, url).apply()
        return true
    }

    private fun openClassic() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
    }

    override fun onDestroy() {
        if (::ctl.isInitialized) ctl.release()
        netCb?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (settingsDialog?.isShowing == true || !::ctl.isInitialized) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (e.action == KeyEvent.ACTION_DOWN) Dpad.movedAt = SystemClock.elapsedRealtime()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (e.action == KeyEvent.ACTION_UP) openSettings(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Long-press OK opens the server settings; a normal press goes to the focused button on release.
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.repeatCount == 0) { okDownAt = SystemClock.elapsedRealtime(); okLong = false }
                    else if (!okLong && SystemClock.elapsedRealtime() - okDownAt > 700) { okLong = true; openSettings() }
                    if (okLong) return true
                } else if (okLong) { okLong = false; return true }
            }
            KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) back(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun back() {
        if (ctl.overlay != null) { ctl.closeOverlay(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - ctl.topLevelBackAt < 2000) { finish(); return }
        ctl.topLevelBackAt = now
        Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return
        val input = EditText(this).apply {
            setText(serverUrl); setSelection(text.length); isSingleLine = true; textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(TextView(context).apply {
                text = "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · native · default ${BuildConfig.DEFAULT_URL}"
                textSize = 13f; alpha = .7f; setPadding(0, dp(8), 0, 0)
            })
            addView(Button(context).apply {
                text = "Classic web mode"
                setOnClickListener {
                    prefs.edit().putBoolean(MainActivity.KEY_CLASSIC, true).apply()
                    settingsDialog?.dismiss()
                    openClassic()
                }
            })
        }
        settingsDialog = AlertDialog.Builder(this, R.style.Theme_DisneyJeopardy_Dialog)
            .setTitle("Game server URL")
            .setView(box)
            .setPositiveButton("Save & reload") { _, _ ->
                val url = input.text.toString().trim()
                if (validUrl(url)) {
                    prefs.edit().putString(MainActivity.KEY_URL, url).apply(); ctl.start(originOf(url))
                } else Toast.makeText(this, "Enter a full http:// or https:// URL", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Reset to default") { _, _ -> prefs.edit().remove(MainActivity.KEY_URL).apply(); ctl.start(originOf(serverUrl)) }
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnDismissListener { settingsDialog = null; immersive() }
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
    }
}
