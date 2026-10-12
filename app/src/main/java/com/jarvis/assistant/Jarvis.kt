package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Device.app = this
        Brain.load(this)
        Ch.create(this)
        Voice.init(this)
        Jarvis.init(this)
    }
}

enum class OrbState { OFF, IDLE, LISTENING, THINKING, SPEAKING }

data class Msg(val text: String, val user: Boolean, val time: Long = System.currentTimeMillis())

private data class Reply(val text: String, val end: Boolean = false)

/**
 * The assistant's brain stem. Owns the wake-word loop, one conversation "session" at a time
 * (listen -> think -> speak -> listen again for 20 s), and the state the UI draws.
 */
object Jarvis {
    private const val ACTIVE_WINDOW_MS = 20_000L
    private const val COOLDOWN_MS = 2_500L

    private const val WORDS = "jarvis|jarvas|jervis|jarvus|garvis|jarvice"
    private const val DEV_WORDS = "जार्?विस|जरविस|जर्विस|जार्विश|जार्वीस|जारवि"
    private val WAKE_RX = Regex("""\b(?:$WORDS)\b|$DEV_WORDS""", RegexOption.IGNORE_CASE)
    private val STRIP_RX = Regex("""\b(?:(?:hey|ok|okay) )?(?:$WORDS)\b[,.]?|(?:हे\s+)?(?:$DEV_WORDS)[,.\u0964]?""", RegexOption.IGNORE_CASE)
    private val LEAD_RX = Regex("""^\s*(?:hey |ok |okay )?(?:$WORDS)\b[,.]?\s*|^\s*(?:हे\s+)?(?:$DEV_WORDS)[,.\u0964]?\s*""", RegexOption.IGNORE_CASE)
    private val HI_CAMERA = Regex("""ye kya hai|yeh kya hai|isko dekho|isse dekho|camera se dekh|photo kheench|यह क्या है|ये क्या है|इसे देखो|इसको देखो|कैमरा से देख|फोटो खींच""", RegexOption.IGNORE_CASE)
    private val HI_SCREEN = Regex("""screen par kya hai|screen pe kya hai|screen dekho|स्क्रीन पर क्या|स्क्रीन देख""", RegexOption.IGNORE_CASE)
    private val RESET = Regex("""\b(forget|clear|reset|restart)\b.*\b(conversation|chat|context|history)\b|\bnew (chat|conversation)\b""", RegexOption.IGNORE_CASE)
    private val NEEDS_AI = Regex("""\b(time|date|day)\b.*\b(in|at|on|for|tomorrow|yesterday|next|last)\b \w""", RegexOption.IGNORE_CASE)
    private val CAMERA_RX = Regex(
        """\b(look at (?:this|that|it)|what do you see|what am i (?:looking|pointing) at|describe (?:this|what you see)|""" +
            """identify (?:this|that)|read (?:this|the (?:text|sign|label|page|menu))|translate (?:this|the (?:text|sign|label|menu))|""" +
            """scan (?:this|that)|how do i look|(?:use|open|take a (?:photo|picture) with) (?:the )?camera|take a (?:photo|picture) and)\b""",
        RegexOption.IGNORE_CASE
    )
    private val SCREEN_RX = Regex(
        """\b(what(?:s| is) on (?:my )?screen|(?:read|look at|explain|describe) (?:my |the |this )?screen|""" +
            """summari[sz]e (?:my |the |this )?(?:screen|page)|what am i (?:reading|watching|looking at on))\b""",
        RegexOption.IGNORE_CASE
    )

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()

    // ---- state the UI observes ----
    val state = MutableStateFlow(OrbState.OFF)
    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val orbVisible = MutableStateFlow(false)
    val wakePulse = MutableStateFlow(0)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val wakeOn = MutableStateFlow(false)
    val voiceMode = MutableStateFlow(true)
    val brainLabel = MutableStateFlow("")

    @Volatile var appVisible = false

    /** Set by MainActivity: take a photo and hand back JPEG bytes + rotation. */
    var cameraHook: ((front: Boolean, cb: (ByteArray?, Int) -> Unit) -> Unit)? = null

    // ---- session bookkeeping (main thread) ----
    private var activeUntil = 0L
    private var busy = false
    private var commandMode = false
    private var woke = false
    private var errorStreak = 0
    private var lastHindi = false
    private var cooldownUntil = 0L   // wake detection stays off until this time (own TTS / launched app audio)
    private var wakeFails = 0        // consecutive Vosk start failures
    private val wakeRetry = Runnable { loopWake() }

