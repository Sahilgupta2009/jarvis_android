package com.jarvis.assistant

import android.view.KeyEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Port of jarvis.py's command router. Rules are tried in registration order; the first one whose
 * handler returns a non-null reply wins. The order below matches the Python file.
 */
object Commands {
    class Rule(val rx: Regex, val name: String, val help: String?, val fn: (MatchResult) -> String?)

    val rules = ArrayList<Rule>()

    /** True while the LLM (not the user) is running a command: calls/texts then always ask for confirmation. */
    @Volatile var fromAi = false

    /** The original, un-normalised text of the current request. */
    @Volatile var raw = ""

    /** The normalised candidate the current rule matched against. */
    @Volatile var cand = ""

    @Volatile private var pendingFn: (() -> String)? = null
    @Volatile private var pendingUntil = 0L

    private const val WA = "whats ?app"
    private const val SAY = "(?:saying|that says|to say|that|:)"

    private val JOKES = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I would tell you a UDP joke, but you might not get it.",
        "There are 10 kinds of people: those who understand binary and those who don't.",
        "A SQL query walks into a bar, walks up to two tables and asks: can I join you?",
        "Why did the developer go broke? Because he used up all his cache.",
        "It works on my machine. Ship the machine."
    )

    private val TOGGLE_NAMES = mapOf(
        "wifi" to "wifi", "wi fi" to "wifi", "bluetooth" to "bluetooth", "mobile data" to "data",
        "cellular data" to "data", "data" to "data", "airplane mode" to "airplane", "airplane" to "airplane",
        "flight mode" to "airplane", "do not disturb" to "dnd", "dnd" to "dnd", "dark mode" to "dark",
        "dark theme" to "dark", "auto rotate" to "rotate", "autorotate" to "rotate", "auto rotation" to "rotate",
        "rotation" to "rotate", "screen rotation" to "rotate", "battery saver" to "saver",
        "power saving" to "saver", "power saving mode" to "saver"
    )

    // ───────────────────────── plumbing ─────────────────────────
    private fun cmd(name: String, pattern: String, help: String? = null, fn: (MatchResult) -> String?) {
        rules.add(Rule(Regex(pattern, RegexOption.IGNORE_CASE), name, help, fn))
    }

    private fun g(m: MatchResult, i: Int): String = m.groupValues.getOrElse(i) { "" }

    fun greeting(): String {
        val h = LocalTime.now().hour
        val part = if (h < 12) "morning" else if (h < 18) "afternoon" else "evening"
        return "Good $part, ${Store.user}."
    }

    /** Stage a risky action. It only runs if the user says yes within [ttl] seconds. */
    fun askConfirm(prompt: String, ttl: Int = 45, fn: () -> String): String {
        pendingFn = fn
        pendingUntil = System.currentTimeMillis() + ttl * 1000L
        return "$prompt Say yes to confirm."
    }

    /** Run the first matching rule. Returns its reply, or null if nothing matched. */
    fun tryCommands(text: String, skip: Set<String> = emptySet()): String? {
        raw = text
        val t = normalize(text)
        val stripped = FILLER.replace(t, "")
        val candidates = if (stripped.isNotEmpty() && stripped != t) listOf(t, stripped) else listOf(t)
        for (c in candidates) {
            cand = c
            for (r in rules) {
                if (r.name in skip) continue
                val m = r.rx.find(c) ?: continue
                val out = try {
                    r.fn(m)
                } catch (q: Quit) {
                    throw q
                } catch (e: Exception) {
                    "Something went wrong with that: ${e.message ?: e.javaClass.simpleName}."
                }
                if (out != null) return out
            }
        }
        return null
    }

    /** Return a response string. Throws [Quit] on exit commands. */
    fun handle(text: String): String {
        if (normalize(text).isEmpty()) return ""
        return tryCommands(text) ?: listOf(
            "I didn't catch a command in that. Say 'help' to see what I can do.",
            "I'm not sure how to do that yet. Try 'help'."
        ).random()
    }

    private fun rawTail(pattern: String, fallback: String): String =
        Regex(pattern, RegexOption.IGNORE_CASE).find(raw.trim())?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: fallback

    /** Message text keeps its original capitalisation and punctuation (normalize() would strip it). */
    private fun bodyFromRaw(fallback: String): String {
        val m = Regex("""\b(?:saying|that says|to say|that)\b[\s:,]*(.+)$""", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex(""":\s*(.+)$""").find(raw)
        var body = (m?.groupValues?.get(1) ?: fallback).trim()
        body = body.replace(Regex("^that\\s+", RegexOption.IGNORE_CASE), "")
        return if (body.isEmpty()) body else body.substring(0, 1).uppercase() + body.substring(1)
    }

    // ───────────────────────── handlers that need more than a one-liner ─────────────────────────
    private fun fromPerson(m: MatchResult): String? {
        val name = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: return null
        val contacts = Device.contacts() ?: emptyList()
        val contact = if (contacts.isNotEmpty()) Device.findContact(name, contacts).first else null
        val tail = contact?.number?.replace(Regex("\\D"), "")?.takeLast(10) ?: ""
        val label = contact?.name ?: name
        val parts = ArrayList<String>()
        val sms = Device.readSms(80)
        if (sms != null) {
            val mine = sms.filter {
                (tail.isNotEmpty() && it.number.replace(Regex("\\D"), "").takeLast(10) == tail) ||
                    Device.smsSender(it).lowercase().contains(name.lowercase())
            }
            mine.take(2).forEach { parts.add(sent("Text from $label: ${short(it.body, 140)}")) }
        }
        val notifs = Device.notifications()
        if (notifs != null) {
            val mine = notifs.filter {
                name.lowercase() in (it.title + " " + it.text).lowercase() ||
                    (contact != null && contact.name.lowercase() in it.title.lowercase())
            }
            mine.take(3).forEach { parts.add(sent("${it.app}: ${short(it.text, 140)}")) }
        }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        if (sms == null && notifs == null) return Device.NO_SMS
        return "I don't see any recent messages from $label."
    }

    private fun callsCmd(m: MatchResult): String? {
        val data = Device.readCalls() ?: return Device.NO_CALLS
        val missedOnly = g(m, 1) == "missed"
        val rows = data.filter { if (missedOnly) it.type == "MISSED" else (it.type == "MISSED" || it.type == "INCOMING") }
        if (rows.isEmpty()) return if (missedOnly) "No missed calls." else "No recent calls."
        val five = rows.take(5)
        val kind = if (missedOnly) "missed call" else "call"
        val who = { c: CallRow -> Device.contactName(c.number, c.name.ifEmpty { c.number.ifEmpty { "someone" } }) }
        return "${five.size} recent $kind${if (five.size != 1) "s" else ""}: " +
            five.joinToString(", ") { "${who(it)} (${it.type.lowercase()})" } + "."
    }

    private fun notifsCmd(m: MatchResult): String? {
        val src = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim()?.lowercase() ?: ""
        var items = Device.notifications() ?: return Device.NO_NOTIF
        if (src.isNotEmpty()) items = items.filter { src in it.app.lowercase() || src in it.title.lowercase() }
        if (items.isEmpty()) return if (src.isEmpty()) "No notifications." else "No notifications from $src."
        val head = "You have ${items.size} notification${if (items.size != 1) "s" else ""}. "
        return head + items.take(5).joinToString(" ") { i ->
            sent("${i.app}: ${i.title}${if (i.title.isNotEmpty() && i.text.isNotEmpty()) ": " else ""}${short(i.text, 90)}")
        }
    }

    private fun briefing(): String {
        val now = LocalDateTime.now()
        val ex = Executors.newSingleThreadExecutor()
        val weatherJob = ex.submit(Callable<String> { getWeather("", true) })
        val sms = try { Device.readSms() } catch (e: Exception) { null }
        val calls = try { Device.readCalls() } catch (e: Exception) { null }
        val notifs = try { Device.notifications() } catch (e: Exception) { null }
        val alarm = try { Device.nextAlarm() } catch (e: Exception) { null }
        val batt = try { Device.battery() } catch (e: Exception) { null }
        val weather = try { weatherJob.get(9, TimeUnit.SECONDS) } catch (e: Exception) { "" }
        ex.shutdown()

        val out = ArrayList<String>()
        out.add(
            "${greeting()} It's ${clockText()} on " +
                now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)) + "."
        )
        if (weather.isNotEmpty()) out.add("Weather: $weather.")
        if (alarm != null) out.add("Your next alarm is ${sayWhen(alarm)}.")

        val todos = Store.todos().filter { !it.done }.map { it.text }
        if (todos.isNotEmpty()) {
            out.add("You have ${todos.size} open task${if (todos.size != 1) "s" else ""}: " + todos.take(3).joinToString(", ") + ".")
        }
        val nowMs = System.currentTimeMillis()
        val soon = Store.reminders().filter { it.due - nowMs in 1..86_400_000 }
        if (soon.isNotEmpty()) {
            out.add(
                "Coming up: " + soon.take(3).joinToString(", ") {
                    "${it.text} ${sayWhen(LocalDateTime.ofInstant(Instant.ofEpochMilli(it.due), ZoneId.systemDefault()))}"
                } + "."
            )
        }
        val unread = (sms ?: emptyList()).filter { !it.read }
        if (unread.isNotEmpty()) {
            out.add("${unread.size} unread text${if (unread.size != 1) "s" else ""}, from ${namesOf(unread) { Device.smsSender(it) }}.")
        }
        val missed = (calls ?: emptyList()).filter { it.type == "MISSED" }.take(5)
        if (missed.isNotEmpty()) {
            out.add(
                "${missed.size} recent missed call${if (missed.size != 1) "s" else ""}, from " +
                    namesOf(missed) { Device.contactName(it.number, it.name.ifEmpty { "someone" }) } + "."
            )
        }
        val items = notifs ?: emptyList()
        if (items.isNotEmpty()) {
            out.add("${items.size} notification${if (items.size != 1) "s" else ""}, mostly from ${namesOf(items, 2) { it.app }}.")
        }
        if (batt != null && batt.first <= 30 && batt.second.contains("discharg")) out.add("Battery is low at ${batt.first} percent.")
        return out.joinToString(" ")
    }

    private fun remindAt(m: MatchResult): String? {
        val first = g(m, 1).isNotEmpty()
        val whenTxt = if (first) g(m, 1) else g(m, 4)
        val what = if (first) g(m, 2) else g(m, 3)
        val dt = parseWhen(whenTxt, allowRelative = false) ?: return null
        if (Regex("""\b(?:in|after) \d""").containsMatchIn(whenTxt)) return null
        Reminders.add(dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), what.trim(), "reminder")
        return "I'll remind you to ${what.trim()} ${sayWhen(dt)}."
    }

    private fun alarmCmd(m: MatchResult): String? {
        val first = g(m, 1).isNotEmpty()
        val whenTxt = if (first) g(m, 1) else g(m, 3)
        val label = if (first) g(m, 2) else ""
        val dt = parseWhen(whenTxt) ?: return "What time? Try 'set an alarm for 6:30 am'."
        if (dt.isAfter(LocalDateTime.now().plusHours(24))) {
            return "I can set alarms up to 24 hours ahead. For later, say 'remind me to ... on friday at 5 pm'."
        }
        if (!Device.setAlarm(dt, label)) return "Your clock app didn't take it."
        Thread.sleep(1200)
        val nxt = Device.nextAlarm()
        return "Alarm sent to your clock app for ${sayWhen(dt)}." +
            (if (nxt != null) " Your phone reports the next alarm as ${sayWhen(nxt)}." else "")
    }

    // ───────────────────────── registration (order matters) ─────────────────────────
    private fun smallTalk() {
        cmd("_hello", """^(hello|hi|hey|yo|good (afternoon|evening))( there)?( jarvis)?$""") { greeting() + " How can I help?" }
        cmd("_how", """\bhow are you\b""") { "All systems operational. Thank you for asking." }
        cmd("_who", """\bwho are you\b|\byour name\b""") { "I'm JARVIS version 2.0, your personal assistant, now living on your phone." }
        cmd("_thanks", """\b(thanks|thank you)\b""") { listOf("You're welcome.", "Anytime.", "My pleasure.").random() }
        cmd("_setname", """^(my name is|call me) (.+)$""") { m ->
            Store.user = titleCase(g(m, 2).trim())
            "Understood. I'll call you ${Store.user}."
        }
        cmd("_getname", """\bwhat('?s| is) my name\b|\bwho am i\b""") { "You are ${Store.user}." }
        cmd("_joke", """\b(joke|make me laugh)\b""", "tell me a joke") { JOKES.random() }
        cmd("_coin", """\b(flip|toss) a coin\b""", "flip a coin") { listOf("Heads.", "Tails.").random() }
        cmd("_dice", """\broll (a |an )?(\d+)?\s*(die|dice|d(\d+))\b""", "roll a dice / roll a d20") { m ->
            val sides = maxOf(g(m, 4).toIntOrNull() ?: 6, 2)
            "You rolled a ${(1..sides).random()}."
        }
        cmd("_rand", """\brandom number (?:between )?(\d+) (?:and|to) (\d+)\b""", "random number between 1 and 100") { m ->
            val a = g(m, 1).toLongOrNull() ?: 1L
            val b = g(m, 2).toLongOrNull() ?: 100L
            "${(minOf(a, b)..maxOf(a, b)).random()}."
        }
        cmd("_time", """\bwhat('?s| is)? the time\b|\btime (is it|now)\b|^time$|\bcurrent time\b""", "what's the time") {
            "It's ${clockText()}."
        }
        cmd("_date", """\b(today'?s? date|what('?s| is) the date|what day is it|date today|^date$)\b""", "what's the date") {
            "Today is " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)) + "."
        }
    }

    private fun deviceControl() {
        cmd("_battery", """^(?!.*\b(?:saver|saving)\b).*(?:\bbattery\b|\bcharge level\b|\bhow much charge\b)""", "battery status") {
            val b = Device.battery()
            if (b == null) "I couldn't read the battery status." else "Battery is at ${b.first} percent and ${b.second}."
        }
        cmd("_torch", """^(?=.*\b(flash ?light|torch)\b)(?=.*\b(on|off|enable|disable|activate|deactivate)\b)""", "flashlight on / off") {
            val state = if (Regex("""\b(off|disable|deactivate)\b""").containsMatchIn(cand)) "off" else "on"
            val err = Device.torch(state == "on")
            if (err != null) "The flashlight command failed: $err" else "Flashlight $state."
        }
        cmd("_volume", """\bvolume (?:to |at )?(\d+)""", "volume 50") { m ->
            Device.setVolume(g(m, 1).toIntOrNull() ?: 100)
            "Volume set to ${g(m, 1)} percent."
        }
        cmd("_brightness", """\bbrightness (?:to |at )?(\d+)""", "brightness 70") { m ->
            Device.setBrightness(g(m, 1).toIntOrNull() ?: 100) ?: "Brightness set to ${g(m, 1)} percent."
        }
        cmd("_clip_get", """\b(read|what('?s| is) in) (my |the )?clipboard\b""", "read clipboard") {
            Device.clipGet().ifBlank { "The clipboard is empty." }
        }
        cmd("_clip_set", """^copy (.+)$""", "copy <text>") { m ->
            Device.clipSet(rawTail("""^\s*(?:(?:hey|ok|okay|please)\s+)*copy\s+(.+)$""", g(m, 1)))
            "Copied."
        }
        cmd("_sysinfo", """\b(system|device) (info|status)\b|\bhow('?s| is) (the )?system\b""", "system info") { Device.sysInfo() }
    }

    private fun confirmations() {
        cmd("_yes", """^(?:yes|yeah|yep|yup|confirm|do it|go ahead|sure)(?: please)?$""") {
            val fn = pendingFn
            if (fn != null && System.currentTimeMillis() < pendingUntil) {
                pendingFn = null
                fn()
            } else null
        }
        cmd("_no", """^(?:no|nope|cancel that|never ?mind|don'?t|abort)$""") {
            if (pendingFn != null && System.currentTimeMillis() < pendingUntil) {
                pendingFn = null
                "Cancelled."
            } else null
        }
    }

    private fun phoneAndMessages() {
        cmd("_call", """^(?:(?:make a |place a )?(?:phone )?call (?:to )?|ring |dial )(?:up )?(.+?)(?: for me| please| now)*$""", "call ajay / call 9876543210") { m ->
            val r = Device.resolveContact(g(m, 1).trim())
            if (r.err != null) r.err else {
                val name = r.name ?: ""
                val number = r.number ?: ""
                if (fromAi) askConfirm("Call $name?") { Device.placeCall(number, name) } else Device.placeCall(number, name)
            }
        }
        cmd(
            "_wa_send",
            """^(?:send )?(?:an? )?(?:${WA})(?: message)?(?: to)? (.+?) ${SAY} (.+)$|^(?:send )?(?:an? )?message (?:to )?(.+?) (?:on|in|via|using) ${WA} (?:saying |that says |to say |that )?(.+)$""",
            "whatsapp ajay saying I'm late"
        ) { m ->
            val first = g(m, 1).isNotEmpty()
            val target = if (first) g(m, 1) else g(m, 3)
            val body = if (first) g(m, 2) else g(m, 4)
            val r = Device.resolveContact(target.trim())
            if (r.err != null) r.err else Device.openWhatsapp(r.number ?: "", r.name ?: "", bodyFromRaw(body))
        }
        cmd("_wa_chat", """^(?:open )?(?:${WA})(?: chat)?(?: with| to)? (.+)$""", "open whatsapp chat with ajay") { m ->
            val r = Device.resolveContact(g(m, 1).trim())
            if (r.err != null) r.err else Device.openWhatsapp(r.number ?: "", r.name ?: "")
        }
        cmd(
            "_sms",
            """^(?!.*whats ?app)(?:send )?(?:an? )?(?:sms|text(?: message)?|message)(?: to)? (.+?) (?:saying|that says|to say|that|:) (.+)$""",
            "text ajay saying I'm on my way"
        ) { m ->
            val r = Device.resolveContact(g(m, 1).trim())
            if (r.err != null) r.err else {
                val body = bodyFromRaw(g(m, 2))
                val name = r.name ?: ""
                val number = r.number ?: ""
                if (body.isEmpty()) "What should the message say?" else {
                    val go = { Device.sendSms(number, body, name) }
                    if (fromAi || Store.confirmSms) askConfirm("Text $name: \"$body\"?", fn = go) else go()
                }
            }
        }
        cmd("_sms_confirm", """^(?:turn )?(on|off) (?:text|sms|message)s? confirmation$|^(dont|do not) confirm (?:texts|sms|messages)$|^(confirm) (?:texts|sms|messages)$""") { m ->
            val off = g(m, 1) == "off" || g(m, 2).isNotEmpty()
            Store.confirmSms = !off
            if (off) "I'll send texts without asking first." else "I'll confirm texts before sending."
        }
        cmd("_cc", """^(?:set )?country code (?:to |is )?\+?(\d{1,3})$""", "set country code to 91") { m ->
            Store.countryCode = g(m, 1)
            "Country code set to plus ${g(m, 1)}."
        }
    }

    private fun readingPhoneData() {
        cmd(
            "_texts_unread",
            """^(?:(?:any|do i have(?: any)?|check(?: my)?|read(?: my)?|what are my) )?(?:new |unread )?(?:texts?|sms|messages?)$""",
            "any new texts"
        ) {
            val data = Device.readSms()
            if (data == null) Device.NO_SMS else {
                val unread = data.filter { !it.read }
                if (unread.isEmpty()) "No unread texts." else {
                    "You have ${unread.size} unread text${if (unread.size != 1) "s" else ""}. " +
                        unread.take(3).joinToString(" ") { sent("${Device.smsSender(it)}: ${short(it.body, 90)}") }
                }
            }
        }
        cmd(
            "_texts_last",
            """^(?:read |show |what(?:s| is) )?(?:my )?(?:last|latest|recent|newest) ?(\d+)? ?(texts?|sms|messages?)$""",
            "read my last text"
        ) { m ->
            val data = Device.readSms()
            if (data == null) Device.NO_SMS else {
                val n = if (g(m, 1).isNotEmpty()) minOf(g(m, 1).toIntOrNull() ?: 1, 5) else (if (g(m, 2).endsWith("s")) 3 else 1)
                if (data.isEmpty()) "Your inbox is empty." else
                    data.take(n).joinToString(" ") { sent("${Device.smsSender(it)}: ${short(it.body, 140)}") }
            }
        }
        cmd(
            "_from_person",
            """^(?:read |show )?(?:my )?(?:texts?|sms|messages?|anything) from (.+)$|^what (?:did|has) (.+?) (?:send|sent|say|said|text|texted|message|messaged)(?: me)?(?: on \w+)?$""",
            "texts from ajay / what did ajay send me"
        ) { m -> fromPerson(m) }
        cmd(
            "_calls",
            """^(?:(?:any|do i have(?: any)?|show|check) )?(?:my )?(missed|recent) calls?$|^who (?:called|rang|phoned)(?: me)?$|^(?:my )?last call$""",
            "any missed calls"
        ) { m -> callsCmd(m) }
        cmd(
            "_notifs",
            """^(?:read|check|list|summari[sz]e|show me)(?: all)?(?: my)? (?:new )?notifications?(?: from (.+))?$|^(?:any|do i have(?: any)?|what are my)(?: new)? ?notifications?(?: from (.+))?$|^notifications? from (.+)$""",
            "read my notifications / notifications from whatsapp"
        ) { m -> notifsCmd(m) }
        cmd("_debug_notifs", """^debug notifications?$""") {
            val items = Device.notifications()
            if (items == null) Device.NO_NOTIF else "${items.size} items. First: ${items.take(2)}"
        }
        cmd(
            "_briefing",
            """^(?:good morning(?: jarvis)?|morning|brief me|(?:give me |what(?:s| is) )?(?:my )?(?:daily )?(?:briefing|brief|summary)|what(?:s| is) my day(?: like)?|what(?:s| is) on today)$""",
            "good morning / brief me"
        ) { briefing() }
    }

    private fun phoneControl() {
        cmd(
            "_toggle",
            """^(?:please )?(turn|switch|set|enable|disable) (?:(on|off|to) )?(?:the |my )?(.+?)(?: (on|off))?$""",
            "turn off wifi / bluetooth / mobile data / airplane mode / dark mode / do not disturb"
        ) { m ->
            val verb = g(m, 1)
            val s1 = g(m, 2)
            val s2 = g(m, 4)
            val k = TOGGLE_NAMES[g(m, 3).trim()]
            if (k == null) null else {
                val state = when {
                    verb == "enable" -> "on"
                    verb == "disable" -> "off"
                    s1 == "to" -> "on"
                    s1.isNotEmpty() -> s1
                    else -> s2
                }
                if (state != "on" && state != "off") null else {
                    val on = state == "on"
                    if (fromAi && (k == "airplane" || k == "data" || (k == "wifi" && !on))) {
                        askConfirm("Turn ${Device.TOGGLE_LABELS[k]} $state?") { Device.toggle(k, on) }
                    } else Device.toggle(k, on)
                }
            }
        }
        cmd("_screenshot", """^(?:take (?:a )?)?screenshot$""", "take a screenshot") { Device.screenshot() }
        cmd("_lock", """^(?:lock|sleep)(?: the)?(?: phone| screen| device)?$|^(?:turn|switch) off (?:the )?screen$""", "lock the phone") {
            Device.lockScreen()
        }
        cmd("_wake", """^wake(?: up)?(?: the)?(?: phone| screen| device)$|^(?:turn|switch) on (?:the )?screen$""") { Device.wakeScreen() }
        cmd("_media_pp", """^(play|resume|pause)(?: the)?(?: music| media| song| video| playback)?$""", "pause / play / next song / previous song") { m ->
            Device.mediaKey(if (g(m, 1) == "pause") KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
            "Done."
        }
        cmd("_media_skip", """^(next|skip)(?: the)?(?: song| track| video)?$|^(previous|prev|last)(?: the)?(?: song| track| video)$""") { m ->
            Device.mediaKey(if (g(m, 1).isNotEmpty()) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "Done."
        }
        cmd("_nav", """^(?:go )?home(?: screen)?$|^go back$|^(?:show )?(?:recent apps|recents)$""") {
            Device.nav(if ("back" in cand) "back" else if ("recent" in cand) "recents" else "home")
        }
        cmd("_shade", """^(?:pull down|show|open) (?:the )?notifications?(?: panel| shade)?$|^(close|hide) (?:the )?notifications?(?: panel| shade)?$""") { m ->
            Device.nav(if (g(m, 1).isNotEmpty()) "collapse" else "shade")
        }
        cmd("_force_stop", """^(?:close|kill|force stop) (?!notifications?)(?:the )?(?:app )?(.+?)(?: app)?$""", "close whatsapp") { m ->
            val name = g(m, 1).trim()
            val pkg = Device.findPackage(name)
            if (pkg == null) "I couldn't find an app called $name." else {
                Device.killApp(pkg)
                "Closed $name."
            }
        }
    }

    private fun alarmsAppsSearch() {
        cmd(
            "_alarm",
            """^(?:set |create |add )?(?:an? )?alarm(?: for| at| in)? (.+?)(?: (?:called|named|labell?ed|saying) (.+))?$|^wake me(?: up)? (.+)$""",
            "set an alarm for 6:30 am / wake me up at 7"
        ) { m -> alarmCmd(m) }
        cmd("_next_alarm", """^(?:what(?:s| is) )?(?:my )?next alarm$|^when(?:s| is) my (?:next )?alarm$|^(?:show|check) (?:my )?alarms?$""", "what's my next alarm") {
            val nxt = Device.nextAlarm()
            if (nxt != null) "Your next alarm is ${sayWhen(nxt)}." else "You have no alarms set."
        }
        cmd(
            "_remind_at",
            """^remind me ((?:at|on|tomorrow|tonight|today|next) .+?) to (.+)$|^remind me (?:to )?(.+?) ((?:at|on|tomorrow|tonight|today|next|this) .+)$""",
            "remind me to call mom tomorrow at 9"
        ) { m -> remindAt(m) }
        cmd("_debug_apps", """^debug (?:apps|packages)(?: matching| for)? (.+)$""") { m ->
            val q = g(m, 1).lowercase().replace(" ", "")
            val found = Device.installedApps().filter { it.pkg.lowercase().contains(q) || it.label.lowercase().replace(" ", "").contains(q) }
            if (found.isNotEmpty()) "Installed apps matching: " + found.take(10).joinToString(", ") { "${it.label} (${it.pkg})" }
            else "None found. Either it isn't installed, or Android is hiding it from Jarvis."
        }
        cmd("_debug_open", """^debug (?:open|launch) (?:the )?(.+)$""") { m ->
            val name = g(m, 1).trim()
            val pkg = Device.findPackage(name)
            "$name (${pkg ?: "no package found"}): " + Device.openByName(name)
        }
        cmd(
            "_open",
            """^(?:open|launch|start)(?! (?:an? )?(?:timer|stopwatch|alarm))(?: up)? (?:the )?(?:app )?(.+?)(?: app)?$""",
            "open youtube / open whatsapp"
        ) { m -> Device.openByName(g(m, 1).trim()) }
        cmd("_search", """^(?:search|google|look up|search for) (.+?)(?: on (?:google|the web))?$""", "search <anything>") { m ->
            val q = g(m, 1).trim()
            val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            if (Device.openUrl(url)) "Searching for $q." else "I can't open a browser here. Search link: $url"
        }
        cmd("_weather", """\bweather(?: in| for| at)? ?(.*)$|\bhow('?s| is) the weather(?: in (.*))?$""", "weather in Delhi") { m ->
            var city = g(m, 1).ifEmpty { g(m, 3) }.trim()
            city = city.replace(Regex("^(today|now|outside|right now|like)$"), "").trim()
            getWeather(city).ifEmpty { "I couldn't reach the weather service. Check your connection." }
        }
        cmd("_calc", """^(?:calculate|compute|calc|solve|math|what is|what's|whats|how much is) (.+)$""", "calculate 12 times 8 / what is 20 percent of 150") { m ->
            val res = Calc.calculate(g(m, 1))
            if (res != null) "That's $res." else null
        }
    }

    private fun notesAndTodos() {
        cmd("_note_add", """^(?:note down|take a note|make a note|note|remember that|remember) (.+)$""", "note down buy milk") { m ->
            Store.addNote(rawTail("""^\s*(?:note down|take a note|make a note|note|remember that|remember)\s+(.+)$""", g(m, 1)))
            "Noted."
        }
        cmd("_note_list", """\b(read|show|list|what are) (my |the )?notes\b""", "read my notes") {
            val notes = Store.noteTexts()
            if (notes.isEmpty()) "You have no notes." else
                "Your notes: " + notes.withIndex().joinToString(" ") { (i, t) -> "${i + 1}. $t." }
        }
        cmd("_note_clear", """\b(clear|delete) (all )?(my |the )?notes\b""") {
            Store.clearNotes()
            "All notes cleared."
        }
        cmd("_todo_add", """^add (.+?) to (?:my |the )?(?:to ?do|task)s?(?: list)?$|^(?:todo|task) (.+)$""", "add gym to my todo list") { m ->
            val item = g(m, 1).ifEmpty { g(m, 2) }
            Store.addTodo(item)
            "Added $item to your to-do list."
        }
        cmd("_todo_list", """\b(show|read|list|what are) (my |the )?(to ?do|tasks?)s?\b""", "show my todos") {
            val todos = Store.todos()
            if (todos.isEmpty()) "Your to-do list is empty." else
                "Your tasks: " + todos.withIndex().joinToString(" ") { (i, t) -> "${i + 1}. ${t.text}${if (t.done) " (done)" else ""}." }
        }
        cmd("_todo_done", """\b(?:done with|complete|finish|finished|tick) (?:task |todo )?(\d+)\b""", "complete task 2") { m ->
            val text = Store.completeTodo((g(m, 1).toIntOrNull() ?: 0) - 1)
            if (text != null) "Marked $text as done." else "There's no task with that number."
        }
        cmd("_todo_clear", """\b(clear|delete) (all )?(my |the )?(to ?do|tasks?)s?\b""") {
            Store.clearTodos()
            "To-do list cleared."
        }
    }

    private fun timersAndReminders() {
        cmd("_remind", """\bremind me (?:to )?(.+?) (?:in|after) (.+)$|\bremind me (?:in|after) (.+?) (?:to )(.+)$""", "remind me to stretch in 20 minutes") { m ->
            val first = g(m, 1).isNotEmpty()
            val what = if (first) g(m, 1) else g(m, 4)
            val dur = if (first) g(m, 2) else g(m, 3)
            val sec = parseDuration(dur)
            if (sec == 0) "How long from now? Try 'remind me to call mom in 10 minutes'." else {
                Reminders.add(System.currentTimeMillis() + sec * 1000L, "Reminder: $what", "timer")
                "I'll remind you to $what in ${humanDuration(sec)}."
            }
        }
        cmd("_timer", """\b(?:set |start )?(?:a |an )?(?:timer|alarm)(?: for| of)? (.+)$|\b(?:set |start )?(?:a )?(.+?) timer$""", "set a timer for 5 minutes") { m ->
            val sec = parseDuration(g(m, 1).ifEmpty { g(m, 2) })
            if (sec == 0) "For how long? Try 'set a timer for 5 minutes'." else {
                Reminders.add(System.currentTimeMillis() + sec * 1000L, "Your timer for ${humanDuration(sec)} is done", "timer")
                "Timer set for ${humanDuration(sec)}."
            }
        }
        cmd("_timers_list", """\b(list|show|what are|how long|any) (my |the )?(timers?|reminders?)\b|\btimers?\b.*\b(left|remaining)\b""") {
            val now = System.currentTimeMillis()
            val parts = ArrayList<String>()
            for (r in Store.reminders()) {
                if (r.due <= now) continue
                if (r.kind == "timer") {
                    parts.add("${r.text.trimEnd('.')}: ${humanDuration(((r.due - now) / 1000).toInt())} left.")
                } else {
                    parts.add("Reminder to ${r.text} ${sayWhen(LocalDateTime.ofInstant(Instant.ofEpochMilli(r.due), ZoneId.systemDefault()))}.")
                }
            }
            if (parts.isEmpty()) "No active timers or reminders." else parts.joinToString(" ")
        }
        cmd("_timers_cancel", """\b(cancel|clear|stop) (all |my )?(timers?|reminders?)\b""") {
            Reminders.cancelAll()
            "All timers and reminders cancelled."
        }
    }

    private fun metaAndFallbacks() {
        cmd("_textmode", """\bswitch to text( mode)?\b|\btext mode\b""") {
            Jarvis.setVoiceMode(false)
            "Switched to text mode."
        }
        cmd("_voicemode", """\bswitch to voice( mode)?\b|\bvoice mode\b""") {
            Jarvis.setVoiceMode(true)
            "Voice mode on. Say 'jarvis' before commands."
        }
        cmd("_help", """\b(help|what can you do|commands)\b""") {
            "Here's what you can say: " + rules.mapNotNull { it.help }.joinToString("; ") + "."
        }
        cmd("_exit", """^(exit|quit|bye|goodbye|shut down|shutdown|go to sleep|stop listening)\b""") { throw Quit() }
        // ---- fallbacks (must stay LAST) ----
        cmd("_bare_math", """^(\(?\d.*|square root of .+)$""") { m ->
            val res = Calc.calculate(g(m, 1))
            if (res != null) "That's $res." else null
        }
        cmd(
            "_wiki",
            """^(?:who is|whos|who was|what is|whats|what was|what are|tell me about|wikipedia|explain) (?:a |an |the )?(.+)$""",
            "who is Elon Musk / tell me about black holes"
        ) { m ->
            val topic = g(m, 1).trim()
            try {
                wiki(topic) ?: wiki(titleCase(topic)) ?: "I couldn't find a clear answer for $topic."
            } catch (e: Exception) {
                "I couldn't reach Wikipedia right now."
            }
        }
    }

    init {
        smallTalk()
        deviceControl()
        confirmations()
        phoneAndMessages()
        readingPhoneData()
        phoneControl()
        alarmsAppsSearch()
        notesAndTodos()
        timersAndReminders()
        metaAndFallbacks()
    }
}
