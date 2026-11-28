package io.craigcloud.disneyjeopardy.tv

import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen episode art with a navy scrim, crossfading when the art changes (the web's `setBg`). */
@Composable
fun Backdrop(url: String?) {
    Crossfade(url, animationSpec = tween(700), label = "bg") { u ->
        Box(Modifier.fillMaxSize()) {
            Img(u, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x8C050A2E), Color(0x40050A2E), Color(0xB3050A2E)))))
        }
    }
}

/** Score lecterns along the bottom (`scores()` in tv.js). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Scores(s: GameState, highlight: String? = null) {
    if (s.players.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(top = .7f.vw, bottom = 1.1f.vw), horizontalArrangement = Arrangement.spacedBy(1.vw, Alignment.CenterHorizontally)) {
        for (p in s.players) key(p.id) { Lectern(s, p, p.id == highlight) }
    }
}

/** One lectern: counts its score up with a gold pulse, drops lost money in red and shakes on a wrong verdict. */
@Composable
private fun Lectern(s: GameState, p: io.craigcloud.disneyjeopardy.tv.Player, hl: Boolean) {
    val ctl = LocalCtl.current
    val c = parseColor(p.color)
    val lift by animateFloatAsState(if (hl) 1f else 0f, tween(200), label = "lift")
    var shown by remember { mutableIntStateOf(p.score) }
    val pulse = remember { Animatable(0f) }
    val shake = remember { Animatable(1f) }
    val drop = remember { Animatable(1f) }
    var lost by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.score) {
        val from = shown; val to = p.score
        if (from == to) return@LaunchedEffect
        if (to > from) {
            val v = ctl.verdict
            if (v != null && v.correct && v.pid == p.id) delay((CHIP_LAND_MS - (SystemClock.elapsedRealtime() - v.at)).coerceAtLeast(0))
            launch { pulse.snapTo(1f); pulse.animateTo(0f, tween(1200)) }
        } else {
            lost = from - to
            launch { drop.snapTo(0f); drop.animateTo(1f, tween(1400, easing = LinearEasing)) }
        }
        Animatable(from.toFloat()).animateTo(to.toFloat(), tween(if (to > from) 900 else 600)) { shown = value.toInt() }
        shown = to
    }
    LaunchedEffect(ctl.verdict?.seq) {
        val v = ctl.verdict
        if (v != null && !v.correct && v.pid == p.id && SystemClock.elapsedRealtime() - v.at < 500) { shake.snapTo(0f); shake.animateTo(1f, tween(650, easing = LinearEasing)) }
    }
    Box(Modifier.onGloballyPositioned { val o = it.positionInRoot(); ctl.lecterns[p.id] = androidx.compose.ui.geometry.Offset(o.x + it.size.width / 2, o.y + it.size.height / 2) }
        .graphicsLayer {
            val k = shake.value
            translationX = kotlin.math.sin(k * 6.283f * 4) * (1 - k) * size.height * .18f
            translationY = -lift * 15f
            val sc = 1 + lift * .08f + pulse.value * .1f
            scaleX = sc; scaleY = sc; alpha = if (p.online) 1f else .45f
        }) {
        val shape = RoundedCornerShape(topStart = 3.vw, bottomStart = 3.vw, topEnd = 1.vw, bottomEnd = 1.vw)
        val glow = pulse.value
        val ring = if (glow > 0f) androidx.compose.ui.graphics.lerp(if (hl) c else Color(0xFFC49A34), Gold, glow) else if (hl) c else Color(0xFFC49A34)
        Row(Modifier.widthIn(min = 12.vw, max = 15.vw).shadow(if (hl || glow > 0f) 2.vw else 1.vw, shape, ambientColor = if (glow > 0f) Gold else c, spotColor = if (glow > 0f) Gold else c)
            .border(if (hl || glow > 0f) .2f.vw else .15f.vw, ring, shape)
            .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(Color(0xFF272C42), Color(0xFF8A6A10), glow), Color(0xFF0D0F19))), shape)
            .padding(start = .35f.vw, end = .9f.vw, top = .35f.vw, bottom = .35f.vw),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(.6f.vw)) {
            Avatar(p.avatar, 3.6f.vw, c)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(.35f.vw)).background(Brush.radialGradient(listOf(Color(0xFF2457F5), Color(0xFF071A7C)))).padding(horizontal = .6f.vw)) {
                    val col = if (shown < 0) Color(0xFFFF9A9A) else Color.White
                    Txt(money(shown), 1.9f, color = if (glow > 0f) androidx.compose.ui.graphics.lerp(col, Gold, glow) else col, family = Anton, weight = androidx.compose.ui.text.font.FontWeight.Normal, maxLines = 1)
                }
                Txt(p.name, 1.05f, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(.2f.vw).background(c))
            }
        }
        if (p.id == s.control) Txt("★", 1.6f, Modifier.align(Alignment.TopEnd).padding(end = .5f.vw).graphicsLayer { translationY = -30f }, Gold)
        if (drop.value < 1f && lost > 0) {
            val d = drop.value
            Txt("–${money(lost)}", 2.4f, Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = -size.height * 1.1f + d * size.height * 2.2f; alpha = (1f - d) * 1.4f; rotationZ = d * 8f },
                color = Color(0xFFFF3B3B), family = Anton, weight = androidx.compose.ui.text.font.FontWeight.Normal)
        }
    }
}

