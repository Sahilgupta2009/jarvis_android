package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

data class Contact(val name: String, val number: String)
data class Resolved(val name: String?, val number: String?, val err: String?)
data class Sms(val number: String, val body: String, val read: Boolean, val date: Long)
data class CallRow(val number: String, val name: String, val type: String, val date: Long)
data class NotifItem(val app: String, val pkg: String, val title: String, val text: String, val whenMs: Long)
data class AppInfo(val label: String, val pkg: String)

/** Everything Jarvis does to the phone itself: the native replacement for Termux:API + ADB. */
object Device {
    lateinit var app: Application

    const val NEEDS_A11Y = "That needs the Jarvis accessibility service. Switch it on in Jarvis settings."
    const val NO_SMS = "I can't read your texts. Allow the SMS permission for Jarvis."
    const val NO_CALLS = "I can't read your call log. Allow the Call log permission for Jarvis."
    const val NO_NOTIF = "I can't read notifications. Give Jarvis notification access in its settings."

    fun hasPerm(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    fun launch(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    fun openSettings(action: String, withPackage: Boolean = false): Boolean {
        val i = Intent(action)
        if (withPackage) i.data = Uri.parse("package:${app.packageName}")
        return launch(i)
    }

    // ───────────── basic device state ─────────────
    fun battery(): Pair<Int, String>? {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val state = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        return (level * 100 / scale) to state
    }

    /** Returns an error message, or null on success. */
    fun torch(on: Boolean): String? {
        return try {
            val cm = app.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flashlight."
            cm.setTorchMode(id, on)
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    fun setVolume(pct: Int) {
        val am = app.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0, 100) * max / 100.0).roundToInt(), 0)
    }

    /** Returns an error message, or null on success. */
    fun setBrightness(pct: Int): String? {
        if (!Settings.System.canWrite(app)) {
            openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, true)
            return "I need permission to change system settings. I've opened the screen. Allow it for Jarvis, then ask again."
        }
        val v = (pct.coerceIn(0, 100) * 255 / 100.0).roundToInt()
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        return null
    }

    fun clipGet(): String {
        val cm = app.getSystemService(ClipboardManager::class.java)
        val c = cm.primaryClip
        return if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(app).toString() else ""
    }

