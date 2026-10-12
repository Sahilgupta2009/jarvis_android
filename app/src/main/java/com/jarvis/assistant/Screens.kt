package com.jarvis.assistant

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Arc, secondary = Ion, background = Ink, surface = Deck,
            onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun JarvisRoot(activity: MainActivity) {
    val orbVisible by Jarvis.orbVisible.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings || orbVisible) {
        if (showSettings) showSettings = false else Jarvis.dismiss()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        HomeScreen(onSettings = { showSettings = true }, onToggleWake = { activity.toggleWake() })
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(tween(260)) { it / 4 } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(200)) { it / 4 } + fadeOut(tween(200))
        ) {
            SettingsScreen(activity) { showSettings = false }
        }
        AnimatedVisibility(
            visible = orbVisible,
            enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.92f),
            exit = fadeOut(tween(250))
        ) {
            OrbOverlay()
        }
    }
}

// ───────────────────────── small building blocks ─────────────────────────
@Composable
private fun Pill(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (active) Arc.copy(alpha = 0.14f) else Deck)
            .border(1.dp, if (active) Arc.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) Arc else Mist, fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(Arc.copy(alpha = if (enabled) 0.16f else 0.06f))
            .border(1.dp, Arc.copy(alpha = if (enabled) 0.6f else 0.2f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, color = if (enabled) Arc else Mist, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Bubble(m: Msg) {
    val mine = m.user
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) Arc.copy(alpha = 0.16f) else Deck)
                .border(
                    1.dp, if (mine) Arc.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

private val QUICK = listOf(
    "Briefing" to "brief me", "Weather" to "weather", "Battery" to "battery", "Time" to "what's the time",
    "Notifications" to "read my notifications", "To-dos" to "show my todos", "Notes" to "read my notes",
    "Joke" to "tell me a joke", "What can you do?" to "help"
)

// ───────────────────────── home ─────────────────────────
@Composable
fun HomeScreen(onSettings: () -> Unit, onToggleWake: () -> Unit) {
    val state by Jarvis.state.collectAsState()
    val messages by Jarvis.messages.collectAsState()
    val wakeOn by Jarvis.wakeOn.collectAsState()
    val voice by Jarvis.voiceMode.collectAsState()
    val brain by Jarvis.brainLabel.collectAsState()
    val level = Jarvis.level.collectAsState()
    val time = rememberClock()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val t = text.trim()
        if (t.isNotEmpty()) {
            Jarvis.submit(t)
            text = ""
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Jarvis", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
                Text(
                    when (state) {
                        OrbState.LISTENING -> "Listening"
                        OrbState.THINKING -> "Thinking"
                        OrbState.SPEAKING -> "Speaking"
                        OrbState.IDLE -> "Waiting for “Jarvis”"
                        OrbState.OFF -> "Tap the orb to talk"
                    },
                    color = Mist, fontSize = 13.sp
                )
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Mist) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Jarvis.manualListen() }
        ) {
            JarvisOrb(state, level, time, Modifier.fillMaxSize())
        }

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Pill(if (wakeOn) "Wake word on" else "Wake word off", wakeOn, onToggleWake) }
            item { Pill(if (voice) "Voice replies on" else "Voice replies off", voice) { Jarvis.setVoiceMode(!voice) } }
            item { Pill(if (brain.isNotEmpty()) "AI: $brain" else "AI off", brain.isNotEmpty(), onSettings) }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(QUICK) { (label, cmd) -> Pill(label, false) { Jarvis.submit(cmd) } }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { Bubble(it) }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(26.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(Deck)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 18.dp, vertical = 13.dp)
            ) {
                if (text.isEmpty()) Text("Type a command", color = Mist, fontSize = 16.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Arc),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { if (text.isBlank()) Jarvis.manualListen() else send() }) {
                Icon(
                    if (text.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (text.isBlank()) "Talk" else "Send", tint = Arc
                )
            }
        }
    }
}

// ───────────────────────── settings ─────────────────────────
private class Special(val title: String, val why: String, val granted: Boolean, val open: () -> Unit)

private fun go(action: String, withPackage: Boolean = true): () -> Unit = { Device.openSettings(action, withPackage) }

