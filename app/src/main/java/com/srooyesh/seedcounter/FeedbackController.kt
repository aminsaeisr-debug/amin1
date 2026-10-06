package com.srooyesh.seedcounter

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Small, lifecycle-bound feedback helper: confirmation/error tones + optional Persian speech. */
class FeedbackController(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    @Volatile private var ttsReady = false
    @Volatile private var released = false
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    override fun onInit(status: Int) {
        if (released || status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        val result = engine.setLanguage(Locale("fa", "IR"))
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (ttsReady) {
            engine.setSpeechRate(0.95f)
        }
    }

    fun successTone() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 160) } catch (_: Exception) { }
    }

    fun errorTone() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 220) } catch (_: Exception) { }
    }

    fun speak(text: String, enabled: Boolean) {
        if (released || !enabled || !ttsReady || text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seed-mas-feedback")
        } catch (_: Exception) { }
    }

    fun release() {
        released = true
        try { tone?.release() } catch (_: Exception) { }
        try { tts?.stop() } catch (_: Exception) { }
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        ttsReady = false
    }
}
