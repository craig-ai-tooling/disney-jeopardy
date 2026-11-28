package io.craigcloud.disneyjeopardy.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The same synthesized cues as `sfx()` / `thinkMusic()` in public/common.js, rendered to PCM once at startup and
 * held in static AudioTracks so each one fires with no decode latency.
 */
class Sfx {
    private class Tone(val f: Double, val start: Double, val dur: Double, val wave: Char = 's', val vol: Double = 0.18, val slide: Double? = null)

    private val defs: Map<String, List<Tone>> = mapOf(
        "buzz" to listOf(Tone(880.0, 0.0, 0.12, 'q', 0.12), Tone(1320.0, 0.1, 0.18, 'q', 0.1)),
        "correct" to listOf(523.0, 659.0, 784.0, 1047.0).mapIndexed { i, f -> Tone(f, i * 0.09, 0.35, 't', 0.2) },
        "wrong" to listOf(Tone(220.0, 0.0, 0.35, 'w', 0.12, 150.0), Tone(160.0, 0.25, 0.4, 'w', 0.12, 110.0)),
        "open" to listOf(Tone(1200.0, 0.0, 0.08, 's', 0.12)),
        "pick" to listOf(Tone(660.0, 0.0, 0.08, 't', 0.15), Tone(990.0, 0.06, 0.12, 't', 0.15)),
        "dd" to listOf(392.0, 523.0, 659.0, 784.0, 1047.0, 1319.0).mapIndexed { i, f -> Tone(f, i * 0.07, 0.5, 't', 0.16) },
        "timeup" to listOf(Tone(440.0, 0.0, 0.25, 'q', 0.1), Tone(330.0, 0.25, 0.4, 'q', 0.1)),
        "tick" to listOf(Tone(1800.0, 0.0, 0.03, 'q', 0.05)),
        "join" to listOf(Tone(784.0, 0.0, 0.1, 's', 0.15), Tone(1175.0, 0.08, 0.2, 's', 0.15)),
        "fanfare" to listOf(523.0, 523.0, 523.0, 659.0, 784.0, 659.0, 784.0, 1047.0).mapIndexed { i, f -> Tone(f, i * 0.13, 0.25, 't', 0.18) },
    )
    private val tracks = HashMap<String, AudioTrack>()
    private var think: AudioTrack? = null

    fun load() {
        for ((k, v) in defs) tracks[k] = track(render(v))
        val notes = intArrayOf(659, 784, 880, 784, 659, 523, 587, 659, 587, 523, 440, 494, 523, 587, 659, 0)
        val tones = (0 until (34 / 0.25).toInt()).mapNotNull { i ->
            val f = notes[i % notes.size]
            if (f == 0) null else Tone(f * (if ((i / 32) % 2 == 1) 1.0595 else 1.0), 0.05 + i * 0.25, 0.22, 't', 0.08)
        }
        think = track(render(tones))
    }

    fun play(kind: String) {
        val t = tracks[kind] ?: return
        runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop(); t.reloadStaticData(); t.play() }
    }

    fun think(on: Boolean) {
        val t = think ?: return
        runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop(); if (on) { t.reloadStaticData(); t.play() } }
    }

    fun release() { tracks.values.forEach { it.release() }; think?.release(); tracks.clear() }

    private fun render(tones: List<Tone>): ShortArray {
        val len = ((tones.maxOf { it.start + it.dur } + 0.06) * RATE).toInt()
        val buf = DoubleArray(len)
        for (t in tones) {
            val s0 = (t.start * RATE).toInt()
            val n = (t.dur * RATE).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val x = i.toDouble() / RATE
                val f = if (t.slide != null) t.f * exp(ln(t.slide / t.f) * x / t.dur) else t.f
                phase += f / RATE
                val p = phase % 1.0
                val w = when (t.wave) {
                    'q' -> if (p < 0.5) 1.0 else -1.0
                    't' -> 4 * abs(p - 0.5) - 1
                    'w' -> 2 * p - 1
                    else -> sin(2 * PI * p)
                }
                // Exponential attack to vol over 20 ms, then exponential decay to silence (matches the Web Audio ramps).
                val g = if (x < 0.02) 0.0001 * exp(ln(t.vol / 0.0001) * x / 0.02)
                else t.vol * exp(ln(0.0001 / t.vol) * (x - 0.02) / (t.dur - 0.02).coerceAtLeast(0.001))
                if (s0 + i < len) buf[s0 + i] += w * g
            }
        }
        return ShortArray(len) { (buf[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    private fun track(pcm: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(pcm.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build().apply { write(pcm, 0, pcm.size) }

    companion object { const val RATE = 22050 }
}
