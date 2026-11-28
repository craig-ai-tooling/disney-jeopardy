@file:OptIn(ExperimentalLayoutApi::class)
package io.craigcloud.disneyjeopardy.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

val MODES = mapOf("mc" to "Multiple choice", "typed" to "Type / speak", "mixed" to "Mixed")
fun hostLabel(s: GameState, id: String) = if (id == "draft") "Vote each game" else s.hosts?.firstOrNull { it.id == id }?.name ?: id

@Composable
fun Badge(t: String) = Box(Modifier.clip(RoundedCornerShape(50)).background(Gold).padding(horizontal = .55f.vw, vertical = .1f.vw)) {
    Txt(t, .8f, color = Color(0xFF102060), shadow = false)
}

@Composable
fun Lobby(ctl: Controller, s: GameState, mem: FocusMemory) {
    val b = s.board
    val set = s.settings
    val n = s.players.size
    val sz = if (n > 8) 4.2f else if (n > 5) 5.2f else 6.4f
    mem.default = "start"
    // Start becomes the target as soon as someone can play, even if the remote wandered while the room was empty.
    androidx.compose.runtime.LaunchedEffect(n > 0) { if (n > 0) { mem.reset(); mem.restore() } }
    Row(Modifier.fillMaxSize().padding(2.vw), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Img(themeAsset("theme/logo-cut.webp"), Modifier.height(9.vw), androidx.compose.ui.layout.ContentScale.Fit)
            if (b != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("TONIGHT'S EPISODE", 1.3f, color = Gold)
                    GoldText(b.title, 4.2f)
                    FlowRow(Modifier.padding(top = .6f.vw), horizontalArrangement = Arrangement.spacedBy(.6f.vw, Alignment.CenterHorizontally)) {
                        for (c in b.categories) Box(Modifier.padding(bottom = .5f.vw).shadow(.5f.vw, RoundedCornerShape(.5f.vw)).background(Brush.verticalGradient(listOf(Blue2, Navy)), RoundedCornerShape(.5f.vw))
                            .border(.12f.vw, Color(0xFFC49A34), RoundedCornerShape(.5f.vw)).padding(horizontal = 1.vw, vertical = .4f.vw)) { Txt(c.title.uppercase(), 1.0f, maxLines = 1) }
                    }
                }
            } else Txt("No boards loaded", 2.4f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(1.4f.vw, Alignment.CenterHorizontally)) {
                for (p in s.players) Column(Modifier.alpha(if (p.online) 1f else .45f).width((sz + 3).vw), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p.avatar, sz.vw, parseColor(p.color))
                    Txt(p.name, 1.3f, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(.3f.vw)) {
                        if (p.id == s.captain) Badge("CAPTAIN"); if (p.id == set.moderator) Badge("MOD"); if (p.alwaysMc) Badge("ABCD")
                    }
                }
                repeat((4 - n).coerceAtLeast(0)) {
                    Column(Modifier.alpha(.55f).width((sz + 3).vw), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(null, sz.vw, Color(0x66FFFFFF)); Txt("Join in!", 1.3f)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(1.vw)) {
                TvButton("▶ Start the show", "start", mem, enabled = n > 0) { ctl.send("start") }
                TvButton("Pick episode", "boards", mem, secondary = true) { ctl.openOverlay(Overlay.boards) }
                TvButton("Surprise me", "shuffle", mem, secondary = true) { ctl.send("shuffle") }
                TvButton("Settings", "settings", mem, secondary = true) { ctl.openOverlay(Overlay.settings) }
            }
            Txt("${MODES[set.mode] ?: set.mode} answers · Wrong answers ${if (set.negatives) "lose $" else "cost nothing"} · Host voice ${if (set.voice) "on" else "off"} · Host: ${hostLabel(s, set.host)}",
                1.05f, color = Color(0xCCFFFFFF), weight = FontWeight.Normal)
        }
        JoinCard(ctl, s)
    }
}

@Composable
fun JoinCard(ctl: Controller, s: GameState) {
    val base = (ctl.info?.joinBase ?: ctl.origin).trimEnd('/')
    val qr = remember(base, s.code) { qrBitmap("$base/${s.code}") }
    Column(Modifier.width(24.vw).shadow(2.vw, RoundedCornerShape(1.6f.vw)).background(Card, RoundedCornerShape(1.6f.vw))
        .border(.25f.vw, Gold.copy(alpha = .7f), RoundedCornerShape(1.6f.vw)).padding(1.6f.vw), horizontalAlignment = Alignment.CenterHorizontally) {
        GoldText("Grab your phone!", 2.1f)
        Spacer(Modifier.height(1.vw))
        Image(qr.asImageBitmap(), "QR", Modifier.size(17.vw).clip(RoundedCornerShape(1.vw)).background(Color.White).padding(.8f.vw), filterQuality = FilterQuality.None)
        Txt(base.replace(Regex("^https?://"), ""), 1.2f, Modifier.padding(top = .7f.vw), maxLines = 1)
        Txt("ROOM CODE", 1.1f, Modifier.padding(top = .8f.vw), color = Gold)
        Txt(s.code, 5f, family = Anton, weight = FontWeight.Normal, color = Color.White)
    }
}

fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF0A1F6E.toInt() else -1)
    return bmp
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Scrim(content: @Composable () -> Unit) = Box(
    Modifier.fillMaxSize().background(Color(0xE6040A28))
        .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }.focusGroup(),
    contentAlignment = Alignment.Center
) { content() }

@Composable
fun Episodes(ctl: Controller, s: GameState, mem: FocusMemory, onArt: (String?) -> Unit) {
    val cur = s.board?.id
    mem.default = cur?.let { "board:$it" }
    val list = s.boards.orEmpty()
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (list.indexOfFirst { it.id == (mem.key?.removePrefix("board:") ?: cur) }).coerceAtLeast(0))
    Scrim {
        Column(Modifier.fillMaxSize().padding(horizontal = 3.vw, vertical = 2.vw)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                GoldText("Pick tonight's episode", 3f)
                Txt("OK to choose · BACK to close", 1.2f, color = Color(0xCCFFFFFF))
            }
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().padding(top = 1.vw), grid,
                horizontalArrangement = Arrangement.spacedBy(1.2f.vw), verticalArrangement = Arrangement.spacedBy(1.2f.vw),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(1.vw)) {
                items(list, key = { it.id }) { x ->
                    var f by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(1.vw)
                    Box(Modifier.scale(if (f) 1.06f else 1f).then(mem.item("board:${x.id}"))
                        .onFocusChanged { f = it.isFocused; if (it.isFocused) onArt(artOf(x)) }
                        .tvClickable { ctl.sfx.play("pick"); ctl.send("board", "id" to x.id); ctl.closeOverlay() }
                        .aspectRatio(16f / 10).shadow(1.vw, shape).clip(shape)
                        .border(if (f) .35f.vw else .12f.vw, if (f) Color.White else Gold.copy(alpha = .5f), shape)
                        .alpha(if (x.played && !f) .6f else 1f)) {
                        Img(artOf(x), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
                        Txt(x.title, 1.25f, Modifier.align(Alignment.BottomCenter).padding(.6f.vw), maxLines = 2)
                        if (x.id == cur) Box(Modifier.align(Alignment.TopEnd).padding(.5f.vw)) { Badge("TONIGHT") }
                        else if (x.played) Box(Modifier.align(Alignment.TopEnd).padding(.5f.vw)) { Badge("PLAYED") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(ctl: Controller, s: GameState, mem: FocusMemory) {
    val set = s.settings
    mem.default = "mode:${set.mode}"
    @Composable fun Opt(key: String, label: String, on: Boolean, apply: (Settings) -> Settings) =
        TvButton(label, key, mem, secondary = !on, size = 1.15f) { ctl.sendSettings(apply(set)) }
    @Composable fun SetRow(label: String, note: String = "", opts: @Composable () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = .7f.vw), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 1.4f, Modifier.width(16.vw).padding(end = 1.vw), color = Gold, align = androidx.compose.ui.text.style.TextAlign.Start)
        Column { FlowRow(horizontalArrangement = Arrangement.spacedBy(.8f.vw), verticalArrangement = Arrangement.spacedBy(.6f.vw)) { opts() }; if (note.isNotEmpty()) Txt(note, 1f, Modifier.padding(top = .4f.vw), color = Color(0xBBFFFFFF), weight = FontWeight.Normal) }
    }
    Scrim {
        Column(Modifier.width(66.vw).background(Card, RoundedCornerShape(1.6f.vw)).border(.25f.vw, Gold.copy(alpha = .6f), RoundedCornerShape(1.6f.vw)).padding(2.vw),
            horizontalAlignment = Alignment.CenterHorizontally) {
            GoldText("Game settings", 3f)
            SetRow("Answers", "Mixed = type or speak the 200s & 400s, multiple choice from 600 up.") { MODES.forEach { (m, l) -> Opt("mode:$m", l, set.mode == m) { it.copy(mode = m) } } }
            SetRow("Wrong answers") { Opt("neg:1", "Lose $", set.negatives) { it.copy(negatives = true) }; Opt("neg:0", "No penalty", !set.negatives) { it.copy(negatives = false) } }
            SetRow("Host controls pacing", "On = the game waits for OK after each answer.") { Opt("pace:1", "On", !set.autoAdvance) { it.copy(autoAdvance = false) }; Opt("pace:0", "Off", set.autoAdvance) { it.copy(autoAdvance = true) } }
            SetRow("Host voice") { Opt("voice:1", "On", set.voice) { it.copy(voice = true) }; Opt("voice:0", "Off", !set.voice) { it.copy(voice = false) } }
            SetRow("Host") { (listOf("draft") + s.hosts.orEmpty().map { it.id }).forEach { id -> Opt("host:$id", hostLabel(s, id), set.host == id) { it.copy(host = id) } } }
            Txt("Players can turn on \"always multiple choice\" or become moderator on their phones.", 1f, Modifier.padding(top = 1.vw), color = Color(0xBBFFFFFF), weight = FontWeight.Normal)
            Spacer(Modifier.height(1.4f.vw))
            TvButton("Done", "close", mem) { ctl.closeOverlay() }
        }
    }
}

@Composable
fun Center(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.vw), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)

@Composable
fun Subtitle(t: String, size: Float = 1.9f) = Txt(t, size, Modifier.padding(vertical = .4f.vw).width(70.vw), weight = FontWeight.Bold)

@Composable
fun HostDraft(ctl: Controller, s: GameState, mem: FocusMemory) {
    val sp = ctl.speaking?.tag
    val talking = if (ctl.pitchIdx in 0..5 && sp?.startsWith("pitch:") == true) sp.substring(6).toIntOrNull() ?: -1 else -1
    mem.default = "draftDone"
    Center {
        GoldText("PICK TONIGHT'S HOST!", 3.6f)
        Subtitle("Vote on your phones · ${s.hosts.orEmpty().sumOf { it.votes }}/${s.players.count { it.online }} votes")
        Row(Modifier.padding(vertical = 1.vw), horizontalArrangement = Arrangement.spacedBy(1.2f.vw)) {
            s.hosts.orEmpty().forEachIndexed { i, h ->
                val on = i == talking
                val shape = RoundedCornerShape(1.4f.vw)
                Column(Modifier.width(14.vw).scale(if (on) 1.08f else 1f).shadow(if (on) 2.vw else .8f.vw, shape, spotColor = Gold).background(Card, shape)
                    .border(.25f.vw, if (on) Gold else Gold.copy(alpha = .5f), shape).clip(shape), horizontalAlignment = Alignment.CenterHorizontally) {
                    Img(h.img, Modifier.fillMaxWidth().aspectRatio(1f))
                    Txt(h.name, 1.3f, Modifier.padding(top = .4f.vw), maxLines = 1)
                    Txt("★".repeat(h.votes), 1.6f, Modifier.padding(bottom = .4f.vw).height(2.2f.vw), color = Gold)
                }
            }
        }
        TvButton("Done voting ▶", "draftDone", mem) { ctl.send("draftDone") }
    }
}

@Composable
fun ColdOpen(ctl: Controller, s: GameState, mem: FocusMemory) {
    mem.default = "next"
    Center {
        HostCam(ctl, s.host?.anim, s.host?.img, 30.vw)
        GoldText(s.host?.name ?: "", 3.4f, Modifier.padding(top = 1.vw))
        Subtitle(ctl.speaking?.text ?: "")
        Spacer(Modifier.height(1.vw))
        TvButton("Skip ▶", "next", mem) { ctl.send("next") }
    }
}

@Composable
fun TitleCard(art: String?, title: String, modifier: Modifier = Modifier.width(58.vw)) {
    val shape = RoundedCornerShape(1.6f.vw)
    Box(modifier.aspectRatio(16f / 8).shadow(2.vw, shape).clip(shape).border(.3f.vw, Gold, shape), contentAlignment = Alignment.Center) {
        if (art != null) Img(art, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1C48D8), Color(0xFF071865)))))
        Box(Modifier.fillMaxSize().background(Color(0x55000033)))
        GoldText(title, 4.4f, Modifier.padding(2.vw), maxLines = 3)
    }
}

@Composable
fun Intro(ctl: Controller, s: GameState, mem: FocusMemory) {
    val b = s.board ?: return
    mem.default = "next"
    Center {
        TitleCard(artOf(b), b.title)
        Subtitle("Tonight's theme: ${b.theme}")
        TvButton("Let's see the categories ▶", "next", mem) { ctl.send("next") }
    }
}

@Composable
fun Categories(ctl: Controller, s: GameState, mem: FocusMemory) {
    val i = s.catIdx ?: 0
    val c = s.board?.categories?.getOrNull(i) ?: return
    mem.default = "next"
    Center {
        Txt("CATEGORY ${i + 1} OF 5", 1.3f, Modifier.padding(bottom = .6f.vw), color = Gold)
        TitleCard(c.art ?: themeBg(s.board), c.title)
        Spacer(Modifier.height(1.5f.vw))
        TvButton(if (i < 4) "Next category ▶" else "To the board! ▶", "next", mem) { ctl.send("next") }
    }
}
