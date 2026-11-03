package com.jondude1.bookshelf.reader

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Reads a list of text blocks aloud and reports which block is being spoken. */
class TtsReader(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    var ready by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    private var blocks: List<String> = emptyList()
    var onBlock: (Int) -> Unit = {}
    var onFinished: () -> Unit = {}
    private var generation = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { ready = status == TextToSpeech.SUCCESS }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                val p = parse(id) ?: return
                if (p[2] == 0) main.post { if (p[0] == generation) onBlock(p[1]) }
            }
            override fun onDone(id: String) {
                val p = parse(id) ?: return
                main.post {
                    if (p[0] == generation && p[1] == blocks.lastIndex && p[2] == p[3] - 1 && speaking) { speaking = false; onFinished() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) {}
        })
    }

    private fun parse(id: String): List<Int>? = id.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }

    fun speak(list: List<String>, rate: Float, pitch: Float) {
        generation++
        tts.stop()
        blocks = list
        if (list.isEmpty()) { speaking = false; onFinished(); return }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        speaking = true
        list.forEachIndexed { i, t ->
            // TextToSpeech has a per-call limit; long paragraphs are cut at sentence boundaries
            val chunks = if (t.length < 3500) listOf(t) else t.chunked(3500)
            chunks.forEachIndexed { k, c ->
                val id = "$generation:$i:$k:${chunks.size}"
                tts.speak(c, TextToSpeech.QUEUE_ADD, Bundle(), id)
            }
        }
    }

    /** Installed voices for the device language first, as (name, label). */
    fun voices(): List<Pair<String, String>> = runCatching {
        val lang = java.util.Locale.getDefault().language
        tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired || it.features?.contains("notInstalled") != true }
            .sortedWith(compareBy({ it.locale.language != lang }, { it.locale.toLanguageTag() }, { it.name }))
            .map { v -> v.name to "${v.locale.displayName} · ${v.name.substringAfterLast('-').take(16)}${if (v.isNetworkConnectionRequired) " (online)" else ""}" }
            .take(40)
    }.getOrDefault(emptyList())

    fun useVoice(name: String?) {
        runCatching {
            if (name == null) tts.voice = tts.defaultVoice
            else tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
        }
    }

    fun stop() { generation++; speaking = false; tts.stop() }

    fun shutdown() { stop(); tts.shutdown() }
}
