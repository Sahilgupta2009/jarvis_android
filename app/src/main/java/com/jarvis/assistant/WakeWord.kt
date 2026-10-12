package com.jarvis.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Always-on "Jarvis" detector that reads the microphone directly (no SpeechRecognizer, so no beeps).
 * Uses the small Vosk English model (about 40 MB, downloaded once) restricted to the single word "jarvis".
 */
object WakeWord {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
    private val main = Handler(Looper.getMainLooper())

    /** Text for the settings screen: download progress or result. */
    val status = MutableStateFlow("")

    @Volatile var running = false
        private set
    @Volatile private var downloading = false
    private var worker: Thread? = null
    private var model: Model? = null

    private fun modelDir() = File(File(Device.app.filesDir, "vosk"), MODEL_DIR)

    fun ready(): Boolean = File(modelDir(), "am").exists()

    fun download(onDone: () -> Unit) {
        if (downloading) return
        downloading = true
        thread(name = "vosk-download") {
            try {
                status.value = "Downloading…"
                val dest = File(Device.app.filesDir, "vosk")
                dest.mkdirs()
                val tmp = File(dest, "model.zip")
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var got = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val p = (got * 100 / total).toInt()
                                if (p != last) { last = p; status.value = "Downloading… $p%" }
                            }
                        }
                    }
                }
                status.value = "Unpacking…"
                val root = dest.canonicalPath
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(dest, e.name)
                        if (!f.canonicalPath.startsWith(root)) throw IOException("bad zip entry")
                        if (e.isDirectory) {
                            f.mkdirs()
                        } else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { z.copyTo(it) }
                        }
                        e = z.nextEntry
                    }
                }
                tmp.delete()
                status.value = if (ready()) "Installed" else "Install failed"
            } catch (e: Exception) {
                status.value = "Download failed: ${e.message}"
            } finally {
                downloading = false
                main.post(onDone)
            }
        }
    }

    /** Start listening. [onHit] runs on the main thread when "jarvis" is heard; [onFail] if the mic or model can't start. */
    fun start(onHit: () -> Unit, onFail: () -> Unit) {
        if (running || !ready()) return
        running = true
        worker = thread(name = "wake-word") {
            var hit = false
            var failed = false
            var rec: AudioRecord? = null
            var recognizer: Recognizer? = null
            try {
                val m = model ?: Model(modelDir().absolutePath).also { model = it }
                recognizer = Recognizer(m, 16000.0f, "[\"jarvis\", \"[unk]\"]")
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096) * 2
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")
                rec.startRecording()
                val buf = ShortArray(2000)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val text = if (recognizer.acceptWaveForm(buf, n)) {
                        JSONObject(recognizer.result).optString("text")
                    } else {
                        JSONObject(recognizer.partialResult).optString("partial")
                    }
                    if (text.contains("jarvis")) {
                        hit = true
                        break
                    }
                }
            } catch (e: Throwable) {
                failed = running
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
                running = false
            }
            if (hit) main.post(onHit) else if (failed) main.post(onFail)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val w = worker
        if (w != null && Thread.currentThread() != w) {
            try { w.join(600) } catch (_: InterruptedException) {}
        }
    }
}
