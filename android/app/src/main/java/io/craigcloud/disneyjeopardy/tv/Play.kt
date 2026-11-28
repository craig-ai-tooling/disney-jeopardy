@file:OptIn(ExperimentalLayoutApi::class)
package io.craigcloud.disneyjeopardy.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.random.Random

/** Glass glare + CRT scanlines painted over each monitor (the web's `.cat::after, .cell::after`). */
fun Modifier.glass() = drawWithContent {
    drawContent()
    drawRect(Brush.linearGradient(0f to Color(0x33FFFFFF), .26f to Color(0x0DFFFFFF), .38f to Color.Transparent,
        start = Offset(0f, 0f), end = Offset(size.width * .42f, size.height)))
    val px = size.width / 60f
    var y = 0f
    val step = (size.height / 40f).coerceAtLeast(4f)
    while (y < size.height) { drawRect(Color(0x21000000), Offset(0f, y), Size(size.width, step * .36f)); y += step }
}

@Composable
fun Monitor(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(.45f.vw)
    Box(modifier.shadow(.4f.vw, shape).background(Color(0xFF05060C), shape).padding(.3f.vw)
        .clip(RoundedCornerShape(.3f.vw)), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun BoardScreen(ctl: Controller, s: GameState, mem: FocusMemory) {
    val b = s.board ?: return
    mem.default = b.categories.flatMap { it.clues }.firstOrNull { !it.used }?.let { "pick:${it.id}" }
    val wallShape = RoundedCornerShape(.8f.vw)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = .9f.vw, end = .9f.vw, top = .8f.vw, bottom = .6f.vw)
            .shadow(3.vw, wallShape).border(.45f.vw, Color(0xFFC49A34), wallShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF22283E), Color(0xFF0A0C16))), wallShape).padding(.9f.vw)) {
            Column(verticalArrangement = Arrangement.spacedBy(.45f.vw)) {
                Row(Modifier.weight(1.25f), horizontalArrangement = Arrangement.spacedBy(.45f.vw)) {
                    for (c in b.categories) Monitor(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2457F5), Color(0xFF071A7C)))))
                        c.art?.let { Img(it, Modifier.fillMaxSize().alpha(.55f)) }
                        Box(Modifier.fillMaxSize().background(Color(0x55000040)))
                        Txt(c.title.uppercase(), 1.45f, Modifier.padding(.5f.vw), family = Luckiest, weight = FontWeight.Normal, maxLines = 3)
                        Box(Modifier.fillMaxSize().glass())
                    }
                }
                for (r in 0 until 5) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(.45f.vw)) {
                    for (c in b.categories) {
                        val q = c.clues.getOrNull(r)
                        Monitor(Modifier.weight(1f).fillMaxHeight()) {
                            if (q == null || q.used) Box(Modifier.fillMaxSize().background(Color(0xFF050815)).glass())
                            else Cell(ctl, q, mem)
                        }
                    }
                }
            }
        }
        val ctrl = s.players.firstOrNull { it.id == s.control }
        Txt(if (ctrl != null) "${ctrl.name} picks the next clue (on their phone or with the remote)" else "Pick a clue", 1.25f, Modifier.fillMaxWidth(),
            color = if (ctrl != null) parseColor(ctrl.color) else Color.White)
        Scores(s)
    }
}

@Composable
private fun Cell(ctl: Controller, q: CellInfo, mem: FocusMemory) {
    var f by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().then(mem.item("pick:${q.id}")).onFocusChanged { f = it.isFocused }
        .tvClickable { ctl.sfx.play("pick"); mem.chose("pick:${q.id}"); ctl.send("pick", "clue" to q.id) }
        .background(if (f) Brush.radialGradient(listOf(Color(0xFF4C7BFF), Color(0xFF1438B8))) else Brush.radialGradient(listOf(Color(0xFF2457F5), Color(0xFF071A7C))))
        .then(if (f) Modifier.border(.3f.vw, Color.White) else Modifier).glass(), contentAlignment = Alignment.Center) {
        Box(Modifier.scale(if (f) 1.12f else 1f)) {
            Txt("$${q.value}", 4.2f, Modifier.offset(.22f), color = Color.Black, family = Anton, weight = FontWeight.Normal, shadow = false)
            Txt("$${q.value}", 4.2f, color = Color(0xFFFFCB2E), family = Anton, weight = FontWeight.Normal, shadow = false)
        }
    }
}

