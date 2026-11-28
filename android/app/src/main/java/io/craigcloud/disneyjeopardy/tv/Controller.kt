package io.craigcloud.disneyjeopardy.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Mood { idle, talk, celebrate, wrong }
enum class Overlay { boards, settings }

/**
 * Holds the latest server state plus the TV-only presentation state (narration, host mood, flashes, overlays) and
 * runs the same side effects as `effects()` in public/tv.js. Game rules live on the server; this only renders.
 */
class Controller(private val ctx: Context, tvId: String) {
    private val main = Handler(Looper.getMainLooper())
    val sfx = Sfx().also { it.load() }
    private val narrator = Narrator(ctx)

    var origin by mutableStateOf("")
    var info by mutableStateOf<Info?>(null)
    var state by mutableStateOf<GameState?>(null)
        private set
    var conn by mutableStateOf(Conn.Loading)
    var speaking by mutableStateOf<Say?>(null)
    var pitchIdx by mutableIntStateOf(0)
    var mood by mutableStateOf(Mood.idle)
    var overlay by mutableStateOf<Overlay?>(null)
    var flash by mutableStateOf<String?>(null)
    var flashSeq by mutableIntStateOf(0)
    val fx = ConfettiFx()
    var fxSeq by mutableIntStateOf(0)
    var verdict by mutableStateOf<VerdictFx?>(null)
    private var verdictSeq = 0
    /** Lectern centres in root px, written on layout; read only when a score chip starts flying. */
    val lecterns = HashMap<String, androidx.compose.ui.geometry.Offset>()
    private var podiumGen = 0
    var wheelSpin by mutableStateOf(false)
    /** elapsedRealtime at which the server deadline expires (0 = no timer). */
    var deadlineAt by mutableLongStateOf(0L)
    var deadlineTotal by mutableLongStateOf(0L)
    var zoomSeed by mutableIntStateOf(0)

    private var moodUntil = 0L
    private val moodReset = Runnable { moodUntil = 0; setMood(if (speaking != null) Mood.talk else Mood.idle) }
    private val prefetched = HashSet<String>()

    val client = GameClient(Http.client(ctx), tvId, ::onState, ::onSay) { c ->
        conn = c
        if (c == Conn.Online) refreshInfo()
    }

    fun start(origin: String) {
        this.origin = origin
        state = null
        client.stop()
        client.start(origin)
    }

    fun abs(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") || url.startsWith("data:") -> url
        else -> origin + (if (url.startsWith("/")) url else "/$url")
    }

    fun send(t: String, vararg extra: Pair<String, Any?>) = client.send(t, *extra)
    fun sendSettings(s: Settings) = client.send("settings", "settings" to JsonObject(mapOf(
        "mode" to JsonPrimitive(s.mode), "negatives" to JsonPrimitive(s.negatives), "voice" to JsonPrimitive(s.voice),
        "host" to JsonPrimitive(s.host), "buzzMs" to JsonPrimitive(s.buzzMs), "autoAdvance" to JsonPrimitive(s.autoAdvance),
    )))

    private fun refreshInfo() {
        Thread { val i = fetchInfo(Http.client(ctx), origin); main.post { if (i != null) info = i } }.start()
    }

    fun setMood(m: Mood, holdMs: Long = 0) {
        if (holdMs > 0) {
            moodUntil = SystemClock.elapsedRealtime() + holdMs
            main.removeCallbacks(moodReset); main.postDelayed(moodReset, holdMs)
        } else if (SystemClock.elapsedRealtime() < moodUntil) return
        mood = m
    }

    private fun onSay(m: Say) {
        var fired = false
        val ownLine = m.tag?.startsWith("pitch:") != true
        speaking = m
        if (ownLine) setMood(Mood.talk)
        narrator.say(m.copy(url = abs(m.url))) {
            if (fired) return@say
            fired = true
            if (ownLine && speaking === m) { speaking = null; setMood(Mood.idle) }
            val s = state
            val tag = m.tag
            when {
                tag == "cold" && s?.phase == "coldOpen" -> send("next")
                tag?.startsWith("pitch:") == true -> {
                    pitchIdx = (tag.substring(6).toIntOrNull() ?: 0) + 1
                    val next = pitchIdx
                    if (s?.phase == "hostDraft" && next < 6) main.postDelayed({ send("pitch", "i" to next) }, 400)
                }
                tag != null -> send("readDone", "seq" to tag)
            }
        }
    }

    private fun onState(s: GameState) {
        val prev = state
        state = s
        if (overlay != null && s.phase != "lobby") overlay = null
        deadlineTotal = s.deadline
        deadlineAt = if (s.deadline > 0) SystemClock.elapsedRealtime() + s.deadline else 0
        effects(s, prev)
        prefetch(s)
    }

