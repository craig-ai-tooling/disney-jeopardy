package io.craigcloud.disneyjeopardy.tv

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A verdict that just settled; drives the banner, lectern effects and the CRT glitch. */
class VerdictFx(val seq: Int, val pid: String, val correct: Boolean, val amount: Int, val answer: String?) {
    val at = SystemClock.elapsedRealtime()
}

/** Delay between a correct verdict and its lectern count-up, so the score moves when the flying chip lands. */
const val CHIP_LAND_MS = 1650L

/**
 * Confetti cannons, glitter and firework sparks. Everything lives in preallocated arrays in "height units"
 * (y ∈ 0..1 top→bottom, x ∈ 0..ASPECT) and is stepped/drawn by a single Canvas, so a frame allocates nothing.
 */
class ConfettiFx {
    private companion object {
        const val N = 560
        const val S = 1100
        const val ASPECT = 16f / 9f
        const val G = 1.0f
        const val DEAD = 99f
        val DRAG = floatArrayOf(3.0f, 3.6f, 1.7f, 2.6f) // paper, ribbon, glitter, mickey
        val LAYER_SCALE = floatArrayOf(.6f, 1f, 1.45f)
        val LAYER_DIM = floatArrayOf(.58f, .84f, 1f)
        val PALETTE = arrayOf(
            Color(0xFFFFD34D), Color(0xFFFFB300), Color(0xFF4FC3FF), Color(0xFF2457F5), Color(0xFFFF6FAE),
            Color(0xFFE53935), Color(0xFF7DFF8A), Color(0xFFC08CFF), Color(0xFFFFFFFF),
        )
        val SPARK = arrayOf(Color(0xFFFFE27A), Color(0xFFFF8AD8), Color(0xFF7FD8FF), Color(0xFFFFFFFF), Color(0xFFFF6A3D), Color(0xFFB8FF7A))
    }

    private val x = FloatArray(N); private val y = FloatArray(N); private val vx = FloatArray(N); private val vy = FloatArray(N)
    private val rot = FloatArray(N); private val vrot = FloatArray(N); private val flip = FloatArray(N); private val vflip = FloatArray(N)
    private val phase = FloatArray(N); private val sz = FloatArray(N); private val life = FloatArray(N) { DEAD }
    private val kind = IntArray(N); private val layer = IntArray(N); private val col = IntArray(N)
    private val sx = FloatArray(S); private val sy = FloatArray(S); private val svx = FloatArray(S); private val svy = FloatArray(S)
    private val sl = FloatArray(S) { DEAD }; private val smax = FloatArray(S); private val scol = IntArray(S)
    private val bx = FloatArray(8); private val by = FloatArray(8); private val bt = FloatArray(8) { DEAD }; private val bc = IntArray(8)
    private var nextP = 0; private var nextS = 0; private var nextB = 0
    private val rnd = java.util.Random()
    private val ribbon = Path()
    var alive = false; private set

    private fun f() = rnd.nextFloat()

    private fun spawn(px: Float, py: Float, pvx: Float, pvy: Float, wait: Float, k: Int) {
        val i = nextP; nextP = (nextP + 1) % N
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy; life[i] = -wait; kind[i] = k
        rot[i] = f() * 360f; vrot[i] = (f() - .5f) * 900f; flip[i] = f() * 6.28f; vflip[i] = 5f + f() * 9f
        phase[i] = f() * 6.28f
        layer[i] = f().let { if (it < .25f) 0 else if (it < .7f) 1 else 2 }
        sz[i] = when (k) { 0 -> .013f + f() * .009f; 1 -> .017f + f() * .006f; 2 -> .007f + f() * .006f; else -> .02f + f() * .01f }
        col[i] = when (k) { 2 -> if (f() < .7f) 0 else 8; 3 -> if (f() < .6f) 0 else 5; else -> rnd.nextInt(PALETTE.size) }
    }

    private fun pickKind() = f().let { if (it < .58f) 0 else if (it < .75f) 1 else if (it < .95f) 2 else 3 }

    /** Two cannons in the bottom corners, [count] pieces in total. */
    fun cannons(count: Int, power: Float = 1f) {
        for (i in 0 until count) {
            val left = i % 2 == 0
            val ang = Math.toRadians(60.0 + rnd.nextGaussian() * 9).toFloat()
            val sp = (2.4f + f() * 1.3f) * power
            spawn(if (left) -.01f else ASPECT + .01f, 1.01f, cos(ang) * sp * (if (left) 1 else -1), -sin(ang) * sp, f() * .35f, pickKind())
        }
        alive = true
    }