    fun init(a: Application) {
        app = a
        voiceMode.value = Store.voiceReplies
        refreshBrain()
        push(Msg("${Commands.greeting()} JARVIS online. Say “Jarvis” or tap the orb.", false))
    }

    fun refreshBrain() {
        brainLabel.value = Brain.label
    }

    private fun push(m: Msg) {
        messages.value = (messages.value + m).takeLast(200)
    }

    private fun now() = System.currentTimeMillis()

    // ───────────────────────── wake-word loop ─────────────────────────
    fun startWake() {
        main.post {
            wakeOn.value = true
            Store.wakeEnabled = true
            if (state.value == OrbState.OFF) state.value = OrbState.IDLE
            loopWake()
        }
    }

    fun stopWake() {
        main.post {
            wakeOn.value = false
            if (!orbVisible.value && !busy) {
                Voice.stopListening()
                state.value = OrbState.OFF
            }
        }
    }

    fun setVoiceMode(on: Boolean) {
        main.post {
            voiceMode.value = on
            Store.voiceReplies = on
            if (!on) {
                Voice.stopListening()
                Voice.stopSpeaking()
            } else {
                loopWake()
            }
        }
    }

    private fun loopWake() {
        if (!wakeOn.value || !voiceMode.value || busy || commandMode || orbVisible.value || Voice.speaking) return
        val wait = cooldownUntil - now()
        if (wait > 0) {
            main.removeCallbacks(wakeRetry)
            main.postDelayed(wakeRetry, wait + 50)
            return
        }
        if (state.value == OrbState.OFF) state.value = OrbState.IDLE
        if (Store.offlineWake && WakeWord.ready() && wakeFails < 3) {
            // beep-free path: raw mic + tiny on-device model listening only for "jarvis"
            if (!WakeWord.running) {
                WakeWord.start(
                    onHit = { wakeFails = 0; onVoskHit() },
                    onFail = {
                        // mic still held by a recognizer that is shutting down: retry Vosk, don't start beeping
                        wakeFails++
                        main.removeCallbacks(wakeRetry)
                        main.postDelayed(wakeRetry, 1500L * wakeFails)
                    }
                )
            }
            return
        }
        Voice.listen(wakeListener)
    }

    private fun onVoskHit() {
        if (!wakeOn.value || busy || commandMode || orbVisible.value || now() < cooldownUntil) {
            loopWake()
            return
        }
        onWake()
        startCommand()
    }

    /** Re-evaluate which wake engine to use (after toggling the offline option or finishing the model download). */
    fun restartWake() {
        main.post {
            wakeFails = 0
            WakeWord.stop()
            Voice.stopListening()
            if (wakeOn.value) main.postDelayed({ loopWake() }, 300)
        }
    }

    private val wakeListener = object : Voice.Listener {
        override fun onPartial(text: String) {
            if (!woke && WAKE_RX.containsMatchIn(text)) {
                woke = true
                onWake()
            }
            if (woke) partial.value = STRIP_RX.replace(text, "").trim()
        }

        override fun onFinal(text: String) {
            errorStreak = 0
            val wasWoke = woke
            woke = false
            heard(text, wasWoke)
        }

        override fun onError(code: Int) {
            val wasWoke = woke
            woke = false
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wakeOn.value = false
                push(Msg("I lost microphone access. Allow it in Jarvis settings.", false))
                return
            }
            if (wasWoke && !busy && !commandMode) { sayYes(); return }
            val quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (quiet) errorStreak = 0 else errorStreak++
            val wait = when {
                quiet -> 200L
                code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                else -> minOf(5000L, 500L * errorStreak)
            }
            main.postDelayed({ loopWake() }, wait)
        }

