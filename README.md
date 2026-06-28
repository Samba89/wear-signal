# wear-signal

A minimal, receive-only Signal client for Wear OS. It links to your existing Signal
account as a **linked device** (like Signal Desktop) so an LTE watch can show message
notifications while your phone stays at home.

Built against vendored modules from [Signal-Android](https://github.com/signalapp/Signal-Android)
v8.15.0 (`lib/libsignal-service`, `core/network`, `core/util-jvm`, `core/models-jvm`).
AGPL-3.0-only, personal use.

## What it does

- **Pairing**: shows a provisioning QR; scan it from phone Signal →
  Settings → Linked devices → Link new device. **Choose "Don't transfer messages"**
  if asked — the watch can't consume the history backup.
- **Polling**: no push. When the phone is *not* connected to the watch, it polls the
  message queue on an exact-alarm interval (1/5/15 min, default 5), decrypts, notifies,
  disconnects.
- **No double notifications**: when the phone *is* connected (Bluetooth/Wi-Fi), the watch
  never notifies — phone Signal's notifications bridge to the watch natively. A daily
  silent drain keeps the queue short and refreshes the server's 45-day linked-device
  inactivity deadline.
- **Recent messages**: last 50 decrypted messages in a scrollable list.
- Receive-only: no sending, no read receipts, no typing indicators.

## Build

Requires JDK 17+ (e.g. Android Studio's bundled JBR — point `JAVA_HOME` at it, or set
`org.gradle.java.home` in your own `~/.gradle/gradle.properties`) and an Android SDK
configured via `local.properties`.

```
./gradlew :app:assembleDebug
```

## Testing on the emulator

```
~/Android/Sdk/emulator/emulator -avd wearos5 &
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The provisioning URL is also printed to logcat (`LinkingViewModel`) as a dev fallback
if scanning the emulator screen is awkward.

The Status screen has a debug chip to force the phone-connected state
(auto / connected / away) for testing notification dedup without a paired phone.

## Notes

- Prekey upkeep (top-up + 2-day signed/last-resort rotation) runs during the daily drain.
- Unlinking: phone Signal → Linked devices → remove "Watch". Relink anytime (5-device limit).
- Profile names are resolved via profile keys harvested from incoming messages, so the
  first message from a contact may show a truncated ACI until the fetch completes.