@Composable private fun Modifier.offset(v: Float) = this.then(Modifier.padding(start = v.vw, top = v.vw))

@Composable
fun ClueCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(1.6f.vw)
    Column(modifier.fillMaxWidth().padding(horizontal = 3.vw, vertical = 1.2f.vw).shadow(3.vw, shape)
        .background(Brush.radialGradient(listOf(Color(0xFF1C48D8), Color(0xFF071865)), radius = 2200f), shape)
        .border(.3f.vw, Gold.copy(alpha = .8f), shape).clip(shape).padding(horizontal = 2.5f.vw, vertical = 1.4f.vw),
        horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
private fun Meta(l: String, r: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Txt(l.uppercase(), 1.4f, color = Gold, maxLines = 1); Txt(r, 1.4f, color = Gold, family = Anton, weight = FontWeight.Normal)
}

@Composable
private fun ClueMedia(ctl: Controller, c: Clue, phase: String, modifier: Modifier) {
    when {
        c.palette != null -> Row(modifier.fillMaxWidth(.6f).height(12.vw), horizontalArrangement = Arrangement.spacedBy(.6f.vw)) {
            c.palette.forEachIndexed { i, h ->
                val a = remember(c.id) { Animatable(0f) }
                LaunchedEffect(c.id) { kotlinx.coroutines.delay(i * 150L); a.animateTo(1f, tween(500)) }
                Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = a.value; alpha = a.value }.clip(RoundedCornerShape(.8f.vw)).background(parseColor(h)))
            }
        }
        c.emoji != null -> Txt(c.emoji, 8f, modifier, shadow = false)
        c.quote != null -> Txt("“${c.quote}”", 2.6f, modifier.padding(horizontal = 3.vw), color = Gold, weight = FontWeight.Bold)
        c.media?.url == null -> {}
        c.media.type == "video" -> ClueVideo(ctl, ctl.abs(c.media.url)!!, modifier)
        else -> {
            val zoom = c.format == "zoom" && phase != "reveal"
            val z = remember(ctl.zoomSeed) { Animatable(if (zoom) 5f else 1f) }
            val origin = remember(ctl.zoomSeed) { TransformOrigin(.2f + Random.nextFloat() * .6f, .2f + Random.nextFloat() * .6f) }
            // Starts tight on a random spot and pulls back 0.25x per second (the web's zoom clue).
            LaunchedEffect(ctl.zoomSeed, zoom) { if (zoom) z.animateTo(1f, tween(16000, easing = LinearEasing)) else z.snapTo(1f) }
            Box(modifier.height(22.vw).aspectRatio(16f / 10).clip(RoundedCornerShape(1.vw)).border(.2f.vw, Gold.copy(alpha = .6f), RoundedCornerShape(1.vw))) {
                Img(c.media.url, Modifier.fillMaxSize().graphicsLayer { scaleX = z.value; scaleY = z.value; transformOrigin = origin },
                    androidx.compose.ui.layout.ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun ClueVideo(ctl: Controller, url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = remember(url) { MediaCache.player(ctx) }
    DisposableEffect(url) { MediaCache.item(url) { p.setMediaItem(it); p.prepare(); p.play() }; onDispose { p.release() } }
    AndroidView({ PlayerView(it).apply { useController = false; player = p } }, modifier.height(22.vw).aspectRatio(16f / 9).clip(RoundedCornerShape(1.vw)))
}

@Composable
fun ResultLine(s: GameState, r: Result, extra: String = "", button: (@Composable () -> Unit)? = null) {
    val p = s.players.firstOrNull { it.id == r.pid }
    val mark = when (r.verdict) { "correct" -> "✅"; "close" -> "🤔"; else -> "❌" }
    Row(Modifier.padding(.3f.vw).clip(RoundedCornerShape(1.vw)).background(Color(0x73000000)).drawWithContent {
        drawContent(); drawRect(parseColor(p?.color), size = Size(.5f * size.height / 3, size.height)) }
        .padding(start = 1.4f.vw, end = 1.vw, top = .4f.vw, bottom = .4f.vw), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(.6f.vw)) {
        Txt("${p?.name ?: "?"}: “${r.heard ?: "—"}” $mark$extra", 1.5f, weight = FontWeight.Bold)
        button?.invoke()
    }
}

@Composable
private fun Ruling(t: String) = Box(Modifier.padding(top = .6f.vw).clip(RoundedCornerShape(1.vw)).background(Color(0xCC5A3A00)).border(.15f.vw, Gold, RoundedCornerShape(1.vw)).padding(horizontal = 1.4f.vw, vertical = .5f.vw)) {
    Txt(t, 1.5f, color = Gold)
}

@Composable
private fun Lights(t: String) {
    val pulse by rememberInfiniteTransition(label = "lights").animateFloat(.65f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "p")
    Txt(t, 2.4f, Modifier.alpha(pulse).scale(.96f + pulse * .04f), color = Gold, family = Luckiest, weight = FontWeight.Normal)
}

@Composable
fun ClueScreen(ctl: Controller, s: GameState, mem: FocusMemory) {
    val c = s.clue ?: return
    val ph = s.phase
    val name = { id: String? -> s.players.firstOrNull { it.id == id }?.name ?: "?" }
    val showChoices = c.mode == "mc" || (ph == "reveal" && c.results.any { it.mode == "mc" })
    val answerer = if (ph == "answering") s.players.firstOrNull { it.id == c.answerer } else null
    mem.default = "next"
    Column(Modifier.fillMaxSize()) {
        ClueCard(Modifier.weight(1f)) {
            Meta(c.category, if (c.dailyDouble) "Daily Double · ${money(c.wager ?: 0)}" else money(c.value))
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                ClueMedia(ctl, c, ph, Modifier)
                if (ph != "reveal") Txt(c.text, if (c.media != null || c.palette != null) 2.6f else 3.4f, Modifier.padding(horizontal = 2.vw), weight = FontWeight.Black)
                if (showChoices && ph != "reveal" && c.choices != null) FlowRow(Modifier.fillMaxWidth(.9f), horizontalArrangement = Arrangement.spacedBy(1.vw, Alignment.CenterHorizontally)) {
                    c.choices.forEachIndexed { i, x ->
                        val out = x in c.eliminated
                        Box(Modifier.padding(bottom = .7f.vw).width(38.vw).alpha(if (out) .3f else 1f).clip(RoundedCornerShape(1.vw))
                            .background(Brush.verticalGradient(listOf(Blue2, Navy))).border(.15f.vw, Gold.copy(alpha = .7f), RoundedCornerShape(1.vw)).padding(.6f.vw)) {
                            Txt("${"ABCD"[i % 4]}  $x", 1.8f, Modifier.fillMaxWidth(), maxLines = 2)
                        }
                    }
                }
                if (ph == "reveal") {
                    GoldText(c.answer ?: "", 3.6f)
                    if (!c.reveal.isNullOrBlank()) Txt(c.reveal, 1.6f, Modifier.padding(horizontal = 3.vw), weight = FontWeight.Bold, color = Color(0xE6FFFFFF))
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        for (r in c.results) ResultLine(s, r) {
                            val ok = r.verdict == "correct"
                            TvButton(if (ok) "undo" else "accept", "override:${r.pid}", mem, secondary = true, size = .9f) { ctl.send("override", "pid" to r.pid, "correct" to !ok) }
                        }
                    }
                    val ru = s.ruling
                    if (ru != null) Ruling("${if (ru.challenge) "Challenge! " else ""}${name(ru.pid)} said “${ru.heard}” — " +
                        if (ru.by == "moderator") "waiting for moderator ${name(ru.voters.firstOrNull())}…" else "players are voting (${ru.votes}/${ru.voters.size})…")
                    else if (s.lastRuling?.pid != null) Ruling("Ruling: ${if (s.lastRuling.accept) "ACCEPTED ✅" else "REJECTED ❌"}")
                    TvButton("Continue ▶", "next", mem) { ctl.send("next") }
                }
            }
            Box(Modifier.height(3.4f.vw), contentAlignment = Alignment.Center) {
                when {
                    ph == "reading" -> Txt(if (c.dailyDouble) "DAILY DOUBLE for ${name(c.ddPlayer)} — wager ${money(c.wager ?: 0)}" else "Get ready…", 1.8f, color = Gold)
                    ph == "buzzing" -> Lights("BUZZERS OPEN!")
                    answerer != null -> Txt(answerer.name + (if (c.buzzMs != null && !c.dailyDouble) " buzzed in (${"%.2f".format(c.buzzMs / 1000.0)}s)" else "") +
                        " — " + (if (c.mode == "mc") "pick one!" else "type or say it!"), 2f, color = parseColor(answerer.color))
                }
            }
            Countdown(ctl, Modifier)
        }
        Scores(s, answerer?.id)
    }
}

@Composable
fun DdWager(ctl: Controller, s: GameState) {
    val a = remember { Animatable(.2f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(700, easing = CubicBezierEasing(.2f, 1.6f, .4f, 1f))) }
    Center {
        Box(Modifier.scale(a.value), contentAlignment = Alignment.Center) {
            Img(themeAsset("theme/daily-double.webp"), Modifier.width(56.vw).aspectRatio(16f / 9), androidx.compose.ui.layout.ContentScale.Fit)
        }
        GoldText("DAILY DOUBLE!", 5f)
        Txt("${s.players.firstOrNull { it.id == s.control }?.name ?: "?"} is making a wager…", 2.6f, weight = FontWeight.Bold)
    }
}

private val WHEEL = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA).map { Color(it) }

@Composable
fun Wheel(ctl: Controller, f: Final) {
    val n = f.wheel.size.coerceAtLeast(1)
    val seg = 360f / n
    val idx = f.wheel.indexOf(f.film)
    val target = 360f * 8 - (idx * seg + seg / 2)
    val rot = remember(f.film) { Animatable(0f) }
    LaunchedEffect(ctl.wheelSpin, f.film) { if (ctl.wheelSpin) rot.animateTo(target, tween(9000, easing = CubicBezierEasing(.12f, .75f, .12f, 1f))) }
    val textPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK) } }
    Box(contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 1.4f.vw).size(34.vw).rotate(rot.value).shadow(3.vw, CircleShape).border(.5f.vw, Gold, CircleShape)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                for (i in 0 until n) drawArc(WHEEL[i % WHEEL.size], -90f + i * seg, seg, true)
                textPaint.textSize = r * .075f
                for ((i, t) in f.wheel.withIndex()) drawContext.canvas.nativeCanvas.apply {
                    save(); rotate(i * seg + seg / 2 - 90, center.x, center.y)
                    drawText(t.take(22), center.x + r * .18f, center.y + textPaint.textSize / 3, textPaint); restore()
                }
                drawCircle(Gold, r * .12f); drawCircle(Navy, r * .08f)
            }
        }
        Txt("▼", 3f, color = Gold)
    }
}