private fun specials(ctx: Context): List<Special> {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val am = ctx.getSystemService(AlarmManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val out = ArrayList<Special>()
    out.add(Special("Display over other apps", "Lets the orb appear the moment you say Jarvis", Settings.canDrawOverlays(ctx), go(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)))
    out.add(Special("Notification access", "Needed to read your notifications aloud", NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName), go(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false)))
    out.add(Special("Accessibility service", "Home, back, recents, lock, screenshots and reading the screen", JarvisAccess.instance != null, go(Settings.ACTION_ACCESSIBILITY_SETTINGS, false)))
    out.add(Special("Modify system settings", "Brightness and auto-rotate", Settings.System.canWrite(ctx), go(Settings.ACTION_MANAGE_WRITE_SETTINGS)))
    out.add(Special("Do Not Disturb access", "Turn Do Not Disturb on and off", nm.isNotificationPolicyAccessGranted, go(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false)))
    if (Build.VERSION.SDK_INT >= 31) {
        out.add(Special("Exact alarms", "Reminders and timers that fire on the second", am.canScheduleExactAlarms(), go(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)))
    }
    out.add(Special("Unrestricted battery", "Keeps the wake word running in the background", pm.isIgnoringBatteryOptimizations(ctx.packageName), go(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)))
    if (Build.VERSION.SDK_INT >= 34) {
        out.add(Special("Full-screen alerts", "Wakes the screen when the phone is locked", nm.canUseFullScreenIntent(), go(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)))
    }
    return out
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 28.dp, bottom = 6.dp))
}

@Composable
private fun JField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(top = 10.dp)) {
        Text(label, color = Mist, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box(
            Modifier.fillMaxWidth().clip(shape).background(Deck).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Arc),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(desc, color = Mist, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Arc, checkedThumbColor = Ink, uncheckedTrackColor = Deck, uncheckedThumbColor = Mist)
        )
    }
}

