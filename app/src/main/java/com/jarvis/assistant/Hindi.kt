package com.jarvis.assistant

/**
 * Hindi / Hinglish support.
 *  - detects Hindi (Devanagari) and Hinglish (Hindi in Roman letters)
 *  - offline fallback: turns a Hindi/Hinglish request into the English command the router already understands
 *  - localises the router's fixed English replies back into Hindi
 * The AI brain does the real understanding; this file is what keeps things working with no key or no internet.
 */
object Hindi {
    private val DEV_RANGE = '\u0900'..'\u097F'
    private val SPLIT = Regex("[\\s,.;:!?\u0964|\"'()]+")

    const val NOT_UNDERSTOOD = "माफ़ कीजिए, मैं समझ नहीं पाया। सेटिंग्स में AI ब्रेन चालू करें, या आसान शब्दों में दोबारा बोलिए।"

    fun hasDevanagari(s: String) = s.any { it in DEV_RANGE }

    /** Common Hindi words written in Roman letters. One hit is enough to treat the sentence as Hinglish. */
    private val MARKERS = setOf(
        "karo", "kardo", "krdo", "kro", "kijiye", "kariye", "lagao", "lagana", "chalu", "chaalu", "chalao", "kholo", "khol",
        "bolo", "batao", "bata", "mujhe", "mujhko", "mera", "meri", "mere", "hai", "hain", "kya", "kaise", "kab", "kahan",
        "kitna", "kitne", "aaj", "kal", "subah", "shaam", "baje", "bhejo", "bhej", "sunao", "dikhao", "padho", "likho",
        "yaad", "dilana", "dilao", "mausam", "nahi", "nahin", "haan", "haanji", "theek", "zaroor", "abhi", "jaldi", "ko",
        "ka", "ki", "ke", "mein", "aur", "tum", "aap", "mat", "chahiye", "raha", "rahi", "wala", "wali", "gaana", "gaane",
        "shukriya", "dhanyavaad", "namaste", "kaun", "kyun", "kyu", "yaar", "bhai", "dost"
    )

    fun isHindiLike(text: String): Boolean {
        if (hasDevanagari(text)) return true
        return text.lowercase().split(Regex("[^a-z]+")).any { it in MARKERS }
    }

    // ───────────────────────── Devanagari -> Roman ─────────────────────────
    private fun norm(s: String) = s.replace("\u093C", "").replace('\u0901', '\u0902')

