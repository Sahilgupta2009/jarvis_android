package com.jarvis.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Thrown by the "exit / bye / go to sleep" command. Ends the current voice session. */
class Quit : Exception()

/** Anything that goes wrong talking to the AI provider. */
class LLMError(msg: String) : Exception(msg)

// ───────────────────────── text helpers ─────────────────────────
fun normalize(text: String): String {
    var t = text.lowercase().trim().replace("'", "").replace("\u2019", "")
    t = t.replace(Regex("[^\\p{L}\\p{M}\\p{N}_\\s.+\\-*/^%():]"), " ")
    return t.replace(Regex("\\s+"), " ").trim()
}

val FILLER = Regex("^(?:(?:hey|hi|ok|okay|please|can you|could you|would you|will you|i want you to|i want to|i need you to) )+")

fun short(text: String?, n: Int = 100): String {
    val t = (text ?: "").replace(Regex("\\s+"), " ").trim()
    return if (t.length <= n) t else t.substring(0, n - 1).trimEnd() + "…"
}

/** End a spoken fragment with exactly one full stop. */
fun sent(text: String): String {
    val t = text.trimEnd()
    return if (t.isNotEmpty() && t.last() in ".!?…") t else "$t."
}

fun titleCase(s: String): String =
    s.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

fun <T> namesOf(items: List<T>, n: Int = 3, key: (T) -> String?): String {
    val seen = ArrayList<String>()
    for (item in items) {
        val nm = key(item)
        if (!nm.isNullOrEmpty() && nm !in seen) seen.add(nm)
    }
    val shown = seen.take(n)
    if (shown.isEmpty()) return ""
    val head = if (shown.size > 1) shown.dropLast(1).joinToString(", ") + " and " else ""
    return head + shown.last()
}

fun plain(text: String): String {
    var t = text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "")
    t = t.replace(Regex("[*_`#>]+"), "")
    t = t.replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
    return t.replace(Regex("\\n{2,}"), "\n").trim()
}

/** difflib.SequenceMatcher.ratio() equivalent (Ratcliff/Obershelp) for fuzzy contact names. */
fun similarity(a: String, b: String): Double {
    val total = a.length + b.length
    if (total == 0) return 1.0
    return 2.0 * matchCount(a, b) / total
}

private fun matchCount(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var best = 0
    var ai = 0
    var bj = 0
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            if (a[i - 1] == b[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
                if (dp[i][j] > best) {
                    best = dp[i][j]
                    ai = i - best
                    bj = j - best
                }
            }
        }
    }
    if (best == 0) return 0
    return best + matchCount(a.substring(0, ai), b.substring(0, bj)) +
        matchCount(a.substring(ai + best), b.substring(bj + best))
}

// ───────────────────────── durations & dates ─────────────────────────
private val DUR = Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")

fun parseDuration(text: String): Int {
    var total = 0.0
    for (m in DUR.findAll(text)) {
        val n = m.groupValues[1].toDouble()
        val u = m.groupValues[2]
        total += n * (if (u[0] == 'h') 3600 else if (u[0] == 'm') 60 else 1)
    }
    return total.toInt()
}

fun humanDuration(sec: Int): String {
    val h = sec / 3600
    val mi = (sec % 3600) / 60
    val s = sec % 60
    val parts = listOf(
        if (h > 0) "$h hour${if (h != 1) "s" else ""}" else "",
        if (mi > 0) "$mi minute${if (mi != 1) "s" else ""}" else "",
        if (s > 0) "$s second${if (s != 1) "s" else ""}" else ""
    ).filter { it.isNotEmpty() }
    return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
}

private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
private const val AMPM = "(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"
private val NAMED_TIMES = linkedMapOf(
    "noon" to (12 to 0), "midnight" to (0 to 0), "morning" to (8 to 0), "afternoon" to (15 to 0),
    "evening" to (18 to 0), "night" to (21 to 0)
)