    fun clipSet(text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("jarvis", text))
    }

    fun sysInfo(): String {
        val parts = ArrayList<String>()
        parts.add("Android ${Build.VERSION.RELEASE} on ${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            parts.add(String.format(Locale.US, "%.1f GB free of %.1f GB storage", st.availableBytes / 1e9, st.totalBytes / 1e9))
        } catch (_: Exception) {
        }
        try {
            val mi = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            parts.add(String.format(Locale.US, "%.1f GB RAM available of %.1f GB", mi.availMem / 1e9, mi.totalMem / 1e9))
        } catch (_: Exception) {
        }
        return parts.joinToString(". ") + "."
    }

    // ───────────── toggles (Android only lets apps flip some of these; the rest open the right panel) ─────────────
    val TOGGLE_LABELS = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "data" to "Mobile data", "airplane" to "Airplane mode",
        "dnd" to "Do not disturb", "dark" to "Dark mode", "rotate" to "Auto rotate", "saver" to "Battery saver"
    )

    @Suppress("DEPRECATION")
    fun toggle(key: String, on: Boolean): String {
        val label = TOGGLE_LABELS[key] ?: key
        val state = if (on) "on" else "off"
        val opened = "I've opened the $label control. Android doesn't let apps switch it directly."
        return when (key) {
            "wifi" -> {
                if (Build.VERSION.SDK_INT < 29) {
                    try {
                        (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = on
                        "$label $state."
                    } catch (e: Exception) {
                        "I couldn't change $label: ${e.message}"
                    }
                } else if (openSettings(Settings.Panel.ACTION_WIFI)) opened else "I couldn't open $label."
            }
            "bluetooth" -> if (openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)) opened else "I couldn't open $label."
            "data" -> {
                val act = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
                if (openSettings(act)) opened else "I couldn't open $label."
            }
            "airplane" -> if (openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) opened else "I couldn't open $label."
            "dark" -> if (openSettings(Settings.ACTION_DISPLAY_SETTINGS)) opened else "I couldn't open $label."
            "dnd" -> {
                val nm = app.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                    "$label $state."
                } else {
                    openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    "I need Do Not Disturb access. I've opened the screen. Allow it for Jarvis, then ask again."
                }
            }
            "rotate" -> {
                if (Settings.System.canWrite(app)) {
                    Settings.System.putInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                    "$label $state."
                } else {
                    openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, true)
                    "I need permission to change system settings. I've opened the screen. Allow it for Jarvis, then ask again."
                }
            }
            "saver" -> {
                try {
                    Settings.Global.putInt(app.contentResolver, "low_power", if (on) 1 else 0)
                    "$label $state."
                } catch (e: SecurityException) {
                    openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    opened
                }
            }
            else -> "I don't know how to change $label."
        }
    }

    // ───────────── accessibility-powered phone control ─────────────
    fun a11y(action: Int): Boolean = JarvisAccess.instance?.performGlobalAction(action) ?: false

    fun lockScreen(): String =
        if (JarvisAccess.instance == null || Build.VERSION.SDK_INT < 28) NEEDS_A11Y
        else if (a11y(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) "Locked." else "I couldn't lock the screen."

    fun screenshot(): String =
        if (JarvisAccess.instance == null || Build.VERSION.SDK_INT < 28) NEEDS_A11Y
        else if (a11y(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) "Screenshot taken." else "The screenshot failed."

    fun nav(kind: String): String {
        if (JarvisAccess.instance == null) return NEEDS_A11Y
        val action = when (kind) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "collapse" -> if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE else AccessibilityService.GLOBAL_ACTION_BACK
            else -> AccessibilityService.GLOBAL_ACTION_HOME
        }
        return if (a11y(action)) "Done." else "That didn't work."
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(): String {
        return try {
            val pm = app.getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake")
            wl.acquire(3000)
            "Screen on."
        } catch (e: Exception) {
            "I couldn't wake the screen."
        }
    }

    fun mediaKey(code: Int) {
        val am = app.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun killApp(pkg: String) {
        app.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
    }

    // ───────────── alarms ─────────────
    fun setAlarm(dt: LocalDateTime, label: String): Boolean {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, dt.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, dt.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(i)
    }

    fun nextAlarm(): LocalDateTime? {
        val info = app.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(info.triggerTime), ZoneId.systemDefault())
    }

    // ───────────── contacts, calls, texts ─────────────
    private var contactsCache: List<Contact>? = null
    private var contactsT = 0L

    fun contacts(): List<Contact>? {
        val cached = contactsCache
        if (cached != null && System.currentTimeMillis() - contactsT < 300_000) return cached
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return null
        val out = ArrayList<Contact>()
        try {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)
                    val num = c.getString(1)
                    if (!n.isNullOrBlank() && !num.isNullOrBlank()) out.add(Contact(n, num))
                }
            }
        } catch (e: Exception) {
            return null
        }
        contactsCache = out
        contactsT = System.currentTimeMillis()
        return out
    }

    private val NICKNAMES = listOf(
        setOf("mom", "mummy", "mum", "mother", "mama", "maa", "ma", "mommy"),
        setOf("dad", "papa", "father", "pappa", "daddy", "baba", "pa"),
        setOf("brother", "bhai", "bro"),
        setOf("sister", "didi", "sis")
    )

    /** Fuzzy name match: speech recognition rarely spells names exactly. Returns (contact, null) | (null, ambiguous names) | (null, null). */
    fun findContact(query: String, contacts: List<Contact>): Pair<Contact?, List<String>?> {
        val q = query.lowercase().trim()
        val scored = contacts.map { c ->
            val n = c.name.lowercase()
            val words = n.split(" ")
            val sc = when {
                n == q -> 1.0
                q in words -> 0.9
                q.length < 4 -> 0.0
                n.startsWith(q) -> 0.9
                q in n -> 0.8
                else -> maxOf(similarity(q, n), words.maxOfOrNull { similarity(q, it) } ?: 0.0)
            }
            sc to c
        }
        if (scored.isEmpty()) return null to null
        val best = scored.maxOf { it.first }
        if (best < 0.62) return null to null
        val top = scored.filter { it.first >= best - 0.001 }.map { it.second }
        val names = top.map { it.name }.toSortedSet().toList()
        if (names.size > 1) return null to names.take(4)
        return top[0] to null
    }

    /** Spoken name or phone number -> (name, number, error message). */
    fun resolveContact(target: String): Resolved {
        val digits = target.replace(Regex("[\\s\\-().]"), "")
        if (Regex("\\+?\\d{3,15}").matches(digits)) return Resolved(digits, digits, null)
        val book = contacts()
            ?: return Resolved(null, null, "I can't read your contacts. Allow the Contacts permission for Jarvis, or say the number.")
        val names = ArrayList<String>()
        names.add(target.lowercase())
        for (group in NICKNAMES) if (target.lowercase() in group) names.addAll((group - target.lowercase()).sorted())
        var contact: Contact? = null
        var ambiguous: List<String>? = null
        for (nm in names) {
            val r = findContact(nm, book)
            contact = r.first
            ambiguous = r.second
            if (contact != null || ambiguous != null) break
        }
        if (ambiguous != null) return Resolved(null, null, "Which one: " + ambiguous.joinToString(", ") + "? Say the full name.")
        if (contact == null) return Resolved(null, null, "I couldn't find $target in your contacts.")
        return Resolved(contact.name, contact.number, null)
    }

    private fun tail(number: String?): String = (number ?: "").replace(Regex("\\D"), "").takeLast(10)

    fun contactName(number: String, fallback: String = ""): String {
        val t = tail(number)
        for (c in contacts() ?: emptyList()) if (t.isNotEmpty() && tail(c.number) == t) return c.name
        return fallback.ifEmpty { number }
    }

    fun toInternational(number: String): String {
        val digits = number.replace(Regex("\\D"), "")
        if (number.trim().startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.substring(2)
        val cc = Store.countryCode
        if (digits.startsWith("0") || digits.length <= 10) return cc + digits.trimStart('0')
        return digits
    }

    fun placeCall(number: String, label: String): String {
        val n = number.replace(" ", "")
        val uri = Uri.parse("tel:" + Uri.encode(n))
        if (hasPerm(Manifest.permission.CALL_PHONE) && launch(Intent(Intent.ACTION_CALL, uri))) return "Calling $label."
        if (launch(Intent(Intent.ACTION_DIAL, uri))) return "I can't place calls directly yet, so I've opened the dialer for $label. Tap call."
        return "I couldn't start the call. Allow the Phone permission for Jarvis."
    }

    fun openWhatsapp(number: String, name: String, body: String = ""): String {
        val intl = toInternational(number)
        val text = if (body.isNotEmpty()) "&text=" + Uri.encode(body) else ""
        val ready = if (body.isNotEmpty()) " with your message ready" else ""
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$intl$text")))) {
            return if (body.isNotEmpty()) "WhatsApp is open on $name's chat$ready. Tap send." else "WhatsApp is open on $name's chat."
        }
        val web = "https://wa.me/$intl" + (if (body.isNotEmpty()) "?text=" + Uri.encode(body) else "")
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(web)))) return "I've sent $name's WhatsApp link to your browser."
        return "I couldn't open WhatsApp."
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    fun sendSms(number: String, body: String, label: String): String {
        if (!hasPerm(Manifest.permission.SEND_SMS)) return "I couldn't send it. Allow the SMS permission for Jarvis."
        return try {
            val sm = smsManager()
            sm.sendMultipartTextMessage(number.replace(" ", ""), null, sm.divideMessage(body), null, null)
            "Text sent to $label."
        } catch (e: Exception) {
            "I couldn't send it: ${e.message}"
        }
    }

    fun readSms(limit: Int = 40): List<Sms>? {
        if (!hasPerm(Manifest.permission.READ_SMS)) return null
        val out = ArrayList<Sms>()
        try {
            app.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "read", "date"), null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(Sms(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2) == 1, c.getLong(3)))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    fun smsSender(m: Sms): String = contactName(m.number, m.number)

    fun readCalls(limit: Int = 20): List<CallRow>? {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) return null
        val out = ArrayList<CallRow>()
        try {
            app.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val type = when (c.getInt(2)) {
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        else -> "OTHER"
                    }
                    out.add(CallRow(c.getString(0) ?: "", c.getString(1) ?: "", type, c.getLong(3)))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    fun notifications(): List<NotifItem>? = NotifListener.items()

    // ───────────── apps ─────────────
    private val APPS = mapOf(
        "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome",
        "whatsapp" to "com.whatsapp", "whats app" to "com.whatsapp", "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger", "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm",
        "spotify" to "com.spotify.music", "play store" to "com.android.vending",
        "calculator" to "com.google.android.calculator", "photos" to "com.google.android.apps.photos",
        "termux" to "com.termux", "chatgpt" to "com.openai.chatgpt", "claude" to "com.anthropic.claude",
        "acode" to "com.foxdebug.acode", "snapchat" to "com.snapchat.android", "twitter" to "com.twitter.android",
        "x" to "com.twitter.android", "linkedin" to "com.linkedin.android", "discord" to "com.discord",
        "netflix" to "com.netflix.mediaclient", "phonepe" to "com.phonepe.app", "paytm" to "net.one97.paytm",
        "gpay" to "com.google.android.apps.nbu.paisa.user", "google pay" to "com.google.android.apps.nbu.paisa.user",
        "zomato" to "com.application.zomato", "swiggy" to "in.swiggy.android", "flipkart" to "com.flipkart.android",
        "amazon" to "in.amazon.mShop.android.shopping", "replit" to "com.replit.app"
    )

    private val INTENTS: Map<String, () -> Intent> = mapOf(
        "camera" to { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) },
        "settings" to { Intent(Settings.ACTION_SETTINGS) },
        "dialer" to { Intent(Intent.ACTION_DIAL) },
        "phone" to { Intent(Intent.ACTION_DIAL) },
        "wifi settings" to { Intent(Settings.ACTION_WIFI_SETTINGS) },
        "bluetooth settings" to { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        "display settings" to { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "clock" to { Intent(AlarmClock.ACTION_SHOW_ALARMS) },
        "alarms" to { Intent(AlarmClock.ACTION_SHOW_ALARMS) },
        "calculator" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR) },
        "calendar" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR) },
        "contacts" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS) },
        "gallery" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) },
        "messages" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING) },
        "messaging" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING) },
        "email" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL) },
        "browser" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) },
        "music" to { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC) }
    )

    private val SITES = mapOf(
        "youtube" to "https://youtube.com", "github" to "https://github.com", "google" to "https://google.com",
        "gmail" to "https://mail.google.com", "wikipedia" to "https://wikipedia.org", "reddit" to "https://reddit.com"
    )

    private val DEEPLINKS = mapOf(
        "whatsapp" to "whatsapp://", "whats app" to "whatsapp://", "youtube" to "vnd.youtube://",
        "instagram" to "instagram://", "telegram" to "tg://", "spotify" to "spotify:", "snapchat" to "snapchat://",
        "twitter" to "twitter://", "x" to "twitter://", "linkedin" to "linkedin://", "discord" to "discord://",
        "maps" to "geo:0,0", "google maps" to "geo:0,0", "gmail" to "mailto:", "netflix" to "nflx://",
        "phonepe" to "phonepe://", "paytm" to "paytmmp://", "zomato" to "zomato://", "swiggy" to "swiggy://",
        "flipkart" to "flipkart://", "play store" to "market://",
        "chatgpt" to "https://chatgpt.com", "claude" to "https://claude.ai"
    )

    private val GENERIC = setOf("com", "org", "net", "android", "google", "app", "apps", "mobile", "free", "pro", "lite", "the", "my")

    private var appsCache: List<AppInfo>? = null
    private var appsT = 0L

    @Suppress("DEPRECATION")
    fun installedApps(): List<AppInfo> {
        val cached = appsCache
        if (cached != null && System.currentTimeMillis() - appsT < 600_000) return cached
        val pm = app.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(q, 0)
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
        } catch (e: Exception) {
            emptyList()
        }
        appsCache = list
        appsT = System.currentTimeMillis()
        return list
    }

    private fun flat(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun findPackage(rawName: String): String? {
        val name = rawName.lowercase().trim()
        val apps = installedApps()
        APPS[name]?.let { p -> if (apps.any { it.pkg == p }) return p }
        val words = name.replace(Regex("[^a-z0-9 ]"), "").split(" ").filter { it.isNotEmpty() && it !in GENERIC }
        if (words.isEmpty()) return null
        val q = words.joinToString("")
        apps.firstOrNull { flat(it.label) == q }?.let { return it.pkg }
        apps.filter { flat(it.label).startsWith(q) }.minByOrNull { it.label.length }?.let { return it.pkg }

        var bestKey: Pair<Int, Int>? = null
        var best: String? = null
        for (a in apps) {
            val segs = a.pkg.lowercase().split(".")
            val joined = segs.joinToString("")
            val score = when {
                segs.last() == q -> 0
                q in segs -> 1
                segs.last().startsWith(q) -> 2
                words.all { it in joined } -> 3
                else -> -1
            }
            if (score < 0) continue
            val k = score to a.pkg.length
            val bk = bestKey
            if (bk == null || k.first < bk.first || (k.first == bk.first && k.second < bk.second)) {
                bestKey = k
                best = a.pkg
            }
        }
        if (best != null) return best
        apps.filter { flat(it.label).contains(q) }.minByOrNull { it.label.length }?.let { return it.pkg }
        return null
    }

    fun openByName(name: String): String {
        INTENTS[name]?.let { mk -> if (launch(mk())) return "Opening $name." }
        val pkg = findPackage(name)
        val deep = DEEPLINKS[name]
        if (pkg != null || deep != null) {
            if (pkg != null) {
                val li = app.packageManager.getLaunchIntentForPackage(pkg)
                if (li != null && launch(li)) return "Opening $name."
            }
            if (deep != null && launch(Intent(Intent.ACTION_VIEW, Uri.parse(deep)))) return "Opening $name."
            return "I couldn't launch $name."
        }
        val url = SITES[name] ?: if ("." in name && " " !in name) "https://$name" else null
        if (url != null && launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return "Opening $name."
        return "I couldn't find an app called $name."
    }

    fun openUrl(url: String): Boolean = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
