package io.craigcloud.disneyjeopardy.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors `Room.view('tv')` in server/game.mjs. Every field is optional so a newer server never breaks the app. */
@Serializable
data class GameState(
    val phase: String = "lobby",
    val code: String = "",
    val host: Host? = null,
    val hosts: List<HostChoice>? = null,
    val settings: Settings = Settings(),
    val captain: String? = null,
    val control: String? = null,
    val deadline: Long = 0,
    val players: List<Player> = emptyList(),
    val board: Board? = null,
    val catIdx: Int? = null,
    val clue: Clue? = null,
    val ruling: Ruling? = null,
    val lastRuling: LastRuling? = null,
    @SerialName("final") val fin: Final? = null,
    val boards: List<BoardSummary>? = null,
    val prefetch: List<String>? = null,
)

@Serializable data class Host(val id: String = "", val name: String = "", val img: String? = null, val anim: String? = null)
@Serializable data class HostChoice(val id: String = "", val name: String = "", val img: String? = null, val votes: Int = 0)
@Serializable data class Settings(
    val mode: String = "mixed", val moderator: String? = null, val negatives: Boolean = true,
    val voice: Boolean = true, val host: String = "draft", val buzzMs: Int = 9000,
    val autoAdvance: Boolean = true,
)
@Serializable data class Player(
    val id: String, val name: String = "", val score: Int = 0, val color: String = "#ffcf3f",
    val avatar: String? = null, val online: Boolean = true, val alwaysMc: Boolean = false,
)
@Serializable data class Board(val id: String = "", val title: String = "", val theme: String = "", val art: String? = null, val categories: List<Category> = emptyList())
@Serializable data class Category(val id: String = "", val title: String = "", val art: String? = null, val clues: List<CellInfo> = emptyList())
@Serializable data class CellInfo(val id: String, val value: Int = 0, val used: Boolean = false)
@Serializable data class BoardSummary(val id: String, val title: String = "", val theme: String = "", val played: Boolean = false, val art: String? = null)
@Serializable data class Media(val type: String = "image", val url: String? = null)
@Serializable data class Result(
    val pid: String = "", val heard: String? = null, val verdict: String = "wrong", val amount: Int = 0,
    val mode: String? = null, val spoken: Boolean = false, val challenged: Boolean = false,
)
@Serializable data class Clue(
    val id: String = "", val value: Int = 0, val category: String = "", val catId: String = "", val text: String = "",
    val format: String = "text", val media: Media? = null, val palette: List<String>? = null, val quote: String? = null,
    val emoji: String? = null, val dailyDouble: Boolean = false, val wager: Int? = null, val ddPlayer: String? = null,
    val choices: List<String>? = null, val eliminated: List<String> = emptyList(), val attempted: List<String> = emptyList(),
    val answerer: String? = null, val mode: String? = null, val buzzOpen: Boolean = false, val buzzMs: Double? = null,
    val answer: String? = null, val reveal: String? = null, val results: List<Result> = emptyList(),
    val buzzOrder: List<Buzz> = emptyList(),
)
@Serializable data class Buzz(val pid: String = "", val rt: Int = 0)
@Serializable data class Ruling(val pid: String = "", val heard: String? = null, val by: String = "vote", val voters: List<String> = emptyList(), val votes: Int = 0, val challenge: Boolean = false)
@Serializable data class LastRuling(val pid: String? = null, val accept: Boolean = false, val yes: Int = 0, val no: Int = 0)
@Serializable data class Final(
    val wheel: List<String> = emptyList(), val film: String = "", val category: String? = null, val clue: String? = null,
    val choices: List<String>? = null, val entrants: List<String> = emptyList(), val wagered: List<String> = emptyList(),
    val answered: List<String> = emptyList(), val order: List<String> = emptyList(), val step: Int = -1,
    val answerShown: Boolean = false, val answer: String? = null, val reveal: String? = null,
    val results: List<Result> = emptyList(), val wagers: Map<String, Int> = emptyMap(),
)
@Serializable data class Say(val text: String = "", val tag: String? = null, val url: String? = null)
@Serializable data class ApkInfo(val versionCode: Int = 0, val versionName: String = "")
@Serializable data class Info(val joinBase: String? = null, val voice: Boolean = false, val apk: ApkInfo? = null)

val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

fun money(n: Int): String = (if (n < 0) "-$" else "$") + "%,d".format(kotlin.math.abs(n))

val AVATARS = mapOf(
    "castle" to "Castle", "star" to "Wishing Star", "crown" to "Ear Hat", "rocket" to "Monorail", "shell" to "Pineapple Swirl",
    "apple" to "Candy Apple", "lamp" to "Lantern", "slipper" to "Glass Slipper", "rose" to "Enchanted Rose", "wand" to "Churro",
    "balloon" to "Ear Balloon", "teacup" to "Teacup",
)

private val BG = listOf(
    "beach" to Regex("beach|summer|surf|island|pool|sun|vacation|tiki|hawai"),
    "villains" to Regex("villain|halloween|spooky|haunt|dark|evil|scary|monster"),
    "winter" to Regex("winter|christmas|holiday|snow|frozen|ice|new year"),
    "lagoon" to Regex("jungle|tropic|lagoon|animal|safari|wild|nature|adventure"),
    "space" to Regex("space|star|galaxy|future|robot|sci|pixar|tomorrow"),
    "ocean" to Regex("ocean|sea|under|mermaid|water|fish|pirate|sail"),
    "autumn" to Regex("autumn|fall|thanksgiving|harvest|food|feast|cook|snack"),
    "mainstreet" to Regex("main street|parade|retro|classic|vintage|music|broadway|park"),
)

/** Same theme backdrop pick as `bgFor()` in public/common.js. */
fun bgFor(theme: String?, title: String?): String {
    if (theme == null && title == null) return "castle"
    val s = "$theme $title".lowercase()
    return BG.firstOrNull { it.second.containsMatchIn(s) }?.first ?: "castle"
}
fun themeBg(b: Board?) = "/theme/bg-${bgFor(b?.theme, b?.title)}.webp"
fun artOf(b: Board?) = b?.art ?: themeBg(b)
fun artOf(b: BoardSummary) = b.art ?: "/theme/bg-${bgFor(b.theme, b.title)}.webp"
