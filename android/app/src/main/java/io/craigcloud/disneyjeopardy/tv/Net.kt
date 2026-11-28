package io.craigcloud.disneyjeopardy.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared HTTP stack: one OkHttp client whose 512 MB disk cache follows the server's Cache-Control / Last-Modified, so art never goes stale. */
object Http {
    @Volatile private var instance: OkHttpClient? = null
    fun client(ctx: Context): OkHttpClient = instance ?: synchronized(this) {
        instance ?: OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "http"), 512L * 1024 * 1024))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build().also { instance = it }
    }
}

/** `https://host/tv` (the saved setting, shared with the classic WebView mode) -> `https://host`. */
fun originOf(serverUrl: String): String {
    val u = Uri.parse(serverUrl)
    val path = (u.path ?: "").trimEnd('/').removeSuffix("/tv")
    return "${u.scheme}://${u.encodedAuthority}$path"
}

enum class Conn { Loading, Online, Down }

/**
 * The TV's WebSocket (`/ws?role=tv&tv=<id>`). Short drops reconnect silently while the last state stays on screen;
 * only when the server stays away is [Conn.Down] reported, and from then on retries back off 15/30/60/120 s.
 */
class GameClient(
    private val http: OkHttpClient,
    private val tvId: String,
    private val onState: (GameState) -> Unit,
    private val onSay: (Say) -> Unit,
    private val onConn: (Conn) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var origin = ""
    private var ws: WebSocket? = null
    private var gen = 0
    private var online = false
    private var down = false
    private var everOnline = false
    private var quickTries = 0
    private var downTries = 0
    private val queue = ArrayList<String>()
    private val retry = Runnable { connect() }

    fun start(origin: String) {
        this.origin = origin
        everOnline = false; down = false; quickTries = 0; downTries = 0
        onConn(Conn.Loading)
        connect()
    }

    fun retryNow() { main.removeCallbacks(retry); connect() }

    /** Network came back: check soon instead of waiting out the backoff. */
    fun networkAvailable() { if (!online) { main.removeCallbacks(retry); main.postDelayed(retry, 2000) } }

    fun stop() { main.removeCallbacks(retry); gen++; ws?.close(1000, null); ws = null; online = false }

    fun send(t: String, vararg extra: Pair<String, Any?>) {
        val m = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("t", JsonPrimitive(t))
            for ((k, v) in extra) put(k, when (v) {
                is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); null -> kotlinx.serialization.json.JsonNull
                is JsonObject -> v; else -> JsonPrimitive(v.toString())
            })
        }
        val text = JsonObject(m).toString()
        main.post { if (online && ws?.send(text) == true) Unit else queue.add(text) }
    }

    private fun connect() {
        main.removeCallbacks(retry)
        val my = ++gen
        ws?.cancel()
        val wsUrl = origin.replaceFirst(Regex("^http"), "ws") + "/ws?role=tv&tv=" + Uri.encode(tvId)
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = main.post {
                if (my != gen) return@post
                online = true; everOnline = true; down = false; quickTries = 0; downTries = 0
                onConn(Conn.Online)
                for (m in queue.toList()) webSocket.send(m)
                queue.clear()
            }.let {}

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj["t"]?.jsonPrimitive?.content) {
                    "state" -> runCatching { json.decodeFromJsonElement(GameState.serializer(), obj) }
                        .onSuccess { s -> main.post { if (my == gen) onState(s) } }
                        .onFailure { Log.w(TAG, "bad state: $it") }
                    "say" -> runCatching { json.decodeFromJsonElement(Say.serializer(), obj) }
                        .onSuccess { s -> main.post { if (my == gen) onSay(s) } }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = main.post { if (my == gen) lost(code) }.let {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = main.post {
                if (my == gen) { Log.w(TAG, "ws failure: $t"); lost(0) }
            }.let {}
        })
    }

    private fun lost(code: Int) {
        online = false
        if (code == 4000) return // another TV took over this room
        // Quick silent retries (0.5..5 s, ~15 s total) after a drop; the first launch gives up sooner.
        val quick = longArrayOf(500, 1000, 2000, 4000, 5000)
        val budget = if (everOnline) quick.size else 1
        if (!down && quickTries < budget) {
            main.postDelayed(retry, quick[quickTries++])
            return
        }
        if (!down) { down = true; onConn(Conn.Down) }
        val backoff = intArrayOf(15, 30, 60, 120)
        main.postDelayed(retry, backoff[downTries.coerceAtMost(backoff.size - 1)] * 1000L)
        downTries++
    }

    companion object { private const val TAG = "DJNative" }
}

fun fetchInfo(http: OkHttpClient, origin: String): Info? = runCatching {
    http.newCall(Request.Builder().url("$origin/api/info").build()).execute().use { r ->
        if (!r.isSuccessful) null else json.decodeFromString(Info.serializer(), r.body!!.string())
    }
}.getOrNull()