    private fun effects(s: GameState, p: GameState?) {
        val ph = s.phase
        val pp = p?.phase
        if (s.players.size > (p?.players?.size ?: 0) && pp != null) sfx.play("join")
        if (ph != pp) {
            if (ph == "buzzing") sfx.play("open")
            if (ph == "ddWager") sfx.play("dd")
            sfx.think(ph == "finalThink")
            if (ph == "podium") { sfx.play("fanfare"); podiumShow(); setMood(Mood.celebrate, 8000) }
            if (ph == "hostDraft") { pitchIdx = 0; send("pitch", "i" to 0) }
            if (ph == "finalWheel") { wheelSpin = false; main.postDelayed({ wheelSpin = true }, 300) }
        }
        if (ph == "answering" && pp != "answering") sfx.play("buzz")
        val sameClue = p?.clue?.id == s.clue?.id
        if (!sameClue || ph == "reveal" && pp != "reveal") zoomSeed++
        val nr = s.clue?.results?.size ?: 0
        val pr = if (sameClue) p?.clue?.results?.size ?: 0 else 0
        if (nr > pr) {
            val last = s.clue!!.results[nr - 1]
            if (last.verdict == "correct") { sfx.play("correct"); doFlash("correct"); if (s.clue.dailyDouble) { fx.cannons(220, .9f); fxSeq++ } }
            else if (last.verdict == "wrong") { sfx.play("wrong"); doFlash("wrong") }
        }
        val fr = s.fin?.results.orEmpty()
        if (fr.size > (p?.fin?.results?.size ?: 0)) sfx.play(if (fr.last().verdict == "correct") "correct" else "wrong")
        // hostReact(): celebrate / wince for 4 s when a verdict settles.
        fun settled(list: List<Result>, before: List<Result>) =
            list.withIndex().firstOrNull { (i, r) -> r.verdict in setOf("correct", "wrong") && r.verdict != before.getOrNull(i)?.verdict }?.value
        val r = settled(s.clue?.results.orEmpty(), if (sameClue) p?.clue?.results.orEmpty() else emptyList())
            ?: settled(fr, p?.fin?.results.orEmpty())
        if (r != null) {
            setMood(if (r.verdict == "correct") Mood.celebrate else Mood.wrong, 4000)
            val inFinal = r !in s.clue?.results.orEmpty()
            val amount = if (inFinal) s.fin?.wagers?.get(r.pid) ?: r.amount else r.amount
            val answer = if (inFinal) r.heard else s.clue?.answer ?: r.heard
            verdict = VerdictFx(++verdictSeq, r.pid, r.verdict == "correct", amount, answer)
            if (r.verdict == "correct") { fx.puff(.5f, .42f, 70); fxSeq++ }
        }
    }

    private fun doFlash(kind: String) { flash = kind; flashSeq++ }

    /** Podium: both cannons, then firework shells for ~8 s and a second volley. */
    private fun podiumShow() {
        val gen = ++podiumGen
        fx.cannons(440, 1.1f); fxSeq++
        val rnd = java.util.Random()
        for (i in 0 until 13) main.postDelayed({
            if (gen == podiumGen && state?.phase == "podium") { fx.firework(.15f + rnd.nextFloat() * .7f, .12f + rnd.nextFloat() * .3f); fxSeq++ }
        }, 350L + i * 600L)
        main.postDelayed({ if (gen == podiumGen && state?.phase == "podium") { fx.cannons(260, 1f); fxSeq++ } }, 3800)
    }

    /** Warm the caches with everything this board (and the next clue) will show. */
    private fun prefetch(s: GameState) {
        val urls = buildList {
            add(themeBg(s.board)); s.board?.art?.let(::add)
            s.board?.categories?.forEach { c -> c.art?.let(::add) }
            s.players.forEach { p -> p.avatar?.let { add("/theme/avatars/$it.webp") } }
            s.boards?.forEach { b -> b.art?.let(::add) }
            s.clue?.media?.url?.let(::add)
            addAll(s.prefetch.orEmpty())
        }
        val loader = SingletonImageLoader.get(ctx)
        for (u in urls) {
            val a = abs(u) ?: continue
            if (!prefetched.add(a)) continue
            if (a.endsWith(".mp4") || a.endsWith(".webm") || a.endsWith(".mp3")) MediaCache.prefetch(a)
            else loader.enqueue(ImageRequest.Builder(ctx).data(a).build())
        }
        s.host?.anim?.let { base -> Mood.entries.forEach { m -> abs("$base-${m.name}.mp4")?.let { if (prefetched.add(it)) MediaCache.prefetch(it) } } }
    }

    val mems = HashMap<String, FocusMemory>()
    var topLevelBackAt = 0L

    fun mem(screen: String): FocusMemory = mems.getOrPut(screen) { FocusMemory().also { it.onMove = { sfx.play("tick") } } }

    fun openOverlay(o: Overlay) { mems.remove("lobby:${o.name}"); overlay = o }
    fun closeOverlay() { overlay = null }

    fun release() { client.stop(); narrator.release(); sfx.release() }
}
