package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.time.Instant

object Ch {
    const val SERVICE = "jarvis_service"
    const val ALERT = "jarvis_alert"
    const val WAKE = "jarvis_wake"

    fun create(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE, "Wake word listener", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(ALERT, "Reminders and timers", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(WAKE, "Jarvis wake", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
    }
}

object Notifier {
    private var counter = 100

    /** Heads-up notification with sound and vibration (reminders, timers, spoken alerts). */
    fun alert(text: String) {
        val ctx = Device.app
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Ch.ALERT)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(counter++, n)
        } catch (_: SecurityException) {
        }
    }

    /** Full-screen intent used to bring the orb up when the wake word is heard and the app is in the background. */
    fun wakeScreen(intent: Intent) {
        val ctx = Device.app
        val pi = PendingIntent.getActivity(ctx, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, Ch.WAKE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis")
            .setContentText("Listening…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(7, n)
        } catch (_: SecurityException) {
        }
    }
}

// ───────────────────────── reminders & timers (survive restarts) ─────────────────────────
object Reminders {
    private var lastId = 0L

    private fun pending(id: Long, text: String, due: Long): PendingIntent {
        val ctx = Device.app
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text).putExtra("due", due)
        return PendingIntent.getBroadcast(ctx, (id and 0x7fffffff).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun schedule(r: Store.Reminder) {
        val am = Device.app.getSystemService(AlarmManager::class.java)
        val pi = pending(r.id, r.text, r.due)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.due, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.due, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.due, pi)
        }
    }

    @Synchronized
    fun add(dueMs: Long, text: String, kind: String): Long {
        val id = maxOf(System.currentTimeMillis(), lastId + 1)
        lastId = id
        val r = Store.Reminder(id, dueMs, text, kind)
        Store.addReminder(r)
        schedule(r)
        return id
    }

    fun fire(id: Long, text: String, due: Long) {
        Store.removeReminder(id)
        val late = System.currentTimeMillis() - due > 120_000
        val msg = (if (late) "Missed reminder: " else "") + text
        Jarvis.notifyUser(if (Store.lastHindi) HindiReply.localizeAlert(msg) else msg)
    }

    fun cancelAll() {
        val am = Device.app.getSystemService(AlarmManager::class.java)
        for (r in Store.reminders()) am.cancel(pending(r.id, r.text, r.due))
        Store.clearReminders()
    }

    /** After a reboot or app update: re-arm everything, announce what was missed. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (r in Store.reminders()) {
            if (r.due <= now) fire(r.id, r.text, r.due) else schedule(r)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        Reminders.fire(intent.getLongExtra("id", 0L), intent.getStringExtra("text") ?: "Reminder", intent.getLongExtra("due", 0L))
        Handler(Looper.getMainLooper()).postDelayed({ pr.finish() }, 9000)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll()
        // Android 14 doesn't let apps start a microphone service from boot, so ask the user to tap once.
        if (Store.wakeEnabled) {
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java).putExtra("startWake", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, Ch.ALERT)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Jarvis is asleep")
                .setContentText("Tap to turn the “Jarvis” wake word back on")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java).notify(3, n)
            } catch (_: SecurityException) {
            }
        }
    }
}

// ───────────────────────── always-on wake word service ─────────────────────────
class JarvisService : Service() {
    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.STOP"

        fun start(ctx: Context) {
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisService::class.java))
            Jarvis.stopWake()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.wakeEnabled = false
            Jarvis.stopWake()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Voice.hasMic()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Ch.SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say “Jarvis” to wake me")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ServiceCompat.startForeground(
                this, 1, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Store.wakeEnabled = true
        Jarvis.startWake()
        return START_STICKY
    }

    override fun onDestroy() {
        Jarvis.stopWake()
        super.onDestroy()
    }
}

// ───────────────────────── notification reader ─────────────────────────
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile private var instance: NotifListener? = null

        private val NOISE = setOf("android", "com.android.systemui", "com.google.android.gms", "com.jarvis.assistant")
        private val NAMES = mapOf(
            "com.whatsapp" to "WhatsApp", "com.instagram.android" to "Instagram", "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail", "com.google.android.apps.messaging" to "Messages", "com.openai.chatgpt" to "ChatGPT",
            "com.anthropic.claude" to "Claude", "com.snapchat.android" to "Snapchat", "com.twitter.android" to "X",
            "com.linkedin.android" to "LinkedIn", "com.discord" to "Discord", "com.google.android.youtube" to "YouTube"
        )

        private fun label(pkg: String): String {
            NAMES[pkg]?.let { return it }
            try {
                val pm = Device.app.packageManager
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
            }
            return titleCase(pkg.substringAfterLast('.').replace("_", " "))
        }

        /** Current notifications, newest first, or null if notification access isn't granted. */
        fun items(): List<NotifItem>? {
            val inst = instance ?: return null
            return try {
                val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE
                inst.activeNotifications.mapNotNull { sbn ->
                    val n = sbn.notification
                    if (sbn.packageName in NOISE || (n.flags and skipFlags) != 0) return@mapNotNull null
                    val ex = n.extras
                    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                    if (title.isEmpty() && text.isEmpty()) null
                    else NotifItem(label(sbn.packageName), sbn.packageName, title, text, sbn.postTime)
                }.sortedByDescending { it.whenMs }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
}

// ───────────────────────── accessibility: home/back/lock/screenshots/screen reading ─────────────────────────
class JarvisAccess : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccess? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Grab what's on screen right now (Android 11+). Calls [cb] with null on failure. */
    fun grab(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buf = screenshot.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(buf, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        buf.close()
                    }
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) { cb(null) }
            })
        } catch (e: Exception) {
            cb(null)
        }
    }
}
