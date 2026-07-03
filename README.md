# wear-signal

A vibe-coded Signal app I made for WearOS, so I can send a message to somebody without a phone. Tested on a Pixel Watch 4.

A compact Signal client for Wear OS. It links to your existing Signal account as a
**linked device** (like Signal Desktop) so an LTE watch can read and reply to
conversations — and optionally show notifications — while your phone stays at home.

Built against vendored modules from [Signal-Android](https://github.com/signalapp/Signal-Android)
v8.15.0 (`lib/libsignal-service`, `core/network`, `core/util-jvm`, `core/models-jvm`).
AGPL-3.0-only, personal use.

## What it does

- **Pairing**: shows a provisioning QR; scan it from phone Signal →
  Settings → Linked devices → Link new device. **Choose "Don't transfer messages"**
  if asked — the watch can't consume the history backup.
- **Conversations**: opens straight into the conversation list (10 at a time, "Load
  more" for older) → chat-style thread view with left/right bubbles, last 100 messages
  per conversation, populated from every drain (history starts at link time). Contact
  and group photos are fetched and decrypted alongside names; anyone without a photo
  gets a coloured monogram, and group messages are colour-coded per sender. Group
  titles, member lists, and photos are fetched from the GroupsV2 server using master
  keys harvested from message contexts.
- **Sending**: reply in any thread or start a new 1:1 via the contact picker. Group
  replies fan out pairwise to the cached member list with the groupV2 context. Sends
  emit a sync transcript so phone Signal shows them too.
- **Notifications toggle** (manual control): OFF (default) — no background polling; the
  phone's own Signal notifications bridge to the watch. ON (phone dead/away) — the watch
  polls the queue on an exact-alarm interval (1/5/15 min, default 5), decrypts, notifies,
  disconnects.
- **Charge-time maintenance**: a daily WorkManager job that only runs while charging
  drains the queue silently (keeps conversations current and refreshes the server's
  45-day linked-device inactivity deadline) and runs prekey upkeep — regardless of the
  toggle, with zero wearing-time battery cost.
- **Manual poll**: "Check for messages" in the conversation list / "Poll now" on the
  status screen, silent.
- No read receipts, no typing indicators, text only (no attachments).

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

- Prekey upkeep (top-up + 2-day signed/last-resort rotation) runs during the daily
  charge-time maintenance job.
- Group send requires the member list, which is fetched on the first poll that sees a
  message from that group — so a brand-new group needs one incoming drain before you
  can reply to it.
- Unlinking: phone Signal → Linked devices → remove "Watch". Relink anytime (5-device limit).
- Profile names are resolved via profile keys harvested from incoming messages, so the
  first message from a contact may show a truncated ACI until the fetch completes.