@Composable
fun SettingsScreen(activity: MainActivity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val rows = remember(tick) { specials(ctx) }
    val missing = remember(tick) { MainActivity.RUNTIME_PERMS.count { !Device.hasPerm(it) } }

    // AI brain form
    val saved = Brain.cfg
    var prov by remember { mutableStateOf(saved.provider.ifEmpty { "anthropic" }) }
    var key by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model.ifEmpty { Brain.PROVIDERS.getValue("anthropic").model }) }
    var base by remember { mutableStateOf(saved.baseUrl.ifEmpty { Brain.PROVIDERS.getValue("anthropic").base }) }
    var vision by remember { mutableStateOf(saved.visionModel) }
    var brave by remember { mutableStateOf(saved.braveKey) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }

    // phone form
    var name by remember { mutableStateOf(Store.user) }
    var cc by remember { mutableStateOf(Store.countryCode) }
    var confirmSms by remember { mutableStateOf(Store.confirmSms) }
    var silence by remember { mutableStateOf(Store.silenceBeep) }

    fun currentConfig() = Brain.Config(
        prov, key.trim(), model.trim(),
        base.trim().ifEmpty { Brain.PROVIDERS[prov]?.base ?: "" }, brave.trim(), vision.trim()
    )

    Column(
        Modifier.fillMaxSize().background(Ink).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }

        SectionTitle("Access")
        Text(
            if (missing == 0) "Core permissions are all granted." else "$missing core permissions still needed: microphone, contacts, texts, calls and camera.",
            color = Mist, fontSize = 13.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        if (missing > 0) ActionButton("Grant access") { activity.grantAll() }
        Spacer(Modifier.height(6.dp))
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().clickable { r.open() }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (r.granted) Color(0xFF2BF5C4) else Ember))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Color.White, fontSize = 15.sp)
                    Text(r.why, color = Mist, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Text(if (r.granted) "On" else "Open", color = if (r.granted) Mist else Arc, fontSize = 13.sp)
            }
        }

        SectionTitle("Language")
        var lang by remember { mutableStateOf(Store.listenLang) }
        Text(
            "Jarvis understands English, Hindi and Hinglish and replies in the language you spoke. " +
                "Hindi mode also catches English words. For Hindi speech, install the Hindi voice in Android settings → Text-to-speech.",
            color = Mist, fontSize = 13.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Hindi + English", lang == "hi-IN") { lang = "hi-IN"; Store.listenLang = "hi-IN" }
            Pill("English only", lang == "en-IN") { lang = "en-IN"; Store.listenLang = "en-IN" }
        }

        SectionTitle("Wake word")
        SwitchRow(
            "Hide the listening beep",
            "Mutes media volume while idle-listening, only when nothing is playing.",
            silence
        ) { silence = it; Store.silenceBeep = it }

        val wakeStatus by WakeWord.status.collectAsState()
        var offline by remember { mutableStateOf(Store.offlineWake) }
        SwitchRow(
            "Offline wake word (no beeps)",
            "Listens for “Jarvis” on the phone with a 40 MB voice model. Commands after it still use Google speech.",
            offline
        ) { offline = it; Store.offlineWake = it; Jarvis.restartWake() }
        if (WakeWord.ready()) {
            Text("Voice model installed.", color = Mist, fontSize = 12.sp)
        } else {
            ActionButton(if (wakeStatus.isEmpty()) "Download voice model (40 MB)" else wakeStatus, enabled = !wakeStatus.startsWith("Down") && !wakeStatus.startsWith("Unpack")) {
                WakeWord.download { Jarvis.restartWake() }
            }
        }

        SectionTitle("AI brain")
        Text(
            "Adds open-ended answers, web search, and “look at this” camera and screen questions. Quick commands still work offline.",
            color = Mist, fontSize = 13.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Brain.PROVIDERS.entries.toList()) { e ->
                Pill(e.value.label, prov == e.key) {
                    prov = e.key
                    base = e.value.base
                    if (e.value.model.isNotEmpty()) {
                        model = e.value.model
                    } else if (e.key != "anthropic") {
                        model = ""
                    }
                    models = emptyList()
                }
            }
        }
        JField("API key", key, secret = true) { key = it }
        if (prov == "custom") JField("Base URL (for example http://192.168.1.5:11434/v1)", base) { base = it }
        JField("Model", model) { model = it }
        if (prov != "anthropic") {
            Spacer(Modifier.height(8.dp))
            ActionButton(if (busy) "Loading…" else "Fetch models", enabled = !busy && key.isNotBlank()) {
                busy = true
                status = ""
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        try { Result.success(Brain.listModels(currentConfig())) } catch (e: LLMError) { Result.failure(e) }
                    }
                    res.onSuccess { models = it; status = "${it.size} models found. Pick one that supports tool calling." }
                        .onFailure { status = "Couldn't list models: ${it.message}" }
                    busy = false
                }
            }
            if (models.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    for (m in models) {
                        Text(m, color = if (m == model) Arc else Color.White, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { model = m; models = emptyList() }.padding(vertical = 8.dp))
                    }
                }
            }
        }
        JField("Vision model for camera and screen questions (optional)", vision) { vision = it }
        JField("Brave Search API key (optional)", brave, secret = true) { brave = it }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (busy) "Testing…" else "Test and save", enabled = !busy && key.isNotBlank() && model.isNotBlank()) {
                busy = true
                status = "Testing…"
                scope.launch {
                    val cfg = currentConfig()
                    val res = withContext(Dispatchers.IO) {
                        try {
                            Result.success(Brain.ask("Reply with one short sentence confirming you are online.", persist = false, config = cfg))
                        } catch (e: LLMError) {
                            Result.failure(e)
                        }
                    }
                    res.onSuccess {
                        Brain.save(cfg)
                        Jarvis.refreshBrain()
                        status = "Saved. Jarvis says: $it"
                    }.onFailure { status = "Test failed: ${it.message}. Nothing was saved." }
                    busy = false
                }
            }
            if (Brain.enabled) ActionButton("Turn AI off") {
                Brain.save(Brain.Config())
                Jarvis.refreshBrain()
                key = ""
                status = "AI brain is off. Quick commands still work."
            }
        }
        if (status.isNotEmpty()) Text(status, color = Mist, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))

        SectionTitle("You and your phone")
        JField("What should I call you?", name) { name = it }
        JField("Country code for WhatsApp numbers (digits only)", cc) { cc = it.filter { c -> c.isDigit() }.take(3) }
        SwitchRow("Confirm before sending texts", "Jarvis always asks first when the AI starts a text or a call.", confirmSms) {
            confirmSms = it
            Store.confirmSms = it
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("Save") {
            if (name.isNotBlank()) Store.user = name.trim()
            if (cc.isNotBlank()) Store.countryCode = cc
            status = "Saved."
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Optional: grant Jarvis the battery-saver switch with one adb command from a computer:\n" +
                "adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS",
            color = Mist, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}