    /** Glitter and paper puffed radially from a point (e.g. behind the verdict banner). */
    fun puff(cx: Float, cy: Float, count: Int) {
        for (i in 0 until count) {
            val a = f() * 6.283f; val sp = .8f + f() * 1.4f
            spawn(cx * ASPECT, cy, cos(a) * sp * 1.3f, sin(a) * sp - .6f, f() * .08f, if (f() < .6f) 2 else 0)
        }
        alive = true
    }

    /** One firework shell bursting at (cx, cy) in screen fractions. */
    fun firework(cx: Float, cy: Float) {
        val c = rnd.nextInt(SPARK.size)
        val b = nextB; nextB = (nextB + 1) % bx.size
        bx[b] = cx * ASPECT; by[b] = cy; bt[b] = 0f; bc[b] = c
        val n = 90
        for (i in 0 until n) {
            val s = nextS; nextS = (nextS + 1) % S
            val a = i * 6.283f / n + f() * .05f; val sp = .5f + f() * .28f
            sx[s] = bx[b]; sy[s] = cy; svx[s] = cos(a) * sp; svy[s] = sin(a) * sp; sl[s] = 0f; smax[s] = 1.1f + f() * .7f
            scol[s] = if (f() < .8f) c else 3
        }
        alive = true
    }

    fun step(dt: Float) {
        var any = false
        for (i in 0 until N) {
            if (life[i] >= DEAD) continue
            any = true
            life[i] += dt
            if (life[i] < 0f) continue
            val k = DRAG[kind[i]]
            vy[i] += G * dt
            vx[i] -= vx[i] * k * dt
            vy[i] -= vy[i] * k * dt
            val falling = (vy[i] / .3f).coerceIn(0f, 1f)
            x[i] += vx[i] * dt + sin(phase[i] + life[i] * 2.6f) * .16f * dt * falling
            y[i] += vy[i] * dt
            rot[i] += vrot[i] * dt * (.4f + .6f * falling)
            flip[i] += vflip[i] * dt
            if ((y[i] > 1.08f && vy[i] > 0f) || life[i] > 11f) life[i] = DEAD
        }
        for (s in 0 until S) {
            if (sl[s] >= DEAD) continue
            any = true
            sl[s] += dt
            svy[s] += .32f * dt
            svx[s] -= svx[s] * 1.5f * dt; svy[s] -= svy[s] * 1.5f * dt
            sx[s] += svx[s] * dt; sy[s] += svy[s] * dt
            if (sl[s] > smax[s]) sl[s] = DEAD
        }
        for (b in bt.indices) if (bt[b] < DEAD) { any = true; bt[b] += dt; if (bt[b] > .35f) bt[b] = DEAD }
        alive = any
    }