@Composable
fun FinalScreen(ctl: Controller, s: GameState, mem: FocusMemory) {
    val f = s.fin ?: return
    val ph = s.phase
    mem.default = "next"
    when (ph) {
        "finalWheel" -> Center { GoldText("FINAL JEOPARDY", 4f, Modifier.padding(bottom = 1.vw)); Wheel(ctl, f) }
        "finalWager" -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Center {
                    GoldText("FINAL JEOPARDY", 3.4f)
                    TitleCard(null, f.film, Modifier.width(50.vw))
                    Subtitle("Make your wagers on your phones! (${f.wagered.size}/${f.entrants.size})")
                }
            }
            Countdown(ctl, Modifier); Scores(s)
        }
        "finalClue", "finalThink" -> Column(Modifier.fillMaxSize()) {
            ClueCard(Modifier.weight(1f)) {
                Meta("Final Jeopardy · ${f.film}", "${f.answered.size}/${f.entrants.size} answered")
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Txt(f.clue ?: "", 3.4f, Modifier.padding(horizontal = 2.vw)) }
                Box(Modifier.height(3.4f.vw)) { if (ph == "finalThink") Lights("Answer on your phones!") }
                Countdown(ctl, Modifier)
            }
            Scores(s)
        }
        "finalReveal" -> Column(Modifier.fillMaxSize()) {
            ClueCard(Modifier.weight(1f)) {
                Meta("Final Jeopardy · ${f.film}", "")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                    Txt(f.clue ?: "", 2f, Modifier.alpha(.85f).padding(horizontal = 2.vw))
                    if (f.answerShown) { GoldText(f.answer ?: "", 3.6f); if (!f.reveal.isNullOrBlank()) Txt(f.reveal, 1.6f, weight = FontWeight.Bold) }
                    FlowRow(horizontalArrangement = Arrangement.Center) { for (r in f.results) ResultLine(s, r, " · wagered ${money(f.wagers[r.pid] ?: 0)}") }
                    if (s.ruling != null) Ruling("Close call! ${if (s.ruling.by == "moderator") "Moderator" else "Players"} deciding…")
                    TvButton(if (f.step + 1 < f.order.size) "Next player ▶" else if (f.answerShown) "Crown the champion! 👑" else "Reveal the answer ▶", "next", mem) { ctl.send("next") }
                }
            }
            Scores(s, f.results.lastOrNull()?.pid)
        }
    }
}

