package io.craigcloud.disneyjeopardy.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.craigcloud.disneyjeopardy.BuildConfig
import kotlinx.coroutines.delay

/** Which screen (for focus memory) the current state + overlay shows. */
fun screenOf(s: GameState, o: Overlay?) = if (o != null && s.phase == "lobby") "lobby:${o.name}" else when (s.phase) {
    "reading", "buzzing", "answering", "reveal" -> "clue"
    else -> s.phase
}

@Composable
fun TvApp(ctl: Controller, onSettings: () -> Unit) {
    VwScreen {
        CompositionLocalProvider(LocalCtl provides ctl) {
            val s = ctl.state
            when {
                s == null && ctl.conn == Conn.Down -> ErrorScreen(ctl, onSettings)
                s == null -> Loading()
                else -> Game(ctl, s)
            }
            // A drop mid-game keeps the board up; only a long outage shows the (quiet) banner.
            if (s != null && ctl.conn == Conn.Down) Box(Modifier.align(Alignment.TopCenter).padding(top = 1.vw)) { Pill("The server can't be reached · reconnecting…") }
            UpdateBadge(ctl)
        }
    }
}

@Composable
private fun Pill(t: String, modifier: Modifier = Modifier) = Box(modifier.background(Card, RoundedCornerShape(50)).border(.15f.vw, Gold.copy(alpha = .7f), RoundedCornerShape(50))
    .padding(horizontal = 1.4f.vw, vertical = .5f.vw)) { Txt(t, 1.1f, color = Color.White) }

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UpdateBadge(ctl: Controller) {
    val apk = ctl.info?.apk ?: return
    if (apk.versionCode <= BuildConfig.VERSION_CODE) return
    var show by remember(apk.versionCode) { mutableStateOf(true) }
    LaunchedEffect(apk.versionCode) { delay(12000); show = false }
    Box(Modifier.align(Alignment.BottomEnd).padding(1.vw)) {
        if (show) Pill("Update available · v${apk.versionName} at ${ctl.origin.replace(Regex("^https?://"), "")}/apk")
        else Box(Modifier.alpha(.8f).background(Gold, RoundedCornerShape(50)).padding(horizontal = .7f.vw, vertical = .2f.vw)) { Txt("Update", .8f, color = Navy, shadow = false) }
    }
}

@Composable
private fun Brand(sub: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) {
    Img(themeAsset("theme/bg-castle.webp"), Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Color(0x99050A2E)))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Img(themeAsset("theme/logo-cut.webp"), Modifier.height(16.vw), androidx.compose.ui.layout.ContentScale.Fit)
        Spacer(Modifier.height(1.5f.vw))
        sub()
    }
}

@Composable
private fun Loading() = Brand {
    val a by rememberInfiniteTransition(label = "dots").animateFloat(.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Txt("Warming up the board…", 2f, Modifier.alpha(a), color = Gold)
}

@Composable
private fun ErrorScreen(ctl: Controller, onSettings: () -> Unit) {
    val mem = ctl.mem("error")
    mem.default = "retry"
    Brand {
        Txt("The server can't be reached", 2.4f, color = Color.White)
        Txt("We'll keep checking in the background and jump right in when it's back.", 1.3f, Modifier.padding(top = .5f.vw), color = Color(0xCCFFFFFF), weight = FontWeight.Bold)
        Row(Modifier.padding(top = 1.6f.vw), horizontalArrangement = Arrangement.spacedBy(1.vw)) {
            TvButton("Retry", "retry", mem) { ctl.client.retryNow() }
            TvButton("Server settings", "settings", mem, secondary = true) { onSettings() }
        }
    }
    RestoreFocus(mem, ctl.conn)
}

@Composable
private fun Game(ctl: Controller, s: GameState) {
    val screen = screenOf(s, ctl.overlay)
    var preview by remember { mutableStateOf<String?>(null) }
    val bg = when {
        s.phase == "podium" -> themeBg(s.board)
        ctl.overlay == Overlay.boards -> preview ?: artOf(s.board)
        else -> artOf(s.board)
    }
    Box(Modifier.fillMaxSize()) {
        Backdrop(bg)
        AnimatedContent(screenOf(s, null), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "phase") { scr ->
            val st = ctl.state ?: s
            val mem = ctl.mem(scr)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (scr) {
                    "lobby" -> Lobby(ctl, st, mem)
                    "hostDraft" -> HostDraft(ctl, st, mem)
                    "coldOpen" -> ColdOpen(ctl, st, mem)
                    "intro" -> Intro(ctl, st, mem)
                    "categories" -> Categories(ctl, st, mem)
                    "board" -> BoardScreen(ctl, st, mem)
                    "clue" -> ClueScreen(ctl, st, mem)
                    "ddWager" -> DdWager(ctl, st)
                    "podium" -> Podium(ctl, st, mem)
                    else -> if (scr.startsWith("final")) FinalScreen(ctl, st, mem)
                }
                if (scr == "hostDraft" || scr == "lobby") Countdown(ctl, Modifier.align(Alignment.BottomCenter))
            }
            if (ctl.overlay == null || scr != "lobby") RestoreFocus(mem, st, scr)
        }
        // Small corner host cam during play (big one lives in the cold open).
        if (s.phase !in setOf("lobby", "hostDraft", "coldOpen", "podium") && s.host != null)
            HostCam(ctl, s.host.anim, s.host.img, 7.6f.vw, Modifier.align(Alignment.BottomEnd).padding(bottom = 1.2f.vw, end = 1.2f.vw), label = s.host.name)
        val speak = ctl.speaking
        if (speak != null && s.phase !in setOf("coldOpen", "hostDraft") && speak.url == null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.vw)) { Pill(speak.text) }
        when (ctl.overlay) {
            Overlay.boards -> { val m = ctl.mem(screen); Episodes(ctl, s, m) { preview = it }; RestoreFocus(m, s, screen) }
            Overlay.settings -> { val m = ctl.mem(screen); SettingsPanel(ctl, s, m); RestoreFocus(m, s, screen) }
            null -> preview = null
        }
        VerdictBanner(ctl)
        Flash(ctl)
        FxLayer(ctl)
    }
}
