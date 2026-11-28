package io.craigcloud.disneyjeopardy.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-device cache for voice clips, host loops and clue video (Media3 SimpleCache, 1 GB LRU).
 * Voice clips are content-addressed and immutable; everything else is keyed by URL + the server's Last-Modified/size
 * (one HEAD per URL per launch), so a replaced file is fetched fresh instead of replaying a stale copy.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private var cache: SimpleCache? = null
    private lateinit var factory: CacheDataSource.Factory
    private val stamps = ConcurrentHashMap<String, String>()
    private val io = Executors.newFixedThreadPool(3)
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        if (cache != null) return
        appCtx = ctx.applicationContext
        cache = SimpleCache(File(ctx.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024), StandaloneDatabaseProvider(ctx))
        factory = CacheDataSource.Factory()
            .setCache(cache!!)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Http.client(ctx)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun player(ctx: Context): ExoPlayer =
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()

    private fun key(url: String): String {
        if (url.contains("/voice/")) return url
        return stamps.getOrPut(url) {
            runCatching {
                Http.client(appCtx).newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    if (r.isSuccessful) "${r.header("last-modified") ?: ""}|${r.header("content-length") ?: ""}" else ""
                }
            }.getOrDefault("")
        }.let { "$url#$it" }
    }

    /** Builds the cached item off the main thread (the HEAD), then hands it back on main. */
    fun item(url: String, done: (MediaItem) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val known = stamps[url]
        if (url.contains("/voice/") || known != null) return done(MediaItem.Builder().setUri(url).setCustomCacheKey(key(url)).build())
        io.execute { val k = key(url); main.post { done(MediaItem.Builder().setUri(url).setCustomCacheKey(k).build()) } }
    }

    /** Pulls a file into the cache ahead of time (host loops on connect, clue video when the board loads). */
    fun prefetch(url: String) {
        if (cache == null) return
        io.execute {
            runCatching {
                val spec = DataSpec.Builder().setUri(url).setKey(key(url)).build()
                CacheWriter(factory.createDataSource(), spec, null, null).cache()
            }
        }
    }
}

/**
 * Plays the server's narration clips and reports when each finishes, exactly like `say()` in public/tv.js.
 * No clip (voice off / not ready / error) leaves the subtitle up for 1200 + words*380 ms. Never uses TextToSpeech.
 */
@OptIn(UnstableApi::class)
class Narrator(ctx: Context) {
    private val player = MediaCache.player(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var current: Say? = null
    private var onDone: (() -> Unit)? = null
    private var fallback: Runnable? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
            override fun onPlayerError(error: PlaybackException) { fallbackTimer() }
        })
    }

    fun say(m: Say, done: () -> Unit) {
        // A new line replaces the old one; the old one's callback never fires (like swapping audio.src on the web).
        fallback?.let(main::removeCallbacks)
        current = m
        onDone = done
        if (m.url == null) return fallbackTimer()
        MediaCache.item(m.url) { item ->
            if (current !== m) return@item
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
    }

    private fun fallbackTimer() {
        player.stop()
        val m = current ?: return
        val words = m.text.trim().split(Regex("\\s+")).size
        fallback = Runnable { if (current === m) finish() }.also { main.postDelayed(it, 1200L + words * 380L) }
    }

    private fun finish() {
        val d = onDone ?: return
        onDone = null
        d()
    }

    fun release() { fallback?.let(main::removeCallbacks); player.release() }
}