    fun draw(d: DrawScope) = with(d) {
        if (!alive) return@with
        val h = size.height
        val u = size.width / ASPECT
        for (b in bt.indices) if (bt[b] < DEAD) {
            val a = 1f - bt[b] / .35f
            drawCircle(SPARK[bc[b]].copy(alpha = .35f * a), h * (.02f + bt[b] * .2f), Offset(bx[b] * u, by[b] * h))
            drawCircle(Color.White.copy(alpha = .8f * a), h * .012f, Offset(bx[b] * u, by[b] * h))
        }
        for (s in 0 until S) {
            if (sl[s] >= DEAD) continue
            val t = sl[s] / smax[s]
            val a = (1f - t).let { it * it }
            val px = sx[s] * u; val py = sy[s] * h
            val c = SPARK[scol[s]]
            drawLine(c.copy(alpha = a * .8f), Offset(px, py), Offset(px - svx[s] * .09f * u, py - svy[s] * .09f * h), h * .0032f, StrokeCap.Round, blendMode = BlendMode.Plus)
            drawCircle(Color.White.copy(alpha = a), h * .0022f, Offset(px, py))
        }
        for (l in 0..2) {
            val ls = LAYER_SCALE[l]; val dim = LAYER_DIM[l]
            for (i in 0 until N) {
                if (life[i] < 0f || life[i] >= DEAD || layer[i] != l) continue
                val s = sz[i] * h * ls
                val fc = cos(flip[i])
                val shade = dim * (.5f + .5f * abs(fc)) + (if (abs(fc) > .94f) .25f else 0f)
                val base = PALETTE[col[i]]
                val c = Color(min(1f, base.red * shade), min(1f, base.green * shade), min(1f, base.blue * shade), if (l == 0) .8f else 1f)
                withTransform({
                    translate(x[i] * u, y[i] * h)
                    rotate(rot[i], Offset.Zero)
                    scale(if (kind[i] == 1) 1f else fc, if (kind[i] == 0) .6f + .4f * abs(sin(flip[i] * .7f)) else 1f, Offset.Zero)
                }) {
                    when (kind[i]) {
                        0 -> drawRect(c, Offset(-s * .5f, -s * .32f), Size(s, s * .64f))
                        1 -> {
                            ribbon.reset(); ribbon.moveTo(0f, 0f)
                            for (j in 1..8) ribbon.lineTo(sin(phase[i] + j * .9f + life[i] * 5f) * s * .38f, j * s * .45f)
                            drawPath(ribbon, c, style = Stroke(s * .24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        2 -> {
                            val tw = .35f + .65f * abs(sin(life[i] * 9f + phase[i]))
                            val g = c.copy(alpha = tw)
                            drawRect(g, Offset(-s * .6f, -s * .1f), Size(s * 1.2f, s * .2f), blendMode = BlendMode.Plus)
                            drawRect(g, Offset(-s * .1f, -s * .6f), Size(s * .2f, s * 1.2f), blendMode = BlendMode.Plus)
                        }
                        else -> {
                            drawCircle(c, s * .4f, Offset.Zero)
                            drawCircle(c, s * .26f, Offset(-s * .4f, -s * .4f))
                            drawCircle(c, s * .26f, Offset(s * .4f, -s * .4f))
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen layer for [ConfettiFx]; the frame loop only runs while something is alive. */
@Composable
fun FxLayer(ctl: Controller) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(ctl.fxSeq) {
        var last = withFrameNanos { it }
        while (ctl.fx.alive) withFrameNanos { t -> ctl.fx.step(((t - last) / 1e9f).coerceIn(0f, .05f)); last = t; frame++ }
        frame++
    }
    Canvas(Modifier.fillMaxSize()) { if (frame >= 0) ctl.fx.draw(this) }
}

private fun overshoot(t: Float): Float { val c = 1.9f; val p = t - 1; return 1 + (c + 1) * p * p * p + c * p * p }

/** Custom-drawn verdict medallion: gold with a stroked check, red with an X, amber with a "?". */
@Composable
fun VerdictBadge(verdict: String, size: Dp, modifier: Modifier = Modifier) {
    val prog = remember(verdict) { Animatable(0f) }
    LaunchedEffect(verdict) { prog.animateTo(1f, tween(700, easing = LinearEasing)) }
    val (face, rim) = when (verdict) {
        "correct" -> listOf(Color(0xFFFFF6C4), Color(0xFFFFD34D), Color(0xFFC98A00)) to Color(0xFF7A4E00)
        "close" -> listOf(Color(0xFFFFE0A0), Color(0xFFFFA82E), Color(0xFFB85C00)) to Color(0xFF5A3000)
        else -> listOf(Color(0xFFFF9A8A), Color(0xFFE53935), Color(0xFF7A0C0C)) to Color(0xFF3E0000)
    }
    Box(modifier.size(size).graphicsLayer { val p = (prog.value / .4f).coerceIn(0f, 1f); val sc = .3f + .7f * overshoot(p); scaleX = sc; scaleY = sc }
        .drawWithCache {
            val r = this.size.minDimension / 2
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val faceBrush = Brush.radialGradient(face, center + Offset(-r * .3f, -r * .35f), r * 1.5f)
            val rimBrush = Brush.linearGradient(listOf(Color(0xFFFFE9A8), rim, Color(0xFFFFE9A8), rim), Offset.Zero, Offset(this.size.width, this.size.height))
            val w = this.size.width
            val mark = Path().apply {
                when (verdict) {
                    "correct" -> { moveTo(w * .27f, w * .53f); lineTo(w * .43f, w * .69f); lineTo(w * .74f, w * .35f) }
                    "close" -> {}
                    else -> { moveTo(w * .33f, w * .33f); lineTo(w * .67f, w * .67f); moveTo(w * .67f, w * .33f); lineTo(w * .33f, w * .67f) }
                }
            }
            val pm = PathMeasure().apply { setPath(mark, false) }
            val seg = Path()
            val q = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER; textSize = w * .62f; color = android.graphics.Color.WHITE
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(w * .04f, 0f, w * .03f, 0x99000000.toInt())
            }
            onDrawBehind {
                drawCircle(Color.Black.copy(alpha = .35f), r * .98f, center + Offset(0f, r * .1f))
                drawCircle(rimBrush, r)
                drawCircle(faceBrush, r * .82f)
                drawCircle(Color.White.copy(alpha = .4f), r * .7f, style = Stroke(r * .04f))
                drawArc(Color.White.copy(alpha = .28f), 200f, 110f, false, center - Offset(r * .62f, r * .62f), Size(r * 1.24f, r * 1.24f), style = Stroke(r * .09f, cap = StrokeCap.Round))
                val t = ((prog.value - .25f) / .75f).coerceIn(0f, 1f).let { FastOutSlowInEasing.transform(it) }
                if (verdict == "close") {
                    q.alpha = (t * 255).toInt()
                    drawContext.canvas.nativeCanvas.drawText("?", center.x, center.y + w * .22f, q)
                } else if (t > 0f) {
                    seg.reset(); pm.getSegment(0f, pm.length * t, seg, true)
                    drawPath(seg, Color(0xCC1A0E00), style = Stroke(w * .15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(seg, Color.White, style = Stroke(w * .095f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        })
}

/** Gold ribbon with folded tails behind its content. */
@Composable
private fun Ribbon(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.drawWithCache {
        val w = size.width; val h = size.height
        val gold = Brush.verticalGradient(listOf(Color(0xFFFFF6C4), Color(0xFFFFD34D), Color(0xFFE0A21A), Color(0xFFB87800), Color(0xFFFFE27A)))
        val dark = Brush.verticalGradient(listOf(Color(0xFFC98A00), Color(0xFF7A4E00)))
        val band = Path().apply {
            moveTo(w * .04f, h * .08f); quadraticTo(w * .5f, -h * .1f, w * .96f, h * .08f)
            lineTo(w * .96f, h * .92f); quadraticTo(w * .5f, h * .74f, w * .04f, h * .92f); close()
        }
        fun tail(left: Boolean) = Path().apply {
            val e = if (left) 0f else w; val dir = if (left) 1f else -1f
            moveTo(e + dir * w * .1f, h * .24f); lineTo(e - dir * w * .04f, h * .24f); lineTo(e + dir * w * .01f, h * .66f)
            lineTo(e - dir * w * .04f, h * 1.1f); lineTo(e + dir * w * .1f, h * 1.1f); close()
        }
        val tl = tail(true); val tr = tail(false)
        onDrawBehind {
            drawPath(tl, dark); drawPath(tr, dark)
            drawPath(tl, Outline, style = Stroke(h * .03f)); drawPath(tr, Outline, style = Stroke(h * .03f))
            drawPath(band, gold)
            drawPath(band, Outline, style = Stroke(h * .045f))
            drawLine(Color.White.copy(alpha = .55f), Offset(w * .08f, h * .16f), Offset(w * .92f, h * .16f), h * .02f)
        }
    }, contentAlignment = Alignment.Center) { content() }
}

/** The correct-answer moment: sunburst, ribbon with the player's badge and name, the answer, and a chip that flies to the lectern. */
@Composable
fun VerdictBanner(ctl: Controller) {
    val v = ctl.verdict ?: return
    if (!v.correct) return
    val p = ctl.state?.players?.firstOrNull { it.id == v.pid } ?: return
    val t = remember(v.seq) { Animatable(0f) }
    LaunchedEffect(v.seq) { t.animateTo(3.3f, tween(3300, easing = LinearEasing)) }
    if (t.value >= 3.3f) return
    val k = t.value
    val inP = FastOutSlowInEasing.transform((k / .55f).coerceIn(0f, 1f))
    val out = 1f - ((k - 2.85f) / .45f).coerceIn(0f, 1f)
    val chipStart = remember(v.seq) { floatArrayOf(Float.NaN, 0f) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = inP * .85f; rotationZ = k * 12f; scaleX = 2.3f; scaleY = 2.3f }.drawWithCache {
            val r = size.maxDimension / 2
            val center = Offset(size.width / 2, size.height / 2)
            val rays = Path().apply {
                for (i in 0 until 22) {
                    val a0 = (i * 2 * PI / 22).toFloat(); val a1 = a0 + (PI / 22 * .9).toFloat()
                    moveTo(center.x, center.y); lineTo(center.x + cos(a0) * r, center.y + sin(a0) * r); lineTo(center.x + cos(a1) * r, center.y + sin(a1) * r); close()
                }
            }
            val brush = Brush.radialGradient(listOf(Color(0xCCFFE27A), Color(0x66FFB300), Color.Transparent), center, r * .6f)
            val glow = Brush.radialGradient(listOf(Color(0x99FFF3B0), Color(0x33FFB300), Color.Transparent), center, r * .35f)
            onDrawBehind { drawRect(Color(0x99050A2E)); drawPath(rays, brush); drawCircle(glow, r * .35f) }
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.2f.vw)) {
            Ribbon(Modifier.graphicsLayer { translationX = -(1f - inP) * size.width * 1.8f; rotationZ = (1f - inP) * -6f }) {
                Row(Modifier.padding(horizontal = 7.vw, vertical = 1.4f.vw), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.4f.vw)) {
                    Avatar(p.avatar, 7.vw, parseColor(p.color))
                    GoldText(p.name, 4.6f, maxLines = 1)
                }
            }
            val ap = ((k - .4f) / .4f).coerceIn(0f, 1f)
            if (!v.answer.isNullOrBlank()) GoldText(v.answer, 5f, Modifier.padding(horizontal = 6.vw).graphicsLayer { alpha = ap; val s = .5f + .5f * overshoot(ap); scaleX = s; scaleY = s })
            val first = ctl.state?.clue?.buzzOrder?.firstOrNull()
            if (first?.pid == v.pid) Txt("Fastest finger · ${"%.2f".format(first.rt / 1000f)}s", 2f, Modifier.graphicsLayer { alpha = ap }, color = Color(0xFFFFE9A8))
            if (v.amount > 0) {
                val fly = FastOutSlowInEasing.transform(((k - 1.15f) / .5f).coerceIn(0f, 1f))
                Box(Modifier.onGloballyPositioned { if (chipStart[0].isNaN()) { val o = it.positionInRoot(); chipStart[0] = o.x + it.size.width / 2; chipStart[1] = o.y + it.size.height / 2 } }
                    .graphicsLayer {
                        val target = ctl.lecterns[v.pid]
                        if (target != null && !chipStart[0].isNaN()) { translationX = (target.x - chipStart[0]) * fly; translationY = (target.y - chipStart[1]) * fly }
                        val s = (if (k < .9f) overshoot(((k - .6f) / .3f).coerceIn(0f, 1f)) else 1f) * (1f - .45f * fly)
                        scaleX = s; scaleY = s
                        alpha = if (k > 1.68f) 0f else ((k - .6f) / .15f).coerceIn(0f, 1f)
                    }
                    .drawBehind {
                        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFF6C4), Color(0xFFFFC61A), Color(0xFFB87800))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                        drawRoundRect(Outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2), style = Stroke(size.height * .07f))
                    }.padding(horizontal = 2.vw, vertical = .5f.vw)) {
                    Txt("+${money(v.amount)}", 3.2f, color = Outline, family = Anton, weight = androidx.compose.ui.text.font.FontWeight.Normal, shadow = false)
                }
            }
        }
    }
}

private fun hash(n: Int): Float { var h = n * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 65535f }

/** Red CRT static/scanline glitch applied to the clue monitor when a wrong verdict settles. */
@Composable
fun crtGlitch(ctl: Controller): Modifier {
    val v = ctl.verdict
    val g = remember { Animatable(1f) }
    LaunchedEffect(v?.seq) { if (v != null && !v.correct && SystemClock.elapsedRealtime() - v.at < 500) { g.snapTo(0f); g.animateTo(1f, tween(800, easing = LinearEasing)) } }
    if (g.value >= 1f) return Modifier
    return Modifier.drawWithContent {
        val k = g.value
        val frame = (k * 30).toInt()
        val inten = (1f - k) * (if (hash(frame * 7) > .25f) 1f else .35f)
        val bands = 16
        val bh = size.height / bands
        for (i in 0 until bands) {
            val dx = if (hash(frame * 131 + i) > .5f) (hash(frame * 17 + i * 3) - .5f) * size.width * .09f * inten else 0f
            clipRect(0f, i * bh, size.width, (i + 1) * bh) { translate(dx) { this@drawWithContent.drawContent() } }
        }
        drawRect(Color(0xFFFF1E2A).copy(alpha = .38f * inten), blendMode = BlendMode.SrcAtop)
        var yy = 0f
        while (yy < size.height) { drawRect(Color.Black.copy(alpha = .35f * inten), Offset(0f, yy), Size(size.width, 2f)); yy += 5f }
        for (i in 0 until 260) {
            val nx = hash(frame * 977 + i) * size.width; val ny = hash(frame * 613 + i * 7) * size.height
            drawRect((if (i % 3 == 0) Color(0xFFFF6B6B) else Color.White).copy(alpha = .55f * inten), Offset(nx, ny), Size(3f + hash(i) * 10f, 2f))
        }
        val roll = (k * 2.2f % 1f) * size.height
        drawRect(Color.White.copy(alpha = .14f * inten), Offset(0f, roll), Size(size.width, size.height * .06f))
    }
}
