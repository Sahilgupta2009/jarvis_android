package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Everything Jarvis remembers: name, notes, to-dos, reminders and a few settings (data.json in app storage). */
object Store {
    private var file: File? = null
    private val lock = Any()
    private var root = JSONObject()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "data.json")
        file = f
        root = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
        for (k in listOf("notes", "todos", "reminders")) if (!root.has(k)) root.put(k, JSONArray())
        if (!root.has("user")) root.put("user", "sir")
    }

    fun save() {
        val f = file ?: return
        synchronized(lock) {
            try {
                val tmp = File(f.parentFile, "data.json.tmp")
                tmp.writeText(root.toString(2))
                if (!tmp.renameTo(f)) {
                    f.writeText(root.toString(2))
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- simple settings ----
    var user: String
        get() = root.optString("user", "sir")
        set(v) { synchronized(lock) { root.put("user", v) }; save() }

    var countryCode: String
        get() = root.optString("country_code", "91")
        set(v) { synchronized(lock) { root.put("country_code", v) }; save() }

    var confirmSms: Boolean
        get() = root.optBoolean("confirm_sms", true)
        set(v) { synchronized(lock) { root.put("confirm_sms", v) }; save() }

    var wakeEnabled: Boolean
        get() = root.optBoolean("wake_enabled", false)
        set(v) { synchronized(lock) { root.put("wake_enabled", v) }; save() }

    var voiceReplies: Boolean
        get() = root.optBoolean("voice_replies", true)
        set(v) { synchronized(lock) { root.put("voice_replies", v) }; save() }

    /** Recognizer language: "hi-IN" (Hindi, also catches English words) or "en-IN". */
    var listenLang: String
        get() = root.optString("listen_lang", "hi-IN")
        set(v) { synchronized(lock) { root.put("listen_lang", v) }; save() }

    /** True if the user's last request was Hindi/Hinglish (used for reminder wording). */
    var lastHindi: Boolean
        get() = root.optBoolean("last_hindi", false)
        set(v) { synchronized(lock) { root.put("last_hindi", v) } }

    var offlineWake: Boolean
        get() = root.optBoolean("offline_wake", true)
        set(v) { synchronized(lock) { root.put("offline_wake", v) }; save() }

    var silenceBeep: Boolean
        get() = root.optBoolean("silence_beep", true)
        set(v) { synchronized(lock) { root.put("silence_beep", v) }; save() }

    // ---- notes ----
    fun noteTexts(): List<String> = synchronized(lock) {
        val a = root.getJSONArray("notes")
        (0 until a.length()).map { a.getJSONObject(it).optString("text") }
    }

    fun addNote(text: String) {
        synchronized(lock) {
            root.getJSONArray("notes").put(
                JSONObject().put("t", LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString()).put("text", text)
            )
        }
        save()
    }

    fun clearNotes() { synchronized(lock) { root.put("notes", JSONArray()) }; save() }

    // ---- to-dos ----
    data class Todo(val text: String, val done: Boolean)

    fun todos(): List<Todo> = synchronized(lock) {
        val a = root.getJSONArray("todos")
        (0 until a.length()).map { Todo(a.getJSONObject(it).optString("text"), a.getJSONObject(it).optBoolean("done")) }
    }

    fun addTodo(text: String) {
        synchronized(lock) { root.getJSONArray("todos").put(JSONObject().put("text", text).put("done", false)) }
        save()
    }

    /** index is 0-based. Returns the task text, or null if there's no such task. */
    fun completeTodo(index: Int): String? {
        val text = synchronized(lock) {
            val a = root.getJSONArray("todos")
            if (index !in 0 until a.length()) return null
            a.getJSONObject(index).put("done", true)
            a.getJSONObject(index).optString("text")
        }
        save()
        return text
    }

    fun clearTodos() { synchronized(lock) { root.put("todos", JSONArray()) }; save() }

    // ---- reminders & timers ----
    data class Reminder(val id: Long, val due: Long, val text: String, val kind: String)

    fun reminders(): List<Reminder> = synchronized(lock) {
        val a = root.getJSONArray("reminders")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Reminder(o.optLong("id"), o.optLong("due"), o.optString("text"), o.optString("kind", "reminder"))
        }
    }

    fun addReminder(r: Reminder) {
        synchronized(lock) {
            root.getJSONArray("reminders").put(
                JSONObject().put("id", r.id).put("due", r.due).put("text", r.text).put("kind", r.kind)
            )
        }
        save()
    }

    fun removeReminder(id: Long) {
        synchronized(lock) {
            val keep = JSONArray()
            val a = root.getJSONArray("reminders")
            for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("id") != id) keep.put(a.getJSONObject(i))
            root.put("reminders", keep)
        }
        save()
    }

    fun clearReminders() { synchronized(lock) { root.put("reminders", JSONArray()) }; save() }
}