@Composable
fun Podium(ctl: Controller, s: GameState, mem: FocusMemory) {
    val ranked = s.players.sortedByDescending { it.score }
    val order = listOfNotNull(ranked.getOrNull(1), ranked.getOrNull(0), ranked.getOrNull(2))
    mem.default = "newGame"
    val spin by rememberInfiniteTransition(label = "rays").animateFloat(0f, 360f, infiniteRepeatable(tween(40000, easing = LinearEasing)), label = "r")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin; scaleX = 2f; scaleY = 2f }) {
            for (i in 0 until 18) rotate(i * 20f) {
                drawArc(Brush.radialGradient(listOf(Color(0x55FFE27A), Color.Transparent), center, size.maxDimension / 2), -94f, 8f, true)
            }
        }
        Column(Modifier.fillMaxSize().padding(top = 1.5f.vw), horizontalAlignment = Alignment.CenterHorizontally) {
            GoldText("${ranked.firstOrNull()?.name ?: ""} wins!", 6f)
            Txt("Tonight's champion · ${money(ranked.firstOrNull()?.score ?: 0)}", 2f, color = Gold)
            Row(Modifier.weight(1f).padding(top = 1.vw), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.4f.vw)) {
                for (p in order) {
                    val r = ranked.indexOf(p) + 1
                    val c = parseColor(p.color)
                    val rise = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay((3 - r) * 350L + 200); rise.animateTo(1f, tween(800, easing = CubicBezierEasing(.2f, 1.3f, .4f, 1f))) }
                    Column(Modifier.width(16.vw).graphicsLayer { translationY = (1 - rise.value) * 400f; alpha = rise.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(p.avatar, (if (r == 1) 9f else 7f).vw, c)
                        Txt(p.name, 1.8f, maxLines = 1)
                        Txt(money(p.score), 2.2f, color = Gold, family = Anton, weight = FontWeight.Normal)
                        val shape = RoundedCornerShape(topStart = .8f.vw, topEnd = .8f.vw)
                        Box(Modifier.fillMaxWidth().height(when (r) { 1 -> 16; 2 -> 11; else -> 8 }.vw).shadow(2.vw, shape)
                            .background(Brush.verticalGradient(listOf(c.copy(alpha = .95f), Color(0xFF0A1450))), shape).border(.2f.vw, Gold, shape).glass(),
                            contentAlignment = Alignment.TopCenter) { GoldText("$r", 6f) }
                    }
                }
            }
            val rest = ranked.drop(3)
            if (rest.isNotEmpty()) FlowRow(Modifier.padding(top = .6f.vw), horizontalArrangement = Arrangement.spacedBy(1.2f.vw)) {
                rest.forEachIndexed { i, p -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(.4f.vw)) {
                    Txt("${i + 4}.", 1.4f); Avatar(p.avatar, 2.4f.vw, parseColor(p.color)); Txt("${p.name} · ${money(p.score)}", 1.4f) } }
            }
            Box(Modifier.padding(vertical = 1.4f.vw)) { TvButton("Play another episode ▶", "newGame", mem) { ctl.send("newGame") } }
        }
    }
}