        override fun onLevel(level: Float) {
            if (orbVisible.value) this@Jarvis.level.value = level
        }
    }

    private fun stripWake(t: String) = STRIP_RX.replace(t, "").trim().trim(',', '.', ' ')

    private fun heard(text: String, wasWoke: Boolean) {
        val has = WAKE_RX.containsMatchIn(text)
        if (!has && now() > activeUntil) {
            if (wasWoke) dismiss() else main.postDelayed({ loopWake() }, 150)
            return
        }
        if (!wasWoke) onWake()
        val cmd = stripWake(text)
        if (cmd.isEmpty()) sayYes() else submit(cmd, fromVoice = true)
    }

    private fun onWake() {
        activeUntil = now() + ACTIVE_WINDOW_MS
        state.value = OrbState.LISTENING
        partial.value = ""
        if (!orbVisible.value) showOrb()
    }

    private fun showOrb() {
        orbVisible.value = true
        wakePulse.value = wakePulse.value + 1
        bringToFront()
    }

    private fun bringToFront() {
        if (appVisible) return
        val i = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("wake", true)
        if (Settings.canDrawOverlays(app) && Device.launch(i)) return
        Notifier.wakeScreen(i)
    }

    private fun sayYes() {
        busy = true
        if (voiceMode.value) {
            state.value = OrbState.SPEAKING
            Voice.speak(if (lastHindi) "जी?" else "Yes?") { busy = false; startCommand() }
        } else {
            busy = false
            startCommand()
        }
    }

    // ───────────────────────── sessions ─────────────────────────
    private val commandListener = object : Voice.Listener {
        override fun onPartial(text: String) { partial.value = stripWake(text) }
        override fun onFinal(text: String) {
            commandMode = false
            val t = stripWake(text)
            if (t.isEmpty()) retryOrDismiss() else submit(t, fromVoice = true)
        }
        override fun onError(code: Int) {
            commandMode = false
            retryOrDismiss()
        }
        override fun onLevel(level: Float) { this@Jarvis.level.value = level }
    }

    private fun startCommand() {
        if (!voiceMode.value) { dismiss(); return }
        commandMode = true
        state.value = OrbState.LISTENING
        partial.value = ""
        Voice.listen(commandListener)
    }

    /** One recognition attempt per turn. Silence or an error ends the session (no restart loop, no repeated beeps). */
    private fun retryOrDismiss() {
        dismiss()
    }

    /** Tap on the orb / mic button. */
    fun manualListen() {
        main.post {
            if (!Voice.hasMic()) {
                push(Msg("I need the microphone permission. Open settings and tap Grant access.", false))
                return@post
            }
            Voice.stopListening()
            Voice.stopSpeaking()
            busy = false
            woke = false
            if (!voiceMode.value) setVoiceMode(true)
            activeUntil = now() + ACTIVE_WINDOW_MS
            orbVisible.value = true
            wakePulse.value = wakePulse.value + 1
            startCommand()
        }
    }

    /** Typed or spoken text goes through here. */
    fun submit(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        main.post {
            Voice.stopListening()
            Voice.stopSpeaking()
            commandMode = false
            busy = true
            push(Msg(t, true))
            state.value = OrbState.THINKING
            partial.value = ""
            activeUntil = now() + ACTIVE_WINDOW_MS
            if (fromVoice && !orbVisible.value) showOrb()
            scope.launch {
                val reply = try {
                    mutex.withLock { respond(t) }
                } catch (q: Quit) {
                    Reply("Going to sleep.", true)
                } catch (e: Exception) {
                    Reply("Something went wrong: ${e.message ?: e.javaClass.simpleName}.")
                }
                if (reply.text.isNotEmpty()) push(Msg(reply.text, false))
                deliver(reply)
            }
        }
    }

    private fun deliver(r: Reply) {
        val after = {
            busy = false
            activeUntil = now() + ACTIVE_WINDOW_MS
            when {
                r.end -> dismiss()
                orbVisible.value && voiceMode.value && appVisible -> startCommand()
                orbVisible.value && !voiceMode.value -> main.postDelayed({ if (!busy && !commandMode) dismiss() }, 3500)
                orbVisible.value -> dismiss()   // command moved the user to another app: end the session, back to wake word
                else -> {
                    state.value = if (wakeOn.value) OrbState.IDLE else OrbState.OFF
                    cooldownUntil = now() + COOLDOWN_MS
                    loopWake()
                }
            }
        }
        if (voiceMode.value && r.text.isNotEmpty()) {
            state.value = OrbState.SPEAKING
            Voice.speak(r.text) { after() }
        } else {
            after()
        }
    }

    fun dismiss() {
        main.post {
            Voice.stopListening()
            Voice.stopSpeaking()
            commandMode = false
            busy = false
            woke = false
            orbVisible.value = false
            partial.value = ""
            level.value = 0f
            state.value = if (wakeOn.value) OrbState.IDLE else OrbState.OFF
            cooldownUntil = now() + COOLDOWN_MS
            main.removeCallbacks(wakeRetry)
            main.postDelayed(wakeRetry, 500)
        }
    }

    /** Speak and show something unprompted (finished vision answers). */
    fun announce(text: String) {
        main.post {
            push(Msg(text, false))
            if (voiceMode.value) {
                val resume = wakeOn.value && !busy && !commandMode
                if (resume) Voice.stopListening()
                Voice.speak(text) { if (resume) { cooldownUntil = now() + COOLDOWN_MS; loopWake() } }
            }
        }
    }

    /** Reminders and timers: notification + chat + voice. */
    fun notifyUser(text: String) {
        Notifier.alert(text)
        announce(text)
    }

    // ───────────────────────── the router (jarvis2.respond) ─────────────────────────
    private suspend fun respond(text0: String): Reply {
        val text = LEAD_RX.replace(text0, "").trim()
        if (text.isEmpty()) return Reply("Yes?")
        val hindi = Hindi.isHindiLike(text)
        lastHindi = hindi
        Store.lastHindi = hindi
        val low = normalize(text)
        if (RESET.containsMatchIn(low)) {
            Brain.reset()
            return Reply("Conversation cleared.")
        }
        val wantsCamera = CAMERA_RX.containsMatchIn(low) || HI_CAMERA.containsMatchIn(low)
        val wantsScreen = SCREEN_RX.containsMatchIn(low) || HI_SCREEN.containsMatchIn(low)

        if (!Brain.enabled) {
            if (wantsCamera || wantsScreen) {
                return Reply(if (hindi) "देखने के लिए AI ब्रेन चाहिए। सेटिंग्स में API की डालिए।" else "Seeing needs the AI brain. Add an API key in Jarvis settings.")
            }
            if (hindi) return Reply(withContext(Dispatchers.IO) { Hindi.offline(text) })
            return Reply(withContext(Dispatchers.IO) { Commands.handle(text) })
        }
        if (wantsScreen) {
            val secs = Regex("in (\\d+) seconds?").find(low)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(30) ?: 6
            return screenVision(text, secs)
        }
        if (wantsCamera) {
            val front = Regex("""\b(selfie|front camera|how do i look|look at me)\b""").containsMatchIn(low)
            return Reply(cameraVision(text, front))
        }
        return withContext(Dispatchers.IO) {
            // fast path: short, clear commands run instantly and offline
            if (!hindi && low.split(" ").size <= Brain.FAST_PATH_WORDS && !NEEDS_AI.containsMatchIn(low)) {
                val out = Commands.tryCommands(text, setOf("_wiki"))
                if (out != null) {
                    Brain.rememberTurn(text, out)
                    return@withContext Reply(out)
                }
            }
            try {
                Reply(Brain.ask(text).ifEmpty { "I don't have an answer for that." })
            } catch (e: LLMError) {
                val fallback = if (hindi) Hindi.offlineOrNull(text) else Commands.tryCommands(text)
                when {
                    fallback != null -> Reply(fallback)
                    hindi -> Reply("AI से जुड़ नहीं पाया: ${e.message}")
                    else -> Reply("My AI link failed: ${e.message}")
                }
            }
        }
    }

    // ───────────────────────── vision ─────────────────────────
    private suspend fun visionAnswer(question: String, jpeg: ByteArray, tag: String): String =
        withContext(Dispatchers.IO) {
            try {
                Brain.askVision(question, jpeg, tag).ifEmpty { "I couldn't make out anything useful." }
            } catch (e: LLMError) {
                val hint = if (e.message?.contains("HTTP 4") == true) " If your model can't see images, set a vision model in settings." else ""
                "I couldn't analyse the image: ${e.message}$hint"
            }
        }

    private suspend fun cameraVision(question: String, front: Boolean): String {
        val hook = cameraHook ?: return "Open Jarvis on screen so I can use the camera."
        if (!Device.hasPerm(Manifest.permission.CAMERA)) return "I need the camera permission. Allow it in Jarvis settings."
        val res = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Pair<ByteArray?, Int>> { cont ->
                main.post { hook(front) { bytes, rot -> if (cont.isActive) cont.resume(bytes to rot) } }
            }
        } ?: return "I couldn't take a photo."
        val bytes = res.first ?: return "I couldn't take a photo."
        val jpeg = try {
            withContext(Dispatchers.IO) { shrinkJpeg(bytes, res.second) }
        } catch (e: LLMError) {
            return "I couldn't analyse the image: ${e.message}"
        }
        return visionAnswer(question, jpeg, "photo")
    }

    private fun screenVision(question: String, delaySec: Int): Reply {
        if (Build.VERSION.SDK_INT < 30) return Reply("Reading the screen needs Android 11 or newer.")
        val svc = JarvisAccess.instance
        if (svc == null) {
            Device.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            return Reply("Reading the screen needs the Jarvis accessibility service. I've opened the settings. Turn it on, then ask again.")
        }
        scope.launch {
            delay(delaySec * 1000L)
            svc.grab { bmp ->
                if (bmp == null) {
                    announce("I couldn't capture the screen.")
                } else {
                    scope.launch {
                        val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
                        announce(visionAnswer(question, jpeg, "screen"))
                    }
                }
            }
        }
        return Reply("Okay. Switch to the screen you want me to look at. Capturing in $delaySec seconds.", end = true)
    }
}
