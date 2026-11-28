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
import kotlin.random.Random

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
        for (p in s.players) {
            val c = parseColor(p.color)
            val hl = p.id == highlight
            val lift by animateFloatAsState(if (hl) 1f else 0f, tween(200), label = "lift")
            Box(Modifier.graphicsLayer { translationY = -lift * 15f; scaleX = 1 + lift * .08f; scaleY = 1 + lift * .08f; alpha = if (p.online) 1f else .45f }) {
                val shape = RoundedCornerShape(topStart = 3.vw, bottomStart = 3.vw, topEnd = 1.vw, bottomEnd = 1.vw)
                Row(Modifier.widthIn(min = 12.vw, max = 15.vw).shadow(if (hl) 2.vw else 1.vw, shape, ambientColor = c, spotColor = c)
                    .border(if (hl) .2f.vw else .15f.vw, if (hl) c else Color(0xFFC49A34), shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF272C42), Color(0xFF0D0F19))), shape)
                    .padding(start = .35f.vw, end = .9f.vw, top = .35f.vw, bottom = .35f.vw),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(.6f.vw)) {
                    Avatar(p.avatar, 3.6f.vw, c)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.clip(RoundedCornerShape(.35f.vw)).background(Brush.radialGradient(listOf(Color(0xFF2457F5), Color(0xFF071A7C)))).padding(horizontal = .6f.vw)) {
                            Txt(money(p.score), 1.9f, color = if (p.score < 0) Color(0xFFFF9A9A) else Color.White, family = Anton, weight = androidx.compose.ui.text.font.FontWeight.Normal, maxLines = 1)
                        }
                        Txt(p.name, 1.05f, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(.2f.vw).background(c))
                    }
                }
                if (p.id == s.control) Txt("★", 1.6f, Modifier.align(Alignment.TopEnd).padding(end = .5f.vw).graphicsLayer { translationY = -30f }, Gold)
            }
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

/** Green/red full-screen flash on a verdict. */
@Composable
fun Flash(ctl: Controller) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(ctl.flashSeq) { if (ctl.flashSeq > 0) { a.snapTo(.55f); a.animateTo(0f, tween(900)) } }
    if (a.value > 0f) Box(Modifier.fillMaxSize().alpha(a.value).background(
        Brush.radialGradient(listOf(Color.Transparent, if (ctl.flash == "correct") Color(0xFF2EE66B) else Color(0xFFFF2A2A)), radius = 1600f)))
}

private class Bit(val x: Float, val color: Color, val dur: Float, val delay: Float, val spin: Float, val drift: Float)

@Composable
fun Confetti(ctl: Controller) {
    val seq = ctl.confettiSeq
    if (seq == 0) return
    val colors = listOf(0xFFFFD34D, 0xFF4FC3FF, 0xFFFF6FAE, 0xFF7DFF8A, 0xFFC08CFF, 0xFFFFFFFF).map { Color(it) }
    val bits = remember(seq) { List(ctl.confetti) { Bit(Random.nextFloat(), colors[it % colors.size], 2.5f + Random.nextFloat() * 2.5f, Random.nextFloat() * 1.2f, Random.nextFloat() * 720f, Random.nextFloat() * 2 - 1) } }
    var t by remember(seq) { mutableFloatStateOf(0f) }
    LaunchedEffect(seq) { val t0 = withFrameMillis { it }; while (t < 6.5f) withFrameMillis { t = (it - t0) / 1000f } }
    if (t >= 6.5f) return
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width / 100f
        for (b in bits) {
            val p = (t - b.delay) / b.dur
            if (p < 0 || p > 1) continue
            val y = -2 * w + p * (size.height + 4 * w)
            val x = b.x * size.width + kotlin.math.sin(p * 9 + b.drift * 3) * w * 1.5f
            rotate(b.spin * p, androidx.compose.ui.geometry.Offset(x, y)) {
                drawRect(b.color, androidx.compose.ui.geometry.Offset(x - .4f * w, y - .6f * w), androidx.compose.ui.geometry.Size(.8f * w, 1.2f * w))
            }
        }
    }
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