/** '6:30 am', 'tomorrow at 9', 'friday at 5 pm', 'in 20 minutes', 'tonight at 8' -> LocalDateTime (or null). */
fun parseWhen(text: String, allowRelative: Boolean = true, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
    val s = text.lowercase().trim().replace(Regex("^(?:at|on|for|in|after)\\s+"), "")
    if (Regex("\\d\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").containsMatchIn(s) &&
        !Regex("$AMPM|:\\d\\d").containsMatchIn(s)
    ) {
        if (!allowRelative) return null
        val sec = parseDuration(s)
        return if (sec > 0) now.plusSeconds(sec.toLong()) else null
    }

    var day: java.time.LocalDate? = null
    var weekdayNamed = false
    if ("tomorrow" in s) {
        day = now.toLocalDate().plusDays(1)
    } else if (Regex("\\b(today|tonight)\\b").containsMatchIn(s)) {
        day = now.toLocalDate()
    } else {
        for ((i, d) in DAYS.withIndex()) {
            if (d in s) {
                val wd = now.dayOfWeek.value - 1
                day = now.toLocalDate().plusDays(Math.floorMod(i - wd, 7).toLong())
                weekdayNamed = true
                break
            }
        }
    }

    val hintPm = Regex("\\b(tonight|evening|night|afternoon)\\b").containsMatchIn(s)
    val m1 = Regex("\\b(\\d{1,2}):(\\d{2})\\s*${AMPM}?").find(s)
    val m2 = Regex("\\b(\\d{1,2})\\s*${AMPM}").find(s)
    val m3 = Regex("\\bat (\\d{1,2})(?!\\d)").find(s)
    val m4 = Regex("(\\d{1,2})").matchEntire(s)

    var h = -1
    var mi = 0
    var ap = ""
    when {
        m1 != null -> { h = m1.groupValues[1].toInt(); mi = m1.groupValues[2].toInt(); ap = m1.groupValues[3] }
        m2 != null -> { h = m2.groupValues[1].toInt(); ap = m2.groupValues[2] }
        m3 != null -> { h = m3.groupValues[1].toInt() }
        m4 != null -> { h = m4.groupValues[1].toInt() }
    }

    val cands: List<Int>
    if (h >= 0) {
        ap = ap.replace(".", "").replace(" ", "").take(1)
        if (h > 23 || mi > 59) return null
        if (ap == "p" && h < 12) h += 12 else if (ap == "a" && h == 12) h = 0
        var explicit = ap.isNotEmpty() || h > 12 || h == 0
        if (!explicit && hintPm && h < 12) {
            h += 12
            explicit = true
        }
        var c = if (explicit) listOf(h) else (if (h < 12) listOf(h, h + 12) else listOf(h))
        if (day != null && !explicit) c = listOf(if (h in 1..6) h + 12 else h)
        cands = c
    } else {
        val nm = NAMED_TIMES.entries.firstOrNull { it.key in s }?.value
        if (nm == null && day == null) return null
        cands = listOf(nm?.first ?: 9)
        mi = nm?.second ?: 0
    }

    val baseDay = day ?: now.toLocalDate()
    val options = cands.map { LocalDateTime.of(baseDay, LocalTime.of(it % 24, mi)) }
    val key = { d: LocalDateTime -> d.atZone(ZoneId.systemDefault()).toEpochSecond() }
    if (day != null) {
        var future = options.filter { it.isAfter(now) }
        if (future.isEmpty() && weekdayNamed) future = options.map { it.plusDays(7) }
        return future.minByOrNull(key)
    }
    val future = options.filter { it.isAfter(now) }.ifEmpty { options.map { it.plusDays(1) } }
    return future.minByOrNull(key)
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun clockText(t: LocalTime = LocalTime.now()): String = t.format(TIME_FMT)

fun sayWhen(dt: LocalDateTime): String {
    val now = LocalDateTime.now()
    val t = dt.format(TIME_FMT)
    return when (dt.toLocalDate()) {
        now.toLocalDate() -> "today at $t"
        now.toLocalDate().plusDays(1) -> "tomorrow at $t"
        else -> "on ${dt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} at $t"
    }
}

// ───────────────────────── calculator (safe, no eval) ─────────────────────────
object Calc {
    private val FUNC_NAMES = Regex("sqrt|sin|cos|tan|log10|log|exp|factorial|radians|degrees|abs|round|pi")

    private fun wordsToMath(input: String): String {
        var s = input.replace(",", "")
        s = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%) of (\\d+(?:\\.\\d+)?)").replace(s, "($1/100)*$2")
        s = Regex("square root of (\\d+(?:\\.\\d+)?)").replace(s, "sqrt($1)")
        val rules = listOf(
            "\\bto the power of\\b|\\braised to\\b|\\^" to "**",
            "\\bsquared\\b" to "**2",
            "\\bcubed\\b" to "**3",
            "\\bplus\\b|\\band\\b" to "+",
            "\\bminus\\b|\\bsubtract\\b" to "-",
            "\\bmultiplied by\\b|\\btimes\\b|\\bx\\b" to "*",
            "\\bdivided by\\b|\\bover\\b" to "/",
            "\\bmod\\b|\\bmodulo\\b" to "%"
        )
        for ((pattern, rep) in rules) s = Regex(pattern).replace(s, Regex.escapeReplacement(rep))
        return s
    }

    fun calculate(expr: String): String? {
        val s = wordsToMath(expr)
        if (!Regex("\\d").containsMatchIn(s)) return null
        if (Regex("[a-z]{2,}").containsMatchIn(FUNC_NAMES.replace(s, ""))) return null
        val v = try { Parser(s).parse() } catch (e: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    private fun format(v: Double): String {
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val src: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (i != src.length) throw IllegalArgumentException("trailing input")
            return v
        }

        private fun skipWs() { while (i < src.length && src[i].isWhitespace()) i++ }
        private fun peek(): Char { skipWs(); return if (i < src.length) src[i] else '\u0000' }

        private fun expr(): Double {
            var v = term()
            var going = true
            while (going) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> going = false
                }
            }
            return v
        }

        private fun term(): Double {
            var v = unary()
            var going = true
            while (going) {
                skipWs()
                if (src.startsWith("//", i)) {
                    i += 2
                    v = floor(v / unary())
                } else if (src.startsWith("**", i)) {
                    going = false
                } else {
                    when (peek()) {
                        '*' -> { i++; v *= unary() }
                        '/' -> { i++; v /= unary() }
                        '%' -> { i++; val d = unary(); v = ((v % d) + d) % d }
                        else -> going = false
                    }
                }
            }
            return v
        }

        private fun unary(): Double {
            return when (peek()) {
                '-' -> { i++; -unary() }
                '+' -> { i++; unary() }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = atom()
            skipWs()
            if (src.startsWith("**", i)) {
                i += 2
                val e = unary()
                if (abs(e) > 1000) throw IllegalArgumentException("exponent too large")
                return base.pow(e)
            }
            return base
        }

        private fun atom(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                return src.substring(start, i).toDouble()
            }
            if (c.isLetter()) {
                val start = i
                while (i < src.length && (src[i].isLetterOrDigit())) i++
                val name = src.substring(start, i)
                if (peek() == '(') {
                    i++
                    val args = ArrayList<Double>()
                    if (peek() != ')') {
                        args.add(expr())
                        while (peek() == ',') { i++; args.add(expr()) }
                    }
                    if (peek() != ')') throw IllegalArgumentException("missing )")
                    i++
                    return call(name, args)
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("unknown name")
                }
            }
            throw IllegalArgumentException("unexpected token")
        }

        private fun call(name: String, a: List<Double>): Double {
            if (a.isEmpty()) throw IllegalArgumentException("no args")
            val x = a[0]
            return when (name) {
                "sqrt" -> sqrt(x)
                "sin" -> sin(x)
                "cos" -> cos(x)
                "tan" -> tan(x)
                "log" -> if (a.size > 1) ln(x) / ln(a[1]) else ln(x)
                "log10" -> log10(x)
                "exp" -> exp(x)
                "radians" -> Math.toRadians(x)
                "degrees" -> Math.toDegrees(x)
                "abs" -> abs(x)
                "round" -> if (a.size > 1) {
                    val f = 10.0.pow(a[1]); Math.rint(x * f) / f
                } else Math.rint(x)
                "factorial" -> {
                    if (x < 0 || x != Math.rint(x) || x > 170) throw IllegalArgumentException("bad factorial")
                    var r = 1.0
                    for (k in 2..x.toInt()) r *= k
                    r
                }
                else -> throw IllegalArgumentException("unknown function")
            }
        }
    }
}

