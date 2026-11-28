package io.craigcloud.disneyjeopardy.tv

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.hypot

/** Last D-pad arrow press, so a focus change can be attributed to the viewer rather than a re-render. */
object Dpad { @Volatile var movedAt = 0L }

/**
 * Per-screen focus memory, the Compose version of `navMem`/`restoreFocus()` in public/tv.js: each focusable has a
 * stable key; after any state update focus returns to the remembered key, else the spatially nearest survivor,
 * else the screen's default. Until the viewer moves, focus follows the default (e.g. Start once a player joins).
 */
class FocusMemory {
    private class Slot(val req: FocusRequester, var rect: Rect = Rect.Zero, var enabled: Boolean = true)
    private val slots = LinkedHashMap<String, Slot>()
    var key: String? = null; private set
    private var rect = Rect.Zero
    var user = false; private set
    var focusedKey: String? = null; private set
    var default: String? = null
    var version by mutableIntStateOf(0)
    var onMove: () -> Unit = {}

    @Composable
    fun item(key: String, enabled: Boolean = true): Modifier {
        val slot = remember(key) { Slot(FocusRequester()) }
        slot.enabled = enabled
        DisposableEffect(key) {
            slots[key] = slot
            onDispose { if (slots[key] === slot) slots.remove(key); if (focusedKey == key) { focusedKey = null; version++ } }
        }
        return Modifier
            .focusRequester(slot.req)
            .onGloballyPositioned { slot.rect = it.boundsInRoot() }
            .onFocusChanged { st ->
                if (st.isFocused) {
                    val moved = SystemClock.elapsedRealtime() - Dpad.movedAt < 400
                    if (moved && focusedKey != null && focusedKey != key) onMove()
                    focusedKey = key; this.key = key; rect = slot.rect; user = user || moved
                } else if (focusedKey == key) focusedKey = null
            }
    }

    /** OK on an item counts as choosing it, like an arrow move. */
    fun chose(k: String) { if (k == key) user = true }

    fun reset() { key = null; user = false }

    fun restore() {
        val live = slots.filterValues { it.enabled }
        if (live.isEmpty()) return
        val cur = focusedKey
        val def = default?.takeIf { it in live } ?: live.keys.first()
        if (cur != null && cur in live && (user || cur == def)) return
        val target = when {
            !user -> def
            key in live -> key!!
            else -> live.minByOrNull { (_, s) -> hypot(s.rect.center.x - rect.center.x, s.rect.center.y - rect.center.y) }?.key ?: def
        }
        runCatching { live[target]!!.req.requestFocus() }
    }
}

/** Re-applies [mem] after composition settles whenever [trigger] changes (state broadcast, screen change). */
@Composable
fun RestoreFocus(mem: FocusMemory, vararg trigger: Any?) {
    LaunchedEffect(mem, mem.version, *trigger) { withFrameNanos { }; withFrameNanos { }; mem.restore() }
}

fun Modifier.tvClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
}
