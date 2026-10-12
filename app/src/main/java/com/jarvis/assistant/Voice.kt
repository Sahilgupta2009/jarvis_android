package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Low-level speech in/out. Everything here runs on the main thread. */
object Voice {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onLevel(level: Float)
    }

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var offlineFailed = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayList<Pair<String, () -> Unit>>()
    private val callbacks = HashMap<String, () -> Unit>()
    private var utterance = 0
    private var beepMuted = false
    private var speakingHindi = false

    @Volatile var speaking = false
        private set

    fun init(a: Application) {
        app = a
        main.post { initTts() }
    }

    fun hasMic() = Device.hasPerm(Manifest.permission.RECORD_AUDIO)

    // ───────────────────────── listening ─────────────────────────
    fun listen(l: Listener) {
        main.post {
            stopInternal()
            if (!hasMic()) { l.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return@post }
            if (!SpeechRecognizer.isRecognitionAvailable(app)) { l.onError(-1); return@post }
            muteBeep(true)
            val id = ++session
            val r = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                private var done = false
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    if (id == session) l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (id != session || done) return
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    if (t.isNotBlank()) l.onPartial(t)
                }

                override fun onResults(results: Bundle?) {
                    if (id != session || done) return
                    done = true
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    finish(r, id)
                    if (t.isBlank()) l.onError(SpeechRecognizer.ERROR_NO_MATCH) else l.onFinal(t)
                }

                override fun onError(error: Int) {
                    if (id != session || done) return
                    done = true
                    if (error == 12 || error == 13) offlineFailed = true // offline language pack missing
                    finish(r, id)
                    l.onError(error)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.listenLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Store.listenLang)
                if (!offlineFailed) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                r.startListening(i)
            } catch (e: Exception) {
                finish(r, id)
                l.onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun finish(r: SpeechRecognizer, id: Int) {
        if (id == session) recognizer = null
        try { r.destroy() } catch (_: Exception) {}
    }

    private fun stopInternal() {
        WakeWord.stop()
        session++
        recognizer?.let { try { it.cancel(); it.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    fun stopListening() {
        main.post { stopInternal(); muteBeep(false) }
    }

    /** Some recognizers play a start/stop "ding". Muting the media stream while idle-listening hides it. */
    fun muteBeep(mute: Boolean) {
        if (!Store.silenceBeep && mute) return
        try {
            val am = app.getSystemService(AudioManager::class.java)
            if (mute && !beepMuted && !am.isMusicActive && !speaking) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                beepMuted = true
            } else if (!mute && beepMuted) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                beepMuted = false
            }
        } catch (_: Exception) {
            beepMuted = false
        }
    }

    // ───────────────────────── speaking ─────────────────────────
    private fun initTts() {
        tts = TextToSpeech(app) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale.UK)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.getDefault())
                t.setPitch(0.92f)
                t.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { complete(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { complete(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { complete(utteranceId) }
                })
                ttsReady = true
                val queued = ArrayList(pending)
                pending.clear()
                for ((text, cb) in queued) speak(text, cb)
            }
        }
    }

    private fun complete(id: String?) {
        main.post {
            val cb = callbacks.remove(id ?: "")
            if (callbacks.isEmpty()) speaking = false
            cb?.invoke()
        }
    }

    /** Speak [text]; [onDone] runs when it finishes (or immediately if speech isn't possible). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        main.post {
            muteBeep(false)
            val t = tts
            if (!ttsReady || t == null) {
                if (t == null) onDone() else pending.add(text to onDone)
                return@post
            }
            val hindi = text.count { it in '\u0900'..'\u097F' } * 3 > text.length
            if (hindi != speakingHindi) {
                val r = t.setLanguage(if (hindi) Locale.forLanguageTag("hi-IN") else Locale.UK)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.getDefault())
                speakingHindi = hindi
            }
            val id = "u${++utterance}"
            callbacks[id] = onDone
            speaking = true
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            if (t.speak(text.take(600), TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
                callbacks.remove(id)
                if (callbacks.isEmpty()) speaking = false
                onDone()
            }
        }
    }

    /** Stop talking without running completion callbacks. */
    fun stopSpeaking() {
        main.post {
            callbacks.clear()
            pending.clear()
            speaking = false
            try { tts?.stop() } catch (_: Exception) {}
        }
    }
}