// ───────────────────────── networking ─────────────────────────
object Net {
    const val UA = "JarvisAndroid/2.0 (personal assistant)"

    fun get(url: String, timeoutMs: Int = 10_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

fun getWeather(city: String, shortForm: Boolean = false): String {
    val fmt = if (shortForm) "%C,+%t,+feels+like+%f" else "%l:+%C,+%t,+feels+like+%f,+humidity+%h,+wind+%w"
    return try {
        Net.get("https://wttr.in/" + URLEncoder.encode(city, "UTF-8").replace("+", "%20") + "?format=" + fmt, 8000).trim()
    } catch (e: Exception) {
        ""
    }
}

/** First two sentences of a Wikipedia summary, or null if there's no clear article. */
fun wiki(topic: String): String? {
    val api = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
        URLEncoder.encode(topic.replace(" ", "_"), "UTF-8")
    val c = URL(api).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", Net.UA)
        if (c.responseCode == 404) return null
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val extract = j.optString("extract")
        if (j.optString("type") == "disambiguation" || extract.isEmpty()) return null
        return Regex("(?<=[.!?])\\s+").split(extract).take(2).joinToString(" ")
    } finally {
        c.disconnect()
    }
}

// ───────────────────────── images (for vision questions) ─────────────────────────
/** Decode, rotate, shrink to maxPx and re-encode as JPEG so uploads stay small and fast. */
fun shrinkJpeg(data: ByteArray, rotate: Int = 0, maxPx: Int = 1600): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0) throw LLMError("that wasn't a valid image.")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw LLMError("that wasn't a valid image.")
    return bitmapToJpeg(bmp, rotate, maxPx)
}

fun bitmapToJpeg(src: Bitmap, rotate: Int = 0, maxPx: Int = 1600): ByteArray {
    var bmp = src
    val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
    if (rotate != 0 || scale < 1f) {
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (rotate != 0) m.postRotate(rotate.toFloat())
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return out.toByteArray()
}
