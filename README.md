# Jarvis for Android

Native Kotlin + Jetpack Compose port of `jarvis.py` (V1 skills) and `jarvis2.py` (AI brain).
Say **"Jarvis"** any time and the orb rises with edge-glow, light streaks and side equalizers.

## Build
1. Open this folder in **Android Studio** (Ladybug or newer). Let Gradle sync (needs internet once).
2. Plug in a phone (Android 8.0+) or start an emulator, press **Run**.
3. First launch: Settings (gear) -> **Grant access**, then open each "Open" row you want
   (Display over other apps matters most: it lets the orb appear on wake).
4. Tap **Wake word off** on the home screen to turn on always-listening.

## Using it
- Say "Jarvis, what's the time", or just "Jarvis" then the command. After each reply it keeps listening for 20 s
  with no wake word needed (same as the Python version).
- Tap the orb or the mic to talk without the wake word. Type in the box for text mode.
- AI brain: Settings -> AI brain -> pick provider, paste key, Test and save.

## What maps to what
| Python (Termux)                          | Android app                                           |
|------------------------------------------|-------------------------------------------------------|
| termux-speech-to-text / tts              | SpeechRecognizer / TextToSpeech                        |
| battery, torch, volume, clipboard        | BatteryManager, CameraManager, AudioManager, Clipboard |
| contacts, SMS, call log, call, text      | ContentProviders, SmsManager, ACTION_CALL              |
| WhatsApp / open any app / deep links     | Launch intents (no ADB needed)                         |
| notifications                            | NotificationListenerService                            |
| ADB: home/back/recents/lock/screenshot   | Accessibility service global actions                   |
| ADB: media keys                          | AudioManager.dispatchMediaKeyEvent                     |
| ADB: alarms, next alarm                  | AlarmClock intents, AlarmManager.getNextAlarmClock     |
| timers / reminders thread                | AlarmManager + notification (survive reboot)           |
| camera / screen vision                   | CameraX / accessibility screenshot (Android 11+)       |
| `--setup` wizard                         | Settings screen                                        |

## Things Android does differently
- Wi-Fi, Bluetooth, mobile data, airplane mode and dark mode can't be flipped by apps on modern Android.
  Jarvis opens the right panel instead. Do Not Disturb, auto-rotate, brightness work after you grant access.
  Battery saver works silently after: `adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS`
- "Close <app>" ends background processes only (Android has no real force-stop for apps).
- The wake word uses the system speech recognizer (no keys, like the Python version), so it uses some battery
  and the mic indicator stays on. Reading the clipboard only works while Jarvis is on screen (Android rule).
- After a reboot, tap the "Jarvis is asleep" notification once to restart listening (Android 14 rule).
- Texts and calls started by the AI always ask for a spoken "yes"; WhatsApp still needs you to tap send.

## Files (21)
`Utils` helpers, calculator, time parsing | `Store` saved data | `Device` all phone control |
`Commands` the regex skills (port of jarvis.py) | `Brain` LLM + tools + vision (port of jarvis2.py) |
`Voice` speech in/out | `Jarvis` engine + wake sessions | `Services` wake service, listeners, reminders |
`Orb` orb + side effects | `Screens` home/chat/settings | `MainActivity`

## Build with GitHub Actions
Push to GitHub; the **Build APK** workflow runs on every push (or run it by hand from the Actions tab).
Download `Jarvis-debug-apk` from the run page, unzip, and install `app-debug.apk` (allow "install unknown apps").
