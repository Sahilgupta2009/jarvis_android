package com.jarvis.assistant

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Port of jarvis2.py: the LLM that answers open questions, calls phone tools, searches the web and sees images. */
object Brain {
    private const val MAX_HISTORY = 16
    private const val MAX_STEPS = 5
    private const val MAX_TOKENS = 500
    const val FAST_PATH_WORDS = 8

    data class Provider(val label: String, val base: String, val model: String = "")

    val PROVIDERS = linkedMapOf(
        "anthropic" to Provider("Anthropic (Claude)", "https://api.anthropic.com", "claude-haiku-5-5"),
        "groq" to Provider("Groq (free tier)", "https://api.groq.com/openai/v1"),
        "gemini" to Provider("Google Gemini (free tier)", "https://generativelanguage.googleapis.com/v1beta/openai"),
        "openrouter" to Provider("OpenRouter", "https://openrouter.ai/api/v1"),
        "custom" to Provider("Custom OpenAI-compatible server", "")
    )

    data class Config(
        val provider: String = "",
        val apiKey: String = "",
        val model: String = "",
        val baseUrl: String = "",
        val braveKey: String = "",
        val visionModel: String = ""
    )

    @Volatile var cfg = Config()
        private set

    private var dir: File? = null
    private val history = ArrayList<Pair<String, String>>()
    private val lock = Any()

    private val VISION_SYSTEM =
        "You are JARVIS, a voice assistant. You are looking at an image from the user's phone. Answer their request " +
            "from what is actually visible, in one to three short spoken sentences of plain text. No markdown, no lists. " +
            "If asked to read or translate text, give the text itself. If something is unclear or cut off, say so."

    private val BLOCKED = Regex(
        """^(clear|delete|cancel|exit|quit|shut ?down|bye|goodbye|stop)\b|\b(clear|delete) (all )?(my |the )?(notes|to ?dos?|tasks?)\b""",
        RegexOption.IGNORE_CASE
    )

    val enabled: Boolean get() = cfg.apiKey.isNotEmpty() && cfg.model.isNotEmpty() && cfg.baseUrl.isNotEmpty()
    val label: String get() = if (enabled) cfg.model.take(28) else ""