/** Gold bar shrinking to zero at the server's deadline. */
@Composable
fun Countdown(ctl: Controller, modifier: Modifier) {
    val at = ctl.deadlineAt
    val total = ctl.deadlineTotal
    if (at == 0L || total <= 0) return
    var frac by remember(at) { mutableFloatStateOf(1f) }
    LaunchedEffect(at) {
        while (true) {
            withFrameMillis { }
            frac = ((at - SystemClock.elapsedRealtime()).toFloat() / total).coerceIn(0f, 1f)
            if (frac <= 0f) break
        }
    }
    Box(modifier.fillMaxWidth().height(.9f.vw)) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = frac; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) }
            .background(if (frac < .25f) Color(0xFFFF5A3C) else Gold))
    }
}

/** Gold/red full-screen flash on a verdict. */
@Composable
fun Flash(ctl: Controller) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(ctl.flashSeq) { if (ctl.flashSeq > 0) { a.snapTo(.55f); a.animateTo(0f, tween(900)) } }
    if (a.value > 0f) Box(Modifier.fillMaxSize().alpha(a.value).background(
        Brush.radialGradient(listOf(Color.Transparent, if (ctl.flash == "correct") Color(0xFFFFC61A) else Color(0xFFFF2A2A)), radius = 1600f)))
}

/**
 * Animated host: four muted, always-looping players (idle/talk/celebrate/wrong) stacked on TextureViews; switching
 * mood just crossfades alpha, so the loops never stall or restart.
 */
@Composable
fun HostCam(ctl: Controller, anim: String?, still: String?, size: Dp, modifier: Modifier = Modifier, label: String? = null) {
    val ctx = LocalContext.current
    Box(modifier.height(size).widthIn(max = size * 1.78f).clip(RoundedCornerShape(size * .08f))
        .border(size * .02f, Gold, RoundedCornerShape(size * .08f)).background(Navy)) {
        if (anim == null) { Img(still, Modifier.fillMaxSize()); return@Box }
        val players = remember(anim) { Mood.entries.associateWith { MediaCache.player(ctx).apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE } } }
        DisposableEffect(anim) {
            for ((m, p) in players) MediaCache.item(ctl.abs("$anim-${m.name}.mp4")!!) { p.setMediaItem(it); p.prepare(); p.play() }
            onDispose { players.values.forEach(ExoPlayer::release) }
        }
        for ((m, p) in players) {
            val a by animateFloatAsState(if (ctl.mood == m) 1f else 0f, tween(260, easing = LinearEasing), label = "mood")
            AndroidView({ TextureView(it).also(p::setVideoTextureView) }, Modifier.fillMaxSize().alpha(if (m == Mood.idle) 1f else a))
        }
        if (label != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6050A2A))))) {
            Txt(label, .9f, Modifier.align(Alignment.Center).padding(vertical = .2f.vw), weight = FontWeight.Black)
        }
    }
}