    private val CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
        'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r",
        'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y"
    )
    private val VOW = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai",
        'ओ' to "o", 'औ' to "au", 'ऑ' to "o"
    )
    private val MATRA = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ॉ' to "o", 'ृ' to "ri"
    )
    private const val VIRAMA = '\u094D'

    private fun translit(w: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val cons = CONS[c]
            if (cons != null) {
                sb.append(cons)
                var j = i + 1
                val nx = if (j < w.length) w[j] else ' '
                if (nx == VIRAMA) {
                    j++
                } else if (MATRA.containsKey(nx)) {
                    sb.append(MATRA[nx])
                    j++
                } else if (j < w.length) {
                    sb.append('a') // schwa kept inside a word, dropped at the end
                }
                i = j
            } else {
                val v = VOW[c]
                if (v != null) {
                    sb.append(v)
                } else if (c == '\u0902') {
                    sb.append('n')
                } else if (c == '\u0903') {
                    sb.append('h')
                } else if (c !in DEV_RANGE) {
                    sb.append(c)
                }
                i++
            }
        }
        return sb.toString()
    }

    /** Devanagari loan words and the common verbs, mapped straight to the Roman keyword the rules look for. */
    private val DEV_WORDS: Map<String, String> = buildMap {
        fun p(roman: String, vararg dev: String) { for (d in dev) put(norm(d), roman) }
        // verbs & particles
        p("kar", "कर"); p("karo", "करो", "कीजिए", "कीजिये", "करिए"); p("karna", "करना")
        p("do", "दो"); p("de", "दे"); p("dena", "देना"); p("dijiye", "दीजिए")
        p("laga", "लगा"); p("lagao", "लगाओ", "लगाइए"); p("lagana", "लगाना")
        p("khol", "खोल"); p("kholo", "खोलो", "खोलिए", "खोलिये")
        p("chalu", "चालू", "चालु"); p("chalao", "चलाओ", "चलाइए"); p("chala", "चला"); p("shuru", "शुरू", "शुरु")
        p("band", "बंद", "बन्द", "बंध"); p("on", "ऑन", "ओन"); p("off", "ऑफ", "ओफ", "ऑफ़")
        p("jala", "जला"); p("jalao", "जलाओ", "जलाइए"); p("bujhao", "बुझाओ"); p("bujha", "बुझा")
        p("sunao", "सुनाओ", "सुनाइए"); p("batao", "बताओ", "बताइए", "बताना"); p("dikhao", "दिखाओ", "दिखाइए")
        p("padho", "पढ़ो", "पढ़िए", "पढ़ना", "पढ़"); p("bhejo", "भेजो", "भेजिए"); p("bhej", "भेज"); p("likho", "लिखो", "लिखिए"); p("likh", "लिख")
        p("yaad", "याद"); p("dilana", "दिलाना"); p("dilao", "दिलाओ", "दिलाइए")
        p("bolo", "बोलो", "बोलिए"); p("khojo", "खोजो"); p("dhundho", "ढूंढो", "ढूँढो")
        p("roko", "रोको"); p("ruko", "रुको"); p("agla", "अगला"); p("pichla", "पिछला"); p("peeche", "पीछे"); p("wapas", "वापस")
        p("jao", "जाओ"); p("so", "सो")
        // postpositions & pronouns
        p("ko", "को"); p("ka", "का"); p("ki", "की", "कि"); p("ke", "के"); p("mein", "में"); p("me", "मे"); p("par", "पर"); p("pe", "पे")
        p("se", "से"); p("aur", "और"); p("mujhe", "मुझे"); p("mujhko", "मुझको"); p("mera", "मेरा"); p("meri", "मेरी"); p("mere", "मेरे")
        p("main", "मैं"); p("hai", "है"); p("hain", "हैं"); p("kya", "क्या"); p("kab", "कब"); p("kaun", "कौन"); p("kitna", "कितना"); p("kitne", "कितने")
        p("aaj", "आज"); p("kal", "कल"); p("abhi", "अभी"); p("baad", "बाद"); p("baje", "बजे"); p("subah", "सुबह"); p("shaam", "शाम"); p("raat", "रात"); p("dopahar", "दोपहर")
        p("haan", "हाँ", "हां", "हाँजी", "हांजी"); p("nahi", "नहीं", "नही"); p("theek", "ठीक"); p("zaroor", "ज़रूर", "जरूर"); p("mat", "मत")
        p("minute", "मिनट", "मिनिट"); p("ghanta", "घंटा"); p("ghante", "घंटे", "घण्टे"); p("second", "सेकंड", "सेकेंड", "सेकण्ड")
        p("shukriya", "शुक्रिया"); p("dhanyavaad", "धन्यवाद"); p("thanks", "थैंक्स", "थैंक्यू"); p("namaste", "नमस्ते"); p("alvida", "अलविदा"); p("bye", "बाय")
        p("hota", "होता"); p("kaam", "काम"); p("naam", "नाम"); p("tum", "तुम"); p("aap", "आप")
        // gadgets & apps
        p("torch", "टॉर्च", "टार्च", "टोर्च"); p("flashlight", "फ्लैशलाइट", "फ़्लैशलाइट", "फ्लैश"); p("battery", "बैटरी", "बेटरी"); p("charge", "चार्ज")
        p("volume", "वॉल्यूम", "वोल्यूम", "आवाज़", "आवाज"); p("brightness", "ब्राइटनेस", "रोशनी")
        p("call", "कॉल", "कोल"); p("phone", "फोन", "फ़ोन"); p("message", "मैसेज", "मेसेज", "संदेश"); p("sms", "एसएमएस"); p("calls", "कॉल्स")
        p("whatsapp", "व्हाट्सएप", "वाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वॉट्सएप", "व्हाट्सएप्प", "व्हॉट्सऐप")
        p("youtube", "यूट्यूब", "यूटयूब"); p("chrome", "क्रोम"); p("camera", "कैमरा", "कैमेरा"); p("settings", "सेटिंग्स", "सेटिंग", "सेटिंग्ज")
        p("gallery", "गैलरी"); p("calculator", "कैलकुलेटर", "कैल्कुलेटर"); p("instagram", "इंस्टाग्राम"); p("telegram", "टेलीग्राम")
        p("gmail", "जीमेल"); p("maps", "मैप्स", "नक्शा"); p("spotify", "स्पॉटिफाई"); p("netflix", "नेटफ्लिक्स"); p("google", "गूगल", "गुगल")
        p("alarm", "अलार्म"); p("timer", "टाइमर"); p("reminder", "रिमाइंडर"); p("remind", "रिमाइंड")
        p("note", "नोट"); p("notes", "नोट्स"); p("todo", "टूडू", "टू-डू"); p("notification", "नोटिफिकेशन"); p("notifications", "नोटिफिकेशंस", "नोटिफिकेशन्स")
        p("missed", "मिस्ड"); p("miss", "मिस")
        p("wifi", "वाईफाई", "वाई-फाई", "वाईफ़ाई", "वायफाय", "वाईफ़ाइ"); p("bluetooth", "ब्लूटूथ", "ब्लूटुथ"); p("data", "डेटा", "डाटा"); p("mobile", "मोबाइल")
        p("airplane", "एयरप्लेन", "एरोप्लेन"); p("flight", "फ्लाइट"); p("mode", "मोड"); p("dark", "डार्क"); p("screen", "स्क्रीन"); p("screenshot", "स्क्रीनशॉट")
        p("lock", "लॉक"); p("music", "म्यूजिक", "म्यूज़िक"); p("gaana", "गाना"); p("gaane", "गाने"); p("play", "प्ले"); p("pause", "पॉज़", "पॉज"); p("next", "नेक्स्ट")
        p("home", "होम"); p("back", "बैक"); p("mausam", "मौसम"); p("samay", "समय"); p("time", "टाइम"); p("tarikh", "तारीख", "तारीख़")
        p("joke", "जोक"); p("chutkula", "चुटकुला"); p("help", "हेल्प"); p("madad", "मदद"); p("briefing", "ब्रीफिंग"); p("good", "गुड"); p("morning", "मॉर्निंग")
        p("search", "सर्च"); p("plus", "प्लस"); p("minus", "माइनस"); p("times", "टाइम्स"); p("jod", "जोड़", "जोड़ो"); p("ghata", "घटा"); p("guna", "गुना"); p("bhag", "भाग")
        p("mummy", "मम्मी", "मां", "माँ", "मम्मा"); p("papa", "पापा", "पापाजी"); p("bhai", "भाई"); p("didi", "दीदी"); p("dost", "दोस्त")
        // numbers
        p("1", "एक"); p("3", "तीन"); p("4", "चार"); p("5", "पांच", "पाँच"); p("6", "छह", "छः", "छे"); p("7", "सात"); p("8", "आठ"); p("9", "नौ")
        p("10", "दस"); p("15", "पंद्रह"); p("20", "बीस"); p("25", "पच्चीस"); p("30", "तीस"); p("40", "चालीस"); p("50", "पचास"); p("60", "साठ")
        p("70", "सत्तर"); p("80", "अस्सी"); p("90", "नब्बे"); p("100", "सौ")
        // cities
        p("delhi", "दिल्ली"); p("mumbai", "मुंबई"); p("kolkata", "कोलकाता"); p("chennai", "चेन्नई"); p("bengaluru", "बेंगलुरु", "बैंगलोर")
        p("hyderabad", "हैदराबाद"); p("pune", "पुणे"); p("jaipur", "जयपुर"); p("lucknow", "लखनऊ"); p("ahmedabad", "अहमदाबाद"); p("patna", "पटना")
        p("bhopal", "भोपाल"); p("chandigarh", "चंडीगढ़", "चंडीगढ")
    }

    /** Splits into (original word, Roman keyword) pairs. */
    private fun tokens(text: String): List<Pair<String, String>> =
        text.trim().split(SPLIT).filter { it.isNotEmpty() }.map { tok ->
            val low = tok.lowercase()
            if (hasDevanagari(low)) {
                val n = norm(low).map { c -> if (c in '०'..'९') '0' + (c - '०') else c }.joinToString("")
                tok to (DEV_WORDS[n] ?: translit(n))
            } else {
                tok to low
            }
        }

    // ───────────────────────── Hindi / Hinglish -> English command ─────────────────────────
    private const val FILLER =
        "mujhe|mujhko|mera|meri|mere|ko|ka|ki|ke|mein|me|par|pe|se|hai|hain|ho|hoga|karo|kar|kardo|kro|krdo|do|de|dena|dijiye|kijiye|" +
            "kariye|karna|zara|please|plz|bhai|yaar|jarvis|ek|ye|yeh|is|isko|bas|aap|tum|zaroor|abhi|jaldi|laga|lagao|lagana"

    private fun durationPhrase(r: String): String? {
        val parts = ArrayList<String>()
        for (m in Regex("(\\d+(?:\\.\\d+)?)\\s*(minutes?|mins?|mint|ghanta|ghante|hours?|hrs?|seconds?|secs?)\\b").findAll(r)) {
            val u = m.groupValues[2]
            val unit = when {
                u.startsWith("min") -> "minutes"
                u.startsWith("gh") || u.startsWith("h") -> "hours"
                else -> "seconds"
            }
            parts.add("${m.groupValues[1]} $unit")
        }
        if (parts.isEmpty() && Regex("\\b(aadha|adha|half) (ghanta|ghante|hour)\\b").containsMatchIn(r)) parts.add("30 minutes")
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /** Returns (isTomorrow, "6", "am") style clock text, e.g. "6:30 pm". */
    private fun clockPhrase(r: String): Pair<Boolean, String>? {
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(?:baje|bje|am|pm)?").find(r) ?: return null
        val pm = Regex("\\b(shaam|sham|raat|dopahar|evening|night|afternoon|pm)\\b").containsMatchIn(r)
        val am = Regex("\\b(subah|savere|morning|am)\\b").containsMatchIn(r)
        val tomorrow = Regex("\\b(kal|tomorrow)\\b").containsMatchIn(r)
        val min = if (m.groupValues[2].isNotEmpty()) ":" + m.groupValues[2] else ""
        val suffix = if (pm) " pm" else if (am) " am" else ""
        return tomorrow to (m.groupValues[1] + min + suffix)
    }

    /** The original words of the request with filler/keywords removed. */
    private fun rest(toks: List<Pair<String, String>>, drop: String): String {
        val rx = Regex("^(?:$drop|$FILLER)$")
        return toks.filter { !rx.matches(it.second) }.joinToString(" ") { it.first }.trim()
    }

    private fun nameBefore(r: String, vararg after: String): String? {
        val m = Regex("^(?:(?:mujhe|mera|meri|please|zara)\\s+)*(.+?)\\s+ko\\s+(?:${after.joinToString("|")})\\b").find(r) ?: return null
        return m.groupValues[1].replace(Regex("\\b(?:$FILLER)\\b"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    fun toCommand(text: String): String? {
        val toks = tokens(text)
        if (toks.isEmpty()) return null
        val r = toks.joinToString(" ") { it.second }
            .replace(Regex("\\bdo (?=(?:minutes?|mins?|mint|ghanta|ghante|seconds?|secs?|baje)\\b)"), "2 ")
        fun has(rx: String) = Regex(rx).containsMatchIn(r)
        val on = has("\\b(on|chalu|chaalu|shuru|jala|jalao|jalana|enable|activate|start)\\b")
        val off = has("\\b(off|band|bandh|bujha|bujhao|bujhana|disable|deactivate|stop)\\b")
        val state = if (off && !on) "off" else "on"

        // yes / no / exit / small talk
        if (Regex("^(?:haan|haa|han|haanji|ha ji|haan ji|ji haan|ji|theek hai|thik hai|ok|okay|zaroor|bilkul|yes|kar do|kardo|kar de)(?: ji| kar do| kardo)?$").matches(r)) return "yes"
        if (Regex("^(?:nahi|nahin|nai|na|mat karo|mat kar|rehne do|rahne do|cancel|abort)(?: ji)?$").matches(r)) return "no"
        if (has("\\b(alvida|bye|so jao|so ja|good bye|goodbye)\\b")) return "bye"
        if (has("\\b(shukriya|dhanyavaad|dhanyavad|thanks|thank you)\\b")) return "thank you"
        if (Regex("^(?:namaste|namaskar|hello|hi|hey|salaam|ram ram)(?: jarvis)?$").matches(r)) return "hello"
        if (has("\\b(kaise ho|kaisa hai|kaise hain|how are you)\\b")) return "how are you"
        if (has("\\b(tum kaun ho|aap kaun hain|aap kaun ho|tumhara naam|tera naam|aapka naam)\\b")) return "who are you"
        Regex("^(?:mera naam|my name is)\\s+(.+?)(?:\\s+hai)?$").find(r)?.let { return "my name is ${it.groupValues[1]}" }

        // toggles (before battery / torch so "battery saver chalu karo" works)
        val toggleNames = listOf(
            "wifi|wi fi" to "wifi", "bluetooth" to "bluetooth", "mobile data|data" to "mobile data",
            "airplane|flight mode|flight" to "airplane mode", "dnd|do not disturb|disturb" to "do not disturb",
            "dark mode|dark" to "dark mode", "rotate|rotation|autorotate" to "auto rotate", "battery saver|power saving|saver" to "battery saver"
        )
        for ((rx, name) in toggleNames) {
            if (has("\\b($rx)\\b") && (on || off)) return "turn $state $name"
        }

        if (has("\\b(torch|flashlight|flash light|tourch)\\b")) return "turn $state the flashlight"
        Regex("\\b(?:volume|awaaz|avaz|sound)\\b.*?(\\d+)").find(r)?.let { return "volume ${it.groupValues[1]}" }
        Regex("\\b(?:brightness|roshni)\\b.*?(\\d+)").find(r)?.let { return "brightness ${it.groupValues[1]}" }

        // alarms, timers, reminders (before "time" so "6 baje" isn't read as a time question)
        if (has("\\balarm\\b")) {
            val clock = clockPhrase(r)
            val dur = durationPhrase(r)
            if (dur != null) return "set an alarm in $dur"
            if (clock != null && has("\\d")) return "set an alarm for ${if (clock.first) "tomorrow " else ""}${clock.second}"
            return "what's my next alarm"
        }
        if (has("\\btimer\\b")) {
            val dur = durationPhrase(r) ?: return null
            return "set a timer for $dur"
        }
        if (has("\\b(yaad|remind|reminder)\\b")) {
            val what = rest(toks, "yaad|dilana|dilao|dila|dilado|remind|reminder|baad|baje|subah|shaam|raat|kal|aaj|dopahar|minutes?|mins?|mint|ghanta|ghante|hours?|seconds?|secs?|am|pm|\\d+(?::\\d+)?|set|a|an|new")
            if (what.isEmpty()) return null
            val dur = durationPhrase(r)
            if (dur != null) return "remind me to $what in $dur"
            val clock = clockPhrase(r)
            if (clock != null && has("\\d")) return "remind me to $what ${if (clock.first) "tomorrow at" else "at"} ${clock.second}"
            return null
        }

        // notes & to-dos
        if (has("\\b(notes?)\\b") && has("\\b(padho|sunao|dikhao|batao|read|show|list)\\b")) return "read my notes"
        if (has("\\b(notes?|likh lo|likh le)\\b") && !has("\\b(todo|to do)\\b")) {
            val body = rest(toks, "note|notes|likh|lo|le|likho|kar|karo|save|yaad|rakho|rakh")
            return if (body.isEmpty()) null else "note down $body"
        }
        if (has("\\b(todo|to do|task|kaam ki list)\\b")) {
            Regex("(\\d+)\\s*(?:done|poora|pura|complete|ho gaya)|(?:done|complete)\\s*(\\d+)").find(r)?.let {
                return "complete task ${it.groupValues[1].ifEmpty { it.groupValues[2] }}"
            }
            if (has("\\b(dikhao|padho|sunao|batao|show|list|kya kya)\\b")) return "show my todos"
            val item = rest(toks, "todo|to|task|kaam|list|add|jodo|jod|daalo|dalo|likho|likh|mein|me|ko|mere|meri|naya|new")
            return if (item.isEmpty()) "show my todos" else "add $item to my todo list"
        }

        // phone data
        if (has("\\bnotifications?\\b")) {
            val app = nameBefore(r, "notifications?")
            if (app != null && app != "mere" && app != "meri") return "notifications from $app"
            return "read my notifications"
        }
        if (has("\\b(miss|missed|miss call|misscall)\\b") || Regex("\\bkiska call\\b").containsMatchIn(r)) return "any missed calls"
        if (has("\\b(whatsapp)\\b")) {
            val name = nameBefore(r, "whatsapp")
            val body = Regex("(?:\\bki\\b|\\bke\\b|कि|की|के)\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
            if (name != null && !body.isNullOrEmpty()) return "whatsapp $name saying $body"
            if (name != null) return "open whatsapp chat with $name"
            return "open whatsapp"
        }
        if (has("\\b(message|messages|sms|msg|text)\\b")) {
            val send = has("\\b(bhejo|bhej|send|likho|likh|bolo|bol)\\b")
            val name = nameBefore(r, "message|messages|sms|msg|text|bolo|bol")
            if (send && name != null) {
                val body = Regex("(?:\\bki\\b|\\bke\\b|कि|की|के|\\bsaying\\b|\\bke liye\\b)\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
                return if (body.isNullOrEmpty()) null else "text $name saying $body"
            }
            if (name != null && has("\\bka\\b|\\bki\\b")) return "texts from $name"
            if (has("\\b(padho|sunao|dikhao|batao|aaye|aaya|naye|new|unread|read|check)\\b")) return "any new texts"
        }

        // calls
        nameBefore(r, "call|phone|fone|ring|dial")?.let { return "call $it" }
        Regex("^(?:call|phone|dial)\\s+(?:karo\\s+|lagao\\s+|laga do\\s+)?(?:to\\s+)?(.+?)(?:\\s+ko)?(?:\\s+(?:karo|kar do|lagao|laga do|kar|laga))*$").find(r)?.let {
            val n = it.groupValues[1].replace(Regex("\\b(?:$FILLER)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (n.isNotEmpty()) return "call $n"
        }

        // device actions
        if (has("\\b(screenshot|screen shot)\\b")) return "take a screenshot"
        if (has("\\b(phone|screen|mobile)\\b") && has("\\b(lock|band)\\b")) return "lock the phone"
        if (has("\\b(gaana|gaane|song|music|video)\\b") || Regex("^(?:play|pause|next|resume)$").matches(r)) {
            if (has("\\b(roko|ruko|pause|stop)\\b")) return "pause"
            if (has("\\b(agla|next|skip)\\b")) return "next song"
            if (has("\\b(pichla|previous|peeche)\\b")) return "previous song"
            if (has("\\b(chalao|chala|play|chalu|resume|bajao|baja)\\b")) return "play"
        }
        if (Regex("^(?:home|back|peeche jao|wapas jao|wapas)(?: jao| karo)?$").matches(r)) return if (r.startsWith("home")) "go home" else "go back"
        if (has("\\b(clipboard)\\b")) return "read clipboard"
        if (has("\\b(system info|phone info|phone status)\\b")) return "system info"

        // info
        if (has("\\b(mausam|weather)\\b")) {
            val m = Regex("(?:^|\\s)([a-z]+(?: [a-z]+)?)\\s+(?:ka|ki|ke|mein|me)\\s+(?:mausam|weather)").find(r)
            val city = m?.groupValues?.get(1)?.replace(Regex("\\b(?:$FILLER|aaj|abhi|ka|ki|ke)\\b"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return if (city.isNotEmpty()) "weather in $city" else "weather"
        }
        if (has("\\b(good morning|suprabhat|briefing|aaj ka plan|aaj ka din|subah ki khabar)\\b")) return "brief me"
        if (has("\\b(tarikh|date)\\b") || has("\\bkaun sa din\\b")) return "what's the date"
        if (has("\\b(joke|chutkula|mazak)\\b")) return "tell me a joke"
        if (has("\\b(sikka|coin)\\b")) return "flip a coin"
        if (has("\\b(dice|pasa|ludo)\\b")) return "roll a dice"
        if (has("\\b(madad|help)\\b") || has("\\bkya kya kar\\b|\\bkya kar sakte\\b")) return "help"
        if (has("\\b(time|samay|kitne baje|baje kya)\\b")) return "what's the time"

        // math: "5 plus 3 kitna hota hai"
        if (has("\\d") && has("\\b(plus|minus|times|guna|bhag|jod|ghata|percent|multiply|divide|divided)\\b|[+*/]")) {
            val expr = r.replace(Regex("\\bjod\\b"), "plus").replace(Regex("\\bghata\\b"), "minus").replace(Regex("\\bguna\\b"), "times")
                .replace(Regex("\\bbhag\\b"), "divided by")
                .replace(Regex("\\b(?:kitna|kitne|hota|hoga|hai|kya|batao|bata|karo|kar|do|calculate|ka|ke|ki|mujhe)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (expr.isNotEmpty()) return "calculate $expr"
        }

        // open / search / close an app
        Regex("\\b(?:search|khojo|dhundho|dhoondo)\\b").find(r)?.let {
            val q = rest(toks, "search|khojo|dhundho|dhoondo|google|karo|kar|par|pe|me|mein|on|internet|web|ke|liye|bare|baare")
            if (q.isNotEmpty()) return "search $q"
        }
        Regex("^(?:(?:mujhe|please|zara)\\s+)*(.+?)\\s+(?:kholo|khol do|khol de|khol|open karo|open kar do|open|chalu karo|chalu kar do|chalu|chalao|chala do|launch karo|start karo)$").find(r)?.let {
            val app = it.groupValues[1].replace(Regex("\\b(?:$FILLER)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (app.isNotEmpty()) return "open $app"
        }
        Regex("^(?:open|launch|start)\\s+(.+?)(?:\\s+(?:karo|kar do|kar))?$").find(r)?.let { return "open ${it.groupValues[1]}" }
        Regex("^(.+?)\\s+(?:band karo|band kar do|band kar|close karo|close kar do)$").find(r)?.let {
            val app = it.groupValues[1].replace(Regex("\\b(?:$FILLER)\\b"), " ").replace(Regex("\\s+"), " ").trim()
            if (app.isNotEmpty()) return "close $app"
        }

        // "<topic> ke bare mein batao" / "<topic> kya hai" / "<topic> kaun hai"
        Regex("^(.+?)\\s+(?:ke bare mein|ke baare mein|kya hai|kaun hai|kaun the|kaun thi)\\b").find(r)?.let {
            val topic = rest(toks, "bare|baare|batao|bata|bolo|kaun|kya|the|thi|hai").ifEmpty { it.groupValues[1] }
            return "tell me about $topic"
        }
        return null
    }

    /** Offline path: Hindi in, Hindi out. Null when nothing matched. */
    fun offlineOrNull(text: String): String? {
        val cmd = toCommand(text) ?: return null
        val out = Commands.tryCommands(cmd) ?: return null
        return HindiReply.localize(out)
    }

    fun offline(text: String): String = offlineOrNull(text) ?: NOT_UNDERSTOOD
}

/** Turns the router's fixed English replies into Hindi. Anything dynamic or unknown is left as it is. */
object HindiReply {
    private fun dur(s: String) = s
        .replace(Regex("hours?"), "घंटे").replace(Regex("minutes?"), "मिनट").replace(Regex("seconds?"), "सेकंड")
        .replace(" and ", " और ")

    private val STATE = mapOf(
        "charging" to "चार्ज हो रही है", "discharging" to "डिस्चार्ज हो रही है", "full" to "पूरी भरी है",
        "not charging" to "चार्ज नहीं हो रही", "unknown" to "स्थिति पता नहीं"
    )
    private val TOGGLES = mapOf(
        "Wi-Fi" to "वाई-फाई", "Bluetooth" to "ब्लूटूथ", "Mobile data" to "मोबाइल डेटा", "Airplane mode" to "एयरप्लेन मोड",
        "Do not disturb" to "डू नॉट डिस्टर्ब", "Dark mode" to "डार्क मोड", "Auto rotate" to "ऑटो रोटेट", "Battery saver" to "बैटरी सेवर"
    )

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^Flashlight (on|off)\\.$") to { m: MatchResult -> "फ्लैशलाइट ${if (m.groupValues[1] == "on") "चालू" else "बंद"} कर दी।" },
        Regex("^Battery is at (\\d+) percent and (.+)\\.$") to { m: MatchResult ->
            "बैटरी ${m.groupValues[1]} प्रतिशत है और ${STATE[m.groupValues[2]] ?: m.groupValues[2]}।"
        },
        Regex("^It's (.+)\\.$") to { m: MatchResult -> "अभी ${m.groupValues[1]} हुए हैं।" },
        Regex("^Today is (.+)\\.$") to { m: MatchResult -> "आज ${m.groupValues[1]} है।" },
        Regex("^Opening (.+)\\.$") to { m: MatchResult -> "${m.groupValues[1]} खोल रहा हूँ।" },
        Regex("^Calling (.+)\\.$") to { m: MatchResult -> "${m.groupValues[1]} को कॉल कर रहा हूँ।" },
        Regex("^Timer set for (.+)\\.$") to { m: MatchResult -> "${dur(m.groupValues[1])} का टाइमर लगा दिया।" },
        Regex("^Volume set to (\\d+) percent\\.$") to { m: MatchResult -> "वॉल्यूम ${m.groupValues[1]} प्रतिशत कर दिया।" },
        Regex("^Brightness set to (\\d+) percent\\.$") to { m: MatchResult -> "ब्राइटनेस ${m.groupValues[1]} प्रतिशत कर दी।" },
        Regex("^Noted\\.$") to { _: MatchResult -> "नोट कर लिया।" },
        Regex("^Done\\.$") to { _: MatchResult -> "हो गया।" },
        Regex("^Cancelled\\.$") to { _: MatchResult -> "रद्द कर दिया।" },
        Regex("^Copied\\.$") to { _: MatchResult -> "कॉपी कर लिया।" },
        Regex("^Locked\\.$") to { _: MatchResult -> "फ़ोन लॉक कर दिया।" },
        Regex("^Screenshot taken\\.$") to { _: MatchResult -> "स्क्रीनशॉट ले लिया।" },
        Regex("^Screen on\\.$") to { _: MatchResult -> "स्क्रीन चालू कर दी।" },
        Regex("^Text sent to (.+)\\.$") to { m: MatchResult -> "${m.groupValues[1]} को मैसेज भेज दिया।" },
        Regex("^Call (.+)\\? Say yes to confirm\\.$") to { m: MatchResult -> "${m.groupValues[1]} को कॉल करूँ? पक्का करने के लिए \"हाँ\" बोलिए।" },
        Regex("^Text (.+?): \"(.*)\"\\? Say yes to confirm\\.$") to { m: MatchResult ->
            "${m.groupValues[1]} को मैसेज भेजूँ: \"${m.groupValues[2]}\"? पक्का करने के लिए \"हाँ\" बोलिए।"
        },
        Regex("^Alarm sent to your clock app for (.+?)\\..*$") to { _: MatchResult -> "अलार्म लगा दिया।" },
        Regex("^Your next alarm is (.+)\\.$") to { m: MatchResult -> "आपका अगला अलार्म ${m.groupValues[1]} है।" },
        Regex("^You have no alarms set\\.$") to { _: MatchResult -> "कोई अलार्म सेट नहीं है।" },
        Regex("^I'll remind you to (.+) in (.+)\\.$") to { m: MatchResult -> "ठीक है, ${dur(m.groupValues[2])} में याद दिलाऊँगा: ${m.groupValues[1]}।" },
        Regex("^I'll remind you to (.+)\\.$") to { m: MatchResult -> "ठीक है, याद दिलाऊँगा: ${m.groupValues[1]}।" },
        Regex("^Added (.+) to your to-do list\\.$") to { m: MatchResult -> "${m.groupValues[1]} आपकी टू-डू लिस्ट में जोड़ दिया।" },
        Regex("^Marked (.+) as done\\.$") to { m: MatchResult -> "${m.groupValues[1]} पूरा हो गया, निशान लगा दिया।" },
        Regex("^Your to-do list is empty\\.$") to { _: MatchResult -> "आपकी टू-डू लिस्ट खाली है।" },
        Regex("^You have no notes\\.$") to { _: MatchResult -> "आपका कोई नोट नहीं है।" },
        Regex("^No unread texts\\.$") to { _: MatchResult -> "कोई नया मैसेज नहीं है।" },
        Regex("^No missed calls\\.$") to { _: MatchResult -> "कोई मिस्ड कॉल नहीं है।" },
        Regex("^No notifications\\.$") to { _: MatchResult -> "कोई नोटिफिकेशन नहीं है।" },
        Regex("^No active timers or reminders\\.$") to { _: MatchResult -> "कोई टाइमर या रिमाइंडर चालू नहीं है।" },
        Regex("^All timers and reminders cancelled\\.$") to { _: MatchResult -> "सारे टाइमर और रिमाइंडर रद्द कर दिए।" },
        Regex("^Switched to text mode\\.$") to { _: MatchResult -> "टेक्स्ट मोड चालू।" },
        Regex("^Voice mode on\\..*$") to { _: MatchResult -> "वॉइस मोड चालू। कमांड से पहले \"जार्विस\" बोलिए।" },
        Regex("^You are (.+)\\.$") to { m: MatchResult -> "आप ${m.groupValues[1]} हैं।" },
        Regex("^Understood\\. I'll call you (.+)\\.$") to { m: MatchResult -> "ठीक है, मैं आपको ${m.groupValues[1]} बुलाऊँगा।" },
        Regex("^(?:You're welcome|Anytime|My pleasure)\\.$") to { _: MatchResult -> "कोई बात नहीं।" },
        Regex("^All systems operational\\..*$") to { _: MatchResult -> "सब कुछ ठीक चल रहा है। पूछने के लिए शुक्रिया।" },
        Regex("^Heads\\.$") to { _: MatchResult -> "चित।" },
        Regex("^Tails\\.$") to { _: MatchResult -> "पट।" },
        Regex("^You rolled a (\\d+)\\.$") to { m: MatchResult -> "पासे पर ${m.groupValues[1]} आया।" },
        Regex("^Going to sleep\\.$") to { _: MatchResult -> "ठीक है, मैं सो रहा हूँ।" },
        Regex("^I couldn't find (.+) in your contacts\\.$") to { m: MatchResult -> "मुझे आपके कॉन्टैक्ट्स में ${m.groupValues[1]} नहीं मिला।" },
        Regex("^I couldn't find an app called (.+)\\.$") to { m: MatchResult -> "${m.groupValues[1]} नाम का कोई ऐप नहीं मिला।" },
        Regex("^(Wi-Fi|Bluetooth|Mobile data|Airplane mode|Do not disturb|Dark mode|Auto rotate|Battery saver) (on|off)\\.$") to { m: MatchResult ->
            "${TOGGLES[m.groupValues[1]] ?: m.groupValues[1]} ${if (m.groupValues[2] == "on") "चालू" else "बंद"} कर दिया।"
        }
    )

    fun localize(english: String): String {
        val t = english.trim()
        for ((rx, f) in RULES) {
            val m = rx.matchEntire(t) ?: continue
            return f(m)
        }
        return english
    }

    /** Reminder / timer notifications for someone who last spoke Hindi. */
    fun localizeAlert(text: String): String {
        Regex("^Your timer for (.+) is done$").matchEntire(text)?.let { return "आपका ${dur(it.groupValues[1])} का टाइमर पूरा हो गया।" }
        Regex("^Reminder: (.+)$").matchEntire(text)?.let { return "याद दिलाना: ${it.groupValues[1]}" }
        Regex("^Missed reminder: (.+)$").matchEntire(text)?.let { return "छूटा हुआ रिमाइंडर: ${localizeAlert(it.groupValues[1])}" }
        return text
    }
}