    // ───────────────────────── config & memory ─────────────────────────
    fun load(ctx: Context) {
        dir = ctx.filesDir
        try {
            val j = JSONObject(File(ctx.filesDir, "config.json").readText())
            val prov = j.optString("provider")
            if (prov in PROVIDERS) {
                val d = PROVIDERS.getValue(prov)
                cfg = Config(
                    prov, j.optString("api_key"), j.optString("model").ifEmpty { d.model },
                    j.optString("base_url").ifEmpty { d.base }.trimEnd('/'),
                    j.optString("brave_key"), j.optString("vision_model")
                )
            }
        } catch (_: Exception) {
        }
        try {
            val a = JSONArray(File(ctx.filesDir, "history.json").readText())
            synchronized(lock) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val role = o.optString("role")
                    if (role == "user" || role == "assistant") history.add(role to o.optString("content"))
                }
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }
        } catch (_: Exception) {
        }
    }

    fun save(c: Config) {
        cfg = c.copy(baseUrl = c.baseUrl.trimEnd('/'))
        try {
            val j = JSONObject()
                .put("provider", c.provider).put("api_key", c.apiKey).put("model", c.model)
                .put("base_url", c.baseUrl).put("brave_key", c.braveKey).put("vision_model", c.visionModel)
            File(dir, "config.json").writeText(j.toString(2))
        } catch (_: Exception) {
        }
    }

    private fun saveHistory() {
        try {
            val a = JSONArray()
            for ((r, c) in history) a.put(JSONObject().put("role", r).put("content", c))
            File(dir, "history.json").writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    fun rememberTurn(user: String, reply: String) {
        synchronized(lock) {
            history.add("user" to user.take(600))
            history.add("assistant" to reply.take(600))
            while (history.size > MAX_HISTORY) history.removeAt(0)
            saveHistory()
        }
    }

    fun reset() {
        synchronized(lock) { history.clear(); saveHistory() }
    }

    // ───────────────────────── http ─────────────────────────
    fun request(url: String, headers: Map<String, String>, payload: JSONObject? = null, timeoutMs: Int = 45_000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "JarvisAndroid/2.0")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (payload != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                var msg = text
                try {
                    val j = JSONObject(text)
                    val e = j.opt("error")
                    msg = if (e is JSONObject) e.optString("message", text) else (e?.toString() ?: text)
                } catch (_: Exception) {
                }
                val hint = if (code == 401 || code == 403) " Check your API key in Jarvis settings." else ""
                throw LLMError("HTTP $code: ${msg.take(200)}.$hint")
            }
            return JSONObject(text)
        } catch (e: LLMError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LLMError("the AI service timed out.")
        } catch (e: java.io.IOException) {
            throw LLMError("can't reach the AI service (${e.message}). Check your connection.")
        } catch (e: org.json.JSONException) {
            throw LLMError("unexpected response from the AI service.")
        } finally {
            c.disconnect()
        }
    }

    // ───────────────────────── tools the LLM can use ─────────────────────────
    private class Tool(val name: String, val desc: String, val schema: JSONObject)

    private fun toolDefs(): List<Tool> {
        val cmds = Commands.rules.mapNotNull { it.help }.joinToString("; ")
        fun schema(prop: String) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put(prop, JSONObject().put("type", "string")))
            .put("required", JSONArray().put(prop))
        return listOf(
            Tool(
                "phone_action",
                "Do something on the user's phone or in the assistant. Pass ONE short plain-English command exactly as a user " +
                    "would say it, e.g. 'turn on the flashlight', 'set a timer for 5 minutes', 'note down buy milk', " +
                    "'add gym to my todo list', 'remind me to stretch in 20 minutes', 'battery', 'weather in Delhi', " +
                    "'open youtube', 'volume 40'. Supported: $cmds. Returns the result text. Only claim an action happened if this confirms it.",
                schema("command")
            ),
            Tool(
                "web_search",
                "Search the web for current facts: news, scores, prices, recent events, people, definitions.",
                schema("query")
            )
        )
    }

    private fun toolPhoneAction(command: String): String {
        if (BLOCKED.containsMatchIn(command.trim())) {
            return "Blocked: destructive or exit commands must be said by the user directly. Tell them to say it themselves."
        }
        Commands.fromAi = true
        try {
            return Commands.tryCommands(command, skip = setOf("_wiki")) ?: "No such command. Check the supported list."
        } catch (q: Quit) {
            return "Blocked."
        } finally {
            Commands.fromAi = false
        }
    }

    private fun toolWebSearch(query0: String): String {
        val query = query0.trim().take(200)
        if (cfg.braveKey.isNotEmpty()) {
            try {
                val url = "https://api.search.brave.com/res/v1/web/search?count=5&q=" + URLEncoder.encode(query, "UTF-8")
                val data = request(url, mapOf("X-Subscription-Token" to cfg.braveKey, "Accept" to "application/json"), null, 15_000)
                val res = data.optJSONObject("web")?.optJSONArray("results")
                if (res != null && res.length() > 0) {
                    val clean = { t: String -> t.replace(Regex("<[^>]+>"), "") }
                    val sb = StringBuilder()
                    for (i in 0 until minOf(5, res.length())) {
                        val r = res.getJSONObject(i)
                        sb.append("- ").append(clean(r.optString("title"))).append(": ").append(clean(r.optString("description"))).append("\n")
                    }
                    return sb.toString().trim().take(2500)
                }
            } catch (_: Exception) {
            }
        }
        return try {
            val out = wiki(query) ?: wiki(titleCase(query))
            if (out != null) "Wikipedia: $out" else "No results found."
        } catch (e: Exception) {
            "Search failed (no internet?)."
        }
    }

    private fun runTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "phone_action" -> toolPhoneAction(args.optString("command"))
                "web_search" -> toolWebSearch(args.optString("query"))
                else -> "Unknown tool."
            }
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
    }

    // ───────────────────────── the brain ─────────────────────────
    private fun systemPrompt(): String {
        val user = Store.user
        val notes = Store.noteTexts().takeLast(8).joinToString("; ").ifEmpty { "none" }
        val todos = Store.todos().filter { !it.done }.joinToString("; ") { it.text }.ifEmpty { "none" }
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy, hh:mm a ('UTC'xx)", Locale.ENGLISH))
        return "You are JARVIS, a personal voice assistant running on $user's Android phone. Address the user as $user. " +
            "LANGUAGE: the user may speak English, Hindi (Devanagari) or Hinglish (Hindi in Roman letters), often mixed, with rough " +
            "grammar and speech-recognition mistakes. Work out what they mean, like a friend would. Always reply in the language they " +
            "used: if they used Hindi or Hinglish, answer in simple, natural Hindi written in Devanagari (keep app names, brand names " +
            "and technical words in English letters); if they used English, answer in English. When you call phone_action, always write " +
            "the command in plain English with names and app names in Roman letters (for example 'call mummy', 'open youtube', " +
            "'turn on the flashlight', 'set an alarm for tomorrow 6 am'); but keep the text of a message, note, todo or reminder in the " +
            "user's own language. If a request is unclear, ask one short question in their language.\n" +
            "Your replies are spoken aloud: answer in one to three short sentences of plain text. No markdown, no lists, " +
            "no emoji. Tone: polished, calm, dry wit, never grovelling. Be direct: lead with the answer.\n" +
            "Use phone_action to carry out requests on the phone (flashlight, timers, reminders, notes, todos, apps, volume, " +
            "battery, weather, phone calls). Infer intent from casual phrasing. Calls and text messages need the user's spoken " +
            "confirmation: when phone_action replies with a confirmation question, relay it and wait; never claim a call or " +
            "text happened until the user confirms. WhatsApp only opens the chat with the message typed in: the user must tap " +
            "send, so never say it was sent. Use web_search for anything current or that you don't reliably know. Never invent " +
            "results. If a tool fails, say so plainly.\n" +
            "Current time: $now.\nUser's saved notes: $notes.\nUser's open tasks: $todos."
    }

    private fun anthropic(system: String, start: List<JSONObject>): String {
        val tools = JSONArray()
        for (t in toolDefs()) tools.put(JSONObject().put("name", t.name).put("description", t.desc).put("input_schema", t.schema))
        val headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        val msgs = ArrayList<JSONObject>(start)
        for (step in 0 until MAX_STEPS) {
            val body = JSONObject().put("model", cfg.model).put("max_tokens", MAX_TOKENS).put("system", system)
                .put("messages", JSONArray(msgs)).put("tools", tools)
            val data = request(cfg.baseUrl + "/v1/messages", headers, body)
            val content = data.optJSONArray("content") ?: JSONArray()
            val blocks = JSONArray()
            val calls = ArrayList<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> {
                        val t = b.optString("text")
                        if (t.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", t))
                        text.append(t)
                    }
                    "tool_use" -> {
                        val tb = JSONObject().put("type", "tool_use").put("id", b.getString("id")).put("name", b.getString("name"))
                            .put("input", b.optJSONObject("input") ?: JSONObject())
                        blocks.put(tb)
                        calls.add(tb)
                    }
                }
            }
            if (calls.isEmpty()) return text.toString()
            msgs.add(JSONObject().put("role", "assistant").put("content", blocks))
            val results = JSONArray()
            for (c in calls) {
                results.put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", c.getString("id"))
                        .put("content", runTool(c.getString("name"), c.getJSONObject("input")))
                )
            }
            msgs.add(JSONObject().put("role", "user").put("content", results))
        }
        return "I got tangled up trying to do that. Could you rephrase it?"
    }

    private fun openai(system: String, start: List<JSONObject>): String {
        val tools = JSONArray()
        for (t in toolDefs()) {
            tools.put(
                JSONObject().put("type", "function").put(
                    "function", JSONObject().put("name", t.name).put("description", t.desc).put("parameters", t.schema)
                )
            )
        }
        val headers = mapOf("Authorization" to "Bearer ${cfg.apiKey}")
        val msgs = ArrayList<JSONObject>()
        msgs.add(JSONObject().put("role", "system").put("content", system))
        msgs.addAll(start)
        for (step in 0 until MAX_STEPS) {
            val body = JSONObject().put("model", cfg.model).put("max_tokens", MAX_TOKENS)
                .put("messages", JSONArray(msgs)).put("tools", tools)
            val data = request(cfg.baseUrl + "/chat/completions", headers, body)
            val m = data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw LLMError("unexpected response from the AI service.")
            val calls = m.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return m.optString("content", "")
            msgs.add(JSONObject().put("role", "assistant").put("content", m.optString("content", "")).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val tc = calls.getJSONObject(i)
                val fn = tc.optJSONObject("function") ?: JSONObject()
                val args = try { JSONObject(fn.optString("arguments").ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
                msgs.add(
                    JSONObject().put("role", "tool").put("tool_call_id", tc.optString("id"))
                        .put("content", runTool(fn.optString("name"), args))
                )
            }
        }
        return "I got tangled up trying to do that. Could you rephrase it?"
    }

    /** Ask a question using the current config. Set [persist] false for tests. */
    fun ask(text: String, persist: Boolean = true, config: Config = cfg): String {
        val prev = cfg
        val start = ArrayList<JSONObject>()
        synchronized(lock) { for ((r, c) in history.takeLast(MAX_HISTORY)) start.add(JSONObject().put("role", r).put("content", c)) }
        start.add(JSONObject().put("role", "user").put("content", text))
        if (config !== prev) cfg = config
        try {
            val reply = plain(if (cfg.provider == "anthropic") anthropic(systemPrompt(), start) else openai(systemPrompt(), start))
            if (reply.isNotEmpty() && persist) rememberTurn(text, reply)
            return reply
        } finally {
            if (config !== prev) cfg = prev
        }
    }

    /** One image + a question. Uses the vision model if set, else the normal model. */
    fun askVision(question: String, jpeg: ByteArray, tag: String): String {
        val c = cfg
        val model = c.visionModel.ifEmpty { c.model }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val q = question.trim().ifEmpty { "Describe what you see." }
        val reply: String
        if (c.provider == "anthropic") {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("type", "text").put("text", q))
            val resp = request(
                c.baseUrl + "/v1/messages",
                mapOf("x-api-key" to c.apiKey, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", MAX_TOKENS).put("system", VISION_SYSTEM)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))),
                75_000
            )
            val sb = StringBuilder()
            val arr = resp.optJSONArray("content") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            reply = sb.toString()
        } else {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", q))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            val resp = request(
                c.baseUrl + "/chat/completions",
                mapOf("Authorization" to "Bearer ${c.apiKey}"),
                JSONObject().put("model", model).put("max_tokens", MAX_TOKENS).put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", VISION_SYSTEM))
                        .put(JSONObject().put("role", "user").put("content", content))
                ),
                75_000
            )
            reply = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                ?: throw LLMError("unexpected response from the AI service.")
        }
        val out = plain(reply)
        if (out.isNotEmpty()) rememberTurn("[I showed you my $tag] $q", out)
        return out
    }

    /** Model ids offered by an OpenAI-compatible provider (for the settings screen). */
    fun listModels(c: Config): List<String> {
        val data = request(c.baseUrl.trimEnd('/') + "/models", mapOf("Authorization" to "Bearer ${c.apiKey}"), null, 20_000)
        val arr = data.optJSONArray("data") ?: data.optJSONArray("models") ?: JSONArray()
        val skip = Regex("whisper|tts|embed|guard|moderation|orpheus|playai|image|rerank|imagen|veo|aqa|vision-only", RegexOption.IGNORE_CASE)
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.getJSONObject(i).optString("id").replace("models/", "")
            if (id.isNotEmpty() && !skip.containsMatchIn(id)) ids.add(id)
        }
        return ids.sorted()
    }
}
