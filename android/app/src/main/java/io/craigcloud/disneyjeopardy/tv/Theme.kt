package io.craigcloud.disneyjeopardy.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

val Gold = Color(0xFFFFD34D)
val Gold2 = Color(0xFFFFB300)
val Navy = Color(0xFF0A1F6E)
val Blue2 = Color(0xFF1438B8)
val Card = Color(0xD1081450)
val Outline = Color(0xFF0B2490)

lateinit var Luckiest: FontFamily
lateinit var Nunito: FontFamily
lateinit var Anton: FontFamily

fun loadFonts(assets: android.content.res.AssetManager) {
    Luckiest = FontFamily(Font("fonts/LuckiestGuy-Regular.ttf", assets))
    Nunito = FontFamily(Font("fonts/Nunito.ttf", assets, FontWeight.Normal), Font("fonts/Nunito.ttf", assets, FontWeight.Black,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(900))))
    Anton = FontFamily(Font("fonts/Anton-Regular.ttf", assets))
}

/** Layout unit: 1/100th of the screen width, so every screen scales like the web's vw-based CSS. */
val LocalVw = staticCompositionLocalOf { 19.2.dp }
val Float.vw: Dp @Composable get() = LocalVw.current * this
val Int.vw: Dp @Composable get() = LocalVw.current * this.toFloat()
val Float.vsp: TextUnit @Composable get() = with(LocalDensity.current) { (LocalVw.current * this@vsp).toSp() }
val Int.vsp: TextUnit @Composable get() = this.toFloat().vsp

@Composable
fun VwScreen(content: @Composable BoxScope.() -> Unit) = BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF050A2E))) {
    CompositionLocalProvider(LocalVw provides maxWidth / 100f) { content() }
}

fun parseColor(s: String?, fallback: Color = Gold): Color = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(fallback)

private val goldBrush = Brush.verticalGradient(
    0f to Color(0xFFFFFBE2), .2f to Color(0xFFFFE685), .46f to Color(0xFFFFC61A), .54f to Color(0xFFC97F00),
    .72f to Color(0xFFFFD23C), 1f to Color(0xFFFFF0A6),
)

/** Logo-style headline: blue outline + drop glow underneath, gold gradient fill on top (the web's `.goldtxt`). */
@Composable
fun GoldText(text: String, size: Float, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val fs = size.vsp
    val stroke = with(LocalDensity.current) { (LocalVw.current * size * 0.16f).toPx() }
    val base = TextStyle(fontFamily = Luckiest, fontSize = fs, letterSpacing = fs * 0.035f, textAlign = TextAlign.Center, lineHeight = fs * 1.05f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, maxLines = maxLines, style = base.copy(color = Outline, drawStyle = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            shadow = Shadow(Color(0xFF04104A), Offset(0f, stroke * .75f), stroke * 1.5f)))
        Text(text, maxLines = maxLines, style = base.copy(brush = goldBrush))
    }
}

@Composable
fun Txt(text: String, size: Float, modifier: Modifier = Modifier, color: Color = Color.White, weight: FontWeight = FontWeight.Black,
        family: FontFamily = Nunito, align: TextAlign = TextAlign.Center, maxLines: Int = Int.MAX_VALUE, shadow: Boolean = true) {
    Text(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = TextStyle(
        color = color, fontSize = size.vsp, fontFamily = family, fontWeight = weight, textAlign = align, lineHeight = size.vsp * 1.2f,
        shadow = if (shadow) Shadow(Color(0xAA000000), Offset(2f, 3f), 6f) else null))
}

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, ctl: Controller = LocalCtl.current) {
    val a = if (url?.startsWith("asset:") == true) "file:///android_asset/${url.removePrefix("asset:")}" else ctl.abs(url) ?: return
    AsyncImage(a, null, modifier, contentScale = scale)
}

val LocalCtl = staticCompositionLocalOf<Controller> { error("no controller") }

/** Bundled theme art (assets/theme) when present, else the server copy. */
fun themeAsset(path: String): String = "asset:" + path.removePrefix("/")

@Composable
fun Avatar(key: String?, size: Dp, ring: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size).shadow(size * .2f, CircleShape).border(size * 0.05f, ring, CircleShape).clip(CircleShape).background(Navy)) {
        if (key != null) Img(themeAsset("theme/avatars/$key.webp"), Modifier.fillMaxSize())
        else Txt("?", size.value / LocalVw.current.value * .5f, Modifier.align(Alignment.Center), Gold)
    }
}

/**
 * A remote-focusable pill button (gold primary / blue secondary, as `button` in tv.css). [key] identifies it in the
 * screen's [FocusMemory] so focus is restored to the same item after every state broadcast.
 */
@Composable
fun TvButton(label: String, key: String, mem: FocusMemory, modifier: Modifier = Modifier, secondary: Boolean = false, enabled: Boolean = true,
             size: Float = 1.5f, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.08f else 1f, tween(120), label = "btn")
    val shape = RoundedCornerShape(50)
    val bg = if (secondary) Brush.verticalGradient(listOf(Color(0xFF2D62F2), Color(0xFF0C2A9C)))
    else Brush.verticalGradient(0f to Color(0xFFFFF1A8), .45f to Color(0xFFFFD04A), 1f to Color(0xFFF0A800))
    val ctl = LocalCtl.current
    Box(modifier
        .scale(sc)
        .then(mem.item(key, enabled))
        .onFocusChanged { focused = it.isFocused }
        .tvClickable(enabled) { ctl.sfx.play("pick"); mem.chose(key); onClick() }
        .shadow(.6f.vw, shape)
        .then(if (focused) Modifier.border(.3f.vw, Color.White, shape) else if (secondary) Modifier.border(.14f.vw, Color(0xFFE8B93A), shape) else Modifier)
        .background(bg, shape)
        .drawWithContent { drawContent(); if (!enabled) drawRect(Color(0x88000000)) }
        .padding(horizontal = (size * 1.33f).vw, vertical = (size * .53f).vw)) {
        Txt(label, size, color = if (secondary) Color.White else Color(0xFF102060), shadow = secondary, maxLines = 1)
    }
}
