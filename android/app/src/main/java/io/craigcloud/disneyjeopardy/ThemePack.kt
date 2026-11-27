package io.craigcloud.disneyjeopardy

import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject

/**
 * Serves `/theme/<path>?v=N` requests from the APK's `assets/theme/` when the file is bundled and the bundled
 * `manifest.json` version is >= N; everything else returns null so WebView fetches it from the network.
 */
class ThemePack(private val assets: AssetManager) {

    val version: Int
    private val files: Set<String>

    init {
        files = HashSet<String>().also { index(ROOT, it) }
        version = runCatching {
            assets.open("$ROOT/manifest.json").bufferedReader().use { JSONObject(it.readText()).optInt("version", 0) }
        }.getOrElse { Log.w(TAG, "theme manifest unreadable: $it"); 0 }
        Log.i(TAG, "theme pack v$version, ${files.size} files")
    }

    fun has(path: String) = path in files

    fun open(path: String) = assets.open("$ROOT/$path")

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        val path = assetPath(request.url) ?: return null
        val mime = MIME[path.substringAfterLast('.', "").lowercase()] ?: return null
        if (path !in files) return null
        val v = request.url.getQueryParameter("v")
        val wanted = if (v.isNullOrEmpty()) 0 else v.toIntOrNull() ?: return null
        if (version < wanted) return null
        return try {
            WebResourceResponse(
                mime, if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("xml")) "utf-8" else null,
                200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache", "X-DJ-Theme" to "bundled-v$version"),
                open(path),
            )
        } catch (e: Exception) {
            Log.w(TAG, "theme asset $path failed: $e")
            null
        }
    }

    private fun index(dir: String, out: MutableSet<String>) {
        val children = runCatching { assets.list(dir) }.getOrNull() ?: return
        for (name in children) {
            val full = "$dir/$name"
            val sub = runCatching { assets.list(full) }.getOrNull()
            if (!sub.isNullOrEmpty()) index(full, out) else out.add(full.removePrefix("$ROOT/"))
        }
    }

    companion object {
        private const val TAG = "DisneyJeopardy"
        private const val ROOT = "theme"

        val MIME = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
            "svg" to "image/svg+xml", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "woff2" to "font/woff2",
            "json" to "application/json", "css" to "text/css", "js" to "text/javascript",
        )

        /** `/theme/a/b.png` -> `a/b.png`; null for other paths or anything trying to escape the folder. */
        fun assetPath(url: Uri): String? {
            val scheme = url.scheme ?: return null
            if (scheme != "http" && scheme != "https") return null
            val p = url.path ?: return null
            if (!p.startsWith("/theme/")) return null
            val rel = p.removePrefix("/theme/")
            val parts = rel.split('/')
            if (rel.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return rel
        }
    }
}
